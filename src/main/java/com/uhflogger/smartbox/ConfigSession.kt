package com.uhflogger.smartbox

import android.content.Context
import com.uhflogger.smartbox.ble.Bytes
import com.uhflogger.smartbox.ble.Challenge
import com.uhflogger.smartbox.ble.FarmResult
import com.uhflogger.smartbox.ble.LinkBrokenException
import com.uhflogger.smartbox.ble.Op
import com.uhflogger.smartbox.ble.OpenSession
import com.uhflogger.smartbox.ble.SmartboxConnection
import com.uhflogger.smartbox.ble.SmartboxStatusException
import com.uhflogger.smartbox.ble.SmartboxTarget
import com.uhflogger.smartbox.ble.SmartboxTelemetry
import com.uhflogger.smartbox.ble.Status
import com.uhflogger.smartbox.ble.Timeouts
import com.uhflogger.smartbox.ble.WifiNetwork
import com.uhflogger.smartbox.ble.parseFarmResult
import com.uhflogger.smartbox.ble.parseWifiScan
import com.uhflogger.smartbox.domain.ConfigForms
import com.uhflogger.smartbox.domain.LoadedConfig
import com.uhflogger.smartbox.radio.CollectMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sessão longa da tela Configurar (seção 12 do guia). Porte do configSession.ts.
 *
 * Conecta uma vez e usa o mesmo link para ler, procurar redes e salvar.
 * Keep-alive GET_DIAG a cada 20 s parado; fecha sozinha em 10 min sem ação (o
 * keep-alive não conta; conferido a cada 2 s); fecha ao sair da tela. Sem
 * reconexão automática: a tela oferece "Reconectar". Segura a trava global de
 * conexão o tempo todo (a coleta automática espera).
 */
class ConfigSession private constructor(
    private val open: OpenSession,
    private val closeSignal: CompletableDeferred<Pair<CloseReason, String?>>,
) {
    enum class CloseReason { USER, IDLE, DROP }

    sealed class SaveOutcome {
        /** Salvou. [farm] = resposta do farm_id (null se não foi mandado). */
        data class Saved(val farm: FarmResult?) : SaveOutcome()
        /** Config SALVA, só a fazenda foi recusada (4 = outra fazenda, 5 = da chave). */
        data class SavedFarmRefused(val status: Int, val farm: FarmResult) : SaveOutcome()
        /** Status 1: a smartbox recusou (texto do portal). NADA foi salvo. */
        data class Refused(val text: String) : SaveOutcome()
    }

    val challenge: Challenge get() = open.challenge

    @Volatile private var lastAction = System.currentTimeMillis()
    @Volatile private var lastCall = System.currentTimeMillis()
    private val busy = AtomicInteger(0)

    private suspend fun <T> act(block: suspend () -> T): T {
        if (closeSignal.isCompleted) throw LinkBrokenException("A conexão com a smartbox já foi fechada")
        lastAction = System.currentTimeMillis()
        busy.incrementAndGet()
        try {
            return block()
        } finally {
            busy.decrementAndGet()
            lastCall = System.currentTimeMillis()
        }
    }

    suspend fun readConfig(): LoadedConfig = act {
        val res = open.session.call(Op.GET_CONFIG)
        if (res.status == Status.ERROR_TEXT && res.data.isEmpty()) {
            throw SmartboxStatusException(Op.GET_CONFIG, res.status, Bytes.ascii("falha interna ao ler a configuracao"))
        }
        if (res.status != Status.OK) throw SmartboxStatusException(res.op, res.status, res.data)
        ConfigForms.parseGetConfig(Bytes.toAscii(res.data))
    }

    /** Só em modo configuração (status 3 em operação). Lista vazia pode ser falha do scan. */
    suspend fun wifiScan(): List<WifiNetwork> = act {
        parseWifiScan(open.session.callOk(Op.WIFI_SCAN, ByteArray(0), Timeouts.WIFI_SCAN_MS))
    }

    suspend fun save(payload: String): SaveOutcome = act {
        val res = open.session.call(Op.SET_CONFIG, Bytes.ascii(payload))
        when (res.status) {
            Status.OK -> SaveOutcome.Saved(if (res.data.size >= 5) parseFarmResult(res.data) else null)
            Status.DIFFERENT_FARM, Status.FARM_FROM_KEY -> SaveOutcome.SavedFarmRefused(res.status, parseFarmResult(res.data))
            Status.ERROR_TEXT -> SaveOutcome.Refused(Bytes.toAscii(res.data).ifEmpty { "sem motivo" })
            else -> throw SmartboxStatusException(res.op, res.status, res.data)
        }
    }

    /** Desconecta (se salvou, a smartbox reinicia agora). */
    fun close() {
        closeSignal.complete(CloseReason.USER to null)
    }

    private suspend fun keepAliveLoop() {
        while (!closeSignal.isCompleted) {
            delay(CHECK_EVERY_MS)
            val now = System.currentTimeMillis()
            if (!open.session.isOpen) {
                closeSignal.complete(CloseReason.DROP to "A conexão com a smartbox caiu.")
                return
            }
            if (now - lastAction >= IDLE_MS) {
                closeSignal.complete(CloseReason.IDLE to "Fechada depois de 10 min sem uso.")
                return
            }
            if (busy.get() == 0 && now - lastCall >= KEEPALIVE_MS) {
                lastCall = now
                // Erro aqui quebra o link e o próximo tick fecha como queda.
                try { open.session.call(Op.GET_DIAG) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val KEEPALIVE_MS = 20_000L
        const val IDLE_MS = 10 * 60_000L
        private const val CHECK_EVERY_MS = 2_000L

        /**
         * Abre a sessão em [scope] (a da tela) e devolve quando está pronta.
         * [onClosed] é chamado uma vez, depois que a conexão fechou de fato.
         */
        suspend fun open(
            context: Context,
            target: SmartboxTarget,
            scope: CoroutineScope,
            onClosed: (CloseReason, String?) -> Unit,
        ): ConfigSession {
            val ready = CompletableDeferred<ConfigSession>()
            val closeSignal = CompletableDeferred<Pair<CloseReason, String?>>()
            scope.launch {
                try {
                    SmartboxConnection.withSession(
                        context = context,
                        target = target,
                        purpose = SmartboxTelemetry.Purpose.CONFIGURE,
                        collectMode = CollectMode.MANUAL,
                        waitIfBusy = true,
                    ) { open ->
                        val s = ConfigSession(open, closeSignal)
                        ready.complete(s)
                        launch { s.keepAliveLoop() }
                        closeSignal.await()
                    }
                    val (reason, msg) = closeSignal.await()
                    onClosed(reason, msg)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    ready.completeExceptionally(e)
                    throw e
                } catch (e: Exception) {
                    if (!ready.isCompleted) ready.completeExceptionally(e)
                    else onClosed(CloseReason.DROP, e.message)
                }
            }
            return ready.await()
        }
    }
}
