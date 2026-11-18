package com.uhflogger.smartbox

import android.content.Context
import com.uhflogger.smartbox.sync.UploadSummary

/** Preferências da smartbox (SharedPreferences próprio, fora das do leitor). */
object SmartboxPrefs {
    private const val FILE = "smartbox"
    private const val KEY_AUTO_ENABLED = "auto_collect_enabled"
    private const val KEY_LAST_UPLOAD_FARM = "last_upload_farm"
    private const val KEY_LAST_UPLOAD_TEXT = "last_upload_text"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Coleta automática: LIGADA por padrão (o operador não decide nada). Só o técnico desliga. */
    fun isAutoEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_ENABLED, true)

    fun setAutoEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_ENABLED, value).apply()

    fun lastUploadFarm(context: Context): Long = prefs(context).getLong(KEY_LAST_UPLOAD_FARM, 0)

    fun setLastUploadFarm(context: Context, farmId: Long) = prefs(context).edit().putLong(KEY_LAST_UPLOAD_FARM, farmId).apply()

    fun setLastUpload(context: Context, s: UploadSummary) {
        val text = s.stoppedBecause?.let { "Envio parou: $it" }
            ?: "${s.sentReadings} leitura(s) enviada(s)" + if (s.errors.isEmpty()) "" else " · ${s.errors.size} aviso(s)"
        prefs(context).edit()
            .putString(KEY_LAST_UPLOAD_TEXT, text)
            .putLong(KEY_LAST_UPLOAD_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastUploadText(context: Context): String? = prefs(context).getString(KEY_LAST_UPLOAD_TEXT, null)
}
