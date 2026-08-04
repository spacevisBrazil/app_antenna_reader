package com.uhflogger.backend

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuração e credenciais do envio ao backend SpaceVis.
 *
 * O Google Drive continua funcionando exatamente como antes e de forma
 * independente: este destino é ADICIONAL, não substituto. Com o envio
 * desligado (padrão), nada aqui é lido e o app se comporta como sempre.
 *
 * SEM SEGREDO NO CÓDIGO
 * `client_id`/`client_secret` do Keycloak são configurados em runtime, não
 * compilados no APK. Ficam vazios por padrão — sem eles o app ainda ativa por
 * chave e envia normalmente; só não consegue renovar o token sozinho quando
 * ele expira (aí a tela pede a chave de novo).
 */
object BackendSettings {

    private const val PREFS_NAME = "backend_prefs"

    private const val KEY_ENABLED       = "enabled"
    private const val KEY_BASE_URL      = "base_url"
    private const val KEY_FARM_ID       = "farm_id"
    private const val KEY_ACCESS_TOKEN  = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT    = "expires_at"
    private const val KEY_CLIENT_ID     = "kc_client_id"
    private const val KEY_CLIENT_SECRET = "kc_client_secret"
    private const val KEY_DEVICE_EMAIL  = "device_email"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Destino ───────────────────────────────────────────────────────────────

    /**
     * PRONTO PRA ENVIAR AGORA: além de configurado, tem sessão. É o que o
     * worker consulta antes de tentar subir qualquer coisa.
     */
    fun isEnabled(context: Context): Boolean =
        isConfigured(context) && getAccessToken(context).isNotEmpty()

    /**
     * A INTENÇÃO do operador: este aparelho deve mandar pro servidor.
     * Independe de haver sessão válida no momento.
     *
     * A distinção existe por causa da retenção do arquivo local. Se a decisão
     * de apagar o CSV olhasse `isEnabled`, bastaria o token expirar (ou o
     * refresh ser recusado, que limpa a sessão) pra o app concluir "não uso
     * backend" e deixar o Drive apagar leituras que nunca chegaram ao
     * servidor — em campo, sem ninguém ver. Enquanto o envio estiver LIGADO,
     * o arquivo espera, mesmo que a sessão precise ser reativada.
     */
    fun isConfigured(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false) &&
                getBaseUrl(context).isNotEmpty() &&
                getFarmId(context) > 0

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()

    /** Sempre sem barra final — a montagem dos caminhos assume isso. */
    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, "").orEmpty().trim().trimEnd('/')

    fun setBaseUrl(context: Context, value: String) =
        prefs(context).edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    fun getFarmId(context: Context): Int =
        prefs(context).getInt(KEY_FARM_ID, 0)

    fun setFarmId(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_FARM_ID, value).apply()

    // ── Sessão (ativação por chave — ADR 0002) ────────────────────────────────

    fun getAccessToken(context: Context): String =
        prefs(context).getString(KEY_ACCESS_TOKEN, "").orEmpty()

    fun getRefreshToken(context: Context): String =
        prefs(context).getString(KEY_REFRESH_TOKEN, "").orEmpty()

    fun getExpiresAt(context: Context): Long =
        prefs(context).getLong(KEY_EXPIRES_AT, 0L)

    fun getDeviceEmail(context: Context): String =
        prefs(context).getString(KEY_DEVICE_EMAIL, "").orEmpty()

    /**
     * Grava a sessão com `commit()` (síncrono) de propósito: o mesmo motivo do
     * `SettingsManager.setCaptureState` — acontece raramente e um SIGKILL logo
     * depois faria o aparelho perder o token recém-obtido e pedir a chave de
     * novo, que é justamente o que não pode acontecer em campo.
     */
    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
        deviceEmail: String? = null,
    ) {
        prefs(context).edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            if (deviceEmail != null) putString(KEY_DEVICE_EMAIL, deviceEmail)
        }.commit()
    }

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_DEVICE_EMAIL)
            .commit()
    }

    // ── Keycloak (opcional, só pra renovar token) ─────────────────────────────

    fun getClientId(context: Context): String =
        prefs(context).getString(KEY_CLIENT_ID, "").orEmpty()

    fun setClientId(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CLIENT_ID, value.trim()).apply()

    fun getClientSecret(context: Context): String =
        prefs(context).getString(KEY_CLIENT_SECRET, "").orEmpty()

    fun setClientSecret(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CLIENT_SECRET, value.trim()).apply()

    fun canRefresh(context: Context): Boolean =
        getRefreshToken(context).isNotEmpty() &&
                getClientId(context).isNotEmpty() &&
                getClientSecret(context).isNotEmpty()
}
