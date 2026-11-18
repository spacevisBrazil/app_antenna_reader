package com.uhflogger.smartbox.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.uhflogger.analytics.Analytics
import com.uhflogger.smartbox.radio.RadioDecision
import com.uhflogger.smartbox.radio.ReaderRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uma smartbox vista no scan. */
data class NearbySmartbox(
    val adv: Advertisement,
    /** Endereço BLE: só serve para conectar. A identidade é [Advertisement.moduleId]. */
    val address: String,
    val rssi: Int,
    val seenAt: Long,
)

/**
 * Regras de tempo do scan (seção 7.1 do guia), puras para teste:
 * - no máximo 5 inícios de scan a cada 30 s por app; passou disso o Android
 *   entrega ZERO resultados sem erro → intervalo mínimo de 6 s entre inícios;
 * - Bluetooth ligado e nenhum anúncio em 30 s → para, espera 30 s, reabre;
 * - scan aberto há mais de 30 min vira "oportunista" → reabre a cada 25 min;
 * - erro no scan → tenta de novo em 5 s.
 */
object ScanScheduler {
    const val MIN_START_INTERVAL_MS = 6_000L
    const val SILENCE_LIMIT_MS = 30_000L
    const val SILENCE_BACKOFF_MS = 30_000L
    const val REOPEN_AFTER_MS = 25 * 60_000L
    const val ERROR_RETRY_MS = 5_000L
    const val TICK_MS = 2_000L

    data class State(
        val scanning: Boolean = false,
        val startedAt: Long = 0,
        val lastStartAt: Long = Long.MIN_VALUE / 2,
        val lastAdvAt: Long = 0,
        val holdUntil: Long = 0,
    )

    sealed class Action {
        object None : Action()
        object Start : Action()
        object Stop : Action()
        data class StopAndHold(val until: Long) : Action()
    }

    /** @param wanted alguém pediu o scan e nada impede (permissão, BT, leitor, conexão). */
    fun onTick(s: State, now: Long, wanted: Boolean): Action {
        if (!wanted || now < s.holdUntil) return if (s.scanning) Action.Stop else Action.None
        if (s.scanning) {
            if (now - s.lastAdvAt > SILENCE_LIMIT_MS) return Action.StopAndHold(now + SILENCE_BACKOFF_MS)
            if (now - s.startedAt > REOPEN_AFTER_MS) return Action.Stop
            return Action.None
        }
        return if (now - s.lastStartAt >= MIN_START_INTERVAL_MS) Action.Start else Action.None
    }
}

/**
 * Um único scan de smartboxes no app, ligado enquanto alguém pede (tela
 * Buscar agora; coleta automática no PR 3) e nada impede:
 * - nenhuma conexão de smartbox aberta (scan + conexão juntos = Android instável);
 * - o leitor não está com o rádio ([RadioPolicy]: captura BT bloqueia).
 *
 * Scan com filtro no hardware (manufacturer data 0x02E5 'S''V'), que é o que o
 * Android mantém com a tela apagada.
 */
@SuppressLint("MissingPermission")
object SmartboxScanner {
    private const val TAG = "SmartboxScanner"

    enum class Status { IDLE, SCANNING, NO_PERMISSION, BLUETOOTH_OFF, BLOCKED_BY_READER, PAUSED_FOR_CONNECTION, ERROR }

