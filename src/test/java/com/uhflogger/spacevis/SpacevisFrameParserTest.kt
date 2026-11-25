package com.uhflogger.spacevis

import com.uhflogger.spacevis.SpacevisProtocolTest.Companion.bytes
import com.uhflogger.spacevis.SpacevisProtocolTest.Companion.hex
import com.uhflogger.spacevis.SpacevisProtocolTest.Companion.readerFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpacevisFrameParserTest {

    private val event = bytes("55 AA 0F C0 01 C9 0C E2 80 68 94 00 00 50 12 34 56 78 90 9E A3")
    private val infoResp = bytes("55 AA 06 81 00 01 01 01 02 08 2E 8A")

    private val tags = mutableListOf<SpacevisProtocol.TagEvent>()
    private val responses = mutableListOf<Pair<Int, ByteArray>>()
    private val errors = mutableListOf<String>()
    private val parser = SpacevisFrameParser(
        onTag = { tags += it },
        onResponse = { cmd, p -> responses += cmd to p },
        onError = { m, _ -> errors += m },
    )

    @Test
    fun `stream picado 1 byte por vez com lixo`() {
        val stream = bytes("00 55 13") + event + infoResp + event
        for (b in stream) parser.feed(byteArrayOf(b))

        assertEquals(2, tags.size)
        tags.forEach {
            assertEquals("E28068940000501234567890", it.epcHex)
            assertEquals(-55, it.rssi)
            assertEquals(1, it.antenna)
        }
        assertEquals(1, responses.size)
        assertEquals(0x81, responses[0].first)
        assertEquals("00 01 01 01 02 08", responses[0].second.hex())
    }

    @Test
    fun `ressincroniza depois de CRC quebrado`() {
        val broken = event.copyOf().also { it[10] = 0x00 }
        parser.feed(broken + event)
        assertEquals(1, tags.size)
        assertEquals(1L, parser.crcErrors)
    }

    @Test
    fun `55 AA falso dentro de lixo nao engole o frame verdadeiro`() {
        // "55 AA 05" falso: LEN=5 pediria 11 bytes e cobriria o início do evento.
        parser.feed(bytes("55 AA 05 C0") + event)
        assertEquals(1, tags.size)
    }

    @Test
    fun `varios frames num pedaco so`() {
        parser.feed(event + event + infoResp)
        assertEquals(2, tags.size)
        assertEquals(1, responses.size)
    }

    @Test
    fun `evento malformado e descartado e a leitura continua`() {
        val bad = readerFrame(0xC0, bytes("01 C9 00"))
        parser.feed(bad + event)
        assertEquals(1, tags.size)
        assertEquals(1L, parser.malformedEvents)
    }

    @Test
    fun `EPC de 32 bytes e LEN acima de 16 sao aceitos no sentido leitor para app`() {
        val epc = ByteArray(32) { it.toByte() }
        parser.feed(readerFrame(0xC0, byteArrayOf(2, 0xB0.toByte(), 32) + epc))
        assertEquals(1, tags.size)
        assertEquals(64, tags[0].epcHex.length)
        assertEquals(2, tags[0].antenna)
        assertEquals(-80, tags[0].rssi)
    }

    @Test
    fun `excecao no callback nao escapa do parser`() {
        var calls = 0
        val p = SpacevisFrameParser(onTag = { calls++; error("boom") }, onResponse = { _, _ -> }, onError = { m, _ -> errors += m })
        p.feed(event + event)
        assertEquals(2, calls)
        assertEquals(2, errors.size)
    }

    @Test
    fun `payload da resposta e copia independente do buffer`() {
        parser.feed(infoResp)
        parser.feed(infoResp.copyOf().also { it[5] = 0x07; })  // CRC quebra, descartado
        assertArrayEquals(bytes("00 01 01 01 02 08"), responses[0].second)
    }
}
