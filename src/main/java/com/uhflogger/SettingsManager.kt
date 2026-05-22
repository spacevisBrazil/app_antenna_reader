package com.uhflogger

import android.content.Context
import android.content.SharedPreferences

/**
 * Centraliza leitura e escrita de todas as preferências do app.
 * Compatível com API 26+.
 */
object SettingsManager {

    private const val PREFS_NAME = "uhf_logger_prefs"

    // Chaves
    const val KEY_AUTO_SAVE_TAGS     = "auto_save_tags"
    const val KEY_AUTO_SAVE_MINUTES  = "auto_save_minutes"
    const val KEY_LOCATION_MODE      = "location_mode"

    // Valores para location mode
    const val LOCATION_MODE_HYBRID   = "hybrid"   // GNSS + Rede
    const val LOCATION_MODE_GNSS     = "gnss"     // Somente GNSS

    // Valores padrão
    const val DEFAULT_AUTO_SAVE_TAGS    = 10_000
    const val DEFAULT_AUTO_SAVE_MINUTES = 10
    const val DEFAULT_LOCATION_MODE     = LOCATION_MODE_HYBRID

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAutoSaveTags(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_SAVE_TAGS, DEFAULT_AUTO_SAVE_TAGS)

    fun setAutoSaveTags(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_AUTO_SAVE_TAGS, value).apply()

    fun getAutoSaveMinutes(context: Context): Long =
        prefs(context).getInt(KEY_AUTO_SAVE_MINUTES, DEFAULT_AUTO_SAVE_MINUTES).toLong()

    fun setAutoSaveMinutes(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_AUTO_SAVE_MINUTES, value).apply()

    fun getLocationMode(context: Context): String =
        prefs(context).getString(KEY_LOCATION_MODE, DEFAULT_LOCATION_MODE) ?: DEFAULT_LOCATION_MODE

    fun setLocationMode(context: Context, value: String) =
        prefs(context).edit().putString(KEY_LOCATION_MODE, value).apply()
}