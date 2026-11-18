package com.uhflogger.smartbox.ble

/**
 * Utilitários de bytes do protocolo da smartbox. Tudo little-endian.
 *
 * Kotlin não tem byte sem sinal no array, então toda leitura passa por [u8].
 * Valores u32 (fazenda, last_seq, pendentes do ACK) saem como Long: não cabem
 * num Int.
 */
internal object Bytes {

    fun u8(d: ByteArray, p: Int): Int = if (p in d.indices) d[p].toInt() and 0xFF else 0

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            p.copyInto(out, offset)
            offset += p.size
        }
        return out
    }

    fun ascii(text: String): ByteArray {
        val out = ByteArray(text.length)
        for (i in text.indices) {
            val code = text[i].code
            require(code <= 0x7F) { "Texto não é ASCII" }
            out[i] = code.toByte()
        }
        return out
    }

    /** Bytes → texto, byte a byte (o firmware só manda ASCII). */
    fun toAscii(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) sb.append((b.toInt() and 0xFF).toChar())
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun toHex(bytes: ByteArray, upper: Boolean = false): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return if (upper) sb.toString().uppercase() else sb.toString()
    }

    /** Aceita espaços e ':' entre os pares. Lança [IllegalArgumentException] se inválido. */
    fun fromHex(hex: String): ByteArray {
        val clean = hex.filterNot { it.isWhitespace() || it == ':' }
        require(clean.length % 2 == 0 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Hex inválido"
        }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun readU16(d: ByteArray, p: Int): Int = u8(d, p) or (u8(d, p + 1) shl 8)

    fun readU24(d: ByteArray, p: Int): Int = u8(d, p) or (u8(d, p + 1) shl 8) or (u8(d, p + 2) shl 16)

    fun readU32(d: ByteArray, p: Int): Long = readU16(d, p).toLong() or (readU16(d, p + 2).toLong() shl 16)

    fun readI16(d: ByteArray, p: Int): Int = readU16(d, p).toShort().toInt()

    fun u32(value: Long): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )

    /** int64 LE a partir de ms Unix. */
    fun i64Ms(ms: Long): ByteArray {
        require(ms >= 0) { "Hora inválida" }
        return ByteArray(8) { i -> (ms ushr (8 * i)).toByte() }
    }

    /** Comparação em tempo constante (o tamanho não é segredo). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
