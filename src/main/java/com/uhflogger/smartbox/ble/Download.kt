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
    /**
     * Consultado depois de cada ACK: true = parar ali (ex.: o leitor pediu o
     * rádio). Nunca interrompe entre commit e ACK.
     */
    val shouldStop: () -> Boolean = { false },
)

data class DownloadResult(
    val batches: Int,
    val readings: Int,
    val remaining: Long,
    val durationMs: Long,
    /** true = parou a pedido de [DownloadDeps.shouldStop], com lote(s) ainda na smartbox. */
    val stoppedEarly: Boolean,
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

            // Commit primeiro. Só então o ACK.
            deps.persistBatch(readings)

            remaining = parseAckResponse(ensureOk(session.call(Op.ACK, buildAckRequest(head.lastSeq))))
            lastAckedSeq = head.lastSeq
            batches += 1
            received += readings.size
            val elapsed = maxOf(1L, deps.now() - startedAt)
            deps.onProgress(DownloadProgress(received, remaining, (received * 1000L / elapsed).toInt()))

            if (remaining > 0 && deps.shouldStop()) {
                return DownloadResult(batches, received, remaining, deps.now() - startedAt, stoppedEarly = true)
            }
        }

        return DownloadResult(batches, received, remaining, deps.now() - startedAt, stoppedEarly = false)
    }
}
