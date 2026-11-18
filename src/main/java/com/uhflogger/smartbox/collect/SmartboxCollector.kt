package com.uhflogger.smartbox.collect

import android.content.Context
import android.util.Log
import com.uhflogger.backend.BackendSettings
import com.uhflogger.smartbox.SmartboxPrefs
import com.uhflogger.smartbox.ble.DownloadControl
import com.uhflogger.smartbox.ble.DownloadException
import com.uhflogger.smartbox.ble.DownloadResult
import com.uhflogger.smartbox.ble.FieldKey
import com.uhflogger.smartbox.ble.NearbySmartbox
import com.uhflogger.smartbox.ble.SmartboxBusyException
import com.uhflogger.smartbox.ble.SmartboxConnection
import com.uhflogger.smartbox.ble.SmartboxMode
import com.uhflogger.smartbox.ble.SmartboxScanner
import com.uhflogger.smartbox.ble.SmartboxTarget
import com.uhflogger.smartbox.ble.SmartboxTelemetry
import com.uhflogger.smartbox.data.RoomSmartboxRepository
import com.uhflogger.smartbox.data.SmartboxRepository
import com.uhflogger.smartbox.radio.CollectMode
import com.uhflogger.smartbox.radio.RadioDecision
import com.uhflogger.smartbox.radio.ReaderRadio
import com.uhflogger.smartbox.sync.SmartboxUploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coleta automática (seção 7 do guia + regras do Igor):
 *
 *   PROCURANDO → CONECTANDO → BAIXANDO → (desconecta SEMPRE) → RESPIRO 2 s → …
 *
 * - Ligada por padrão; sem chave no build, sem fazenda selecionada ou sem
 *   permissão, nem começa ([AutoCollectRules.shouldRun]).
 * - A leitura de brincos SEMPRE vence: com captura BT nem busca; com captura
 *   USB roda em segundo plano; com o leitor parado, roda na frente (a
 *   MainActivity abre a tela de progresso).
 * - Falhas são silenciosas: retenta com as pausas do §7, só telemetria.
 * - Cada sessão roda num Job filho: o corte do yield mata a sessão, não o laço.
 *
 * Vive no processo, ligado/desligado pelo [SmartboxCollectService].
 */
object SmartboxCollector {
    private const val TAG = "SmartboxCollector"
    private const val PICK_INTERVAL_MS = 1_000L
    private const val BREATH_MS = 2_000L
    private const val DONE_SHOW_MS = 3_000L
    private const val SEEN_WRITE_INTERVAL_MS = 60_000L
    private const val STALE_CHECK_INTERVAL_MS = 60_000L

    enum class Phase { OFF, WAITING_READER, PAUSED_FOR_TECHNICIAN, SEARCHING, CONNECTING, DOWNLOADING, DONE }

