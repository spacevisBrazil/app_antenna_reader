package com.uhflogger.spacevis

/**
 * Stream SPP → frames SPACEVIS_PROTOCOL.
 *
 * O SPP não preserva fronteira de mensagem: um frame chega picado e um pedaço
 * traz vários frames, com eventos e respostas misturados e às vezes lixo. Aqui
 * se acumula, acha o 55 AA, valida o CRC e ressincroniza sozinho.
 *
 * Regras que vieram de bug real (não "simplificar"):
 * - CRC errado descarta **1 byte**, não o frame: o 55 AA pode ter sido falso e o
 *   frame verdadeiro começar dentro dele.
 * - Nenhuma exceção escapa de [feed]: na thread de leitura, uma exceção já matou
 *   a recepção para sempre (bug histórico #18). Os callbacks são protegidos um a um.
 * - Roda na thread de leitura: os callbacks não podem bloquear.
 *
 * Não é thread-safe: um parser por conexão, alimentado só pela thread de leitura.
 */
class SpacevisFrameParser(
    private val onTag: (SpacevisProtocol.TagEvent) -> Unit,
    private val onResponse: (cmd: Int, payload: ByteArray) -> Unit,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    /** Frames com CRC inválido descartados (diagnóstico). */
    var crcErrors: Long = 0
        private set
    var malformedEvents: Long = 0
        private set

    fun feed(data: ByteArray, length: Int = data.size) {
        try {
            append(data, length)
            drain()
        } catch (e: Throwable) {
            // Estado inconsistente não pode sobreviver ao erro: zera e segue.
            size = 0
            onError("parser: erro inesperado, buffer descartado", e)
        }
    }

    fun reset() { size = 0 }

    private fun append(data: ByteArray, length: Int) {
        if (length <= 0) return
        // Depois de cada drain() sobra no máximo um frame incompleto (< 261
        // bytes), então o buffer só cresce se um único pedaço vier grande.
        if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
        System.arraycopy(data, 0, buf, size, length)
        size += length
    }

    private fun drain() {
        var pos = 0
        while (true) {
            // Descarta tudo que não for 0x55.
            while (pos < size && (buf[pos].toInt() and 0xFF) != SpacevisProtocol.SYNC_1) pos++
            if (size - pos < 3) break
            if ((buf[pos + 1].toInt() and 0xFF) != SpacevisProtocol.SYNC_2) { pos++; continue }
            val len = buf[pos + 2].toInt() and 0xFF
            val total = len + SpacevisProtocol.FRAME_OVERHEAD
            if (size - pos < total) break
            val crcRx = (buf[pos + total - 2].toInt() and 0xFF) or
                ((buf[pos + total - 1].toInt() and 0xFF) shl 8)
            if (SpacevisProtocol.crc16(buf, pos + 2, pos + total - 2) != crcRx) {
                crcErrors++
                pos++
                continue
            }
            val cmd = buf[pos + 3].toInt() and 0xFF
            val payload = buf.copyOfRange(pos + 4, pos + 4 + len)
            pos += total
            dispatch(cmd, payload)
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, size - pos)
            size -= pos
        }
    }

    private fun dispatch(cmd: Int, payload: ByteArray) {
        try {
            if (cmd == SpacevisProtocol.EVT_TAG) {
                val evt = SpacevisProtocol.parseTagEvent(payload)
                if (evt == null) { malformedEvents++; return }
                onTag(evt)
            } else {
                onResponse(cmd, payload)
            }
        } catch (e: Throwable) {
            onError("parser: callback falhou para cmd=0x%02X — descartado, leitura CONTINUA".format(cmd), e)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
