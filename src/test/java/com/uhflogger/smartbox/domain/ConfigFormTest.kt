package com.uhflogger.smartbox.domain

import com.uhflogger.smartbox.domain.ConfigForms.applyFactoryDefaults
import com.uhflogger.smartbox.domain.ConfigForms.applyTemplate
import com.uhflogger.smartbox.domain.ConfigForms.brasiliaHint
import com.uhflogger.smartbox.domain.ConfigForms.canKeepPassword
import com.uhflogger.smartbox.domain.ConfigForms.durationHint
import com.uhflogger.smartbox.domain.ConfigForms.factoryForm
import com.uhflogger.smartbox.domain.ConfigForms.formatActCode
import com.uhflogger.smartbox.domain.ConfigForms.parseGetConfig
import com.uhflogger.smartbox.domain.ConfigForms.parseTemplate
import com.uhflogger.smartbox.domain.ConfigForms.serialize
import com.uhflogger.smartbox.domain.ConfigForms.summarizeChanges
import com.uhflogger.smartbox.domain.ConfigForms.templateFromForm
import com.uhflogger.smartbox.domain.ConfigForms.templateToJson
import com.uhflogger.smartbox.domain.ConfigForms.validate
import com.uhflogger.smartbox.domain.ConfigForms.windowTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Porte de spacevis-mobile/__tests__/features/smartbox/configForm.spec.ts. */
class ConfigFormTest {

    // GET_CONFIG típico de uma smartbox em Wi-Fi (sem senha nem chave, como o firmware manda).
    private val WIFI_CONFIG =
        "envio=0&ssid=Fazenda+S%C3%A3o+Jos%C3%A9&ant1=1&ant3=1&power=27&sweep_sec=60&valid_sec=120" +
            "&adaptive=1&w_enabled=1&w1_sh=11&w1_sm=0&w1_ss=0&w1_eh=21&w1_em=30&w1_es=0&w1_on=10" +
            "&w1_off=20&w2_on=10&w2_off=20&novo_campo=7&flag_nova&farm_id=65&farm_src=0"

    private val BT_CONFIG =
        "envio=1&ant1=1&power=30&sweep_sec=60&valid_sec=120&adaptive=1&w_enabled=1&w1_sh=8&w1_sm=0" +
            "&w1_ss=0&w1_eh=18&w1_em=0&w1_es=0&w1_on=10&w1_off=20&w2_on=10&w2_off=20&farm_id=0&farm_src=0"

    private val wifi = parseGetConfig(WIFI_CONFIG)
    private val bt = parseGetConfig(BT_CONFIG)

    private fun assertStarts(prefix: String, actual: String?) =
        assertTrue("esperava começar com '$prefix', veio '$actual'", actual?.startsWith(prefix) == true)

    // ── urlForm ─────────────────────────────────────────────────────────────

    @Test
    fun `conta bytes UTF-8 (o firmware conta bytes, nao letras)`() {
        assertEquals(7, UrlForm.utf8Length("abcdefg"))
        assertEquals(6, UrlForm.utf8Length("ação"))
        assertEquals(4, UrlForm.utf8Length("🐄"))
    }

    @Test
    fun `ida e volta UTF-8 e urlencoded`() {
        val s = "Rede São José 🐄"
        assertEquals(s, UrlForm.utf8Decode(UrlForm.utf8Encode(s)))
        assertEquals(s, UrlForm.formDecode(UrlForm.formEncode(s)))
        assertEquals("a%20b%26c%3Dd", UrlForm.formEncode("a b&c=d"))
        assertEquals("a b c", UrlForm.formDecode("a+b%20c"))
    }

    @Test
    fun `bytes que nao sao UTF-8 viram U+FFFD na tela, mas a forma XX guarda os bytes`() {
        val raw = byteArrayOf(0x52, 0xFF.toByte(), 0x45)
        assertEquals("R�E", UrlForm.utf8Decode(raw))
        assertEquals("R%FFE", UrlForm.percentEncodeBytes(raw))
    }

