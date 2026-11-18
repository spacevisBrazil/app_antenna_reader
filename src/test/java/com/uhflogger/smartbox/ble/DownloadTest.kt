package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.FakeSmartboxFixtures.MODULE_ID
import com.uhflogger.smartbox.ble.FakeSmartboxFixtures.handshakeDeps
import com.uhflogger.smartbox.ble.FakeSmartboxFixtures.reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Porte da parte `downloadAll` de spacevis-mobile/__tests__/features/smartbox/session.spec.ts. */
class DownloadTest {

    private fun record(epcByte: Int, ts: Long): ByteArray {
        val r = ByteArray(20)
        r[0] = 1
        r[1] = epcByte.toByte()
        r[13] = 1
        r[14] = (-60).toByte(); r[15] = (-60 shr 8).toByte()
        Bytes.u32(ts).copyInto(r, 16)
        return r
    }

    /** Smartbox com `lotes` (cada um uma quantidade de leituras). */
    private fun loteHandler(lotes: List<Int>, log: MutableList<String>): FakeHandler {
        var current = 0
        var pending = lotes.sum().toLong()
        return { op, data ->
            when (op) {
                Op.LOTE -> {
                    val part = data[0].toInt() and 0xFF
                    val total = lotes.getOrElse(current) { 0 }
                    val start = part * 48
                    val k = (total - start).coerceIn(0, 48)
                    val records = Array(k) { i -> record((start + i) and 0xFF, 1_762_000_000L + start + i) }
                    log += "LOTE $part"
                    reply(
                        op, 0,
                        Bytes.concat(
                            byteArrayOf((total and 0xFF).toByte(), (total shr 8).toByte()),
                            Bytes.u32(1000L + current),
                            byteArrayOf(part.toByte(), k.toByte()),
                            *records,
                        ),
                    )
                }
                Op.ACK -> {
                    log += "ACK"
                    pending -= lotes.getOrElse(current) { 0 }
                    current += 1
                    reply(op, 0, Bytes.u32(pending))
                }
                else -> reply(op, 0)
            }
        }
    }

    private suspend fun session(fake: FakeSmartbox): SmartboxSession =
        SmartboxSession(fake.openLink()).also { it.handshake(MODULE_ID, handshakeDeps) }

    private fun deps(
        persisted: MutableList<List<Reading>>,
        log: MutableList<String> = mutableListOf(),
        control: DownloadControl = DownloadControl(),
    ) = DownloadDeps(
        persistBatch = { r -> log += "COMMIT ${r.size}"; persisted += r },
        now = { System.currentTimeMillis() },
        control = control,
    )

    @Test
    fun `baixa lotes em partes e so confirma depois do commit`() = runTest {
        val log = mutableListOf<String>()
        val persisted = mutableListOf<List<Reading>>()
        val s = session(FakeSmartbox(handler = loteHandler(listOf(100, 3), log)))

        val result = SmartboxDownload.downloadAll(s, deps(persisted, log))

        assertEquals(2, result.batches)
        assertEquals(103, result.readings)
        assertEquals(0L, result.remaining)
        assertFalse(result.stoppedEarly)
        assertEquals(listOf(100, 3), persisted.map { it.size })
        assertEquals(
            listOf("LOTE 0", "LOTE 1", "LOTE 2", "COMMIT 100", "ACK", "LOTE 0", "COMMIT 3", "ACK", "LOTE 0"),
            log,
        )
    }

    @Test
    fun `commit que falha impede o ACK`() = runTest {
        val log = mutableListOf<String>()
        val s = session(FakeSmartbox(handler = loteHandler(listOf(5), log)))
        try {
            SmartboxDownload.downloadAll(
                s,
                DownloadDeps(persistBatch = { throw IllegalStateException("disco cheio") }, now = { 0L }),
            )
            fail("deveria lançar")
        } catch (e: IllegalStateException) {
            assertEquals("disco cheio", e.message)
        }
        assertFalse(log.contains("ACK"))
    }

    @Test
    fun `parar a pedido so acontece depois do ACK e diz quanto faltou`() = runTest {
        val log = mutableListOf<String>()
        val persisted = mutableListOf<List<Reading>>()
        val s = session(FakeSmartbox(handler = loteHandler(listOf(10, 20, 30), log)))

        val control = DownloadControl().also { it.requestStop() }
        val result = SmartboxDownload.downloadAll(s, deps(persisted, log, control))
        assertEquals(10, control.received)
        assertEquals(50L, control.remaining)

        assertTrue(result.stoppedEarly)
        assertEquals(1, result.batches)
        assertEquals(50L, result.remaining)
        assertEquals(listOf("LOTE 0", "COMMIT 10", "ACK"), log)
    }

