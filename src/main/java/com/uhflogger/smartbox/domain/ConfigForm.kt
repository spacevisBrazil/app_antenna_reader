package com.uhflogger.smartbox.domain

import org.json.JSONObject

/**
 * Formulário da tela Configurar (seção 12 do guia): parse do GET_CONFIG,
 * padrão de fábrica, validação, resumo das mudanças e serialização do
 * SET_CONFIG. Puro, sem rádio. Porte do configForm.ts do spacevis-mobile.
 *
 * Contrato com o firmware que NÃO pode mudar:
 * - checkbox presente = marcado, ausente = desmarcado (sempre o formulário inteiro);
 * - `password` e `act_code` nunca vêm no GET_CONFIG; em branco = mantém a salva;
 * - chave desconhecida (firmware mais novo) volta igual no SET_CONFIG;
 * - a Window é validada e enviada mesmo desligada (o firmware valida sempre);
 * - tudo ASCII (urlencoded), sem byte nulo, até 1023 bytes.
 */
enum class Envio { WIFI, BLUETOOTH }

data class ConfigForm(
    val envio: Envio,
    /** SSID como texto (o que aparece no campo). */
    val ssid: String,
    /** Forma codificada EXATA do SSID (do GET_CONFIG ou da lista de redes). null = digitado à mão. */
    val ssidEncoded: String?,
    /** Só o que foi digitado nesta tela (nunca vem da smartbox). */
    val password: String,
    /** Idem: em branco mantém a chave atual. */
    val actCode: String,
    val nums: Map<String, String>,
    val checks: Map<String, Boolean>,
) {
    fun num(key: String): String = nums[key].orEmpty()
    fun check(key: String): Boolean = checks[key] == true
    fun withNum(key: String, v: String) = copy(nums = nums + (key to v))
    fun withCheck(key: String, v: Boolean) = copy(checks = checks + (key to v))
}

/** O que a smartbox devolveu no GET_CONFIG: base do resumo de mudanças. */
data class LoadedConfig(
    val form: ConfigForm,
    /** Pares desconhecidos, como vieram (`k` ou `k=v`). */
    val extras: List<String>,
    /** Só leitura (cabeçalho). null = firmware não mandou. */
    val farmId: Long?,
    /** 0 = gravada pelo app, 1 = veio da chave de ativação. */
    val farmSrc: Int?,
)

class ConfigPayloadException(message: String) : Exception(message)

class TemplateParseException : Exception("Não deu para ler este modelo. Apague e salve de novo.")

/** O que um modelo guarda: o formulário SEM chave de ativação e SEM senha. */
data class TemplateForm(
    val envio: Envio,
    val ssid: String,
    val ssidEncoded: String?,
    val nums: Map<String, String>,
    val checks: Map<String, Boolean>,
)

object ConfigForms {
    val NUM_KEYS = listOf(
        "power", "sweep_sec", "valid_sec",
        "w1_sh", "w1_sm", "w1_ss", "w1_eh", "w1_em", "w1_es",
        "w1_on", "w1_off", "w2_on", "w2_off",
    )
    val CHECK_KEYS = listOf("open_network", "ant1", "ant2", "ant3", "ant4", "adaptive", "w_enabled", "w1_esp32", "w2_esp32")

    // ── Padrões de fábrica (12.2) ──────────────────────────────────────────

    private val FACTORY_NUMS = mapOf(
        "power" to "30", "sweep_sec" to "60", "valid_sec" to "120",
        "w1_sh" to "8", "w1_sm" to "0", "w1_ss" to "0",
        "w1_eh" to "18", "w1_em" to "0", "w1_es" to "0",
        "w1_on" to "10", "w1_off" to "20", "w2_on" to "10", "w2_off" to "20",
    )
    private val FACTORY_CHECKS = mapOf(
        "ant1" to true, "ant2" to false, "ant3" to false, "ant4" to false,
        "adaptive" to true, "w_enabled" to true, "w1_esp32" to false, "w2_esp32" to false,
    )

