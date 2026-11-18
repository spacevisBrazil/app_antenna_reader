package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.SmartboxProtocol as P
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cripto do handshake e da sessão da smartbox (seções 4.5 e 4.6 do guia).
 *
 * HMAC-SHA256 e AES-128-GCM do próprio javax.crypto (API 26+, sem lib). Os
 * vetores do Anexo A do guia estão em CryptoTest.
 *
 * Nada aqui loga: chave de campo, Ks e nonces nunca saem do processo.
 */
object SmartboxCrypto {

    /** Direção no nonce do GCM. */
    enum class Direction(val byte: Int) { APP_TO_ESP(0), ESP_TO_APP(1) }

    private val LABEL_CEL = Bytes.ascii("cel")
    private val LABEL_ESP = Bytes.ascii("esp")
    private val LABEL_KS = Bytes.ascii("ks")
    private const val GCM_NONCE_LENGTH = 12

    private fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /**
     * AUTH = `02 | nonce_cel(16) | hora int64 LE(8) | HMAC(K, "cel"‖nonce_esp‖nonce_cel‖hora8)(32)`.
     * A hora do AUTH acerta o relógio da smartbox, sempre.
     */
    fun buildAuth(key: ByteArray, nonceEsp: ByteArray, nonceCel: ByteArray, nowMs: Long): ByteArray {
        require(nonceCel.size == P.NONCE_LENGTH && nonceEsp.size == P.NONCE_LENGTH) { "Nonce com tamanho errado" }
        val hora8 = Bytes.i64Ms(nowMs)
        val mac = hmacSha256(key, LABEL_CEL, nonceEsp, nonceCel, hora8)
        return Bytes.concat(byteArrayOf(Op.AUTH.toByte()), nonceCel, hora8, mac)
    }

    /**
     * Confere o AUTH_OK (`82 00 | HMAC(K, "esp"‖nonce_cel‖nonce_esp)`) em tempo
     * constante. Falso = ESP falso: desconectar sem gravar nada.
     */
    fun verifyAuthOk(key: ByteArray, authOk: ByteArray, nonceEsp: ByteArray, nonceCel: ByteArray): Boolean {
        if (authOk.size != P.AUTH_OK_LENGTH) return false
        if (Bytes.u8(authOk, 0) != (Op.AUTH or P.RESPONSE_FLAG) || Bytes.u8(authOk, 1) != 0) return false
        val expected = hmacSha256(key, LABEL_ESP, nonceCel, nonceEsp)
        return Bytes.constantTimeEquals(authOk.copyOfRange(2, authOk.size), expected)
    }

    /** Ks = 16 primeiros bytes de HMAC(K, "ks"‖nonce_esp‖nonce_cel). */
    fun deriveSessionKey(key: ByteArray, nonceEsp: ByteArray, nonceCel: ByteArray): ByteArray =
        hmacSha256(key, LABEL_KS, nonceEsp, nonceCel).copyOf(P.SESSION_KEY_LENGTH)

    /** Nonce GCM de 12 B: `[direção][7 × 00][contador u32 LE]`. */
    fun gcmNonce(direction: Direction, counter: Long): ByteArray {
        val n = ByteArray(GCM_NONCE_LENGTH)
        n[0] = direction.byte.toByte()
        Bytes.u32(counter).copyInto(n, 8)
        return n
    }

    private fun gcm(mode: Int, sessionKey: ByteArray, direction: Direction, counter: Long): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(P.GCM_TAG_LENGTH * 8, gcmNonce(direction, counter)))
        }

    /** AES-128-GCM sem AAD → `cifrado ‖ tag(16)`. */
    fun seal(sessionKey: ByteArray, direction: Direction, counter: Long, plain: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, sessionKey, direction, counter).doFinal(plain)

    /** Abre a mensagem. Lança se o tag não confere: quem chama derruba o link. */
    fun open(sessionKey: ByteArray, direction: Direction, counter: Long, sealed: ByteArray): ByteArray =
        gcm(Cipher.DECRYPT_MODE, sessionKey, direction, counter).doFinal(sealed)
}
