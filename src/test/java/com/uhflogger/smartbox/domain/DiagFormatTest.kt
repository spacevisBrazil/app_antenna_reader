package com.uhflogger.smartbox.domain

import com.uhflogger.smartbox.domain.DiagFormat.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Porte dos casos de spacevis-mobile/__tests__/features/smartbox/diag.spec.ts + tabela 13.2 do guia. */
class DiagFormatTest {
    private val readAt = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()

    private fun rows(text: String) = DiagFormat.readable(text, readAt, ZoneOffset.UTC).associateBy { it.key }

    @Test
    fun `uptime legivel`() {
        assertEquals("2 d 3 h", DiagFormat.formatUptime(2 * 86_400 + 3 * 3600 + 59))
        assertEquals("4 h 05 min", DiagFormat.formatUptime(4 * 3600 + 5 * 60))
        assertEquals("3 min 07 s", DiagFormat.formatUptime(3 * 60 + 7))
    }

    @Test
    fun `reset e causa com cor`() {
        val r = rows("reset=brownout\ncausa=nenhuma")
        assertEquals("Queda de tensão" to Tone.BAD, r["reset"]!!.value to r["reset"]!!.tone)
        assertEquals("Nenhum" to Tone.OK, r["causa"]!!.value to r["causa"]!!.tone)
        // Valor desconhecido: cru.
        assertEquals("algo novo", rows("causa=algo novo")["causa"]!!.value)
    }

    @Test
    fun `boots conta reinicios depois do primeiro`() {
        assertEquals("Nenhum", rows("boots=1")["boots"]!!.value)
        assertEquals("4" to Tone.WARN, rows("boots=5")["boots"]!!.let { it.value to it.tone })
    }

    @Test
    fun `memoria em KB com limiares`() {
        assertEquals("20 KB" to Tone.WARN, rows("heap_min=20480")["heap_min"]!!.let { it.value to it.tone })
        assertEquals(Tone.BAD, rows("heap_min=19999")["heap_min"]!!.tone)
        assertEquals(Tone.WARN, rows("heap_maior_bloco=9000")["heap_maior_bloco"]!!.tone)
    }

    @Test
    fun `relogio compara com o aparelho no instante da leitura`() {
        assertEquals(Tone.OK, rows("hora=2026-11-18T12:00:04Z")["hora"]!!.tone)
        val ahead = rows("hora=2026-11-18T12:01:00Z")["hora"]!!
        assertEquals(Tone.WARN, ahead.tone)
        assertTrue(ahead.value.contains("adiantado 1 min 00 s"))
        val behind = rows("hora=2026-11-18T11:50:00Z")["hora"]!!
        assertEquals(Tone.BAD, behind.tone)
        assertTrue(behind.value.contains("atrasado"))
        assertEquals("Sem hora (espera o NTP ou o aparelho)" to Tone.BAD, rows("hora=invalida")["hora"]!!.let { it.value to it.tone })
    }

    @Test
    fun `wifi e chave recusada`() {
        assertEquals("Sem falha" to Tone.OK, rows("vfail=0")["vfail"]!!.let { it.value to it.tone })
        assertEquals("Falhou: rede não encontrada (código 201)", rows("vfail=201")["vfail"]!!.value)
        assertEquals("3 desde que ligou (app com outra chave?)" to Tone.WARN, rows("auth_recusadas=3")["auth_recusadas"]!!.let { it.value to it.tone })
    }

    @Test
    fun `temperatura numa linha so com a cor do nivel`() {
        val t = rows("temp_rfid=42\ntemp_rfid_max=55\nnivel_termico=quente")["temp_rfid"]!!
        assertEquals("42 °C (máx. 55 °C desde o último reinício) · quente", t.value)
        assertEquals(Tone.WARN, t.tone)
        assertTrue(rows("firmware=1.0")["temp_rfid"]!!.value.startsWith("— sem leitura"))
    }

    @Test
    fun `antenas juntas, em ordem, e ausente nunca vira zero`() {
        val list = DiagFormat.readable("return_loss_ant3=5\nfirmware=1\nreturn_loss_ant1=12\nreturn_loss_ant2=-1", readAt, ZoneOffset.UTC)
        val ant = list.filter { it.key.startsWith("return_loss_ant") }
        assertEquals(listOf("return_loss_ant1", "return_loss_ant2", "return_loss_ant3"), ant.map { it.key })
        assertEquals(listOf(Tone.OK, Tone.BAD, Tone.BAD), ant.map { it.tone })
        assertEquals(Tone.WARN, rows("return_loss_ant1=7")["return_loss_ant1"]!!.tone)
        assertTrue(rows("firmware=1")["return_loss"]!!.value.contains("ainda não medido"))
    }

    @Test
    fun `campo desconhecido e linha sem igual aparecem como vieram`() {
        val r = rows("campo_novo=xyz\nlinha solta")
        assertEquals("xyz", r["campo_novo"]!!.value)
        assertEquals("linha solta", r["linha solta"]!!.label)
    }

    @Test
    fun `nada veio, nada mostra`() {
        assertTrue(DiagFormat.readable("", readAt).isEmpty())
    }
}