    fun factoryForm() = ConfigForm(
        envio = Envio.WIFI, ssid = "", ssidEncoded = null, password = "", actCode = "",
        nums = FACTORY_NUMS, checks = FACTORY_CHECKS + ("open_network" to false),
    )

    /**
     * Modelo "Padrão de fábrica" (12.4): preenche leitor e Window com o de
     * fábrica e MANTÉM envio, rede, senha e chave (trocar o envio sem querer
     * tira a smartbox do Bluetooth).
     */
    fun applyFactoryDefaults(form: ConfigForm) = form.copy(
        nums = FACTORY_NUMS,
        checks = FACTORY_CHECKS + ("open_network" to form.check("open_network")),
    )

    // ── Parse do GET_CONFIG (12.1) ─────────────────────────────────────────

    /** Nunca vêm; se vierem, não devolvemos (seriam segredo em claro no formulário). */
    private val SECRET_KEYS = setOf("password", "act_code")
    private val FARM_ID = Regex("^\\d{1,10}$")
    private val FARM_SRC = Regex("^\\d{1,3}$")

    fun parseGetConfig(text: String): LoadedConfig {
        var envio = Envio.WIFI
        var ssid = ""
        var ssidEncoded: String? = null
        val nums = FACTORY_NUMS.toMutableMap()
        // Checkbox ausente = desmarcado (não o padrão de fábrica).
        val checks = CHECK_KEYS.associateWith { false }.toMutableMap()
        val extras = mutableListOf<String>()
        var farmId: Long? = null
        var farmSrc: Int? = null

        for (piece in text.trim().split('&')) {
            if (piece.isEmpty()) continue
            val eq = piece.indexOf('=')
            val key = if (eq < 0) piece else piece.substring(0, eq)
            val raw = if (eq < 0) "" else piece.substring(eq + 1)
            when {
                key == "envio" -> envio = if (raw == "1") Envio.BLUETOOTH else Envio.WIFI
                key == "ssid" -> {
                    ssid = UrlForm.formDecode(raw)
                    ssidEncoded = raw.ifEmpty { null }
                }
                key == "farm_id" -> farmId = if (FARM_ID.matches(raw)) raw.toLong() else null
                key == "farm_src" -> farmSrc = if (FARM_SRC.matches(raw)) raw.toInt() else null
                key in CHECK_KEYS -> checks[key] = true
                key in NUM_KEYS -> nums[key] = UrlForm.formDecode(raw).trim()
                key in SECRET_KEYS -> Unit
                else -> extras += piece
            }
        }
        return LoadedConfig(ConfigForm(envio, ssid, ssidEncoded, "", "", nums, checks), extras, farmId, farmSrc)
    }

    // ── Regras auxiliares ──────────────────────────────────────────────────

    private val DIGITS = Regex("^\\d{1,9}$")

    /** Só dígitos decimais (o firmware leria lixo como 0). */
    fun num(value: String): Long? = value.trim().takeIf { DIGITS.matches(it) }?.toLong()

    fun ssidBytes(form: ConfigForm): ByteArray =
        form.ssidEncoded?.let { UrlForm.formDecodeBytes(it) } ?: UrlForm.utf8Encode(form.ssid)

    /**
     * Senha em branco mantém a salva só se: a rede é a mesma (bytes iguais),
     * havia rede salva e ela não era aberta.
     */
    fun canKeepPassword(original: ConfigForm, form: ConfigForm): Boolean {
        val saved = ssidBytes(original)
        if (saved.isEmpty() || original.check("open_network")) return false
        return saved.contentEquals(ssidBytes(form))
    }

    const val ACT_CODE_MAX = 51

    /** Tira espaços e traços, maiúsculas. Não remove caractere inválido: a validação acusa. */
    fun normalizeActCode(raw: String): String = raw.replace(Regex("[\\s-]"), "").uppercase()

    /** `XXXX-XXXX-…` (enquanto digita e no envio). */
    fun formatActCode(raw: String): String = normalizeActCode(raw).chunked(4).joinToString("-")