    // ── parseGetConfig ──────────────────────────────────────────────────────

    @Test
    fun `le o formulario, separa fazenda e guarda extras como vieram`() {
        assertEquals(Envio.WIFI, wifi.form.envio)
        assertEquals("Fazenda São José", wifi.form.ssid)
        assertEquals("Fazenda+S%C3%A3o+Jos%C3%A9", wifi.form.ssidEncoded)
        assertEquals("", wifi.form.password)
        assertEquals("", wifi.form.actCode)
        assertEquals(65L, wifi.farmId)
        assertEquals(0, wifi.farmSrc)
        assertEquals(listOf("novo_campo=7", "flag_nova"), wifi.extras)
        assertEquals("27", wifi.form.num("power"))
    }

    @Test
    fun `checkbox ausente = desmarcado (nao o padrao de fabrica)`() {
        assertTrue(wifi.form.check("ant1"))
        assertFalse(wifi.form.check("ant2"))
        assertTrue(wifi.form.check("ant3"))
        assertFalse(wifi.form.check("w1_esp32"))
        assertFalse(wifi.form.check("open_network"))
    }

    @Test
    fun `envio so e Bluetooth com valor exatamente 1`() {
        assertEquals(Envio.BLUETOOTH, parseGetConfig("envio=1").form.envio)
        assertEquals(Envio.WIFI, parseGetConfig("envio=01").form.envio)
    }

    @Test
    fun `nunca devolve senha nem chave, mesmo se vierem`() {
        val c = parseGetConfig("envio=0&password=x&act_code=y")
        assertEquals(emptyList<String>(), c.extras)
        assertEquals("", c.form.password)
    }

    // ── validate ────────────────────────────────────────────────────────────

    @Test
    fun `aceita a configuracao lida sem mudanca (senha em branco mantem a salva)`() {
        assertNull(validate(wifi, wifi.form))
        assertNull(validate(bt, bt.form))
    }

    @Test
    fun `Wi-Fi sem rede`() {
        assertEquals("Selecione uma rede antes de salvar.", validate(wifi, wifi.form.copy(ssid = "", ssidEncoded = null)))
    }

    @Test
    fun `SSID com mais de 31 bytes (regra so do app)`() {
        val f = wifi.form.copy(ssid = "ã".repeat(16), ssidEncoded = null, password = "12345678")   // 32 bytes
        assertEquals("Nome da rede com mais de 31 bytes: a smartbox nao aceita.", validate(wifi, f))
    }

    @Test
    fun `rede nova exige senha, rede aberta nao`() {
        val f = wifi.form.copy(ssid = "Outra", ssidEncoded = null)
        assertStarts("Senha vazia", validate(wifi, f))
        assertNull(validate(wifi, f.withCheck("open_network", true)))
    }

    @Test
    fun `senha conta bytes - 7 letras com acento passam de 8 bytes`() {
        assertEquals(
            "Senha recusada: a senha da rede precisa ter pelo menos 8 caracteres.",
            validate(wifi, wifi.form.copy(password = "abcdef")),
        )
        assertNull(validate(wifi, wifi.form.copy(password = "abcdefã")))   // 8 bytes
        assertEquals(
            "Senha com mais de 63 caracteres: a smartbox nao aceita.",
            validate(wifi, wifi.form.copy(password = "a".repeat(64))),
        )
    }

    @Test
    fun `pelo menos uma antena e faixas do leitor`() {
        assertEquals("Selecione pelo menos uma antena.", validate(wifi, wifi.form.withCheck("ant1", false).withCheck("ant3", false)))
        assertEquals("Potencia fora do intervalo permitido (5 a 33 dBm).", validate(wifi, wifi.form.withNum("power", "34")))
        assertEquals("Tempo de varredura fora do intervalo permitido (10 a 3600s).", validate(wifi, wifi.form.withNum("sweep_sec", "9")))
        assertEquals("Tempo de validade fora do intervalo permitido (60 a 86400s).", validate(wifi, wifi.form.withNum("valid_sec", "")))
    }

