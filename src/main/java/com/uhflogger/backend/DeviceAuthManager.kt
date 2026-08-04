package com.uhflogger.backend

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Sessão da conta-device no backend (ADR 0002).
 *
 * O aparelho da antena não tem operador: ele é ativado UMA VEZ com uma chave
 * gerada pelo admin e, a partir daí, carrega um offline token da conta-device —
 * é assim que ele sabe em que fazenda está e por quem responde.
 *
 * Mesmo fluxo do app de campo (`POST /api/auth/device/activate`), inclusive o
 * shape da resposta.
 */
object DeviceAuthManager {

    private const val TAG = "DeviceAuthManager"

    // Renova um pouco antes de expirar: um token que vence no meio do upload de
    // um lote faria a leitura voltar como 401 e o lote inteiro ser refeito.
    private const val RENEW_MARGIN_MS = 5 * 60 * 1000L

    class AuthException(message: String) : Exception(message)

    /**
     * Troca a chave de ativação por uma sessão. Chamado uma vez, pela tela de
     * configuração — nunca de forma automática, porque a chave é de uso único.
     */
    fun activate(context: Context, code: String) {
        val baseUrl = BackendSettings.getBaseUrl(context)
        if (baseUrl.isEmpty()) throw AuthException("Configure o endereço do servidor primeiro")

        val response = BackendApi.post(
            "$baseUrl/api/auth/device/activate",
            JSONObject().put("code", code.trim()),
        )

        if (response.status == 0)   throw AuthException("Sem conexão com o servidor")
        if (response.status == 429) throw AuthException("Muitas tentativas. Aguarde alguns minutos.")
        if (response.status == 400 || response.status == 404) {
            throw AuthException("Chave de ativação inválida ou expirada")
        }
        if (!response.isSuccess)    throw AuthException("Falha na ativação (${response.status})")

        val json = response.json() ?: throw AuthException("Resposta inesperada do servidor")
        val accessToken = json.optString("access_token")
        if (accessToken.isEmpty()) throw AuthException("Servidor não devolveu token")

        BackendSettings.saveSession(
            context,
            accessToken      = accessToken,
            refreshToken     = json.optString("refresh_token"),
            expiresInSeconds = json.optLong("expires_in", 0L),
            deviceEmail      = json.optString("device_email").ifEmpty { null },
        )
        Log.i(TAG, "Dispositivo ativado")
    }

    /**
     * Token válido pra usar agora, renovando se estiver perto de vencer.
     * Devolve `null` quando não há sessão utilizável — quem chama para de
     * tentar e a tela mostra que precisa reativar.
     */
    fun validAccessToken(context: Context): String? {
        val token = BackendSettings.getAccessToken(context)
        if (token.isEmpty()) return null

        val expiresAt = BackendSettings.getExpiresAt(context)
        val stillValid = expiresAt == 0L || System.currentTimeMillis() < expiresAt - RENEW_MARGIN_MS
        if (stillValid) return token

        return refresh(context)
    }

    /**
     * Renova pelo refresh token. Só funciona com client_id/secret configurados —
     * sem eles, a sessão simplesmente dura o que durar e a tela pede a chave de
     * novo. Não hardcodamos credencial de client no APK.
     */
    @Synchronized
    fun refresh(context: Context): String? {
        if (!BackendSettings.canRefresh(context)) {
            Log.w(TAG, "Token expirado e sem client_id/secret configurados — precisa reativar")
            return null
        }

        val baseUrl = BackendSettings.getBaseUrl(context)
        val body = JSONObject()
            .put("grant_type", "refresh_token")
            .put("client_id", BackendSettings.getClientId(context))
            .put("client_secret", BackendSettings.getClientSecret(context))
            .put("refresh_token", BackendSettings.getRefreshToken(context))

        val response = BackendApi.post("$baseUrl/api/auth/token", body)

        // Falha de REDE não invalida a sessão: derrubar o token porque a fazenda
        // está sem sinal é exatamente como se perde o aparelho em campo.
        if (response.status == 0) {
            Log.i(TAG, "Renovação adiada — sem conexão")
            return null
        }

        val json = response.json()
        val accessToken = json?.optString("access_token").orEmpty()
        if (!response.isSuccess || accessToken.isEmpty()) {
            Log.w(TAG, "Refresh recusado (${response.status}) — sessão precisa ser reativada")
            BackendSettings.clearSession(context)
            return null
        }

        BackendSettings.saveSession(
            context,
            accessToken      = accessToken,
            refreshToken     = json.optString("refresh_token").ifEmpty { BackendSettings.getRefreshToken(context) },
            expiresInSeconds = json.optLong("expires_in", 0L),
        )
        Log.i(TAG, "Token renovado")
        return accessToken
    }

    /** Cabeçalhos de toda chamada autenticada do módulo. */
    fun authHeaders(context: Context, token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "farmid" to BackendSettings.getFarmId(context).toString(),
    )
}