    private fun secondsOfDay(h: String, m: String, s: String): Long? {
        val hh = num(h) ?: return null
        val mm = num(m) ?: return null
        val ss = num(s) ?: return null
        if (hh > 23 || mm > 59 || ss > 59) return null
        return hh * 3600 + mm * 60 + ss
    }

    data class WindowTimes(val start: Long?, val end: Long?, val d1: Long?, val d2: Long?)

    fun windowTimes(form: ConfigForm): WindowTimes {
        val start = secondsOfDay(form.num("w1_sh"), form.num("w1_sm"), form.num("w1_ss"))
        val end = secondsOfDay(form.num("w1_eh"), form.num("w1_em"), form.num("w1_es"))
        val d1 = if (start != null && end != null && end > start) end - start else null
        return WindowTimes(start, end, d1, d1?.let { 86_400 - it })
    }

    /** "10 h 30 min" para a dica de duração. */
    fun durationHint(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h h"
        if (m > 0) parts += "$m min"
        if (s > 0 || parts.isEmpty()) parts += "$s s"
        return parts.joinToString(" ")
    }

    /** Horário UTC da smartbox → HH:MM de Brasília (UTC−3). */
    fun brasiliaHint(h: String, m: String): String? {
        val hh = num(h) ?: return null
        val mm = num(m) ?: return null
        if (hh > 23 || mm > 59) return null
        return "%02d:%02d".format((hh + 21) % 24, mm)
    }

    // ── Validação (12.5.1): mesma ordem e textos do firmware; para no primeiro ──

    private val ACT_CODE_OK = Regex("^[A-Z0-9]{1,$ACT_CODE_MAX}$")

    fun validate(original: LoadedConfig, form: ConfigForm): String? {
        val wifi = form.envio == Envio.WIFI
        if (wifi) {
            val ssid = ssidBytes(form)
            if (ssid.isEmpty()) return "Selecione uma rede antes de salvar."
            // Regra só do app: o firmware corta em silêncio.
            if (ssid.size > 31) return "Nome da rede com mais de 31 bytes: a smartbox nao aceita."
            if (!form.check("open_network")) {
                if (form.password.isEmpty()) {
                    if (!canKeepPassword(original.form, form)) {
                        return "Senha vazia. Digite a senha da rede, ou marque \"rede sem senha\" se for mesmo uma rede aberta."
                    }
                } else {
                    val len = UrlForm.utf8Length(form.password)
                    if (len < 8) return "Senha recusada: a senha da rede precisa ter pelo menos 8 caracteres."
                    if (len > 63) return "Senha com mais de 63 caracteres: a smartbox nao aceita."
                }
            }
        }

        if (!form.check("ant1") && !form.check("ant2") && !form.check("ant3") && !form.check("ant4")) {
            return "Selecione pelo menos uma antena."
        }
        val power = num(form.num("power"))
        if (power == null || power < 5 || power > 33) return "Potencia fora do intervalo permitido (5 a 33 dBm)."
        val sweep = num(form.num("sweep_sec"))
        if (sweep == null || sweep < 10 || sweep > 3600) return "Tempo de varredura fora do intervalo permitido (10 a 3600s)."
        val valid = num(form.num("valid_sec"))
        if (valid == null || valid < 60 || valid > 86400) return "Tempo de validade fora do intervalo permitido (60 a 86400s)."

        if (wifi) {
            val code = normalizeActCode(form.actCode)
            // Regra só do app: Bluetooth → Wi-Fi sem chave fica sem enviar.
            if (original.form.envio == Envio.BLUETOOTH && code.isEmpty()) {
                return "Digite a chave de ativacao desta smartbox: sem ela, no modo Wi-Fi, ela nao consegue enviar."
            }
            if (code.isNotEmpty() && !ACT_CODE_OK.matches(code)) {
                return "Chave de ativacao invalida: use so letras e numeros (tracos opcionais)."
            }
        }

        // A Window é validada mesmo desligada (o firmware valida sempre).
        val w = windowTimes(form)
        val start = w.start ?: return "Horario de inicio da Window 1 invalido."
        val end = w.end ?: return "Horario de fim da Window 1 invalido."
        if (end <= start) return "Fim da Window 1 deve ser maior que o inicio (sem cruzar meia-noite)."
        val d1 = end - start
        if (d1 < 60 || d1 > 86_340) return "Window 1 deve ter entre 60 e 86340 segundos de duracao."
        val d2 = 86_400 - d1
        val w1On = num(form.num("w1_on"))
        if (w1On == null || w1On < 10 || w1On > d1) return "Ciclo ON da Window 1 invalido (min. 10s, max = duracao da window)."
        val w1Off = num(form.num("w1_off"))
        if (w1Off == null || w1Off > d1 - 1) return "Ciclo OFF da Window 1 invalido (0 a duracao-1 segundos)."
        val w2On = num(form.num("w2_on"))
        if (w2On == null || w2On < 10 || w2On > d2) return "Ciclo ON da Window 2 invalido (min. 10s, max = duracao da window 2)."
        val w2Off = num(form.num("w2_off"))
        if (w2Off == null || w2Off > d2 - 1) return "Ciclo OFF da Window 2 invalido (0 a duracao-1 segundos)."
        return null
    }

