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
     * Resultado da ativação. A fazenda vem do servidor quando ele consegue
     * resolvê-la sozinho — ver resolveFarm().
     */
    data class Farm(val id: Int, val name: String)

    /**
     * `farmId` preenchido = resolvido sozinho (o caso normal: a chave pertence a
     * UMA fazenda). `options` com mais de uma = a conta tem acesso a várias e
     * quem escolhe é a pessoa — mesma regra do app de campo, que só auto-seleciona
     * quando `farms.length === 1`.
     */
    data class ActivationResult(
        val farmId  : Int?,
        val farmName: String?,
        val options : List<Farm> = emptyList(),
    )

    /**
     * Troca a chave de ativação por uma sessão. Chamado uma vez, pela tela de
     * configuração — nunca de forma automática, porque a chave é de uso único.
     */
    fun activate(context: Context, code: String): ActivationResult {
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
        // Backend antigo não manda: o aparelho funciona igual, só sem a volta
        // automática quando a sessão offline morrer.
        val deviceSecret = json.optString("device_secret")
        if (deviceSecret.isNotEmpty()) BackendSettings.saveDeviceSecret(context, deviceSecret)
        Log.i(TAG, "Dispositivo ativado")

        return resolveFarm(context, accessToken)
    }

    /**
     * Descobre a fazenda do aparelho a partir da própria sessão.
     *
     * A chave de ativação JÁ NASCE atrelada a uma fazenda — pedir o número dela
     * de novo ao operador é pedir que ele confirme algo que o servidor já sabe.
     * E o erro é silencioso: digitar o número errado não impede a ativação (o
     * `/activate` nem olha pra fazenda), só faz todo envio posterior falhar lá
     * no `farmValidator`, longe da tela e sem mensagem que ajude.
     *
     * Best-effort de propósito: se a rede cair aqui, a ativação continua válida
     * e o campo segue editável na tela. Só decide sozinho quando o servidor
     * devolve UMA fazenda — com nenhuma ou várias, quem escolhe é o operador.
     */
    private fun resolveFarm(context: Context, token: String): ActivationResult {
        val lista = fetchFarms(context, token) ?: return ActivationResult(null, null)
        if (lista.isEmpty()) return ActivationResult(null, null)

        // Uma só: decide sozinho. Várias: quem escolhe é a pessoa — escolher por
        // ela seria mandar leitura pra fazenda errada sem ninguém notar.
        if (lista.size > 1) {
            Log.i(TAG, "Aparelho tem ${lista.size} fazendas — precisa escolher")
            return ActivationResult(null, null, lista)
        }

        val farm = lista.first()
        BackendSettings.setFarm(context, farm.id, farm.name)
        Log.i(TAG, "Fazenda resolvida pela chave: ${farm.id} (${farm.name})")
        return ActivationResult(farm.id, farm.name, lista)
    }

    /**
     * Fazendas do aparelho, direto do servidor, e já persistidas.
     *
     * Existe separado da ativação porque a lista MUDA depois dela: a conta-device
     * pode ganhar acesso a outra fazenda semanas depois, e um seletor que só
     * soubesse do que veio na ativação nunca mostraria a nova. É chamado ao abrir
     * a tela de configuração, não só uma vez na vida do aparelho.
     *
     * @return `null` quando não deu pra falar com o servidor (a lista em disco
     *         continua valendo); lista vazia quando o servidor respondeu que não
     *         há fazenda nenhuma.
     */
    fun fetchFarms(context: Context, token: String): List<Farm>? {
        val baseUrl = BackendSettings.getBaseUrl(context)
        val response = BackendApi.get(
            "$baseUrl/api/mobile/v1/session/bootstrap",
            mapOf("Authorization" to "Bearer $token"),
        )
        if (!response.isSuccess) {
            Log.w(TAG, "Não listou as fazendas (${response.status})")
            return null
        }

        val farms = response.json()?.optJSONArray("farms")
        if (farms == null || farms.length() == 0) {
            Log.i(TAG, "Nenhuma fazenda vinculada ao aparelho")
            return emptyList()
        }

        val lista = (0 until farms.length()).mapNotNull { i ->
            val o = farms.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("id", 0)
            if (id <= 0) null else Farm(id, o.optString("name").ifEmpty { "Fazenda $id" })
        }
        BackendSettings.setFarms(context, lista.map { it.id to it.name })

        // Renomear a fazenda no cadastro não pode deixar a tela mentindo com o
        // nome antigo. O id é a âncora; o nome é o que se corrige.
        val atual = BackendSettings.getFarmId(context)
        lista.firstOrNull { it.id == atual }?.let { BackendSettings.setFarm(context, it.id, it.name) }

        return lista
    }

    /**
     * Token válido pra usar agora, renovando se estiver perto de vencer.
     * Devolve `null` quando não há sessão utilizável — quem chama para de
     * tentar e a tela mostra que precisa reativar.
     */
    fun validAccessToken(context: Context): String? {
        val token = BackendSettings.getAccessToken(context)
        // Sem token mas com o segredo do aparelho: dá pra recuperar sozinho.
        if (token.isEmpty()) return if (BackendSettings.hasDeviceSecret(context)) refresh(context) else null

        val expiresAt = BackendSettings.getExpiresAt(context)
        val stillValid = expiresAt == 0L || System.currentTimeMillis() < expiresAt - RENEW_MARGIN_MS
        if (stillValid) return token

        return refresh(context)
    }

    /**
     * Renova pelo refresh token — só o refresh token, que é tudo que o
     * `/api/auth/token` pede no grant `refresh_token`. O client_id/secret são do
     * ambiente do BACKEND; mandá-los daqui era ruído, e EXIGI-LOS era o que
     * matava o envio 5h depois de cada ativação (ver BackendSettings.canRefresh).
     *
     * Continua valendo a regra de não hardcodar credencial de client no APK: o
     * segredo nunca esteve no aparelho, e agora não precisa estar mesmo.
     *
     * O refresh token é offline (`scope=offline_access` na ativação), então ele
     * só morre por 30 dias de ociosidade — e o worker roda a cada 15 min. Mas o
     * bebedouro fica DESLIGADO na entressafra: aí o refresh é recusado e quem
     * resolve é o segredo do aparelho (ver reauth), sem chave nova.
     */
    @Synchronized
    fun refresh(context: Context): String? {
        if (BackendSettings.canRefresh(context)) {
            val baseUrl = BackendSettings.getBaseUrl(context)
            val body = JSONObject()
                .put("grant_type", "refresh_token")
                .put("refresh_token", BackendSettings.getRefreshToken(context))

            val response = BackendApi.post("$baseUrl/api/auth/token", body)

            // Falha de REDE não invalida a sessão: derrubar o token porque a fazenda
            // está sem sinal é exatamente como se perde o aparelho em campo.
            if (response.status == 0) {
                Log.i(TAG, "Renovação adiada — sem conexão")
                return null
            }

            if (response.isSuccess) {
                val saved = saveTokens(context, response)
                if (saved != null) {
                    Log.i(TAG, "Token renovado")
                    ensureDeviceSecret(context, saved)
                    return saved
                }
            }

            // 5xx (deploy, backend fora), 429 e afins NÃO são recusa da sessão.
            // Antes qualquer resposta que não fosse 2xx apagava a sessão — uma
            // oscilação do servidor bastava pro bebedouro perder o acesso pra
            // sempre e o cliente precisar pedir chave nova.
            if (!isDefinitiveRejection(response.status)) {
                Log.w(TAG, "Renovação adiada — servidor respondeu ${response.status}")
                return null
            }
            Log.w(TAG, "Refresh recusado (${response.status}) — reautenticando o aparelho")
        }

        return reauth(context)
    }

    /**
     * O refresh foi RECUSADO (sessão offline morreu por ociosidade) ou não há
     * refresh token: troca o segredo do aparelho, recebido na ativação, por uma
     * sessão nova — sem chave nova, sem ninguém ligar pra gente.
     *
     * Só apaga a sessão em corte DEFINITIVO: aparelho revogado (403) ou não
     * reconhecido (400/401 — excluído). Rede ou servidor fora: mantém e tenta
     * de novo na próxima passada do worker.
     */
    private fun reauth(context: Context): String? {
        val secret = BackendSettings.getDeviceSecret(context)
        if (secret.isEmpty()) {
            Log.w(TAG, "Sem segredo do aparelho — precisa reativar pela chave")
            BackendSettings.clearSession(context)
            return null
        }

        val baseUrl = BackendSettings.getBaseUrl(context)
        val response = BackendApi.post(
            "$baseUrl/api/auth/device/reauth",
            JSONObject().put("device_secret", secret),
        )

        if (response.isSuccess) {
            val saved = saveTokens(context, response)
            if (saved != null) {
                Log.i(TAG, "Aparelho reautenticado sem chave nova")
                return saved
            }
        }

        val cortado = response.status == 403 || isDefinitiveRejection(response.status)
        if (!cortado) {
            Log.w(TAG, "Reautenticação adiada (${response.status})")
            return null
        }

        Log.w(TAG, "Aparelho revogado ou não reconhecido (${response.status}) — precisa de chave nova")
        BackendSettings.clearSession(context)
        return null
    }

    /**
     * Aparelho ativado ANTES do segredo existir pega o seu, uma vez, pela
     * própria sessão — é o que dá ao bebedouro já instalado o caminho de volta
     * sem reativar. Best-effort: 409 (já emitido), backend antigo ou sem rede
     * ficam pra próxima renovação.
     */
    private fun ensureDeviceSecret(context: Context, token: String) {
        if (BackendSettings.hasDeviceSecret(context)) return
        val baseUrl = BackendSettings.getBaseUrl(context)
        val response = BackendApi.post(
            "$baseUrl/api/mobile/v1/session/device-secret",
            JSONObject(),
            mapOf("Authorization" to "Bearer $token"),
        )
        if (!response.isSuccess) return
        val secret = response.json()?.optString("device_secret").orEmpty()
        if (secret.isNotEmpty()) {
            BackendSettings.saveDeviceSecret(context, secret)
            Log.i(TAG, "Segredo do aparelho recebido")
        }
    }

    /** Grava a sessão de uma resposta de token. `null` se ela veio sem token. */
    private fun saveTokens(context: Context, response: BackendApi.Response): String? {
        val json = response.json() ?: return null
        val accessToken = json.optString("access_token")
        if (accessToken.isEmpty()) return null
        val newRefresh = json.optString("refresh_token")
        BackendSettings.saveSession(
            context,
            accessToken      = accessToken,
            refreshToken     = if (newRefresh.isNotEmpty()) newRefresh else BackendSettings.getRefreshToken(context),
            expiresInSeconds = json.optLong("expires_in", 0L),
        )
        return accessToken
    }

    /**
     * O servidor disse que ESTA sessão não vale mais (invalid_grant: 400 do
     * Keycloak, 401 do proxy). Todo o resto é transitório.
     */
    private fun isDefinitiveRejection(status: Int): Boolean = status == 400 || status == 401

    /**
     * Há como recuperar a sessão sem chave nova (refresh token ou segredo do
     * aparelho). Separa "sem sinal agora" de "precisa reativar".
     */
    fun hasRecoverableSession(context: Context): Boolean =
        BackendSettings.canRefresh(context) || BackendSettings.hasDeviceSecret(context)

    /**
     * Cabeçalhos de toda chamada autenticada do módulo.
     *
     * A fazenda vem por PARÂMETRO, não do estado global: quem envia um arquivo
     * usa a fazenda em que aquele arquivo foi capturado (BackendUploadEntry.farmId),
     * e não a que estiver selecionada no momento do envio. Ler o global aqui
     * reabriria, num único ponto escondido, o desvio que o farmId por arquivo
     * existe pra impedir.
     */
    fun authHeaders(token: String, farmId: Int): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "farmid" to farmId.toString(),
    )
}