    @Test
    fun `Bluetooth para Wi-Fi sem chave de ativacao (regra so do app)`() {
        var f = bt.form.copy(envio = Envio.WIFI, ssid = "Curral", password = "12345678")
        assertStarts("Digite a chave de ativacao desta smartbox", validate(bt, f))
        f = f.copy(actCode = "ab12-cd34")
        assertNull(validate(bt, f))
        f = f.copy(actCode = "AB12*")
        assertStarts("Chave de ativacao invalida", validate(bt, f))
    }

    @Test
    fun `Window - validada mesmo desligada, na ordem do firmware`() {
        val off = bt.form.withCheck("w_enabled", false)
        assertEquals("Horario de inicio da Window 1 invalido.", validate(bt, off.withNum("w1_sh", "24")))
        assertEquals("Horario de fim da Window 1 invalido.", validate(bt, off.withNum("w1_em", "60")))
        assertEquals("Fim da Window 1 deve ser maior que o inicio (sem cruzar meia-noite).", validate(bt, off.withNum("w1_eh", "8")))
        assertEquals(
            "Window 1 deve ter entre 60 e 86340 segundos de duracao.",
            validate(bt, off.withNum("w1_eh", "8").withNum("w1_es", "30")),
        )
        assertStarts("Ciclo ON da Window 1", validate(bt, off.withNum("w1_on", "9")))
        assertStarts("Ciclo OFF da Window 1", validate(bt, off.withNum("w1_off", "36000")))
        assertStarts("Ciclo ON da Window 2", validate(bt, off.withNum("w2_on", "50401")))
        assertStarts("Ciclo OFF da Window 2", validate(bt, off.withNum("w2_off", "50400")))
    }

    @Test
    fun `numero so com digitos (lixo seria lido como 0 pelo firmware)`() {
        assertStarts("Potencia", validate(bt, bt.form.withNum("power", "3O")))
    }

    // ── canKeepPassword ─────────────────────────────────────────────────────

    @Test
    fun `mesma rede salva e fechada mantem`() {
        assertTrue(canKeepPassword(wifi.form, wifi.form))
    }

    @Test
    fun `mesma rede digitada a mao (mesmos bytes) tambem mantem`() {
        assertTrue(canKeepPassword(wifi.form, wifi.form.copy(ssid = "Fazenda São José", ssidEncoded = null)))
    }

    @Test
    fun `original aberta ou sem SSID nunca mantem`() {
        val open = parseGetConfig("$WIFI_CONFIG&open_network=1")
        assertFalse(canKeepPassword(open.form, open.form))
        assertFalse(canKeepPassword(bt.form, bt.form.copy(ssid = "x")))
    }

    // ── summarizeChanges ────────────────────────────────────────────────────

    @Test
    fun `nada mudou`() {
        assertEquals(emptyList<String>(), summarizeChanges(wifi.form, wifi.form).lines)
    }

    @Test
    fun `compara numero como numero (030 = 30)`() {
        assertEquals(emptyList<String>(), summarizeChanges(bt.form, bt.form.withNum("power", "030")).lines)
    }

    @Test
    fun `Envio primeiro, rede, senha, chave, antenas, leitor e horarios no fim`() {
        val f = bt.form.copy(envio = Envio.WIFI, ssid = "Curral", password = "12345678", actCode = "AB12")
            .withCheck("ant2", true).withNum("power", "25").withNum("w1_sh", "9")
        val s = summarizeChanges(bt.form, f)
        assertTrue(s.toWifi)
        assertEquals(
            listOf(
                "• Envio: Bluetooth → Wi-Fi",
                "• Rede Wi-Fi: (nenhuma) → Curral",
                "• Senha: nova",
                "• Chave de ativação: nova",
                "• Antenas ativas · Antena 2: não → sim",
                "• Leitor RFID · Potência (dBm): 30 → 25",
                "• Modo Window · Window 1 · Início (UTC): 08:00:00 → 09:00:00",
            ),
            s.lines,
        )
    }