    // ── Resumo das mudanças (12.5.2) ───────────────────────────────────────

    data class ChangeSummary(
        val lines: List<String>,
        /** Bluetooth → Wi-Fi: título "Passar para Wi-Fi?" e botão de ação destrutiva. */
        val toWifi: Boolean,
    )

    private fun envioLabel(e: Envio) = if (e == Envio.WIFI) "Wi-Fi" else "Bluetooth"
    private fun yesNo(v: Boolean) = if (v) "sim" else "não"

    private fun sameNumber(a: String, b: String): Boolean {
        val na = num(a)
        val nb = num(b)
        return if (na != null && nb != null) na == nb else a.trim() == b.trim()
    }

    private fun hms(h: String, m: String, s: String): String =
        listOf(h, m, s).joinToString(":") { v -> num(v)?.let { "%02d".format(it) } ?: v.trim() }

    private val CHECK_LABELS = listOf(
        "ant1" to "Antenas ativas · Antena 1",
        "ant2" to "Antenas ativas · Antena 2",
        "ant3" to "Antenas ativas · Antena 3",
        "ant4" to "Antenas ativas · Antena 4",
    )
    private val READER_NUMS = listOf(
        "power" to "Leitor RFID · Potência (dBm)",
        "sweep_sec" to "Leitor RFID · Intervalo entre varreduras (s)",
        "valid_sec" to "Leitor RFID · Bloqueio da tag após envio (s)",
    )
    /** (chave, rótulo, éCheckbox). */
    private val WINDOW_FIELDS = listOf(
        Triple("w1_on", "Modo Window · Window 1 · Tempo lendo (s)", false),
        Triple("w1_off", "Modo Window · Window 1 · Tempo parado (s)", false),
        Triple("w1_esp32", "Modo Window · Window 1 · Ciclos no ESP32", true),
        Triple("w2_on", "Modo Window · Window 2 · Tempo lendo (s)", false),
        Triple("w2_off", "Modo Window · Window 2 · Tempo parado (s)", false),
        Triple("w2_esp32", "Modo Window · Window 2 · Ciclos no ESP32", true),
    )

