package com.uhflogger.smartbox.sync

import android.content.Context
import android.util.Log
import com.uhflogger.backend.BackendApi
import com.uhflogger.backend.BackendSettings
import com.uhflogger.backend.DeviceAuthManager
import com.uhflogger.smartbox.data.PendingReading
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backend da smartbox (seção 9.2 do guia), com a credencial do APARELHO
 * (DeviceAuthManager). As rotas /api/antenna-fixed/v1 usam o mesmo guard de
 * licença e a mesma role (read_animal) que o UHF Logger já usa em /api/antenna/v1.
 *
 * Bloqueante (HttpURLConnection): chamar fora da main. Tokens nunca vão para log.
 */
object SmartboxApi {
    private const val TAG = "SmartboxApi"

    sealed class Result {
        object Ok : Result()
        data class Failed(val message: String, val status: Int) : Result()
    }

    /**
     * Cadastra o módulo na fazenda. 200/201 = criado, 409 = já existia: os
     * dois são sucesso. 401 renova uma vez e repete.
     *
     * Mesmo moduleId, nome e tipo que o ESP usa em Wi-Fi: a smartbox que muda
     * de Wi-Fi para BLE continua sendo o mesmo módulo, com as mesmas visitas.
     */
    fun registerModule(context: Context, farmId: Int, moduleId: String): Result {
        var token = DeviceAuthManager.validAccessToken(context)
            ?: return Result.Failed("Aparelho sem sessão no servidor. Ative pela chave nas configurações.", 401)
        val url = "${BackendSettings.getBaseUrl(context)}/api/antenna-fixed/v1/farms/$farmId/modules"
        val body = JSONObject()
            .put("moduleId", moduleId)
            .put("name", "Bebedouro $moduleId")
            .put("type", "water_trough")

        var response = BackendApi.post(url, body, DeviceAuthManager.authHeaders(token, farmId))
        if (response.status == 401) {
            token = DeviceAuthManager.refresh(context)
                ?: return Result.Failed("Sessão do aparelho recusada (401). Ative de novo pela chave.", 401)
            response = BackendApi.post(url, body, DeviceAuthManager.authHeaders(token, farmId))
        }
        if (response.isSuccess || response.status == 409) return Result.Ok
        Log.w(TAG, "cadastro do módulo $moduleId na fazenda $farmId recusado (${response.status})")
        return Result.Failed(describe(response), response.status)
    }

    /**
     * Implementação real do [SmartboxBackend]: POST com a credencial do
     * aparelho; 401 renova o token uma vez e repete.
     */
    class Backend(private val context: Context) : SmartboxBackend {

        private fun post(farmId: Long, path: String, body: JSONObject): BackendApi.Response {
            val farm = farmId.toInt()
            val url = "${BackendSettings.getBaseUrl(context)}/api/antenna-fixed/v1/farms/$farm$path"
            var token = DeviceAuthManager.validAccessToken(context)
                ?: return BackendApi.Response(401, "")
            var response = BackendApi.post(url, body, DeviceAuthManager.authHeaders(token, farm))
            if (response.status == 401) {
                token = DeviceAuthManager.refresh(context) ?: return response
                response = BackendApi.post(url, body, DeviceAuthManager.authHeaders(token, farm))
            }
            return response
        }

        override fun registerModule(farmId: Long, moduleId: String): HttpOutcome {
            val r = post(
                farmId, "/modules",
                JSONObject().put("moduleId", moduleId).put("name", "Bebedouro $moduleId").put("type", "water_trough"),
            )
            // 409 = já existia: é sucesso.
            return HttpOutcome(if (r.status == 409) 201 else r.status, describe(r))
        }

        override fun sendVisits(farmId: Long, moduleId: String, readings: List<PendingReading>): HttpOutcome {
            val arr = JSONArray()
            readings.forEach {
                arr.put(
                    JSONObject()
                        .put("epc_raw", it.epc)
                        .put("antenna_port", it.antenna)
                        .put("rssi_dbm", it.rssi)
                        .put("reading_timestamp", SmartboxUploader.toReadingTimestamp(it.ts)),
                )
            }
            val r = post(farmId, "/modules/$moduleId/visits/batch", JSONObject().put("readings", arr))
            val accepted = if (r.isSuccess) r.json()?.optInt("accepted", readings.size) ?: readings.size else 0
            return HttpOutcome(r.status, describe(r), accepted)
        }
    }

    /** "Sem conexão com o servidor" ou "HTTP N: <message|error do corpo>" (seção 9.3). */
    fun describe(response: BackendApi.Response): String {
        if (response.status == 0) return "Sem conexão com o servidor"
        val detail = response.json()?.let { it.optString("message").ifEmpty { it.optString("error") } }.orEmpty()
        return if (detail.isEmpty()) "HTTP ${response.status}" else "HTTP ${response.status}: ${detail.take(120)}"
    }
}