    private val _nearby = MutableStateFlow<Map<String, NearbySmartbox>>(emptyMap())
    val nearby: StateFlow<Map<String, NearbySmartbox>> = _nearby.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile var lastError: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owners = mutableSetOf<String>()
    private var tickJob: Job? = null
    private var appContext: Context? = null
    private var state = ScanScheduler.State()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            val now = System.currentTimeMillis()
            val openMs = now - state.startedAt
            state = state.copy(scanning = false, holdUntil = now + ScanScheduler.ERROR_RETRY_MS)
            lastError = SmartboxTelemetry.scanErrorText(errorCode)
            _status.value = Status.ERROR
            Log.w(TAG, "scan falhou: $lastError")
            try {
                Analytics.track(
                    SmartboxTelemetry.EVENT_SCAN_FAILED,
                    SmartboxTelemetry.scanFailed(errorCode, ReaderRadio.current().telemetry, openMs),
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(SmartboxProtocol.COMPANY_ID) ?: return
        val adv = try {
            parseAdvertisement(withCompanyId(data))
        } catch (_: Exception) {
            null
        } ?: return
        val now = System.currentTimeMillis()
        state = state.copy(lastAdvAt = now)
        _nearby.update { it + (adv.moduleId to NearbySmartbox(adv, result.device.address, result.rssi, now)) }
    }

    fun hasScanPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /** Pede o scan enquanto [owner] precisar. Devolve quem libera (idempotente). Chamar na main. */
    fun request(context: Context, owner: String): () -> Unit {
        appContext = context.applicationContext
        owners += owner
        if (tickJob?.isActive != true) {
            tickJob = scope.launch {
                while (isActive) {
                    tick()
                    delay(ScanScheduler.TICK_MS)
                }
            }
        }
        var released = false
        return {
            if (!released) {
                released = true
                owners -= owner
                if (owners.isEmpty()) shutdown()
            }
        }
    }

    /** "Procurar de novo" / permissão recém-concedida. */
    fun retryNow() {
        state = state.copy(holdUntil = 0, lastStartAt = Long.MIN_VALUE / 2)
        scope.launch { tick() }
    }

    /** Chamado por [SmartboxConnection] antes de conectar: só volta com o scan parado. */
    suspend fun stopForConnection() {
        withContext(Dispatchers.Main.immediate) { stopScan(Status.PAUSED_FOR_CONNECTION) }
    }

    private fun tick() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        _nearby.update { map -> map.filterValues { now - it.seenAt <= SmartboxProtocol.NEARBY_WINDOW_MS } }

        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val blocker: Status? = when {
            owners.isEmpty() -> Status.IDLE
            !hasScanPermission(ctx) -> Status.NO_PERMISSION
            adapter == null || !adapter.isEnabled -> Status.BLUETOOTH_OFF
            ReaderRadio.decision() == RadioDecision.BLOCK -> Status.BLOCKED_BY_READER
            SmartboxConnection.isBusy -> Status.PAUSED_FOR_CONNECTION
            else -> null
        }
        when (val action = ScanScheduler.onTick(state, now, wanted = blocker == null)) {
            ScanScheduler.Action.None -> if (blocker != null && !state.scanning && _status.value != Status.ERROR) _status.value = blocker
            ScanScheduler.Action.Stop -> stopScan(blocker ?: Status.IDLE)
            is ScanScheduler.Action.StopAndHold -> {
                stopScan(Status.SCANNING)  // segue "procurando" para quem olha a tela
                state = state.copy(holdUntil = action.until)
            }
            ScanScheduler.Action.Start -> startScan(adapter!!, now)
        }
        if (blocker != null && _status.value == Status.ERROR && now >= state.holdUntil) _status.value = blocker
    }

    private fun startScan(adapter: android.bluetooth.BluetoothAdapter, now: Long) {
        val scanner = adapter.bluetoothLeScanner ?: run { _status.value = Status.BLUETOOTH_OFF; return }
        val filter = ScanFilter.Builder()
            .setManufacturerData(SmartboxProtocol.COMPANY_ID, SmartboxProtocol.ADV_SIGNATURE, byteArrayOf(-1, -1))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            state = state.copy(scanning = true, startedAt = now, lastStartAt = now, lastAdvAt = now)
            lastError = null
            _status.value = Status.SCANNING
        } catch (e: Exception) {
            // SecurityException / IllegalStateException (BT desligou no meio)
            Log.w(TAG, "startScan falhou: ${e.javaClass.simpleName}")
            state = state.copy(lastStartAt = now, holdUntil = now + ScanScheduler.ERROR_RETRY_MS)
            lastError = e.javaClass.simpleName
            _status.value = Status.ERROR
        }
    }

    private fun stopScan(newStatus: Status) {
        if (state.scanning) {
            state = state.copy(scanning = false)
            try {
                val adapter = (appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                adapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: Exception) {
            }
        }
        _status.value = newStatus
    }

    private fun shutdown() {
        stopScan(Status.IDLE)
        tickJob?.cancel()
        tickJob = null
        _nearby.value = emptyMap()
    }
}
