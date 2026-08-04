package com.uhflogger.backend

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP do backend SpaceVis.
 *
 * `HttpURLConnection` puro de propósito: o app não tem cliente HTTP próprio (o
 * Google Drive traz o dele, acoplado ao SDK do Google), e este módulo faz cinco
 * chamadas JSON simples. Trazer Retrofit/OkHttp só pra isso adicionaria peso e
 * risco de conflito de versão num projeto que hoje resolve dependência por
 * catálogo externo. O que existe no SDK dá conta.
 */
object BackendApi {

    private const val TAG = "BackendApi"
    private const val CONNECT_TIMEOUT_MS = 15_000
    // Generoso: um lote de 500 leituras sobe por rede de fazenda, que oscila.
    private const val READ_TIMEOUT_MS    = 60_000

    /** Resposta crua — quem chama decide o que fazer com cada status. */
    data class Response(val status: Int, val body: String) {
        val isSuccess: Boolean get() = status in 200..299
        /** Sessão inválida/expirada: quem chama tenta renovar e repete. */
        val isUnauthorized: Boolean get() = status == 401
        /** Chave de device vencida ou revogada (deviceLicenseInactive). */
        val isForbidden: Boolean get() = status == 403

        fun json(): JSONObject? = try {
            if (body.isBlank()) null else JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Resposta não é JSON: ${body.take(200)}")
            null
        }
    }

    fun post(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): Response =
        request("POST", url, body, headers)

    // Não há PATCH aqui de propósito: o HttpURLConnection do Android recusa o
    // verbo, e o Express do backend não tem `method-override` pra aceitar o
    // header de contorno. O módulo /antenna/v1 expõe o `close` como POST — a
    // correção ficou do lado que ainda não tinha consumidor, em vez de virar
    // workaround permanente aqui.

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
        request("GET", url, null, headers)

    private fun request(
        method : String,
        url    : String,
        body   : JSONObject?,
        headers: Map<String, String>,
    ): Response {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod  = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout    = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }

            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val status = conn.responseCode
            // errorStream nos status de erro: o corpo do erro carrega o `i18n`
            // que diferencia "chave inativa" de "captura não encontrada".
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (status !in 200..299) {
                Log.w(TAG, "$method $url → $status: ${text.take(300)}")
            }
            Response(status, text)
        } catch (e: IOException) {
            // Sem rede não é erro de programa — é o estado normal em campo. Quem
            // chama trata como "tenta de novo depois", e o WorkManager reagenda.
            Log.i(TAG, "$method $url falhou (rede): ${e.message}")
            Response(0, "")
        } catch (e: Exception) {
            Log.e(TAG, "$method $url falhou", e)
            Response(0, "")
        } finally {
            conn?.disconnect()
        }
    }
}
