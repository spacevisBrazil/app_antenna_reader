package com.uhflogger.spacevis

import com.uhflogger.spacevis.SpacevisProtocol as P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Frames de referência do guia (seção 13), conferidos com o firmware. */
class SpacevisProtocolTest {

    @Test
    fun `crc16 MCRF4XX do vetor de checagem`() {
        assertEquals(0x6F91, P.crc16("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `cada comando gera exatamente os bytes do guia`() {
        val cases = listOf(
            P.getInfo() to "55 AA 00 01 31 E1",
            P.setAntennas(0x01) to "55 AA 01 02 01 D6 41",
            P.setAntennas(0x03) to "55 AA 01 02 03 C4 62",
            P.setAntennas(0x0F) to "55 AA 01 02 0F A8 A8",
            P.setPower(30) to "55 AA 01 03 1E 78 B0",
            P.setPower(33) to "55 AA 01 03 21 0C 79",
            P.setPowerPerPort(intArrayOf(30, 30, 30, 30)) to "55 AA 04 03 1E 1E 1E 1E 03 0B",
            P.setMode(0) to "55 AA 01 04 00 8F 04",
            P.setMode(1) to "55 AA 01 04 01 06 15",
            P.start() to "55 AA 00 05 15 A7",
            P.stop() to "55 AA 00 06 8E 95",
            P.getConfig() to "55 AA 00 07 07 84",
            P.getStatus() to "55 AA 00 08 F0 7C",
        )
        for ((frame, expected) in cases) assertEquals(expected, frame.hex())
    }

    @Test
    fun `decodifica GET_INFO, GET_CONFIG e GET_STATUS de referencia`() {
        val info = P.parseInfo(payloadOf("55 AA 06 81 00 01 01 01 02 08 2E 8A"))!!
        assertEquals(P.Info(0, 1, 1, 1, 2, 8), info)
        assertEquals("2.8", info.firmware)

        val cfg = P.parseConfig(payloadOf("55 AA 08 87 00 01 1E 1E 1E 1E 01 01 98 19"))!!
        assertEquals(P.Config(0, 0x01, listOf(30, 30, 30, 30), 1, true), cfg)

        val st = P.parseStatus(payloadOf("55 AA 09 88 00 01 24 00 00 09 FF FF FF AD 90"))!!
        assertEquals(true, st.moduleOk)
        assertEquals(36, st.temperatureC)
        assertEquals(0, st.thermalLevel)
        assertEquals(false, st.inBurst)
        assertEquals(listOf(9, null, null, null), st.returnLoss)
    }

    @Test
    fun `temperatura e int8 com sinal e 0x7F e desconhecida`() {
        val neg = P.parseStatus(bytes("00 01 F6 00 00 FF FF FF FF"))!!
        assertEquals(-10, neg.temperatureC)
        val unknown = P.parseStatus(bytes("00 01 7F 00 00 FF FF FF FF"))!!
        assertNull(unknown.temperatureC)
    }

    @Test
    fun `evento de tag - RSSI com sinal e EPC em hex maiusculo`() {
        val evt = P.parseTagEvent(payloadOf("55 AA 0F C0 01 C9 0C E2 80 68 94 00 00 50 12 34 56 78 90 9E A3"))!!
        assertEquals(1, evt.antenna)
        assertEquals(-55, evt.rssi)
        assertEquals("E28068940000501234567890", evt.epcHex)
    }

    @Test
    fun `evento malformado e descartado`() {
        assertNull(P.parseTagEvent(bytes("01 C9")))
        assertNull(P.parseTagEvent(bytes("01 C9 00")))
        assertNull(P.parseTagEvent(bytes("01 C9 05 E2 80")))
    }

    @Test
    fun `mascara de portas`() {
        assertEquals(listOf(1, 3), P.portsOf(0x05))
        assertEquals(0x0A, P.maskOf(listOf(2, 4)))
        assertEquals("Porta 1", P.portsLabel(0x01))
        assertEquals("Portas 1, 2", P.portsLabel(0x03))
    }

    companion object {
        fun bytes(hex: String): ByteArray =
            hex.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

        fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

        /** Payload de um frame completo (sem 55 AA, LEN, CMD e CRC). */
        fun payloadOf(frameHex: String): ByteArray {
            val f = bytes(frameHex)
            return f.copyOfRange(4, f.size - 2)
        }

        /** Frame leitor → app (sem o limite de 16 bytes do sentido app → leitor). */
        fun readerFrame(cmd: Int, payload: ByteArray): ByteArray {
            val body = byteArrayOf(payload.size.toByte(), cmd.toByte()) + payload
            val crc = P.crc16(body)
            return byteArrayOf(0x55, 0xAA.toByte()) + body + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
        }
    }
}