    fun summarizeChanges(original: ConfigForm, form: ConfigForm): ChangeSummary {
        val lines = mutableListOf<String>()
        fun line(label: String, before: String?, after: String) {
            lines += if (before == null) "• $label: $after" else "• $label: $before → $after"
        }

        if (original.envio != form.envio) line("Envio", envioLabel(original.envio), envioLabel(form.envio))

        if (form.envio == Envio.WIFI) {
            if (!ssidBytes(original).contentEquals(ssidBytes(form))) {
                line("Rede Wi-Fi", original.ssid.ifEmpty { "(nenhuma)" }, form.ssid)
            }
            if (form.check("open_network") && !(original.check("open_network") && original.envio == Envio.WIFI)) {
                line("Senha", null, "rede aberta (sem senha)")
            } else if (!form.check("open_network") && form.password.isNotEmpty()) {
                line("Senha", null, "nova")
            }
            if (normalizeActCode(form.actCode).isNotEmpty()) line("Chave de ativação", null, "nova")
        }

        for ((k, label) in CHECK_LABELS) {
            if (original.check(k) != form.check(k)) line(label, yesNo(original.check(k)), yesNo(form.check(k)))
        }
        for ((k, label) in READER_NUMS) {
            if (!sameNumber(original.num(k), form.num(k))) line(label, original.num(k).trim(), form.num(k).trim())
        }
        if (original.check("adaptive") != form.check("adaptive")) {
            line("Leitor RFID · Modo adaptativo", yesNo(original.check("adaptive")), yesNo(form.check("adaptive")))
        }
        if (original.check("w_enabled") != form.check("w_enabled")) {
            line("Modo Window · Ligado", yesNo(original.check("w_enabled")), yesNo(form.check("w_enabled")))
        }
        for ((k, label, isCheck) in WINDOW_FIELDS) {
            if (isCheck) {
                if (original.check(k) != form.check(k)) line(label, yesNo(original.check(k)), yesNo(form.check(k)))
            } else if (!sameNumber(original.num(k), form.num(k))) {
                line(label, original.num(k).trim(), form.num(k).trim())
            }
        }

        val startChanged = !listOf("w1_sh", "w1_sm", "w1_ss").all { sameNumber(original.num(it), form.num(it)) }
        val endChanged = !listOf("w1_eh", "w1_em", "w1_es").all { sameNumber(original.num(it), form.num(it)) }
        if (startChanged) {
            line(
                "Modo Window · Window 1 · Início (UTC)",
                hms(original.num("w1_sh"), original.num("w1_sm"), original.num("w1_ss")),
                hms(form.num("w1_sh"), form.num("w1_sm"), form.num("w1_ss")),
            )
        }
        if (endChanged) {
            line(
                "Modo Window · Window 1 · Fim (UTC)",
                hms(original.num("w1_eh"), original.num("w1_em"), original.num("w1_es")),
                hms(form.num("w1_eh"), form.num("w1_em"), form.num("w1_es")),
            )
        }
        return ChangeSummary(lines, original.envio == Envio.BLUETOOTH && form.envio == Envio.WIFI)
    }

    // ── Serialização do SET_CONFIG (12.5.3) ─────────────────────────────────

    const val SET_CONFIG_MAX_BYTES = 1023

    /** (éCheckbox, chave) na ordem do portal. */
    private val SERIAL_ORDER = listOf(
        true to "ant1", true to "ant2", true to "ant3", true to "ant4",
        false to "power", false to "sweep_sec", false to "valid_sec",
        true to "adaptive", true to "w_enabled",
        false to "w1_sh", false to "w1_sm", false to "w1_ss",
        false to "w1_eh", false to "w1_em", false to "w1_es",
        false to "w1_on", false to "w1_off", true to "w1_esp32",
        false to "w2_on", false to "w2_off", true to "w2_esp32",
    )
    private val ASCII_NO_NUL = Regex("^[\\x01-\\x7f]*$")

