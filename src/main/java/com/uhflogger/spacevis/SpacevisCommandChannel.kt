package com.uhflogger.spacevis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Envio de comando com espera de resposta (guia, seção 8).
 *
 * - Um comando por vez: tudo passa pela mesma trava. Ela é reentrante para a
 *   sequência de configuração poder segurá-la inteira ([sequence]) e um STOP
 *   pedido no meio esperar o START sair — senão o leitor fica lendo.
 * - 2,5 s por tentativa, até 3 tentativas. Resposta certa = CMD or 0x80; as
 *   outras (atrasadas, da sonda do watchdog) são ignoradas. Uma resposta
 *   atrasada da tentativa anterior é aceita: todo comando é idempotente.
 * - [onResponse] é chamado pelo parser, na thread de leitura. [send] NUNCA pode
 *   rodar nessa thread (deadlock: quem libera a espera é ela).
 *
 * @param write escreve o frame na conexão; lança em erro de escrita.
 */
class SpacevisCommandChannel(
    private val write: (ByteArray) -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val attempts: Int = DEFAULT_ATTEMPTS,
) {
    sealed class Result {
        data class Response(val payload: ByteArray) : Result() {
            val status: Int get() = SpacevisProtocol.statusOf(payload)
            override fun equals(other: Any?): Boolean = other is Response && other.payload.contentEquals(payload)
            override fun hashCode(): Int = payload.contentHashCode()
        }
        /** Sem resposta depois de todas as tentativas: o ESP32 não responde. */
        object NoResponse : Result()
        /** Erro de escrita no Bluetooth: conexão perdida. */
        data class WriteFailed(val error: Throwable) : Result()
    }

    private class Pending(val expectedCmd: Int) {
        val signal = CountDownLatch(1)
        @Volatile var payload: ByteArray? = null
    }

    private val lock = ReentrantLock()
    @Volatile private var pending: Pending? = null
    @Volatile private var closed = false

    /** Executa [block] segurando a trava: nenhum outro comando sai no meio. */
    fun <T> sequence(block: () -> T): T {
        lock.lockInterruptibly()
        try { return block() } finally { lock.unlock() }
    }

    fun send(frame: ByteArray): Result {
        val cmd = frame[3].toInt() and 0xFF
        return sequence {
            var result: Result = Result.NoResponse
            for (attempt in 1..attempts) {
                if (closed) { result = Result.NoResponse; break }
                val p = Pending(SpacevisProtocol.responseCmd(cmd))
                pending = p
                try {
                    write(frame)
                } catch (e: Throwable) {
                    pending = null
                    return@sequence Result.WriteFailed(e)
                }
                val got = try {
                    p.signal.await(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                val payload = p.payload
                if (got && payload != null) { result = Result.Response(payload); break }
            }
            pending = null
            result
        }
    }

    /** Chamado pelo parser (thread de leitura). Nunca bloqueia. */
    fun onResponse(cmd: Int, payload: ByteArray) {
        val p = pending ?: return
        if (cmd != p.expectedCmd) return
        p.payload = payload
        p.signal.countDown()
    }

    /** Escrita direta, sem esperar: sonda do watchdog e STOP de emergência. */
    fun writeNoWait(frame: ByteArray) = write(frame)

    /** Conexão fechada: libera quem estiver esperando e recusa novas tentativas. */
    fun close() {
        closed = true
        pending?.signal?.countDown()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_500L
        const val DEFAULT_ATTEMPTS = 3
    }
}
