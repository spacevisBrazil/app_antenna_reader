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
 * SEM SEGREDO NO APARELHO
 * Não há client_id/client_secret aqui, nem compilados nem digitados: quem fala
 * com o Keycloak é o backend, com as credenciais DELE. O aparelho carrega
 * apenas a chave de ativação (uso único) e, depois, os tokens da conta-device.
 */
object BackendSettings {

    private const val PREFS_NAME = "backend_prefs"

    /**
     * Servidor do AMBIENTE DESTE BUILD (flavor `hml` ou `prd`).
     *
     * Mesmo padrão do app de campo, onde o eas.json define EXPO_PUBLIC_API_URL
     * por perfil e não existe campo de servidor em tela nenhuma. O endereço tem
     * 49 caracteres e é sempre o mesmo por ambiente: pedir que alguém o digite
     * num celular, em campo, é criar um erro que não precisa existir — e um
     * caractere trocado falha com "sem conexão", que não diz onde foi o engano.
     *
     * Pior: com o endereço editável, o mesmo aparelho pode acabar mandando dado
     * de produção pra homologação sem ninguém perceber. Sendo propriedade do
     * artefato, o APK de homologação não tem como falar com produção.
     */
    val DEFAULT_BASE_URL: String get() = com.uhflogger.BuildConfig.API_BASE_URL

    private const val KEY_ENABLED       = "enabled"
    private const val KEY_BASE_URL      = "base_url"
    private const val KEY_FARM_ID       = "farm_id"
    private const val KEY_ACCESS_TOKEN  = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT    = "expires_at"
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

    /**
     * Sempre sem barra final — a montagem dos caminhos assume isso.
     * Sem nada salvo, devolve o servidor DESTE build. Só fica diferente disso se
     * um link de ativação trouxer `&servidor=` explicitamente (ver
     * BackendSettingsActivity), que é a saída para um caso excepcional.
     */
    fun getBaseUrl(context: Context): String {
        val salvo = prefs(context).getString(KEY_BASE_URL, "").orEmpty().trim().trimEnd('/')
        return salvo.ifEmpty { DEFAULT_BASE_URL }
    }

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

    /**
     * Basta o refresh token. O `POST /api/auth/token` do backend monta o
     * client_id/client_secret a partir do PRÓPRIO ambiente dele (KC_CLIENT_ID /
     * KC_CLIENT_SECRET) e ignora o que vier no corpo — exigir aqui credenciais
     * que o servidor nem lê fazia o app desistir de renovar sem sequer tentar.
     *
     * O efeito era invisível e terminal: o access token do realm dura 5h, os
     * campos de client são opcionais e ficam no fim da tela, ninguém em campo os
     * preenche — então 5h depois da ativação o aparelho parava de enviar PARA
     * SEMPRE, sem erro em log nenhum, até alguém reativar com uma chave nova.
     */
    fun canRefresh(context: Context): Boolean =
        getRefreshToken(context).isNotEmpty()
}