    @Test
    fun `rede aberta`() {
        assertEquals(listOf("• Senha: rede aberta (sem senha)"), summarizeChanges(wifi.form, wifi.form.withCheck("open_network", true)).lines)
    }

    // ── serialize ───────────────────────────────────────────────────────────

    @Test
    fun `Wi-Fi - SSID original intacto, sem senha se nao digitada, extras e farm_id no fim`() {
        assertEquals(
            "envio=0&ssid=Fazenda+S%C3%A3o+Jos%C3%A9&ant1=1&ant3=1&power=27&sweep_sec=60&valid_sec=120" +
                "&adaptive=1&w_enabled=1&w1_sh=11&w1_sm=0&w1_ss=0&w1_eh=21&w1_em=30&w1_es=0&w1_on=10" +
                "&w1_off=20&w2_on=10&w2_off=20&novo_campo=7&flag_nova&farm_id=65",
            serialize(wifi, wifi.form, 65),
        )
    }

    @Test
    fun `senha e chave so quando digitadas, chave normalizada com tracos`() {
        val p = serialize(wifi, wifi.form.copy(password = "senha 123", actCode = "ab12cd34e"), null)
        assertTrue(p.contains("&password=senha%20123&act_code=AB12-CD34-E&"))
        assertFalse(p.contains("farm_id"))
    }

    @Test
    fun `rede aberta manda open_network e nenhuma senha`() {
        val p = serialize(wifi, wifi.form.withCheck("open_network", true).copy(password = "ignorada"), 65)
        assertTrue(p.contains("&open_network=1&"))
        assertFalse(p.contains("password"))
    }

    @Test
    fun `Bluetooth nao manda rede, senha nem chave, checkbox desmarcado fica ausente`() {
        val p = serialize(bt, bt.form.copy(ssid = "sobrou", password = "12345678").withCheck("adaptive", false), 65)
        assertTrue(p.startsWith("envio=1&ant1=1&power=30"))
        assertFalse(Regex("ssid|password|act_code|adaptive").containsMatchIn(p))
    }

    @Test
    fun `e ASCII`() {
        val p = serialize(wifi, wifi.form.copy(ssid = "Café 🐄", ssidEncoded = null, password = "çççççççç"), 65)
        assertTrue(Regex("^[\\x01-\\x7f]+$").matches(p))
    }

    @Test
    fun `recusa passar de 1023 bytes`() {
        val big = bt.copy(extras = listOf("x=".padEnd(1100, 'a')))
        try {
            serialize(big, bt.form, 1)
            fail("deveria lançar")
        } catch (e: ConfigPayloadException) {
            assertTrue(e.message!!.contains("grande demais"))
        }
    }

    // ── modelos ─────────────────────────────────────────────────────────────

    @Test
    fun `padrao de fabrica mantem envio, rede, senha e chave`() {
        val out = applyFactoryDefaults(wifi.form.copy(password = "12345678", actCode = "AB"))
        assertEquals(Envio.WIFI, out.envio)
        assertEquals(wifi.form.ssidEncoded, out.ssidEncoded)
        assertEquals("12345678", out.password)
        assertEquals("AB", out.actCode)
        assertEquals(factoryForm().nums, out.nums)
        assertFalse(out.check("ant3"))
        assertTrue(out.check("adaptive"))
    }

