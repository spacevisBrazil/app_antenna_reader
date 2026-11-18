package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.FakeSmartboxFixtures.MODULE_ID
import com.uhflogger.smartbox.ble.FakeSmartboxFixtures.handshakeDeps
import com.uhflogger.smartbox.ble.FakeSmartboxFixtures.reply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Porte de spacevis-mobile/__tests__/features/smartbox/session.spec.ts (parte da sessão). */
class SessionTest {

    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("esperava ${T::class.simpleName}, veio ${t::class.simpleName}: ${t.message}", t)
        }
        fail("esperava ${T::class.simpleName}")
        throw IllegalStateException()
    }

    private suspend fun connect(fake: FakeSmartbox): Triple<FakeSmartbox, SmartboxLink, SmartboxSession> {
        val link = fake.openLink()
        val session = SmartboxSession(link)
        session.handshake(MODULE_ID, handshakeDeps)
        return Triple(fake, link, session)
    }

    @Test
    fun `faz o handshake, manda a hora do aparelho e conversa cifrado`() = runTest {
        val (fake, _, session) = connect(
            FakeSmartbox(handler = { op, _ ->
                if (op == Op.GET_DIAG) reply(op, 0, Bytes.ascii("firmware=1.0\n")) else reply(op, 0)
            }),
        )
        assertEquals("fw-test", session.challenge.firmware)
        assertEquals(85L, session.challenge.farmId)
        assertEquals(handshakeDeps.now(), fake.authTimeMs)

        assertEquals("firmware=1.0\n", Bytes.toAscii(session.callOk(Op.GET_DIAG)))
        // Os contadores sobem por mensagem: a segunda chamada também abre.
        session.callOk(Op.GET_DIAG)
        assertEquals(listOf(Op.GET_DIAG, Op.GET_DIAG), fake.received.map { it.op })
        assertTrue(session.isOpen)
    }

    @Test
    fun `recusa ESP que nao prova a chave`() = runTest {
        val link = FakeSmartbox(espKey = ByteArray(32) { 9 }).openLink()
        val e = assertThrowsSuspend<HandshakeException> { SmartboxSession(link).handshake(MODULE_ID, handshakeDeps) }
        assertEquals(HandshakeException.Kind.FAKE_DEVICE, e.kind)
        assertTrue(link.isBroken)
    }

    @Test
    fun `app com chave errada nao abre sessao`() = runTest {
        // O ESP fala com a de bancada; o app tenta outra (ex.: build com K velha).
        val link = FakeSmartbox().openLink()
        val wrong = HandshakeDeps(ByteArray(32) { 0x55 }, handshakeDeps.randomBytes, handshakeDeps.now)
        assertThrowsSuspend<HandshakeException> { SmartboxSession(link).handshake(MODULE_ID, wrong) }
        assertTrue(link.isBroken)
    }

    @Test
    fun `recusa MAC diferente do anuncio e protocolo diferente`() = runTest {
        val other = FakeSmartbox(mac = "000000000001").openLink()
        assertEquals(
            HandshakeException.Kind.WRONG_MODULE,
            assertThrowsSuspend<HandshakeException> { SmartboxSession(other).handshake(MODULE_ID, handshakeDeps) }.kind,
        )
        val old = FakeSmartbox(proto = 1).openLink()
        assertEquals(
            HandshakeException.Kind.PROTOCOL_VERSION,
            assertThrowsSuspend<HandshakeException> { SmartboxSession(old).handshake(MODULE_ID, handshakeDeps) }.kind,
        )
    }

    @Test
    fun `nao conecta com o relogio do aparelho antes de 2024`() = runTest {
        val link = FakeSmartbox().openLink()
        val deps = HandshakeDeps(handshakeDeps.key, handshakeDeps.randomBytes) { 0L }
        assertEquals(
            HandshakeException.Kind.CLOCK,
            assertThrowsSuspend<HandshakeException> { SmartboxSession(link).handshake(MODULE_ID, deps) }.kind,
        )
    }

    @Test
    fun `status de erro nao quebra a sessao`() = runTest {
        val (_, link, session) = connect(
            FakeSmartbox(handler = { op, _ ->
                if (op == Op.SET_FARM) reply(op, Status.DIFFERENT_FARM, Bytes.concat(Bytes.u32(7), byteArrayOf(0))) else reply(op, 0)
            }),
        )
        val e = assertThrowsSuspend<SmartboxStatusException> { session.callOk(Op.SET_FARM, ByteArray(5)) }
        assertEquals(7L, parseFarmResult(e.data).farmId)
        assertFalse(link.isBroken)
        session.callOk(Op.GET_DIAG)
    }

    @Test
    fun `timeout quebra o link`() = runTest {
        val (_, link, session) = connect(FakeSmartbox(handler = { _, _ -> null }))
        assertThrowsSuspend<LinkBrokenException> { session.call(Op.GET_DIAG) }
        assertTrue(link.isBroken)
        // Morto é morto: a próxima pergunta nem sai.
        assertThrowsSuspend<LinkBrokenException> { session.call(Op.GET_DIAG) }
    }

    @Test
    fun `quebra avisa os ouvintes uma vez so`() = runTest {
        val link = FakeSmartbox().openLink()
        var calls = 0
        link.onBroken { calls++ }
        link.breakLink("queda")
        link.breakLink("queda de novo")
        assertEquals(1, calls)
    }

    @Test
    fun `notify sem pedido quebra o link`() {
        val link = FakeSmartbox().openLink()
        link.handleNotify(byteArrayOf(0x80.toByte(), 0x01))
        assertTrue(link.isBroken)
    }
}
