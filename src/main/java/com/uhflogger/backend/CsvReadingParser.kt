package com.uhflogger.backend

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Converte uma linha do CSV local no JSON que o backend espera.
 *
 * POR QUE O CSV É A FILA
 * O arquivo já é a fila de envio: ele é gravado de qualquer jeito (é o que o
 * Drive consome), sobrevive a queda de processo, e guarda a captura inteira em
 * ordem. Manter uma segunda fila em banco com as MESMAS leituras dobraria a
 * escrita em disco de centenas de milhares de linhas sem ganhar nada. O único
 * estado que o envio precisa é "até que linha eu já mandei" — um inteiro por
 * arquivo (ver BackendUploadEntry).
 *
 * Formato (CsvExporter/TagRecord):
 *   EPC,RSSI,Antenna,Timestamp,Latitude,Longitude,Bearing,Temperature,
 *   GNSS Speed,Location Timestamp,Location Provider
 *
 * A leitura é por ÍNDICE e tolerante a colunas extras no fim: o CSV já cresceu
 * de 8 pra 11 colunas uma vez, e um parser que exigisse a contagem exata
 * quebraria em silêncio na próxima adição.
 */
object CsvReadingParser {

    private const val TAG = "CsvReadingParser"

    private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * @param sourceFile nome do arquivo — entra na identidade da leitura.
     * @param lineIndex  número da linha NO ARQUIVO (0 = cabeçalho).
     *
     * A identidade é `arquivo|linha` e não `leitor|hora|epc`: o leitor lê a
     * mesma tag várias vezes no mesmo milissegundo, então uma chave baseada em
     * tempo faria leituras legítimas se deduplicarem entre si e sumirem. Linha
     * de arquivo é única por construção e continua determinística no reenvio.
     */
    fun parseLine(sourceFile: String, lineIndex: Int, line: String): JSONObject? {
        val parts = line.split(',')
        if (parts.size < 4) return null

        val epc = parts[0].trim()
        if (epc.isEmpty()) return null

        val timestamp = parts[3].trim().toLongOrNull() ?: return null

        return try {
            JSONObject().apply {
                put("client_event_id", Uuid5.from("$sourceFile|$lineIndex"))
                put("epc", epc)
                put("read_at", ISO_UTC.format(java.util.Date(timestamp)))
                putOrNull("rssi_dbm", parts.getOrNull(1)?.trim()?.toIntOrNull())
                putOrNull("antenna_port", parts.getOrNull(2)?.trim()?.toIntOrNull())
                putOrNull("latitude", parts.getOrNull(4)?.trim()?.toDoubleOrNull())
                putOrNull("longitude", parts.getOrNull(5)?.trim()?.toDoubleOrNull())
                putOrNull("compass_bearing", parts.getOrNull(6)?.trim()?.toDoubleOrNull())
                putOrNull("temperature", parts.getOrNull(7)?.trim()?.toDoubleOrNull())
                // Colunas 8-10 (GNSS Speed, Location Timestamp, Location Provider)
                // existem no CSV mas NÃO são enviadas: `antenna_reading_raw` não
                // tem onde guardá-las hoje, e inventar um destino (concatenar em
                // outro campo, por exemplo) corromperia a evidência bruta, que é
                // justamente o que a tabela existe pra preservar. Ficam no CSV —
                // que continua indo pro Drive — até o backend ganhar as colunas.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Linha $lineIndex de $sourceFile ignorada: ${e.message}")
            null
        }
    }

    /**
     * `client_id` da captura, derivado do nome do arquivo.
     *
     * Um arquivo = uma captura. No modo "novo arquivo a cada auto-save" isso dá
     * uma captura por arquivo, cada uma com seu started_at — o que é o
     * comportamento correto, não um efeito colateral. E como é derivado, não há
     * id de captura a guardar nem a sincronizar: qualquer execução futura chega
     * no mesmo valor.
     */
    fun captureClientId(sourceFile: String): String = Uuid5.from("capture|$sourceFile")

    /**
     * Instante de início da captura, extraído do nome do arquivo
     * (`{prefixo}_{yyyyMMdd_HHmmss}.csv`, ver CsvExporter.startSession).
     * Sem isso o backend não teria `started_at` — que é NOT NULL.
     *
     * O carimbo no nome é gravado em HORA LOCAL do aparelho e sem fuso, então é
     * interpretado no fuso do próprio aparelho — que é onde ele foi gerado — e
     * convertido pra UTC na saída. É o melhor que o nome permite; o instante
     * exato de cada leitura não depende disto (vem da coluna Timestamp, que é
     * epoch e não tem ambiguidade).
     */
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun startedAtFromFileName(sourceFile: String): String? {
        // "winnix_20260804_120000.csv" -> "20260804_120000": o carimbo são os
        // DOIS últimos trechos separados por "_", porque o próprio carimbo tem um.
        val semExtensao = sourceFile.substringBeforeLast('.')
        val partes = semExtensao.split('_')
        if (partes.size < 2) return null
        val carimbo = partes.takeLast(2).joinToString("_")
        return try {
            ISO_UTC.format(FILE_STAMP.parse(carimbo) ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    // JSONObject.put(String, null) grava a string "null"; ausente é o correto —
    // o backend já trata campo ausente como null.
    private fun JSONObject.putOrNull(key: String, value: Any?) {
        if (value != null) put(key, value)
    }
}
