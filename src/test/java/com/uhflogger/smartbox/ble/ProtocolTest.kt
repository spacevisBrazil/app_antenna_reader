package com.uhflogger.smartbox.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Porte de spacevis-mobile/__tests__/features/smartbox/protocol.spec.ts. */
class ProtocolTest {

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("esperava ${T::class.simpleName}, veio ${t::class.simpleName}", t)
        }
        fail("esperava ${T::class.simpleName}")
        throw IllegalStateException()
    }

    private fun advBytes(vararg overrides: Pair<Int, Int>): ByteArray {
        val d = Bytes.fromHex(
            "e502" +          // company
                "5356" +      // 'SV'
                "02" +        // proto
                "48e7299ed5b4" + // MAC
                "42" +        // estado: hora real + memória cheia
                "00" +        // motivo
                "d20400" +    // pendentes = 1234
                "55000000" +  // fazenda = 85
                "0700",       // cfg = 7
        )
        for ((i, v) in overrides) d[i] = v.toByte()
        return d
    }

    private fun record(epcHex: String, antenna: Int, rssi: Int, ts: Long): ByteArray {
        val r = ByteArray(20)
        val epc = Bytes.fromHex(epcHex)
        r[0] = epc.size.toByte()
        epc.copyInto(r, 1)
        r[13] = antenna.toByte()
        r[14] = rssi.toByte(); r[15] = (rssi shr 8).toByte()
        Bytes.u32(ts).copyInto(r, 16)
        return r
    }

    // ── Anúncio ─────────────────────────────────────────────────────────────

    @Test
    fun `parseAdvertisement le os campos a partir do company ID`() {
        assertEquals(
            Advertisement(proto = 2, moduleId = "48E7299ED5B4", state = 0x42, reason = 0, pending = 1234, farmId = 85, configCounter = 7),
            parseAdvertisement(advBytes()),
        )
    }

    @Test
    fun `parseAdvertisement ignora outros Espressif e anuncio curto`() {
        assertNull(parseAdvertisement(advBytes(2 to 0x00)))
        assertNull(parseAdvertisement(advBytes().copyOf(21)))
    }

    @Test
    fun `parseAdvertisement le fazenda u32 alta sem sinal`() {
        val d = advBytes(16 to 0xFF, 17 to 0xFF, 18 to 0xFF, 19 to 0xFF)
        assertEquals(4_294_967_295L, parseAdvertisement(d)?.farmId)
    }

    @Test
    fun `withCompanyId remonta o formato do guia a partir do ScanRecord`() {
        val full = advBytes()
        assertEquals(parseAdvertisement(full), parseAdvertisement(withCompanyId(full.copyOfRange(2, full.size))))
    }

    @Test
    fun `decodeStateFlags decodifica bits e ativacao`() {
        assertEquals(
            StateFlags(configMode = true, realTime = false, wifi = true, activation = ActivationState.ACTIVATED, farmFromKey = false, memoryFull = true),
            decodeStateFlags(0b0101_0101),
        )
        assertEquals(ActivationState.SAVED, decodeStateFlags(0b0000_1000).activation)
    }

    @Test
    fun `displayName usa os 6 ultimos hex do MAC`() {
        assertEquals("SPV-9ED5B4", displayName("48E7299ED5B4"))
    }

    // ── CHALLENGE ───────────────────────────────────────────────────────────

    private val nonce = Bytes.fromHex("101112131415161718191a1b1c1d1e1f")
    private val challengeBase = Bytes.concat(
        Bytes.fromHex("8100"),
        nonce,
        Bytes.fromHex("48e7299ed5b4"),
        byteArrayOf(2),                 // proto
        Bytes.fromHex("0a0000"),        // pendentes 10
        byteArrayOf(1),                 // modo operação
        byteArrayOf(0x02),
        byteArrayOf(0),
        Bytes.u32(85),
        Bytes.fromHex("0300"),
    )

    @Test
    fun `parseChallenge le o CHALLENGE com a versao do firmware`() {
        val c = parseChallenge(Bytes.concat(challengeBase, Bytes.ascii("1.4.2")))
        assertEquals("48E7299ED5B4", c.moduleId)
        assertEquals(SmartboxMode.OPERATION, c.mode)
        assertEquals(10, c.pending)
        assertEquals(85L, c.farmId)
        assertEquals(3, c.configCounter)
        assertEquals("1.4.2", c.firmware)
        assertArrayEquals(nonce, c.nonceEsp)
    }

    @Test
    fun `parseChallenge recusa tamanho fora de 37 a 69`() {
        assertThrows<ProtocolException> { parseChallenge(challengeBase.copyOf(36)) }
        assertThrows<ProtocolException> { parseChallenge(Bytes.concat(challengeBase, ByteArray(33))) }
    }

    // ── Respostas ───────────────────────────────────────────────────────────

    @Test
    fun `parseResponse separa status e dados`() {
        val r = parseResponse(0x10, Bytes.fromHex("90006f6b"))
        assertEquals(0, r.status)
        assertArrayEquals(byteArrayOf(0x6f, 0x6b), r.data)
    }

    @Test
    fun `parseResponse lanca quando a resposta e de outro opcode`() {
        val e = assertThrows<ProtocolException> { parseResponse(0x10, Bytes.fromHex("a000")) }
        assertEquals("Resposta de outro pedido", e.message)
    }

    @Test
    fun `status de erro texto devolve o texto do ESP`() {
        val e = SmartboxStatusException(Op.LOTE, Status.ERROR_TEXT, Bytes.ascii("flash cheia"))
        assertEquals("flash cheia", e.message)
        assertEquals("flash cheia", e.text)
        assertEquals("", SmartboxStatusException(Op.SET_FARM, Status.DIFFERENT_FARM, ByteArray(0)).text)
    }

    // ── LOTE / ACK ──────────────────────────────────────────────────────────

    @Test
    fun `parseLotePart le os registros da parte`() {
        val header = Bytes.concat(Bytes.fromHex("0200"), Bytes.u32(99), byteArrayOf(0, 2))
        val part = parseLotePart(
            Bytes.concat(
                header,
                record("e2801170000002123456789a", 1, -62, 1_762_261_520),
                record("abcd", 4, -80, 1_762_261_521),
            ),
        )
        assertEquals(
            LotePart(
                total = 2, lastSeq = 99, part = 0, count = 2,
                readings = listOf(
                    Reading("E2801170000002123456789A", 1, -62, 1_762_261_520),
                    Reading("ABCD", 4, -80, 1_762_261_521),
                ),
            ),
            part,
        )
    }

    @Test
    fun `n igual a 0 e fim da descarga`() {
        val part = parseLotePart(Bytes.concat(Bytes.fromHex("0000"), Bytes.u32(0), byteArrayOf(0, 0)))
        assertEquals(0, part.total)
        assertTrue(part.readings.isEmpty())
    }

    @Test
    fun `parseLotePart recusa tamanho que nao bate com k e EPC maior que 12`() {
        val header = Bytes.concat(Bytes.fromHex("0100"), Bytes.u32(1), byteArrayOf(0, 1))
        assertThrows<ProtocolException> { parseLotePart(header) }
        val bad = record("ab", 1, -50, 1).also { it[0] = 13 }
        val e = assertThrows<ProtocolException> { parseLotePart(Bytes.concat(header, bad)) }
        assertEquals("EPC inválido no LOTE", e.message)
    }

    @Test
    fun `descarta registro com EPC vazio mas conta no k`() {
        val header = Bytes.concat(Bytes.fromHex("0100"), Bytes.u32(1), byteArrayOf(0, 1))
        val part = parseLotePart(Bytes.concat(header, ByteArray(20)))
        assertEquals(1, part.count)
        assertTrue(part.readings.isEmpty())
    }

    @Test
    fun `lastSeq u32 alto nao vira negativo`() {
        val part = parseLotePart(Bytes.concat(Bytes.fromHex("0000"), Bytes.u32(0xFFFF_FFF0L), byteArrayOf(0, 0)))
        assertEquals(0xFFFF_FFF0L, part.lastSeq)
        assertArrayEquals(Bytes.u32(0xFFFF_FFF0L), buildAckRequest(part.lastSeq))
    }

    @Test
    fun `parseAckResponse le os pendentes`() {
        assertEquals(17_000_000L, parseAckResponse(Bytes.u32(17_000_000)))
        assertThrows<ProtocolException> { parseAckResponse(ByteArray(3)) }
    }

    // ── Fazenda ─────────────────────────────────────────────────────────────

    @Test
    fun `buildSetFarm monta com force e recusa fora de u32`() {
        assertArrayEquals(byteArrayOf(85, 0, 0, 0, 1), buildSetFarm(85, true))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, 0), buildSetFarm(0xFFFF_FFFFL, false))
        assertThrows<ProtocolException> { buildSetFarm(-1, false) }
        assertThrows<ProtocolException> { buildSetFarm(1L shl 32, false) }
    }

    @Test
    fun `parseFarmResult le fazenda e origem`() {
        assertEquals(FarmResult(85, fromKey = true), parseFarmResult(Bytes.concat(Bytes.u32(85), byteArrayOf(1))))
        assertFalse(parseFarmResult(Bytes.concat(Bytes.u32(85), byteArrayOf(0))).fromKey)
    }

    // ── Framing ─────────────────────────────────────────────────────────────

    @Test
    fun `framing parte e remonta respeitando MTU menos 4`() {
        val msg = ByteArray(57) { it.toByte() }
        val parts = Framing.split(msg, 23)
        assertEquals(3, parts.size)
        assertEquals(listOf(0x00, 0x01, 0x82), parts.map { it[0].toInt() and 0xFF })
        assertTrue(parts.all { it.size <= 20 })

        val r = Reassembler()
        assertNull(r.push(parts[0]))
        assertNull(r.push(parts[1]))
        assertArrayEquals(msg, r.push(parts[2]))
    }

    @Test
    fun `mensagem que cabe numa parte vai com o bit de ultima`() {
        val parts = Framing.split(byteArrayOf(1), 517)
        assertEquals(1, parts.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 1), parts[0])
    }

    @Test
    fun `parte fora de ordem e notify vazio quebram`() {
        assertThrows<FramingException> { Reassembler().push(byteArrayOf(0x81.toByte(), 1)) }
        assertThrows<FramingException> { Reassembler().push(ByteArray(0)) }
    }

    @Test
    fun `mensagem acima de 1040 B e recusada`() {
        assertThrows<FramingException> { Framing.split(ByteArray(1041), 517) }
        assertEquals(3, Framing.split(ByteArray(1040), 517).size)
    }

    // ── WIFI_SCAN ───────────────────────────────────────────────────────────

    private fun item(rssi: Int, open: Int, ssid: ByteArray) =
        Bytes.concat(byteArrayOf(rssi.toByte(), open.toByte(), ssid.size.toByte()), ssid)

    @Test
    fun `parseWifiScan agrupa repetidas pelos bytes do SSID, mais forte primeiro`() {
        val a = byteArrayOf(0x41)                 // "A"
        val b = byteArrayOf(0x42, 0xFF.toByte())  // não é UTF-8 válido
        val list = parseWifiScan(Bytes.concat(item(-80, 0, a), item(-60, 1, b), item(-50, 0, a), item(-90, 1, b)))
        assertEquals(2, list.size)
        assertEquals(-50, list[0].rssi); assertFalse(list[0].open); assertArrayEquals(a, list[0].ssid)
        assertEquals(-60, list[1].rssi); assertTrue(list[1].open); assertArrayEquals(b, list[1].ssid)
    }

    @Test
    fun `parseWifiScan descarta tam 0 ou maior que 32 e para em lista truncada`() {
        val long = ByteArray(33) { 0x61 }
        assertEquals(listOf(-40), parseWifiScan(Bytes.concat(item(-70, 0, ByteArray(0)), item(-70, 0, long), item(-40, 0, byteArrayOf(0x43)))).map { it.rssi })
        assertTrue(parseWifiScan(byteArrayOf(0xC0.toByte(), 0, 5, 0x41)).isEmpty())
        assertTrue(parseWifiScan(ByteArray(0)).isEmpty())
    }
}
