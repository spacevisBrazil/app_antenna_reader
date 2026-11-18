package com.uhflogger.smartbox.sync

import android.content.Context
import android.util.Log
import com.uhflogger.backend.BackendApi
import com.uhflogger.backend.BackendSettings
import com.uhflogger.backend.DeviceAuthManager
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

    /** "Sem conexão com o servidor" ou "HTTP N: <message|error do corpo>" (seção 9.3). */
    fun describe(response: BackendApi.Response): String {
        if (response.status == 0) return "Sem conexão com o servidor"
        val detail = response.json()?.let { it.optString("message").ifEmpty { it.optString("error") } }.orEmpty()
        return if (detail.isEmpty()) "HTTP ${response.status}" else "HTTP ${response.status}: ${detail.take(120)}"
    }
}
