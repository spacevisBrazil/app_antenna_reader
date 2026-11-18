package com.uhflogger.smartbox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.uhflogger.smartbox.domain.ConfigForm
import com.uhflogger.smartbox.domain.ConfigForms
import com.uhflogger.smartbox.domain.TemplateForm
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Modelos salvos da tela Configurar (seção 12.4 do guia).
 *
 * O modelo guarda o formulário SEM chave de ativação (cada smartbox tem a sua)
 * e SEM a senha. A senha do Wi-Fi vai cifrada com uma chave do Android
 * Keystore (que não sai do aparelho), em `modelo_senha_<id>`.
 */
object ConfigTemplates {
    private const val TAG = "ConfigTemplates"
    private const val FILE = "smartbox_templates"
    private const val LIST_KEY = "list.v1"
    private fun passwordKey(id: String) = "modelo_senha_$id"

    data class Stored(val id: String, val name: String, val json: String, val savedAt: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Stored> {
        val raw = prefs(ctx).getString(LIST_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                val json = o.optString("json")
                if (id.isEmpty() || name.isEmpty() || json.isEmpty()) null else Stored(id, name, json, o.optLong("savedAt"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(ctx: Context, list: List<Stored>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("json", it.json).put("savedAt", it.savedAt)) }
        prefs(ctx).edit().putString(LIST_KEY, arr.toString()).apply()
    }

    fun findByName(list: List<Stored>, name: String): Stored? =
        list.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

    /**
     * Guarda (ou substitui, se o nome já existe sem diferenciar maiúscula).
     * [password] só deve vir se foi digitada nesta tela, em Wi-Fi não aberta.
     */
    fun save(ctx: Context, name: String, form: ConfigForm, password: String?): Stored {
        findByName(list(ctx), name)?.let { remove(ctx, it.id) }
        val tpl = Stored(
            id = UUID.randomUUID().toString().replace("-", ""),
            name = name.trim(),
            json = ConfigForms.templateToJson(ConfigForms.templateFromForm(form)),
            savedAt = System.currentTimeMillis(),
        )
        if (!password.isNullOrEmpty()) SecretStore.put(ctx, passwordKey(tpl.id), password)
        write(ctx, list(ctx).filter { it.id != tpl.id } + tpl)
        return tpl
    }

    fun remove(ctx: Context, id: String) {
        SecretStore.remove(ctx, passwordKey(id))
        write(ctx, list(ctx).filter { it.id != id })
    }

    /** Lança TemplateParseException se o modelo estiver estragado. */
    fun load(ctx: Context, tpl: Stored): Pair<TemplateForm, String?> =
        ConfigForms.parseTemplate(tpl.json) to SecretStore.get(ctx, passwordKey(tpl.id))

    /** AES-GCM com chave do Android Keystore. Falha = segue sem senha (nunca derruba a tela). */
    private object SecretStore {
        private const val ALIAS = "uhflogger_smartbox_templates"
        private const val FILE = "smartbox_secrets"

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            return gen.generateKey()
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun put(ctx: Context, name: String, value: String) {
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                val blob = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
                prefs(ctx).edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
            } catch (e: Exception) {
                Log.w(TAG, "não guardou a senha do modelo: ${e.javaClass.simpleName}")
            }
        }

        fun get(ctx: Context, name: String): String? = try {
            prefs(ctx).getString(name, null)?.let { b64 ->
                val blob = Base64.decode(b64, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
                String(c.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "não leu a senha do modelo: ${e.javaClass.simpleName}")
            null
        }

        fun remove(ctx: Context, name: String) = prefs(ctx).edit().remove(name).apply()
    }
}
