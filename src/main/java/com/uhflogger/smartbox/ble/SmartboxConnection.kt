package com.uhflogger.smartbox.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.uhflogger.analytics.Analytics
import com.uhflogger.smartbox.radio.CollectMode
import com.uhflogger.smartbox.radio.ReaderRadio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** Quem conectar: endereço BLE (só serve para conectar) + MAC do anúncio (identidade). */
data class SmartboxTarget(val address: String, val moduleId: String)

class SmartboxBusyException(val busyWith: String) : Exception("Outra conexão com smartbox em andamento")

class SmartboxConnectException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sessão aberta, entregue ao bloco de [SmartboxConnection.withSession]. */
class OpenSession internal constructor(
    val session: SmartboxSession,
    val challenge: Challenge,
    val mtu: Int,
)

/**
 * Conexão BLE com a smartbox (seção 4.3 do guia). Porte de connection.ts.
 *
 * Sequência (a ordem importa): parar o scan → connect (10 s, até 3 tentativas)
 * → MTU 517 separado → discover → prioridade alta → notify do TX ligado ANTES
 * de escrever (confere o CCCD) → handshake (5 s no total, o ESP derruba depois).
 *
 * Uma conexão de smartbox por vez no app inteiro: ocupado = falha na hora
 * ([SmartboxBusyException]), não enfileira. Toda ação passa por [withSession],
 * que SEMPRE fecha no finally. Não há reconexão automática.
 *
 * Nada aqui loga a K nem a Ks.
 */
object SmartboxConnection {
    private const val TAG = "SmartboxConnection"

    private class Active(
        val moduleId: String,
        val purpose: String,
        val collectMode: CollectMode,
        val control: DownloadControl?,
        val job: Job?,
    ) {
        @Volatile var link: SmartboxLink? = null
        /** Yield em andamento nesta sessão: um segundo pedido só espera este. */
        var yielding: CompletableDeferred<Unit>? = null
    }

    /**
     * Escopo do processo para o que não pode morrer com uma tela (ex.: ceder o
     * rádio e então iniciar a captura). Main: o resultado mexe em UI/serviço.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val active = MutableStateFlow<Active?>(null)
    private val _activeModuleId = MutableStateFlow<String?>(null)

    /** Smartbox conectada (ou conectando) agora. */
    val activeModuleId: StateFlow<String?> = _activeModuleId.asStateFlow()

    val isBusy: Boolean get() = active.value != null

    private val random = SecureRandom()