    @Test
    fun `modelo nao guarda chave nem senha, aplicar limpa a chave e usa a senha guardada`() {
        val tpl = templateFromForm(wifi.form.copy(password = "12345678", actCode = "AB12"))
        val json = templateToJson(tpl)
        assertFalse(Regex("12345678|AB12").containsMatchIn(json))
        val applied = applyTemplate(parseTemplate(json), "segura99")
        assertEquals("", applied.actCode)
        assertEquals("segura99", applied.password)
        assertEquals(wifi.form.ssidEncoded, applied.ssidEncoded)
    }

    @Test
    fun `modelo de esquema antigo completa com o padrao, JSON invalido falha`() {
        val tpl = parseTemplate("""{"envio":"bluetooth","nums":{"power":25,"lixo":1}}""")
        assertEquals(Envio.BLUETOOTH, tpl.envio)
        assertEquals("25", tpl.nums["power"])
        assertEquals("60", tpl.nums["sweep_sec"])
        for (bad in listOf("não é json", "[1]")) {
            try {
                parseTemplate(bad)
                fail("deveria lançar para $bad")
            } catch (_: TemplateParseException) {
            }
        }
    }

    // ── ajudas da tela ──────────────────────────────────────────────────────

    @Test
    fun `chave formatada enquanto digita`() {
        assertEquals("AB12-CD34-EF", formatActCode("ab12 cd34-ef"))
    }

    @Test
    fun `duracoes e horario de Brasilia`() {
        val w = windowTimes(bt.form)
        assertEquals(36_000L, w.d1)
        assertEquals(50_400L, w.d2)
        assertEquals("10 h 30 min", durationHint(37_800))
        assertEquals("08:00", brasiliaHint("11", "0"))
        assertEquals("22:05", brasiliaHint("1", "5"))
    }
}

class WifiOptionsTest {
    private fun scan(ssid: String, freq: Int, caps: String, rssi: Int) = WifiOptions.PhoneScan(ssid, freq, caps, rssi)

    @Test
    fun `motivo na ordem 5 GHz, Enterprise, WPA3`() {
        assertEquals(WifiOptions.REASON_5GHZ, WifiOptions.reasonOf(scan("a", 5180, "[WPA2-EAP-CCMP]", -50)))
        assertEquals(WifiOptions.REASON_ENTERPRISE, WifiOptions.reasonOf(scan("a", 2412, "[WPA2-EAP-CCMP][RSN-EAP-CCMP]", -50)))
        assertEquals(WifiOptions.REASON_WPA3, WifiOptions.reasonOf(scan("a", 2412, "[RSN-SAE-CCMP][ESS]", -50)))
        assertNull(WifiOptions.reasonOf(scan("a", 2412, "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]", -50)))
        // Modo de transição WPA2/WPA3: a smartbox entra pelo WPA2.
        assertNull(WifiOptions.reasonOf(scan("a", 2437, "[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]", -50)))
        assertNull(WifiOptions.reasonOf(scan("a", 2437, "[ESS]", -50)))
    }

    @Test
    fun `agrupa por SSID, ignora vazio, compativeis primeiro e pelo sinal`() {
        val list = WifiOptions.fromPhone(
            listOf(
                scan("Casa", 5180, "[WPA2-PSK-CCMP]", -40),
                scan("Casa", 2412, "[WPA2-PSK-CCMP]", -70),
                scan("Escritorio", 2412, "[WPA2-EAP-CCMP]", -30),
                scan("", 2412, "[ESS]", -20),
                scan("Aberta", 2462, "[ESS]", -60),
            ),
        )
        assertEquals(listOf("Aberta", "Casa", "Escritorio"), list.map { it.ssid })
        assertTrue(list[0].open)
        assertEquals(-70, list[1].rssi)          // a entrada de 2,4 GHz, não a de 5 GHz
        assertEquals(WifiOptions.REASON_ENTERPRISE, list[2].incompatibleReason)
        assertEquals("Casa", UrlForm.formDecode(list[1].ssidEncoded))
    }
}
