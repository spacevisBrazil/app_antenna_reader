package com.uhflogger.spacevis

import com.uhflogger.spacevis.SpacevisProtocol as P
import com.uhflogger.spacevis.SpacevisSession.ReaderConfig
import com.uhflogger.spacevis.SpacevisSession.Validation
import com.uhflogger.spacevis.SpacevisSession.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacevisSessionTest {

    private val defaults = ReaderConfig(antennaMask = 0x03, powerDbm = 33, mode = P.MODE_ADAPTIVE)

    private fun session(reader: FakeSpacevisReader) = SpacevisSession(reader.channel())

    private fun validInfo(reader: FakeSpacevisReader, s: SpacevisSession): P.Info =
        (s.validate() as Validation.Valid).info.also { reader.received.clear() }

    // ─── Validação ───

    @Test
    fun `GET_INFO valido`() {
        val reader = FakeSpacevisReader()
        val v = session(reader).validate()
        assertTrue(v is Validation.Valid)
        assertEquals(1, (v as Validation.Valid).info.ports)
    }

    @Test
    fun `sem resposta - provavelmente tipo errado (ex Winnix)`() {
        val reader = FakeSpacevisReader().apply { mute += P.CMD_GET_INFO }
        assertEquals(Validation.NoResponse, session(reader).validate())
    }

    @Test
    fun `modulo diferente de RoyalRay`() {
        val reader = FakeSpacevisReader().apply { moduleType = 2 }
        assertTrue(session(reader).validate() is Validation.WrongModule)
    }

    @Test
    fun `protocolo mais novo que o app`() {
        val reader = FakeSpacevisReader().apply { protoVersion = 2 }
        assertTrue(session(reader).validate() is Validation.FirmwareNewer)
    }

    // ─── Configuração ───

    @Test
    fun `sequencia completa na ordem do guia`() {
        val reader = FakeSpacevisReader().apply { ports = 4; returnLoss = intArrayOf(12, 11, 0xFF, 0xFF) }
        val s = session(reader)
        val out = s.configureAndStart(validInfo(reader, s), defaults)
        assertEquals(
            listOf(P.CMD_SET_ANTENAS, P.CMD_SET_POTENCIA, P.CMD_SET_MODO, P.CMD_GET_STATUS, P.CMD_START),
            reader.received,
        )
        assertEquals(emptyList<Warning>(), out.warnings)
        assertEquals(0x03, out.effectiveMask)
        assertEquals(36, out.startTemperatureC)
        assertFalse(out.connectionLost)
    }

    @Test
    fun `modulo de 1 porta com padrao 1+2 avisa e segue`() {
        val reader = FakeSpacevisReader().apply { ports = 1 }
        val s = session(reader)
        val out = s.configureAndStart(validInfo(reader, s), defaults)
        assertEquals(listOf(Warning.PortsReduced(1, 0x03, 0x01)), out.warnings)
        assertEquals("Leitor tem 1 porta(s). Lendo em: Porta 1.", SpacevisMessages.warning(out.warnings[0]))
        assertEquals(P.CMD_START, reader.received.last())
    }

    @Test
    fun `porta sem antena - RECUSOU mantem a mascara anterior`() {
        val reader = FakeSpacevisReader().apply { ports = 4; antennaStatus = P.ST_RECUSOU; effectiveMaskOverride = 0x01 }
        val s = session(reader)
        val out = s.configureAndStart(validInfo(reader, s), defaults)
        assertEquals(listOf(Warning.PortRefused(0x02, 0x01)), out.warnings)
        assertEquals(
            "Módulo recusou a porta 2 (porta sem antena?). Lendo em: Porta 1.",
            SpacevisMessages.warning(out.warnings[0]),
        )
        assertEquals(P.CMD_START, reader.received.last())
    }

    @Test
    fun `nenhuma porta existe - PARAMETRO`() {
        val reader = FakeSpacevisReader().apply { ports = 1; antennaStatus = P.ST_PARAMETRO; effectiveMaskOverride = 0x01 }
        val s = session(reader)
        val out = s.configureAndStart(validInfo(reader, s), defaults.copy(antennaMask = 0x0C))
        assertEquals(listOf(Warning.NoPortExists(1)), out.warnings)
        assertFalse(out.connectionLost)
    }

    @Test
    fun `OFFLINE avisa uma vez so e START sai mesmo assim`() {
        val reader = FakeSpacevisReader().apply {
            ports = 4
            antennaStatus = P.ST_OFFLINE; powerStatus = P.ST_OFFLINE; modeStatus = P.ST_OFFLINE; startStatus = P.ST_OFFLINE
        }
        val s = session(reader)
        val out = s.configureAndStart(validInfo(reader, s), defaults)
        assertEquals(listOf<Warning>(Warning.ModuleOffline), out.warnings)
        assertFalse(out.connectionLost)
        assertEquals(P.CMD_START, reader.received.last())
    }

    @Test
    fun `antena ruim so nas portas usadas`() {
        val reader = FakeSpacevisReader().apply { ports = 4; returnLoss = intArrayOf(9, 5, 3, 0xFF) }
        val s = session(reader)
        val out = s.configureAndStart(validInfo(reader, s), defaults)  // portas 1 e 2
        assertEquals(listOf(Warning.BadAntenna(1, 9), Warning.BadAntenna(2, 5)), out.warnings)
        assertEquals(
            "Antena da porta 1 com sinal ruim (RL 9 dB). Confira antena, cabo e conector.",
            SpacevisMessages.warning(out.warnings[0]),
        )
    }

    @Test
    fun `comando sem resposta na configuracao e conexao perdida`() {
        val reader = FakeSpacevisReader().apply { mute += P.CMD_SET_MODO }
        val s = session(reader)
        val out = s.configureAndStart(validInfo(reader, s), defaults)
        assertTrue(out.connectionLost)
        assertFalse(P.CMD_START in reader.received)
    }

    @Test
    fun `parar no meio da configuracao nao manda START`() {
        val reader = FakeSpacevisReader()
        val s = session(reader)
        var tempSeen: Int? = -1
        val out = s.configureAndStart(validInfo(reader, s), defaults) { t -> tempSeen = t; false }
        assertEquals(36, tempSeen)
        assertFalse(P.CMD_START in reader.received)
        assertFalse(out.connectionLost)
    }

    @Test
    fun `temperatura desconhecida vira null`() {
        val reader = FakeSpacevisReader().apply { temperature = 0x7F }
        val s = session(reader)
        assertNull(s.configureAndStart(validInfo(reader, s), defaults).startTemperatureC)
    }

    // ─── Parar ───

    @Test
    fun `STOP e temperatura final`() {
        val reader = FakeSpacevisReader().apply { temperature = 41 }
        val out = session(reader).stop()
        assertEquals(SpacevisSession.StopOutcome(true, 41), out)
        assertEquals(listOf(P.CMD_STOP, P.CMD_GET_STATUS), reader.received)
    }

    @Test
    fun `STOP sem resposta finaliza assim mesmo`() {
        val reader = FakeSpacevisReader().apply { mute += P.CMD_STOP }
        assertEquals(SpacevisSession.StopOutcome(false, null), session(reader).stop())
    }

    // ─── Mensagens ───

    @Test
    fun `mensagens de validacao`() {
        val info = P.Info(0, 2, 1, 1, 2, 8)
        assertEquals("X: firmware do leitor é mais novo que o app. Atualize o app.",
            SpacevisMessages.validation("X", Validation.FirmwareNewer(info)))
        assertEquals("X: leitor não respondeu como RoyalRay. Confira o tipo de leitor.",
            SpacevisMessages.validation("X", Validation.NoResponse))
        assertNull(SpacevisMessages.validation("X", Validation.Valid(info)))
    }
}
