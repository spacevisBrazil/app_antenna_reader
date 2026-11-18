package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.SmartboxCrypto.Direction
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ESP falso para os testes: faz o lado da smartbox do handshake e da sessão
 * cifrada, com a mesma cripto do app. Porte de
 * spacevis-mobile/__tests__/features/smartbox/fakeSmartbox.ts.
 *
 * Usa SÓ a chave de bancada (00 01 … 1F, pública, Anexo A do guia). A chave de
 * campo nunca entra em teste.
 */
object FakeSmartboxFixtures {
    /** Chave de TESTE de bancada do Anexo A. Não é a K de campo. */
    val BENCH_KEY = ByteArray(32) { it.toByte() }
    const val MODULE_ID = "48E7299ED5B4"
    val NONCE_ESP: ByteArray = Bytes.fromHex("101112131415161718191a1b1c1d1e1f")
    const val NOW_MS = 1_762_000_000_000L

    val handshakeDeps = HandshakeDeps(
        key = BENCH_KEY,
        randomBytes = { n -> ByteArray(n) { 7 } },
        now = { NOW_MS },
    )

    /** Monta uma resposta `[op|0x80][status][dados]`. */
    fun reply(op: Int, status: Int, data: ByteArray = ByteArray(0)): ByteArray =
        Bytes.concat(byteArrayOf((op or 0x80).toByte(), status.toByte()), data)
}

/** null = o ESP fica mudo (simula timeout). */
typealias FakeHandler = (op: Int, data: ByteArray) -> ByteArray?

class FakeSmartbox(
    private val mode: Int = 1,
    private val farmId: Long = 85,
    private val proto: Int = 2,
    private val mac: String = FakeSmartboxFixtures.MODULE_ID.lowercase(),
    /** Chave que o ESP usa para o AUTH_OK (≠ da do app = ESP falso). */
    private val espKey: ByteArray = FakeSmartboxFixtures.BENCH_KEY,
    private val handler: FakeHandler? = null,
) {
    data class Received(val op: Int, val data: ByteArray)

    val received = mutableListOf<Received>()
    var authTimeMs: Long? = null
        private set

    private val reassembler = Reassembler()
    private var ks: ByteArray? = null
    private var rx = 0L
    private var tx = 0L
    private lateinit var link: SmartboxLink

    val port = object : GattPort {
        override val mtu: Int = 185
        override suspend fun writePart(part: ByteArray) {
            reassembler.push(part)?.let { onMessage(it) }
        }
    }

    fun openLink(): SmartboxLink = SmartboxLink(port).also { link = it }

    private fun deliver(msg: ByteArray) {
        // Entrega síncrona por notify: o link já registrou a espera antes do write.
        for (part in Framing.split(msg, 517)) link.handleNotify(part)
    }

    private fun onMessage(msg: ByteArray) {
        val key = ks
        if (key == null) {
            if (msg.size == 1 && msg[0].toInt() == Op.HI) {
                deliver(
                    Bytes.concat(
                        Bytes.fromHex("8100"),
                        FakeSmartboxFixtures.NONCE_ESP,
                        Bytes.fromHex(mac),
                        byteArrayOf(proto.toByte()),
                        Bytes.fromHex("000000"),
                        byteArrayOf(mode.toByte()),
                        byteArrayOf(0x02, 0),
                        Bytes.u32(farmId),
                        Bytes.fromHex("0100"),
                        Bytes.ascii("fw-test"),
                    ),
                )
                return
            }
            if (msg.size == 57 && msg[0].toInt() == Op.AUTH) {
                val nonceCel = msg.copyOfRange(1, 17)
                var t = 0L
                for (i in 0 until 8) t = t or ((msg[17 + i].toLong() and 0xFF) shl (8 * i))
                authTimeMs = t
                val hmac = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(espKey, "HmacSHA256"))
                    doFinal(Bytes.concat(Bytes.ascii("esp"), nonceCel, FakeSmartboxFixtures.NONCE_ESP))
                }
                ks = SmartboxCrypto.deriveSessionKey(FakeSmartboxFixtures.BENCH_KEY, FakeSmartboxFixtures.NONCE_ESP, nonceCel)
                deliver(Bytes.concat(Bytes.fromHex("8200"), hmac))
            }
            return
        }
        val plain = SmartboxCrypto.open(key, Direction.APP_TO_ESP, rx++, msg)
        val op = plain[0].toInt() and 0xFF
        val data = plain.copyOfRange(1, plain.size)
        received += Received(op, data)
        val answer = handler?.invoke(op, data) ?: if (handler == null) FakeSmartboxFixtures.reply(op, 0) else null
        if (answer == null) return
        deliver(SmartboxCrypto.seal(key, Direction.ESP_TO_APP, tx++, answer))
    }
}
