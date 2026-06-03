package com.uhflogger

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "uhf_logger_prefs"

    // Keys
    const val KEY_AUTO_SAVE_TAGS     = "auto_save_tags"
    const val KEY_AUTO_SAVE_MINUTES  = "auto_save_minutes"
    const val KEY_LOCATION_MODE      = "location_mode"
    const val KEY_ANTENNA_TYPE       = "antenna_type"
    const val KEY_WINNIX_ANT_COUNT   = "winnix_ant_count"
    const val KEY_WINNIX_POWER_DBM   = "winnix_power_dbm"
    const val KEY_WINNIX_WORKING_MS  = "winnix_working_ms"

    // Location mode values
    const val LOCATION_MODE_HYBRID   = "hybrid"
    const val LOCATION_MODE_GNSS     = "gnss"

    // Antenna type values
    const val ANTENNA_TYPE_JIETONG   = "jietong"
    const val ANTENNA_TYPE_WINNIX    = "winnix"

    // Defaults
    const val DEFAULT_AUTO_SAVE_TAGS    = 10_000
    const val DEFAULT_AUTO_SAVE_MINUTES = 10
    const val DEFAULT_LOCATION_MODE     = LOCATION_MODE_HYBRID
    const val DEFAULT_ANTENNA_TYPE      = ANTENNA_TYPE_JIETONG
    const val DEFAULT_WINNIX_ANT_COUNT  = 1
    const val DEFAULT_WINNIX_POWER_DBM  = 30
    const val DEFAULT_WINNIX_WORKING_MS = 100

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

    fun getAntennaType(context: Context): String =
        prefs(context).getString(KEY_ANTENNA_TYPE, DEFAULT_ANTENNA_TYPE) ?: DEFAULT_ANTENNA_TYPE

    fun setAntennaType(context: Context, value: String) =
        prefs(context).edit().putString(KEY_ANTENNA_TYPE, value).apply()

    fun getWinnixAntCount(context: Context): Int =
        prefs(context).getInt(KEY_WINNIX_ANT_COUNT, DEFAULT_WINNIX_ANT_COUNT)

    fun setWinnixAntCount(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_WINNIX_ANT_COUNT, value).apply()

    fun getWinnixPowerDbm(context: Context): Int =
        prefs(context).getInt(KEY_WINNIX_POWER_DBM, DEFAULT_WINNIX_POWER_DBM)

    fun setWinnixPowerDbm(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_WINNIX_POWER_DBM, value).apply()

    fun getWinnixWorkingMs(context: Context): Int =
        prefs(context).getInt(KEY_WINNIX_WORKING_MS, DEFAULT_WINNIX_WORKING_MS)

    fun setWinnixWorkingMs(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_WINNIX_WORKING_MS, value).apply()
}