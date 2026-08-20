package com.uhflogger

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "uhf_logger_prefs"

    // Keys
    const val KEY_AUTO_SAVE_TAGS          = "auto_save_tags"
    const val KEY_AUTO_SAVE_MINUTES       = "auto_save_minutes"
    const val KEY_LOCATION_MODE           = "location_mode"
    const val KEY_ANTENNA_TYPE            = "antenna_type"
    const val KEY_WINNIX_ANT_COUNT        = "winnix_ant_count"
    const val KEY_WINNIX_POWER_DBM        = "winnix_power_dbm"
    const val KEY_WINNIX_WORKING_MS       = "winnix_working_ms"
    const val KEY_WINNIX_INVENTORY_MODE   = "winnix_inventory_mode"
    // Estado de captura persistido — usado para retomar (ou não) a leitura
    // sozinho depois que o processo é morto (SIGKILL de backup, OOM-kill, etc.)
    // e o Android recria o Service. Sem isso, o app não tem como saber se
    // estava lendo ou parado quando voltar.
    const val KEY_WAS_CAPTURING           = "was_capturing"
    const val KEY_LAST_DEVICE_NAME        = "last_device_name"

    // Filtro de tags. A camada 3 (persistência SIGKILL-safe) não tem chave
    // própria — é automática sempre que a camada 2 está ativa, ver
    // TagFilterEngine (não faz sentido o usuário desligar só a proteção
    // contra perda de dados por crash, mantendo a consolidação ligada).
    const val KEY_FILTER_ENABLED          = "filter_enabled"
    const val KEY_FILTER_L1_ENABLED       = "filter_l1_enabled"
    const val KEY_FILTER_L1_PATTERNS      = "filter_l1_patterns"
    const val KEY_FILTER_L2_ENABLED       = "filter_l2_enabled"
    const val KEY_FILTER_L2_WINDOW_MIN    = "filter_l2_window_min"
    const val KEY_FILTER_L2_SWEEP_MIN     = "filter_l2_sweep_min"

    // Location mode values
    const val LOCATION_MODE_HYBRID        = "hybrid"
    const val LOCATION_MODE_GNSS          = "gnss"

    // Antenna type values
    const val ANTENNA_TYPE_JIETONG        = "jietong"
    const val ANTENNA_TYPE_WINNIX         = "winnix"

    // Winnix inventory mode values
    const val WINNIX_INV_MODE_MULTITAG    = 1   // Multi-tag — S1, high accuracy
    const val WINNIX_INV_MODE_FAST        = 2   // Fast read — S0, max read rate (moving tags)
    const val WINNIX_INV_MODE_ADAPTIVE    = 5   // Adaptive  — S0+S1, recommended default

    // Defaults
    const val DEFAULT_AUTO_SAVE_TAGS         = 5_000
    const val DEFAULT_AUTO_SAVE_MINUTES      = 10
    // Único modo de auto-save suportado: sempre abre um novo arquivo. Mantido
    // como constante (não mais uma preferência configurável) porque o valor é
    // enviado ao backend em DeviceIdentity.captureConfig().
    const val AUTO_SAVE_MODE_NEW_FILE        = 1
    const val DEFAULT_LOCATION_MODE          = LOCATION_MODE_HYBRID
    const val DEFAULT_ANTENNA_TYPE           = ANTENNA_TYPE_WINNIX
    const val DEFAULT_WINNIX_ANT_COUNT       = 2
    const val DEFAULT_WINNIX_POWER_DBM       = 30
    const val DEFAULT_WINNIX_WORKING_MS      = 100
    const val DEFAULT_WINNIX_INVENTORY_MODE  = WINNIX_INV_MODE_ADAPTIVE

    // Filtro — ligado por padrão, com as camadas 1 e 2 ativas e a camada 1 já
    // com os padrões de EPC conhecidos das antenas em uso.
    const val DEFAULT_FILTER_ENABLED         = true
    const val DEFAULT_FILTER_L1_ENABLED      = true
    const val DEFAULT_FILTER_L1_PATTERNS     = "00001000000XXXXX,0000000000000000000XXXXX,00760000000XXXXX"
    const val DEFAULT_FILTER_L2_ENABLED      = true
    const val DEFAULT_FILTER_L2_WINDOW_MIN   = 30
    const val DEFAULT_FILTER_L2_SWEEP_MIN    = 5

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

    fun getWinnixInventoryMode(context: Context): Int =
        prefs(context).getInt(KEY_WINNIX_INVENTORY_MODE, DEFAULT_WINNIX_INVENTORY_MODE)

    fun setWinnixInventoryMode(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_WINNIX_INVENTORY_MODE, value).apply()

    fun winnixInventoryModeLabel(mode: Int): String = when (mode) {
        WINNIX_INV_MODE_MULTITAG -> "Multi-tag"
        WINNIX_INV_MODE_FAST     -> "Fast read"
        WINNIX_INV_MODE_ADAPTIVE -> "Adaptive"
        else                     -> "Desconhecido"
    }

    // -------------------------------------------------------------------
    // Estado de captura — grava com commit() (síncrono) de propósito: essa
    // gravação acontece raramente (só ao iniciar/parar), então o custo é
    // desprezível, e queremos garantia de que já está em disco antes de
    // continuar, já que um SIGKILL pode chegar a qualquer momento depois.
    // apply() (assíncrono) correria o risco de perder essa escrita.
    // -------------------------------------------------------------------
    fun setCaptureState(context: Context, capturing: Boolean, deviceName: String? = null) {
        prefs(context).edit().apply {
            putBoolean(KEY_WAS_CAPTURING, capturing)
            if (deviceName != null) putString(KEY_LAST_DEVICE_NAME, deviceName)
        }.commit()
    }

    fun wasCapturing(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAS_CAPTURING, false)

    fun getLastDeviceName(context: Context): String? =
        prefs(context).getString(KEY_LAST_DEVICE_NAME, null)

    // -------------------------------------------------------------------
    // Filtro de 3 camadas
    // -------------------------------------------------------------------
    fun isFilterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILTER_ENABLED, DEFAULT_FILTER_ENABLED)

    fun setFilterEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_FILTER_ENABLED, value).apply()

    fun isFilterL1Enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILTER_L1_ENABLED, DEFAULT_FILTER_L1_ENABLED)

    fun setFilterL1Enabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_FILTER_L1_ENABLED, value).apply()

    // Padrões separados por vírgula, ex.: "0000100000000XXX,0076000000004XXX".
    // Posições com 'X' aceitam qualquer dígito hex; as demais precisam bater
    // exatamente. Lista vazia = camada 1 não filtra nada (evita o risco de
    // descartar tudo silenciosamente por falta de configuração).
    fun getFilterL1Patterns(context: Context): String =
        prefs(context).getString(KEY_FILTER_L1_PATTERNS, DEFAULT_FILTER_L1_PATTERNS) ?: DEFAULT_FILTER_L1_PATTERNS

    fun setFilterL1Patterns(context: Context, value: String) =
        prefs(context).edit().putString(KEY_FILTER_L1_PATTERNS, value).apply()

    fun isFilterL2Enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FILTER_L2_ENABLED, DEFAULT_FILTER_L2_ENABLED)

    fun setFilterL2Enabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_FILTER_L2_ENABLED, value).apply()

    fun getFilterL2WindowMin(context: Context): Int =
        prefs(context).getInt(KEY_FILTER_L2_WINDOW_MIN, DEFAULT_FILTER_L2_WINDOW_MIN)

    fun setFilterL2WindowMin(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_FILTER_L2_WINDOW_MIN, value).apply()

    // Intervalo do sweep periódico que expira entradas da camada 2. Precisa
    // ser MENOR que a janela (validado em SettingsActivity) — senão entradas
    // expiradas poderiam nunca ser detectadas antes da próxima rotação.
    fun getFilterL2SweepMin(context: Context): Int =
        prefs(context).getInt(KEY_FILTER_L2_SWEEP_MIN, DEFAULT_FILTER_L2_SWEEP_MIN)

    fun setFilterL2SweepMin(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_FILTER_L2_SWEEP_MIN, value).apply()
}