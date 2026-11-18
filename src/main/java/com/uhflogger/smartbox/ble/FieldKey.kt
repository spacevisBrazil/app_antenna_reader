package com.uhflogger.smartbox.ble

import com.uhflogger.BuildConfig
import com.uhflogger.smartbox.ble.SmartboxProtocol as P

/**
 * Chave `K` de campo da smartbox (seção 4.1 do guia).
 *
 * 32 bytes, a mesma em todos os firmwares de produção e em todos os apps. Vai
 * DENTRO do APK de qualquer jeito (o app precisa dela em claro para o HMAC),
 * então o cuidado é não espalhá-la: ela NUNCA entra no Git, em log, em issue ou
 * em chat. Chega no build por fora (ver build.gradle e docs/smartbox.md):
 *
 *   - CI: secret `SMARTBOX_BLE_KEY` do GitHub Actions → env → BuildConfig;
 *   - local: `smartbox.bleKey=<hex>` no local.properties (ignorado pelo git).
 *
 * Sem a chave, a funcionalidade Smartbox fica desligada com aviso
 * ([NOT_CONFIGURED_MESSAGE]); o resto do app não é afetado. A chave de TESTE de
 * bancada (00 01 … 1F) é recusada: o app de produção não deve tentar a de teste.
 */
object FieldKey {

    const val NOT_CONFIGURED_MESSAGE = "Chave da smartbox não configurada neste build. Avise o suporte."

    private fun isBenchTestKey(key: ByteArray): Boolean = key.withIndex().all { (i, b) -> b.toInt() == i }

    /** Valida e decodifica a chave. Exposta para teste; o app usa [get]. */
    fun parse(raw: String?): ByteArray? {
        if (raw.isNullOrBlank()) return null
        val key = try {
            Bytes.fromHex(raw.trim())
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (key.size != P.FIELD_KEY_LENGTH || isBenchTestKey(key)) return null
        return key
    }

    private val cached: ByteArray? by lazy { parse(BuildConfig.SMARTBOX_BLE_KEY) }

    /** A chave de campo, ou null se o build não a recebeu (ou recebeu inválida). Cópia: quem usa não altera a original. */
    fun get(): ByteArray? = cached?.copyOf()

    val isConfigured: Boolean get() = cached != null
}