    private class Trace {
        var step = SmartboxTelemetry.Step.PRECHECK
        var attempts = 0
        val attemptErrors = mutableListOf<String>()
        var mtu = SmartboxProtocol.DEFAULT_MTU
    }

    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Abre a sessão, roda [block] e fecha SEMPRE.
     *
     * @param control usado por [yieldForCapture] para parar a descarga sem perder dado.
     * @param advRssi/advSeenAt do último anúncio, só para a telemetria.
     */
    suspend fun <T> withSession(
        context: Context,
        target: SmartboxTarget,
        purpose: String,
        collectMode: CollectMode,
        control: DownloadControl? = null,
        advRssi: Int? = null,
        advSeenAt: Long? = null,
        block: suspend (OpenSession) -> T,
    ): T {
        val me = Active(target.moduleId, purpose, collectMode, control, currentCoroutineContext()[Job])
        if (!active.compareAndSet(null, me)) throw SmartboxBusyException(active.value?.moduleId ?: "?")
        _activeModuleId.value = target.moduleId

        val started = System.currentTimeMillis()
        val base = SmartboxTelemetry.base(
            moduleId = target.moduleId,
            purpose = purpose,
            collectMode = collectMode.telemetry,
            readerState = ReaderRadio.current().telemetry,
            advRssiDbm = advRssi,
            advAgeMs = advSeenAt?.let { started - it },
        )
        val trace = Trace()
        var client: GattClient? = null
        var opened = false
        var openedAt = 0L
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val (c, open) = openSession(context.applicationContext, target, trace, me)
            client = c
            opened = true
            openedAt = System.currentTimeMillis()
            val rssi = c.readRssi(1_000)
            track(
                SmartboxTelemetry.EVENT_OPENED,
                SmartboxTelemetry.opened(
                    base, trace.attempts, openedAt - started, trace.mtu, rssi,
                    if (open.challenge.mode == SmartboxMode.OPERATION) "operation" else "config",
                ),
            )
            me.link?.onBroken { err ->
                if (finished.get()) return@onBroken
                track(
                    SmartboxTelemetry.EVENT_DROPPED,
                    SmartboxTelemetry.dropped(
                        target.moduleId, purpose, collectMode.telemetry,
                        ReaderRadio.current().telemetry, err.message ?: "queda", System.currentTimeMillis() - openedAt,
                    ),
                )
            }
            return block(open)
        } catch (e: Throwable) {
            val step = if (opened) SmartboxTelemetry.Step.ACTION else trace.step
            Log.w(TAG, "sessão ${target.moduleId} ($purpose) falhou em $step: ${e.javaClass.simpleName}: ${e.message}")
            track(
                SmartboxTelemetry.EVENT_FAILED,
                SmartboxTelemetry.failed(base, step, e, trace.attempts, trace.attemptErrors, System.currentTimeMillis() - started),
            )
            throw e
        } finally {
            finished.set(true)
            client?.close()
            me.link?.breakLink("Sessão encerrada")
            _activeModuleId.value = null
            active.value = null
        }
    }

    private suspend fun openSession(
        context: Context,
        target: SmartboxTarget,
        trace: Trace,
        me: Active,
    ): Pair<GattClient, OpenSession> {
        val key = FieldKey.get() ?: throw SmartboxConnectException(FieldKey.NOT_CONFIGURED_MESSAGE)
        if (!hasConnectPermission(context)) {
            throw SmartboxConnectException("Sem permissão de Bluetooth. Permita \"Dispositivos próximos\" para o UHF Logger.")
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw SmartboxConnectException("Este aparelho não tem Bluetooth.")
        if (!adapter.isEnabled) throw SmartboxConnectException("Ligue o Bluetooth.")
        val device = try {
            adapter.getRemoteDevice(target.address)
        } catch (e: IllegalArgumentException) {
            throw SmartboxConnectException("Endereço Bluetooth inválido", e)
        }

        // O Android se dá mal com scan e conexão juntos.
        SmartboxScanner.stopForConnection()

        trace.step = SmartboxTelemetry.Step.CONNECT
        val client = connectWithRetry(context, device, adapter, trace)
        try {
            trace.step = SmartboxTelemetry.Step.MTU
            trace.mtu = try {
                maxOf(SmartboxProtocol.DEFAULT_MTU, client.requestMtu(SmartboxProtocol.REQUESTED_MTU, Timeouts.HANDSHAKE_MS))
            } catch (e: GattException) {
                // Falhou → segue com o mínimo.
                SmartboxProtocol.DEFAULT_MTU
            }

            trace.step = SmartboxTelemetry.Step.DISCOVER
            client.discoverServices(Timeouts.HANDSHAKE_MS)
            val rx = client.characteristic(SmartboxProtocol.SERVICE_UUID, SmartboxProtocol.RX_UUID)
            val tx = client.characteristic(SmartboxProtocol.SERVICE_UUID, SmartboxProtocol.TX_UUID)
            if (rx == null || tx == null) throw GattException("discover", null, "Serviço da smartbox não encontrado")
            client.requestHighPriority()

            val mtu = trace.mtu
            val link = SmartboxLink(object : GattPort {
                override val mtu: Int = mtu
                override suspend fun writePart(part: ByteArray) = client.write(rx, part, Timeouts.RESPONSE_MS)
            })
            me.link = link
            client.onNotify = { link.handleNotify(it) }
            client.onDisconnected = { status ->
                link.breakLink("A conexão com a smartbox caiu (status $status)", GattException("disconnected", status, "status $status"))
            }

            trace.step = SmartboxTelemetry.Step.NOTIFY
            client.enableNotify(tx, Timeouts.HANDSHAKE_MS)
            waitNotifyEnabled(client, tx)

            trace.step = SmartboxTelemetry.Step.HANDSHAKE
            val session = SmartboxSession(link)
            val challenge = session.handshake(
                target.moduleId,
                HandshakeDeps(
                    key = key,
                    randomBytes = { n -> ByteArray(n).also { random.nextBytes(it) } },
                    now = { System.currentTimeMillis() },
                ),
            )
            return client to OpenSession(session, challenge, mtu)
        } catch (e: Throwable) {
            client.close()
            throw e
        } finally {
            key.fill(0)
        }
    }

    /** Até 3 tentativas: a primeira falha à toa com frequência (status 62). */
    private suspend fun connectWithRetry(
        context: Context,
        device: android.bluetooth.BluetoothDevice,
        adapter: android.bluetooth.BluetoothAdapter,
        trace: Trace,
    ): GattClient {
        var last: Throwable? = null
        for (attempt in 1..Timeouts.CONNECT_ATTEMPTS) {
            trace.attempts = attempt
            val client = GattClient(context, device)
            try {
                client.connect(Timeouts.CONNECT_MS)
                return client
            } catch (e: GattException) {
                last = e
                trace.attemptErrors += (e.status?.toString() ?: "timeout")
                client.close()
                // Bluetooth desligado: repetir não adianta.
                if (!adapter.isEnabled) throw SmartboxConnectException("Ligue o Bluetooth.", e)
                if (!hasConnectPermission(context)) throw SmartboxConnectException("Sem permissão de Bluetooth.", e)
                if (attempt < Timeouts.CONNECT_ATTEMPTS) delay(Timeouts.CONNECT_RETRY_DELAY_MS)
            }
        }
        throw SmartboxConnectException("Não conectou na smartbox. Chegue mais perto e tente de novo.", last)
    }

    private suspend fun waitNotifyEnabled(client: GattClient, tx: android.bluetooth.BluetoothGattCharacteristic) {
        repeat(Timeouts.CCCD_READ_ATTEMPTS) {
            try {
                val v = client.readCccd(tx, 1_000)
                if (v.size >= 2 && v[0].toInt() == 0x01 && v[1].toInt() == 0x00) return
            } catch (_: GattException) {
                // nem toda stack deixa ler o CCCD; tenta de novo
            }
            delay(Timeouts.CCCD_READ_DELAY_MS)
        }
        // Segue mesmo assim: se o notify não ligou, o HI vira timeout e a sessão cai.
    }

    /**
     * A captura de brincos quer o rádio (regras do Igor): "Iniciar leitura" ou
     * "Parar e ler brincos" no meio de uma sessão de smartbox.
     *
     * 1. Pede a parada normal: a descarga termina o lote em mãos (commit + ACK).
     * 2. Passou de [maxWaitMs]: corta. Fora da janela commit → ACK, cancela a
     *    sessão na hora (o lote volta na próxima, o banco não duplica). Dentro
     *    dela, espera o commit e a descarga sai SEM ACK ([DownloadControl.forceCut]).
     *
     * @return false se não havia sessão (a captura começa sem esperar nada).
     */
    suspend fun yieldForCapture(trigger: String, maxWaitMs: Long = 5_000L): Boolean {
        val a = active.value ?: return false
        // "Parar e ler brincos" pede o yield e, em seguida, o caminho do Iniciar
        // pede de novo: o segundo só espera o primeiro (sem telemetria dupla).
        val mine = CompletableDeferred<Unit>()
        val existing = synchronized(a) { a.yielding ?: run { a.yielding = mine; null } }
        if (existing != null) {
            existing.await()
            return true
        }
        try {
            return doYield(a, trigger, maxWaitMs)
        } finally {
            mine.complete(Unit)
        }
    }

    private suspend fun doYield(a: Active, trigger: String, maxWaitMs: Long): Boolean {
        val t0 = System.currentTimeMillis()
        a.control?.requestStop()
        var forced = false
        val ended = withTimeoutOrNull(maxWaitMs) { active.first { it !== a } }
        if (ended == null) {
            forced = true
            val cut = { a.job?.cancel(); Unit }
            if (a.control != null) a.control.forceCut(cut) else cut()
            active.first { it !== a }
        }
        track(
            SmartboxTelemetry.EVENT_COLLECT_YIELDED,
            SmartboxTelemetry.collectYielded(
                moduleId = a.moduleId,
                purpose = a.purpose,
                collectMode = a.collectMode.telemetry,
                trigger = trigger,
                downloaded = a.control?.received ?: 0,
                remaining = a.control?.remaining,
                waitedMs = System.currentTimeMillis() - t0,
                forcedDisconnect = forced,
            ),
        )
        return true
    }

    private fun track(event: String, props: Map<String, Any?>) {
        try {
            Analytics.track(event, props)
        } catch (_: Throwable) {
        }
    }
}
