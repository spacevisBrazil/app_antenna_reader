package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.SmartboxProtocol as P

/** O que a descarga precisa da sessão (facilita o teste). */
interface DownloadSession {
    suspend fun call(op: Int, data: ByteArray = ByteArray(0), timeoutMs: Long = Timeouts.RESPONSE_MS): Response
}

data class DownloadProgress(
    /** Leituras gravadas nesta conexão. */
    val received: Int,
    /** Ainda na smartbox (do último ACK; null antes do primeiro). */
    val remaining: Long?,
    val ratePerSecond: Int,
)

/** O que a descarga precisa de fora. */
class DownloadDeps(
    /**
     * Grava o lote inteiro e só retorna DEPOIS do commit. Lançar aqui impede o
     * ACK: o lote volta na próxima conexão.
     */
    val persistBatch: suspend (List<Reading>) -> Unit,
    val now: () -> Long,
    val onProgress: (DownloadProgress) -> Unit = {},
    /** Pedido de parada vindo de fora (o leitor quer o rádio). Ver [DownloadControl]. */
    val control: DownloadControl = DownloadControl(),
)

/**
 * Como parar uma descarga em andamento sem perder dado (regras do Igor:
 * "Parar e ler brincos" e "Iniciar leitura" no meio da coleta).
 *
 * - [requestStop]: parada normal. A descarga termina o lote em mãos (commit +
 *   ACK) e para antes de pedir o próximo.
 * - [forceCut]: o teto de espera venceu. Fora da janela commit → ACK, corta
 *   na hora (o lote em mãos volta na próxima conexão, o banco não duplica).
 *   DENTRO da janela, não corta: espera o commit terminar, e a descarga sai
 *   SEM mandar o ACK. Cortar no meio de uma transação do banco é seguro, mas
 *   desperdiça o lote; mandar o ACK depois de "cortado" contradiz o pedido.
 *
 * Thread-safe: quem pede é a UI/yield; quem consulta é a coroutine da descarga.
 */
class DownloadControl {
    private val lock = Any()
    @Volatile var stopRequested = false
        private set
    private var forced = false
    private var committing = false

    /** Progresso visto pelo último ACK (para o evento Collect Yielded). */
    @Volatile var received = 0
        internal set
    @Volatile var remaining: Long? = null
        internal set

    fun requestStop() {
        stopRequested = true
    }

    /** @return true se cortou agora; false se adiou para o fim do commit (sem ACK). */
    fun forceCut(cut: () -> Unit): Boolean {
        val cutNow = synchronized(lock) {
            stopRequested = true
            forced = true
            !committing
        }
        if (cutNow) cut()
        return cutNow
    }

    val isForced: Boolean get() = synchronized(lock) { forced }

    internal fun beginCommit() = synchronized(lock) { committing = true }

    internal fun endCommit() = synchronized(lock) { committing = false }
}

data class DownloadResult(
    val batches: Int,
    val readings: Int,
    val remaining: Long,
    val durationMs: Long,
    /** true = parou a pedido do [DownloadControl], com lote(s) ainda na smartbox. */
    val stoppedEarly: Boolean,
    /** true = o corte forçado caiu durante o commit: o lote foi gravado mas NÃO confirmado (volta na próxima). */
    val ackSkipped: Boolean = false,
)

class DownloadException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { CONFIG_MODE }
}

/**
 * Descarga LOTE / ACK (seção 6 do guia).
 *
 * Regras de ouro (não negociáveis):
 * - o ACK NUNCA sai antes do commit no banco local: ACK antes do commit + app
 *   morto = leitura perdida para sempre;
 * - desconectar antes do ACK não marca nada: o mesmo lote volta na próxima
 *   conexão, e o banco (chave única + INSERT OR IGNORE) não duplica;
 * - qualquer inconsistência do lote derruba o link (o estado não é recuperável).
 */
object SmartboxDownload {
    private const val CLOCK_ERROR_PREFIX = "relogio sem hora real"

    private fun ensureOk(res: Response): ByteArray {
        if (res.status == Status.UNAVAILABLE_IN_MODE) {
            throw DownloadException(
                "Smartbox em modo configuração: a descarga só funciona no modo operação Bluetooth.",
                DownloadException.Kind.CONFIG_MODE,
            )
        }
        if (res.status != Status.OK) throw SmartboxStatusException(res.op, res.status, res.data)
        return res.data
    }

    suspend fun downloadAll(session: DownloadSession, deps: DownloadDeps): DownloadResult {
        val startedAt = deps.now()
        var received = 0
        var remaining = 0L
        var batches = 0
        var lastAckedSeq: Long? = null
        var clockFixed = false

        while (true) {
            var first = session.call(Op.LOTE, buildLoteRequest(0))

            // Relógio sem hora real: SET_TIME com a hora do aparelho e LOTE 0 de
            // novo, UMA vez por conexão (o AUTH quase sempre já acerta a hora).
            if (first.status == Status.ERROR_TEXT &&
                Bytes.toAscii(first.data).startsWith(CLOCK_ERROR_PREFIX) &&
                !clockFixed
            ) {
                clockFixed = true
                ensureOk(session.call(Op.SET_TIME, Bytes.i64Ms(deps.now())))
                first = session.call(Op.LOTE, buildLoteRequest(0))
            }

            val head = parseLotePart(ensureOk(first))
            if (head.total == 0) break
            if (head.part != 0) throw ProtocolException("LOTE: parte trocada")
            if (lastAckedSeq != null && head.lastSeq == lastAckedSeq) {
                throw ProtocolException("LOTE repetiu o lote já confirmado")
            }

            val readings = ArrayList(head.readings)
            var counted = head.count
            var part = 1
            while (part * P.LOTE_RECORDS_PER_PART < head.total) {
                val next = parseLotePart(ensureOk(session.call(Op.LOTE, buildLoteRequest(part))))
                if (next.part != part || next.total != head.total || next.lastSeq != head.lastSeq) {
                    throw ProtocolException("LOTE: parte inconsistente")
                }
                counted += next.count
                readings.addAll(next.readings)
                part++
            }
            if (counted != head.total) throw ProtocolException("LOTE: soma dos registros não bate")

            // Commit primeiro. Só então o ACK. O corte forçado não entra nesta janela.
            val control = deps.control
            control.beginCommit()
            val mayAck = try {
                deps.persistBatch(readings)
            } finally {
                // Também no erro do commit, para não deixar o corte forçado travado.
                control.endCommit()
            }.let { !control.isForced }
            if (!mayAck) {
                // Corte pedido durante o commit: sai sem ACK. O lote já está no
                // banco e volta na próxima conexão; o INSERT OR IGNORE não duplica.
                return DownloadResult(batches, received, remaining, deps.now() - startedAt, stoppedEarly = true, ackSkipped = true)
            }

            remaining = parseAckResponse(ensureOk(session.call(Op.ACK, buildAckRequest(head.lastSeq))))
            lastAckedSeq = head.lastSeq
            batches += 1
            received += readings.size
            val elapsed = maxOf(1L, deps.now() - startedAt)
            deps.onProgress(DownloadProgress(received, remaining, (received * 1000L / elapsed).toInt()))
            control.received = received
            control.remaining = remaining

            if (remaining > 0 && control.stopRequested) {
                return DownloadResult(batches, received, remaining, deps.now() - startedAt, stoppedEarly = true)
            }
        }

        return DownloadResult(batches, received, remaining, deps.now() - startedAt, stoppedEarly = false)
    }
}