    data class State(
        val phase: Phase = Phase.OFF,
        val mode: CollectMode? = null,
        val moduleId: String? = null,
        val received: Int = 0,
        val total: Int? = null,
        val ratePerSecond: Int = 0,
        /** Leituras da descarga que acabou (fase DONE). */
        val doneReadings: Int = 0,
        /** Sobe a cada sessão: a tela de progresso abre uma vez por sessão. */
        val sessionSeq: Long = 0,
        /** Smartboxes da fazenda por perto sem baixar há 24 h (aviso amarelo). */
        val stale: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val holds = AtomicInteger(0)
    private val pauses = ConcurrentHashMap<String, AutoCollectRules.Pause>()
    private val lastSeenWrite = HashMap<String, Long>()
    private var lastStaleCheck = 0L
    private var releaseScan: (() -> Unit)? = null

    fun isEnabled(context: Context): Boolean = AutoCollectRules.shouldRun(
        keyConfigured = FieldKey.isConfigured,
        farmId = BackendSettings.getFarmId(context),
        enabledPref = SmartboxPrefs.isAutoEnabled(context),
        hasConnectPermission = SmartboxConnection.hasConnectPermission(context),
    )

    @Synchronized
    fun start(context: Context) {
        if (loopJob?.isActive == true) return
        val app = context.applicationContext
        loopJob = scope.launch {
            try {
                loop(app)
            } finally {
                withContext(Dispatchers.Main.immediate + kotlinx.coroutines.NonCancellable) { releaseScanNow() }
                _state.update { State(stale = it.stale) }
            }
        }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Tela de técnico aberta: a coleta cede o rádio até soltar. Devolve quem solta (idempotente). */
    fun hold(): () -> Unit {
        holds.incrementAndGet()
        var released = false
        return {
            if (!released) {
                released = true
                holds.decrementAndGet()
            }
        }
    }

    private suspend fun requestScan(context: Context) = withContext(Dispatchers.Main.immediate) {
        if (releaseScan == null) releaseScan = SmartboxScanner.request(context, "auto-collect")
    }

    private fun releaseScanNow() {
        releaseScan?.invoke()
        releaseScan = null
    }

    private suspend fun releaseScan() = withContext(Dispatchers.Main.immediate) { releaseScanNow() }

    private suspend fun loop(context: Context) {
        val repo = RoomSmartboxRepository.get(context)
        // Ao ligar, manda o que ficou pendente de antes.
        SmartboxUploadWorker.scheduleNow(context)
        while (true) {
            if (!isEnabled(context)) {
                Log.i(TAG, "coleta desligada (chave/fazenda/preferência/permissão)")
                return
            }
            if (holds.get() > 0) {
                releaseScan()
                _state.update { it.copy(phase = Phase.PAUSED_FOR_TECHNICIAN, moduleId = null) }
                delay(PICK_INTERVAL_MS)
                continue
            }
            val decision = ReaderRadio.decision()
            if (decision == RadioDecision.BLOCK) {
                // Nem procura: a busca já disputaria o rádio com o leitor BT.
                releaseScan()
                _state.update { it.copy(phase = Phase.WAITING_READER, moduleId = null) }
                delay(PICK_INTERVAL_MS)
                continue
            }
            requestScan(context)
            val farmId = BackendSettings.getFarmId(context).toLong()
            val now = System.currentTimeMillis()
            val nearby = SmartboxScanner.nearby.value.values
            recordSeen(repo, nearby, farmId, now)
            refreshStale(repo, now)

            val box = AutoCollectRules.pickCandidate(nearby, farmId, pauses, now)
            if (box == null) {
                _state.update { it.copy(phase = Phase.SEARCHING, mode = null, moduleId = null) }
                delay(PICK_INTERVAL_MS)
                continue
            }
            collectOnce(context, repo, box, farmId, if (decision == RadioDecision.FOREGROUND) CollectMode.BLOCKING else CollectMode.BACKGROUND)
            delay(BREATH_MS)
        }
    }

    private sealed class Attempt {
        object ConfigMode : Attempt()
        object OtherFarm : Attempt()
        class Done(val result: DownloadResult) : Attempt()
    }

    /** Uma tentativa numa caixa. Sempre desconecta (o withSession garante). */
    private suspend fun collectOnce(context: Context, repo: SmartboxRepository, box: NearbySmartbox, farmId: Long, mode: CollectMode) {
        val moduleId = box.adv.moduleId
        val control = DownloadControl()
        _state.update {
            State(
                phase = Phase.CONNECTING, mode = mode, moduleId = moduleId, total = box.adv.pending,
                sessionSeq = it.sessionSeq + 1, stale = it.stale,
            )
        }

        var attempt: Attempt? = null
        var error: Throwable? = null
        coroutineScope {
            // Job filho: o corte do yieldForCapture cancela só este.
            launch {
                try {
                    attempt = SmartboxConnection.withSession(
                        context, SmartboxTarget(box.address, moduleId), SmartboxTelemetry.Purpose.AUTO_COLLECT,
                        mode, control, box.rssi, box.seenAt,
                    ) { open ->
                        when {
                            open.challenge.mode != SmartboxMode.OPERATION -> Attempt.ConfigMode
                            // O anúncio pode estar velho: a fazenda que vale é a do CHALLENGE.
                            open.challenge.farmId != farmId -> Attempt.OtherFarm
                            else -> {
                                _state.update { it.copy(phase = Phase.DOWNLOADING, total = open.challenge.pending) }
                                Attempt.Done(
                                    SmartboxDownloads.run(open, moduleId, farmId, repo, control) { p ->
                                        _state.update {
                                            it.copy(
                                                received = p.received,
                                                total = p.remaining?.let { r -> (p.received + r).toInt() } ?: it.total,
                                                ratePerSecond = p.ratePerSecond,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Cortado pelo yield: não é erro. Cancelamento do laço inteiro segue adiante.
                    if (!control.stopRequested) throw e
                } catch (e: Throwable) {
                    error = e
                }
            }
        }

        val got = attempt
        val outcome = when {
            error is SmartboxBusyException -> null   // técnico usando o rádio: não é falha da caixa
            got is Attempt.Done -> if (got.result.stoppedEarly) AutoCollectRules.Outcome.YIELDED else AutoCollectRules.Outcome.SUCCESS
            got is Attempt.ConfigMode -> AutoCollectRules.Outcome.CONFIG_MODE
            // Não é desta fazenda: pausa de sucesso; o anúncio novo já tira ela das candidatas.
            got is Attempt.OtherFarm -> AutoCollectRules.Outcome.SUCCESS
            control.stopRequested -> AutoCollectRules.Outcome.YIELDED
            error is DownloadException -> AutoCollectRules.Outcome.CONFIG_MODE
            else -> AutoCollectRules.Outcome.FAILURE
        }
        if (outcome != null) pauses[moduleId] = AutoCollectRules.nextPause(pauses[moduleId], outcome, System.currentTimeMillis())
        if (error != null && error !is SmartboxBusyException) {
            // Silencioso para o operador: a telemetria (Session Failed) já registrou.
            Log.w(TAG, "$moduleId: ${error?.javaClass?.simpleName}: ${error?.message} → $outcome")
        }

        val downloaded = (got as? Attempt.Done)?.result?.readings ?: control.received
        if (downloaded > 0) SmartboxUploadWorker.scheduleNow(context)

        if (outcome == AutoCollectRules.Outcome.SUCCESS && got is Attempt.Done && mode == CollectMode.BLOCKING) {
            _state.update { it.copy(phase = Phase.DONE, doneReadings = got.result.readings) }
            delay(DONE_SHOW_MS)
        }
        _state.update { it.copy(phase = Phase.SEARCHING, moduleId = null, received = 0, total = null) }
    }

    private suspend fun recordSeen(repo: SmartboxRepository, nearby: Collection<NearbySmartbox>, farmId: Long, now: Long) {
        val mine = nearby.filter { it.adv.farmId == farmId && now - (lastSeenWrite[it.adv.moduleId] ?: 0) >= SEEN_WRITE_INTERVAL_MS }
        if (mine.isEmpty()) return
        mine.forEach { lastSeenWrite[it.adv.moduleId] = now }
        withContext(Dispatchers.IO) {
            try {
                mine.forEach { repo.markSeen(it.adv.moduleId, it.adv.pending, now) }
            } catch (e: Exception) {
                Log.w(TAG, "markSeen falhou: ${e.message}")
            }
        }
    }

    private suspend fun refreshStale(repo: SmartboxRepository, now: Long) {
        if (now - lastStaleCheck < STALE_CHECK_INTERVAL_MS) return
        lastStaleCheck = now
        val stale = withContext(Dispatchers.IO) {
            try { repo.staleModules(now) } catch (_: Exception) { emptyList() }
        }
        _state.update { it.copy(stale = stale) }
    }
}
