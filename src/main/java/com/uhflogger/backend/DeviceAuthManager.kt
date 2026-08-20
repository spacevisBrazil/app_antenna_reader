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
        if (token.isEmpty()) return null

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
     * só morre por 30 dias de ociosidade — e o worker roda a cada 15 min.
     */
    @Synchronized
    fun refresh(context: Context): String? {
        if (!BackendSettings.canRefresh(context)) {
            Log.w(TAG, "Sem refresh token — precisa reativar pela chave")
            return null
        }

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

        val json = response.json()
        val accessToken = json?.optString("access_token").orEmpty()
        if (json == null || !response.isSuccess || accessToken.isEmpty()) {
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