    @Test
    fun `corte forcado durante o commit espera o commit e nao manda o ACK`() = runTest {
        val log = mutableListOf<String>()
        val control = DownloadControl()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var cutCalled = false
        val s = session(FakeSmartbox(handler = loteHandler(listOf(10, 20), log)))

        val download = async {
            SmartboxDownload.downloadAll(
                s,
                DownloadDeps(
                    persistBatch = { r ->
                        commitStarted.complete(Unit)
                        releaseCommit.await()
                        log += "COMMIT ${r.size}"
                    },
                    now = { 0L },
                    control = control,
                ),
            )
        }
        commitStarted.await()
        // Teto venceu no meio do commit: não corta agora.
        assertFalse(control.forceCut { cutCalled = true })
        assertFalse(cutCalled)
        releaseCommit.complete(Unit)

        val result = download.await()
        assertFalse(cutCalled)
        assertTrue(result.ackSkipped)
        assertTrue(result.stoppedEarly)
        assertEquals(0, result.batches)
        assertEquals(listOf("LOTE 0", "COMMIT 10"), log)   // sem ACK
    }

    @Test
    fun `corte forcado fora do commit corta na hora`() = runTest {
        val control = DownloadControl()
        var cutCalled = false
        assertTrue(control.forceCut { cutCalled = true })
        assertTrue(cutCalled)
        assertTrue(control.stopRequested)
    }

    @Test
    fun `commit que falha libera o corte forcado`() = runTest {
        val control = DownloadControl()
        val s = session(FakeSmartbox(handler = loteHandler(listOf(5), mutableListOf())))
        try {
            SmartboxDownload.downloadAll(
                s,
                DownloadDeps(persistBatch = { throw IllegalStateException("disco cheio") }, now = { 0L }, control = control),
            )
            fail("deveria lançar")
        } catch (_: IllegalStateException) {
        }
        var cutCalled = false
        assertTrue(control.forceCut { cutCalled = true })
        assertTrue(cutCalled)
    }

    @Test
    fun `modo configuracao vira erro legivel`() = runTest {
        val s = session(FakeSmartbox(handler = { op, _ -> reply(op, if (op == Op.LOTE) Status.UNAVAILABLE_IN_MODE else 0) }))
        try {
            SmartboxDownload.downloadAll(s, deps(mutableListOf()))
            fail("deveria lançar")
        } catch (e: DownloadException) {
            assertEquals(DownloadException.Kind.CONFIG_MODE, e.kind)
        }
    }

    @Test
    fun `acerta o relogio uma vez quando o LOTE pede`() = runTest {
        var asked = 0
        val fake = FakeSmartbox(handler = { op, _ ->
            when {
                op == Op.LOTE && asked++ == 0 -> reply(op, Status.ERROR_TEXT, Bytes.ascii("relogio sem hora real: mande SET_TIME"))
                op == Op.LOTE -> reply(op, 0, Bytes.concat(ByteArray(2), Bytes.u32(0), byteArrayOf(0, 0)))
                else -> reply(op, 0)
            }
        })
        val s = session(fake)
        assertEquals(0, SmartboxDownload.downloadAll(s, deps(mutableListOf())).readings)
        assertEquals(listOf(Op.LOTE, Op.SET_TIME, Op.LOTE), fake.received.map { it.op })
        assertEquals(8, fake.received[1].data.size)
    }

    @Test
    fun `lote que repete o ja confirmado derruba`() = runTest {
        // Firmware com defeito devolve o mesmo last_seq depois do ACK.
        val fake = FakeSmartbox(handler = { op, _ ->
            when (op) {
                Op.LOTE -> reply(op, 0, Bytes.concat(Bytes.fromHex("0100"), Bytes.u32(7), byteArrayOf(0, 1), record(1, 1)))
                Op.ACK -> reply(op, 0, Bytes.u32(1))
                else -> reply(op, 0)
            }
        })
        val s = session(fake)
        try {
            SmartboxDownload.downloadAll(s, deps(mutableListOf()))
            fail("deveria lançar")
        } catch (e: ProtocolException) {
            assertEquals("LOTE repetiu o lote já confirmado", e.message)
        }
    }
}