    /**
     * Formulário inteiro, na ordem do portal. Chame só depois de [validate]
     * devolver null. [farmId] = fazenda do aparelho (vai no fim, seção 10.5).
     */
    fun serialize(loaded: LoadedConfig, form: ConfigForm, farmId: Long?): String {
        val parts = mutableListOf("envio=${if (form.envio == Envio.BLUETOOTH) "1" else "0"}")
        if (form.envio == Envio.WIFI) {
            parts += "ssid=${form.ssidEncoded ?: UrlForm.formEncode(form.ssid)}"
            if (form.check("open_network")) parts += "open_network=1"
            else if (form.password.isNotEmpty()) parts += "password=${UrlForm.formEncode(form.password)}"
            val code = normalizeActCode(form.actCode)
            if (code.isNotEmpty()) parts += "act_code=${UrlForm.formEncode(formatActCode(code))}"
        }
        for ((isCheck, key) in SERIAL_ORDER) {
            if (isCheck) { if (form.check(key)) parts += "$key=1" } else parts += "$key=${form.num(key).trim()}"
        }
        parts += loaded.extras
        if (farmId != null) {
            if (farmId < 1 || farmId > 0xFFFF_FFFFL) throw ConfigPayloadException("Fazenda do aparelho inválida.")
            parts += "farm_id=$farmId"
        }
        val payload = parts.joinToString("&")
        if (!ASCII_NO_NUL.matches(payload)) throw ConfigPayloadException("Configuração com caractere inválido.")
        if (payload.length > SET_CONFIG_MAX_BYTES) {
            throw ConfigPayloadException("Configuração grande demais para a smartbox (máx. 1023 bytes).")
        }
        return payload
    }

    // ── Modelos (12.4) ─────────────────────────────────────────────────────

    fun templateFromForm(form: ConfigForm) = TemplateForm(
        envio = form.envio,
        ssid = if (form.envio == Envio.WIFI) form.ssid else "",
        ssidEncoded = if (form.envio == Envio.WIFI) form.ssidEncoded else null,
        nums = form.nums.toMap(),
        checks = form.checks.toMap(),
    )

    fun templateToJson(t: TemplateForm): String = JSONObject().apply {
        put("envio", if (t.envio == Envio.BLUETOOTH) "bluetooth" else "wifi")
        put("ssid", t.ssid)
        t.ssidEncoded?.let { put("ssidEncoded", it) }
        put("nums", JSONObject(t.nums))
        put("checks", JSONObject(t.checks))
    }.toString()

    /**
     * Lê o JSON de um modelo. Esquema antigo/novo: ignora o desconhecido e
     * completa com o padrão de fábrica. Só JSON inválido ou não-objeto falha.
     */
    fun parseTemplate(json: String): TemplateForm {
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw TemplateParseException()
        }
        val base = factoryForm()
        val nums = base.nums.toMutableMap()
        val checks = base.checks.toMutableMap()
        obj.optJSONObject("nums")?.let { n ->
            for (k in NUM_KEYS) {
                if (!n.has(k)) continue
                when (val v = n.opt(k)) {
                    is String -> nums[k] = v
                    is Number -> nums[k] = v.toLong().toString()
                }
            }
        }
        obj.optJSONObject("checks")?.let { c ->
            for (k in CHECK_KEYS) (c.opt(k) as? Boolean)?.let { checks[k] = it }
        }
        return TemplateForm(
            envio = if (obj.optString("envio") == "bluetooth") Envio.BLUETOOTH else Envio.WIFI,
            ssid = obj.optString("ssid", ""),
            ssidEncoded = if (obj.has("ssidEncoded") && obj.opt("ssidEncoded") is String) obj.getString("ssidEncoded") else null,
            nums = nums,
            checks = checks,
        )
    }

    /**
     * Aplica o modelo no formulário INTEIRO, inclusive envio e rede. A chave
     * de ativação fica em branco (cada smartbox tem a sua); a senha vem do
     * armazenamento seguro, se o modelo tiver.
     */
    fun applyTemplate(tpl: TemplateForm, password: String?) = ConfigForm(
        envio = tpl.envio,
        ssid = if (tpl.envio == Envio.WIFI) tpl.ssid else "",
        ssidEncoded = if (tpl.envio == Envio.WIFI) tpl.ssidEncoded else null,
        password = if (tpl.envio == Envio.WIFI && tpl.checks["open_network"] != true) password.orEmpty() else "",
        actCode = "",
        nums = tpl.nums.toMap(),
        checks = tpl.checks.toMap(),
    )

    /** Nome sugerido ao guardar: "Wi-Fi <rede>" ou "Bluetooth". */
    fun suggestedTemplateName(form: ConfigForm): String =
        if (form.envio == Envio.WIFI) "Wi-Fi ${form.ssid}".trim() else "Bluetooth"
}
