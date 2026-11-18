package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.SmartboxCrypto.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Vetores do Anexo A do guia da smartbox, com a chave de TESTE de bancada
 * (00 01 … 1F). Regressão da cripto: se algum destes quebrar, o app deixa de
 * falar com o firmware. Os mesmos vetores do spacevis-mobile (crypto.spec.ts).
 */
class CryptoTest {
    private val key = FakeSmartboxFixtures.BENCH_KEY
    private val nonceEsp = Bytes.fromHex("101112131415161718191a1b1c1d1e1f")
    private val nonceCel = Bytes.fromHex("202122232425262728292a2b2c2d2e2f")
    private val nowMs = 1_700_000_000_000L
    private val ks = "535f12d243a3d6eedad9b5fae396bd1e"

    @Test
    fun `codifica a hora como int64 LE`() {
        assertEquals("0068e5cf8b010000", Bytes.toHex(Bytes.i64Ms(nowMs)))
    }

    @Test
    fun `monta o AUTH de 57 bytes`() {
        val auth = SmartboxCrypto.buildAuth(key, nonceEsp, nonceCel, nowMs)
        assertEquals(57, auth.size)
        assertEquals(
            "02" +
                "202122232425262728292a2b2c2d2e2f" +
                "0068e5cf8b010000" +
                "7f8e7fb30479bccf2bcbc95668f0ec28d9485eb1a64b42ee9b78eb3969879444",
            Bytes.toHex(auth),
        )
    }

    @Test
    fun `confere o AUTH_OK e recusa um HMAC adulterado`() {
        val authOk = Bytes.fromHex("8200" + "e07d8845fbce832fe6bbd87f4925f610b52124367ecf4438a0ebc3c13f767378")
        assertTrue(SmartboxCrypto.verifyAuthOk(key, authOk, nonceEsp, nonceCel))

        val forged = authOk.copyOf().also { it[33] = (it[33].toInt() xor 0x01).toByte() }
        assertFalse(SmartboxCrypto.verifyAuthOk(key, forged, nonceEsp, nonceCel))
        assertFalse(SmartboxCrypto.verifyAuthOk(key, authOk.copyOf(33), nonceEsp, nonceCel))
    }

    @Test
    fun `deriva a chave de sessao Ks`() {
        assertEquals(ks, Bytes.toHex(SmartboxCrypto.deriveSessionKey(key, nonceEsp, nonceCel)))
    }

    @Test
    fun `cifra app para ESP com o contador implicito`() {
        val k = Bytes.fromHex(ks)
        assertEquals(
            "72db54c9018acb088089d7a0bc97550a31",
            Bytes.toHex(SmartboxCrypto.seal(k, Direction.APP_TO_ESP, 0, Bytes.fromHex("10"))),
        )
        assertEquals(
            "3a9636d1cb5badd1e8e895a06d2e6a82dccb",
            Bytes.toHex(SmartboxCrypto.seal(k, Direction.APP_TO_ESP, 1, Bytes.fromHex("2000"))),
        )
    }

    @Test
    fun `abre ESP para app e lanca com tag invalido`() {
        val k = Bytes.fromHex(ks)
        val sealed = Bytes.fromHex("a1e53deb62e9d6247f12e983fe0603d5233b5fcc")
        assertEquals("90006f6b", Bytes.toHex(SmartboxCrypto.open(k, Direction.ESP_TO_APP, 0, sealed)))
        // Contador errado = nonce errado = tag não confere.
        try {
            SmartboxCrypto.open(k, Direction.ESP_TO_APP, 1, sealed)
            fail("deveria lançar")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `nonce do GCM tem direcao e contador LE`() {
        assertEquals("010000000000000004030201", Bytes.toHex(SmartboxCrypto.gcmNonce(Direction.ESP_TO_APP, 0x01020304)))
        // u32: o contador alto não estoura para fora dos 4 bytes.
        assertEquals("0000000000000000ffffffff", Bytes.toHex(SmartboxCrypto.gcmNonce(Direction.APP_TO_ESP, 0xFFFF_FFFFL)))
    }
}
