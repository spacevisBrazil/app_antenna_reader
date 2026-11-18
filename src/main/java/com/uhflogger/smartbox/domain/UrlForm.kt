package com.uhflogger.smartbox.domain

import java.io.ByteArrayOutputStream

/**
 * Codificação do formulário da smartbox (seções 12.1 e 12.5 do guia). Porte
 * do urlForm.ts.
 *
 * O firmware fala `application/x-www-form-urlencoded` em ASCII e conta BYTES
 * (UTF-8), não caracteres.
 */
object UrlForm {

    fun utf8Encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** Bytes UTF-8 → texto. Sequência inválida vira U+FFFD (o SSID pode não ser UTF-8). */
    fun utf8Decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    fun utf8Length(text: String): Int = utf8Encode(text).size

    private fun unreserved(b: Int): Boolean =
        b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code ||
            b == '-'.code || b == '_'.code || b == '.'.code || b == '~'.code

    /** Bytes → forma `%XX` (ASCII puro), mantendo só os não reservados em claro. */
    fun percentEncodeBytes(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            if (b < 0x80 && unreserved(b)) sb.append(b.toChar())
            else sb.append('%').append("%02X".format(b))
        }
        return sb.toString()
    }

    /** Texto → forma urlencoded em ASCII. */
    fun formEncode(text: String): String = percentEncodeBytes(utf8Encode(text))

    private val HEX2 = Regex("^[0-9A-Fa-f]{2}$")

    /** Forma urlencoded (`%XX`, `+`) → bytes exatos. `%` malformado fica como veio. */
    fun formDecodeBytes(encoded: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < encoded.length) {
            val ch = encoded[i]
            when {
                ch == '+' -> { out.write(0x20); i++ }
                ch == '%' && i + 3 <= encoded.length && HEX2.matches(encoded.substring(i + 1, i + 3)) -> {
                    out.write(encoded.substring(i + 1, i + 3).toInt(16))
                    i += 3
                }
                else -> {
                    val cp = encoded.codePointAt(i)
                    out.write(utf8Encode(String(Character.toChars(cp))))
                    i += Character.charCount(cp)
                }
            }
        }
        return out.toByteArray()
    }

    fun formDecode(encoded: String): String = utf8Decode(formDecodeBytes(encoded))
}
