package com.uhflogger.smartbox.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * GET_DIAG legível (seção 13.2 do guia). Porte de
 * spacevis-mobile/src/features/smartbox/domain/diag.ts.
 *
 * Formato: `chave=valor\n`, números crus, textos sem acento. Campo
 * desconhecido aparece como veio (firmware mais novo); linha sem `=` também.
 */
object DiagFormat {

    enum class Tone { OK, WARN, BAD, NEUTRAL }

    data class Row(val key: String, val label: String, val value: String, val tone: Tone)

    data class Parsed(val pairs: List<Pair<String, String>>, val loose: List<String>)

    fun parse(text: String): Parsed {
        val pairs = mutableListOf<Pair<String, String>>()
        val loose = mutableListOf<String>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq < 0) loose += line else pairs += line.substring(0, eq) to line.substring(eq + 1)
        }
        return Parsed(pairs, loose)
    }

    private val INT = Regex("^-?\\d+$")
    private fun int(v: String): Long? = if (INT.matches(v)) v.toLongOrNull() else null

    fun formatUptime(seconds: Long): String {
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            d > 0 -> "$d d $h h"
            h > 0 -> "$h h ${m.toString().padStart(2, '0')} min"
            else -> "$m min ${s.toString().padStart(2, '0')} s"
        }
    }

    private fun kb(bytes: Long) = "${(bytes / 1024.0).roundToLong()} KB"

    private val RESET_TEXT = mapOf(
        "energia ligada" to ("Ligou na energia" to Tone.NEUTRAL),
        "pino EN" to ("Botão de reset (EN)" to Tone.NEUTRAL),
        "software" to ("Pelo próprio firmware" to Tone.NEUTRAL),
        "deep sleep" to ("Acordou do deep sleep" to Tone.NEUTRAL),
        "USB" to ("Pela USB" to Tone.NEUTRAL),
        "JTAG" to ("Pelo JTAG" to Tone.NEUTRAL),
        "SDIO" to ("SDIO" to Tone.NEUTRAL),
        "desconhecido" to ("Desconhecido" to Tone.NEUTRAL),
        "panico" to ("Travou (erro no firmware)" to Tone.BAD),
        "watchdog de interrupcao" to ("Travou (watchdog de interrupção)" to Tone.BAD),
        "watchdog de task" to ("Travou (watchdog de task)" to Tone.BAD),
        "watchdog" to ("Travou (watchdog)" to Tone.BAD),
        "brownout" to ("Queda de tensão" to Tone.BAD),
        "efuse" to ("Erro de efuse" to Tone.BAD),
        "glitch de energia" to ("Falha na energia" to Tone.BAD),
        "CPU travada" to ("CPU travada" to Tone.BAD),
    )

    private val CAUSE_TEXT = mapOf(
        "nenhuma" to ("Nenhum" to Tone.OK),
        "botao GPIO 32" to ("Botão de configuração (GPIO 32)" to Tone.NEUTRAL),
        "modulo RFID sem resposta ao 0x21" to ("Módulo RFID não respondeu (0x21)" to Tone.BAD),
        "modulo RFID mudo ao inventario" to ("Módulo RFID parou de ler" to Tone.BAD),
        "guard de falhas HTTP" to ("Muitas falhas de envio (HTTP)" to Tone.WARN),
        "config salva no portal" to ("Configuração salva no portal" to Tone.NEUTRAL),
        "rede nova nao conectou" to ("A rede Wi-Fi nova não conectou" to Tone.WARN),
        "timeout do modo configuracao" to ("Saiu do modo configuração por tempo" to Tone.NEUTRAL),
        "pedido pelo BLE" to ("Pedido pelo celular" to Tone.NEUTRAL),
        "reset de fabrica" to ("Voltou ao padrão de fábrica" to Tone.NEUTRAL),
    )

    private val WIFI_FAIL_TEXT = mapOf(
        1L to "motivo não informado",
        2L to "autenticação expirou",
        3L to "o roteador desconectou",
        4L to "tempo de associação esgotado",
        8L to "saiu da rede",
        14L to "senha incorreta? (handshake não completou)",
        15L to "senha incorreta? (handshake não completou)",
        204L to "senha incorreta? (handshake não completou)",
        200L to "roteador sumiu (sem beacon)",
        201L to "rede não encontrada",
        202L to "autenticação recusada (senha?)",
        203L to "associação recusada",
        205L to "conexão falhou",
    )

    private val THERMAL_TONE = mapOf("normal" to Tone.OK, "quente" to Tone.WARN, "critico" to Tone.BAD)
    private val THERMAL_TEXT = mapOf("normal" to "normal", "quente" to "quente", "critico" to "crítico")

    private val LOCAL_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    private fun clockRow(value: String, readAtMs: Long, zone: ZoneId): Row {
        val label = "Relógio"
        if (value == "invalida") return Row("hora", label, "Sem hora (espera o NTP ou o aparelho)", Tone.BAD)
        val t = try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            return Row("hora", label, value, Tone.NEUTRAL)
        }
        // Diferença no INSTANTE da leitura, não na hora de desenhar.
        val diffS = ((t - readAtMs) / 1000.0).roundToLong()
        val absS = abs(diffS)
        val local = LOCAL_TIME.format(Instant.ofEpochMilli(t).atZone(zone))
        if (absS <= 5) return Row("hora", label, "$local · igual ao aparelho", Tone.OK)
        val direction = if (diffS > 0) "adiantado" else "atrasado"
        return Row("hora", label, "$local · $direction ${formatUptime(absS)}", if (absS <= 120) Tone.WARN else Tone.BAD)
    }

    private fun returnLossRow(port: Int, raw: String): Row {
        val label = "Antena $port (return loss)"
        val key = "return_loss_ant$port"
        val v = int(raw) ?: return Row(key, label, "—", Tone.NEUTRAL)
        return when {
            v == -1L -> Row(key, label, "sem medição: módulo RFID não respondeu (desconectado?)", Tone.BAD)
            v >= 10 -> Row(key, label, "$v dB · bom, antena bem casada", Tone.OK)
            v >= 6 -> Row(key, label, "$v dB · regular: o alcance cai, confira cabo e conector", Tone.WARN)
            else -> Row(key, label, "$v dB · ruim: troque a antena, o cabo ou o conector", Tone.BAD)
        }
    }

    private val RETURN_LOSS = Regex("^return_loss_ant[1-4]$")

    /** Linhas legíveis. [readAtMs] = hora do aparelho quando o GET_DIAG voltou. */
    fun readable(text: String, readAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Row> {
        val (pairs, loose) = parse(text)
        val map = LinkedHashMap<String, String>().apply { pairs.forEach { (k, v) -> put(k, v) } }
        val rows = mutableListOf<Row>()
        val used = HashSet<String>()

        for ((k, _) in pairs) {
            if (k in used) continue
            val v = map[k].orEmpty()
            val n = int(v)
            when (k) {
                "firmware" -> rows += Row(k, "Versão do firmware", v, Tone.NEUTRAL)
                "reset" -> RESET_TEXT[v].let { rows += Row(k, "Como reiniciou da última vez", it?.first ?: v, it?.second ?: Tone.NEUTRAL) }
                "causa" -> CAUSE_TEXT[v].let { rows += Row(k, "Motivo do reinício (firmware)", it?.first ?: v, it?.second ?: Tone.NEUTRAL) }
                "boots" -> rows += Row(
                    k, "Reinícios desde que ligou",
                    when { n == null -> v; n <= 1 -> "Nenhum"; else -> (n - 1).toString() },
                    if (n != null && n > 3) Tone.WARN else Tone.NEUTRAL,
                )
                "boots_total" -> rows += Row(k, "Partidas no total", v, Tone.NEUTRAL)
                "uptime_s" -> rows += Row(k, "Ligada há", n?.let { formatUptime(it) } ?: v, Tone.NEUTRAL)
                "heap" -> rows += Row(k, "Memória livre", n?.let { kb(it) } ?: v, Tone.NEUTRAL)
                "heap_min" -> rows += Row(
                    k, "Menor memória livre", n?.let { kb(it) } ?: v,
                    when { n == null -> Tone.NEUTRAL; n < 20_000 -> Tone.BAD; n < 40_000 -> Tone.WARN; else -> Tone.NEUTRAL },
                )
                "heap_maior_bloco" -> rows += Row(k, "Maior bloco livre", n?.let { kb(it) } ?: v, if (n != null && n < 10_000) Tone.WARN else Tone.NEUTRAL)
                "hora" -> rows += clockRow(v, readAtMs, zone)
                "ds3231" -> rows += Row(
                    k, "Relógio de bateria (DS3231)",
                    when (v) { "1" -> "OK"; "0" -> "Falha ou não instalado"; else -> v },
                    when (v) { "1" -> Tone.OK; "0" -> Tone.BAD; else -> Tone.NEUTRAL },
                )
                "ligacao" -> rows += Row(k, "Vezes que ligou na energia", v, Tone.NEUTRAL)
                "pendentes" -> rows += Row(k, "Leituras para baixar", v, if (n != null && n > 0) Tone.WARN else Tone.NEUTRAL)
                "vfail" -> rows += Row(
                    k, "Última conexão Wi-Fi",
                    when { n == 0L -> "Sem falha"; n == null -> v; else -> "Falhou: ${WIFI_FAIL_TEXT[n] ?: "motivo"} (código $n)" },
                    if (n == 0L) Tone.OK else Tone.BAD,
                )
                "auth_recusadas" -> rows += Row(
                    k, "Conexões recusadas (chave errada)",
                    when { n == 0L -> "Nenhuma desde que ligou"; n == null -> v; else -> "$n desde que ligou (app com outra chave?)" },
                    if (n != null && n > 0) Tone.WARN else Tone.OK,
                )
                "temp_rfid", "temp_rfid_max", "nivel_termico" -> {
                    used += listOf("temp_rfid", "temp_rfid_max", "nivel_termico")
                    val temp = map["temp_rfid"]
                    val max = map["temp_rfid_max"]
                    val level = map["nivel_termico"]
                    val parts = listOfNotNull(
                        temp?.let { "$it °C" } ?: "—",
                        max?.let { "(máx. $it °C desde o último reinício)" },
                        level?.takeIf { it.isNotEmpty() }?.let { "· ${THERMAL_TEXT[it] ?: it}" },
                    )
                    rows += Row("temp_rfid", "Temperatura do módulo RFID", parts.joinToString(" "), level?.let { THERMAL_TONE[it] } ?: Tone.NEUTRAL)
                }
                else -> if (!RETURN_LOSS.matches(k)) rows += Row(k, k, v, Tone.NEUTRAL)   // antenas: juntas, abaixo
            }
            used += k
        }

        if ("temp_rfid" !in map && "nivel_termico" !in map && pairs.isNotEmpty()) {
            rows += Row("temp_rfid", "Temperatura do módulo RFID", "— sem leitura (leitor parado ou módulo sem sensor)", Tone.NEUTRAL)
        }

        // Antenas juntas e em ordem. Ausente = desabilitada ou não medida (NUNCA 0).
        val antennas = (1..4).filter { "return_loss_ant$it" in map }
        if (antennas.isEmpty() && pairs.isNotEmpty()) {
            rows += Row("return_loss", "Antenas (return loss)", "— ainda não medido (antenas desabilitadas ou o leitor não respondeu)", Tone.NEUTRAL)
        }
        antennas.forEach { rows += returnLossRow(it, map["return_loss_ant$it"].orEmpty()) }

        loose.forEach { rows += Row(it, it, "", Tone.NEUTRAL) }
        return rows
    }
}
