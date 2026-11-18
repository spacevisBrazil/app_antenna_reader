package com.uhflogger.smartbox.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/** O mínimo que o link precisa do rádio. A implementação real (GATT) vem no PR 2. */
interface GattPort {
    /** MTU negociado (mínimo 23). */
    val mtu: Int

    /** Write COM resposta no RX (controle de fluxo). Lança se falhar. */
    suspend fun writePart(part: ByteArray)
}

class LinkBrokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Link de mensagens sobre o GATT (seção 4.4 do guia).
 *
 * Junta as partes do notify numa mensagem e parte as mensagens no write. Não
 * sabe nada de cripto nem de opcodes: isso é da [SmartboxSession].
 *
 * Regras:
 * - a espera pela resposta é registrada ANTES de escrever (a resposta pode
 *   chegar antes do write voltar);
 * - uma mensagem por vez: chegou mensagem sem ninguém esperando = link quebrado;
 * - qualquer erro (partes, timeout, queda, cancelamento) QUEBRA o link de vez.
 *   Resposta atrasada dessincronizaria os contadores do GCM, então não há como continuar.
 *
 * [handleNotify] vem da thread do binder do GATT; o resto, de coroutines. O
 * estado mutável fica sob [lock].
 */
class SmartboxLink(private val port: GattPort) {
    private val lock = Any()
    private val reassembler = Reassembler()
    private var waiter: CompletableDeferred<ByteArray>? = null
    @Volatile private var brokenError: LinkBrokenException? = null
    private val brokenListeners = CopyOnWriteArrayList<(LinkBrokenException) -> Unit>()

    val isBroken: Boolean get() = brokenError != null

    /** Avisado uma vez, quando o link quebra (quem escuta desconecta). */
    fun onBroken(cb: (LinkBrokenException) -> Unit): () -> Unit {
        brokenListeners += cb
        return { brokenListeners -= cb }
    }

    /** Entrada do notify do TX. */
    fun handleNotify(part: ByteArray) {
        var target: CompletableDeferred<ByteArray>? = null
        var message: ByteArray? = null
        synchronized(lock) {
            if (brokenError != null) return
            val m = try {
                reassembler.push(part)
            } catch (e: FramingException) {
                breakLocked(e.message ?: "Parte inválida", e)
                null
            }
            if (m != null) {
                val w = waiter
                if (w == null) {
                    breakLocked("Mensagem sem pedido", null)
                } else {
                    waiter = null
                    target = w
                    message = m
                }
            }
        }
        // Completa fora do lock: quem acorda pode chamar o link de novo na hora.
        val m = message
        if (m != null) target?.complete(m)
        notifyIfBroken()
    }

    /**
     * Manda [message] e espera a próxima mensagem. Timeout (contado do registro
     * da espera, como no app de referência) quebra o link.
     */
    suspend fun exchange(message: ByteArray, timeoutMs: Long): ByteArray {
        val w = CompletableDeferred<ByteArray>()
        synchronized(lock) {
            brokenError?.let { throw it }
            if (waiter != null) throw LinkBrokenException("Já há um pedido em andamento")
            waiter = w
        }
        try {
            val reply = withTimeoutOrNull(timeoutMs) {
                send(message)
                w.await()
            }
            if (reply == null) {
                breakLink("A smartbox não respondeu a tempo")
                throw brokenError!!
            }
            return reply
        } catch (e: CancellationException) {
            // Quem chamou desistiu no meio: a resposta, se vier, chegaria sem
            // dono e com o contador errado. Morre aqui.
            breakLink("Pedido cancelado", e)
            throw e
        }
    }

    private suspend fun send(message: ByteArray) {
        brokenError?.let { throw it }
        val parts = try {
            Framing.split(message, port.mtu)
        } catch (e: FramingException) {
            breakLink("Mensagem inválida", e)
            throw brokenError!!
        }
        for (part in parts) {
            brokenError?.let { throw it }
            try {
                port.writePart(part)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                breakLink("Falha ao escrever na smartbox", e)
                throw brokenError!!
            }
        }
    }

    /** Quebra o link (queda de conexão, erro de cripto, op trocado…). Idempotente. */
    fun breakLink(reason: String, cause: Throwable? = null) {
        synchronized(lock) { breakLocked(reason, cause) }
        notifyIfBroken()
    }

    private var notified = false

    private fun breakLocked(reason: String, cause: Throwable?) {
        if (brokenError != null) return
        val err = LinkBrokenException(reason, cause)
        brokenError = err
        reassembler.reset()
        waiter?.completeExceptionally(err)
        waiter = null
    }

    /** Listeners rodam FORA do lock: um deles pode desconectar o GATT. */
    private fun notifyIfBroken() {
        val err = synchronized(lock) {
            val e = brokenError
            if (e == null || notified) return
            notified = true
            e
        }
        for (cb in brokenListeners) {
            try {
                cb(err)
            } catch (_: Exception) {
                // listener com erro não pode impedir os outros de saber da queda
            }
        }
    }
}
