package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.SmartboxProtocol as P

/**
 * Fragmentação das mensagens sobre o GATT (seção 4.4 do guia).
 *
 * Toda escrita no RX e todo notify do TX = `[cabeçalho 1 B][pedaço]`:
 * bit 7 = última parte, bits 0–6 = número da parte (0..127). Pedaço ≤ MTU − 4.
 * Parte fora de ordem, mensagem grande demais ou parte vazia = link perdido:
 * quem recebe o erro derruba a conexão (o estado não é recuperável).
 */
class FramingException(message: String) : Exception(message)

object Framing {
    /** Parte a mensagem (já cifrada, se for o caso) em pedaços prontos para o write. */
    fun split(message: ByteArray, mtu: Int): List<ByteArray> {
        if (message.isEmpty()) throw FramingException("Mensagem vazia")
        if (message.size > P.MAX_WIRE_MESSAGE) throw FramingException("Mensagem grande demais")
        val chunkSize = maxOf(P.DEFAULT_MTU, mtu) - P.PART_OVERHEAD
        val parts = ArrayList<ByteArray>()
        var offset = 0
        while (offset < message.size) {
            val index = parts.size
            if (index >= P.MAX_PARTS) throw FramingException("Mensagem com partes demais")
            val end = minOf(offset + chunkSize, message.size)
            val isLast = end >= message.size
            val header = ((if (isLast) P.PART_LAST_FLAG else 0) or index).toByte()
            parts += Bytes.concat(byteArrayOf(header), message.copyOfRange(offset, end))
            offset = end
        }
        return parts
    }
}

/**
 * Remonta as partes que chegam por notify. [push] devolve a mensagem inteira
 * quando chega a última parte, null enquanto falta. Qualquer violação lança
 * [FramingException] e zera o estado. Não é thread-safe: o [SmartboxLink] serializa.
 */
class Reassembler {
    private val chunks = ArrayList<ByteArray>()
    private var size = 0

    fun push(part: ByteArray): ByteArray? {
        if (part.isEmpty()) {
            reset()
            throw FramingException("Notify vazio")
        }
        val header = Bytes.u8(part, 0)
        val index = header and P.PART_INDEX_MASK
        val isLast = header and P.PART_LAST_FLAG != 0
        if (index != chunks.size) {
            reset()
            throw FramingException("Parte fora de ordem ($index)")
        }
        val data = part.copyOfRange(1, part.size)
        size += data.size
        if (size > P.MAX_WIRE_MESSAGE) {
            reset()
            throw FramingException("Mensagem grande demais")
        }
        chunks += data
        if (!isLast) return null

        val message = Bytes.concat(*chunks.toTypedArray())
        reset()
        if (message.isEmpty()) throw FramingException("Mensagem vazia")
        return message
    }

    fun reset() {
        chunks.clear()
        size = 0
    }
}
