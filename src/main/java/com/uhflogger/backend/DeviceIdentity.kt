package com.uhflogger.backend

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.uhflogger.SettingsManager
import org.json.JSONObject

/**
 * Quem é este aparelho e com que configuração ele leu.
 *
 * A CONFIGURAÇÃO É PARTE DO DADO: potência, número de antenas, tempo por antena
 * e modo de inventário mudam o significado do RSSI. Sem mandá-la junto, o
 * servidor guarda um sinal que não é comparável com o da captura seguinte —
 * por isso ela viaja na abertura da captura e fica congelada lá.
 */
object DeviceIdentity {

    /**
     * Identidade física do coletor. ANDROID_ID é estável por aparelho+app e não
     * exige permissão nenhuma (ao contrário de IMEI/MAC, que no Android moderno
     * são inacessíveis ou randomizados).
     */
    @SuppressLint("HardwareIds")
    fun readerId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            .ifEmpty { Build.MODEL.orEmpty() }
            .take(120)

    /**
     * Nome legível — o mesmo que o Drive usa pra nomear a pasta do aparelho, pra
     * que os dois destinos falem do mesmo "aparelho" na hora de conferir.
     */
    fun readerName(context: Context): String =
        com.uhflogger.drive.DriveHelper.getDeviceName(context).take(120)

    /** Modelo da antena configurada agora — ver ressalva em BackendUploadWorker. */
    fun antennaModel(context: Context): String? =
        SettingsManager.getAntennaType(context).takeIf { it.isNotEmpty() }

    fun captureConfig(context: Context): JSONObject = JSONObject().apply {
        val antennaType = SettingsManager.getAntennaType(context)
        put("antenna_type", antennaType)
        put("location_mode", SettingsManager.getLocationMode(context))
        // Não é mais configurável pelo usuário — único modo suportado.
        put("auto_save_mode", SettingsManager.AUTO_SAVE_MODE_NEW_FILE)
        // Os parâmetros de rádio existem só no Winnix; no Jietong não há o que
        // congelar, e mandar valor de outra antena seria mentira no registro.
        if (antennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
            put("ant_count", SettingsManager.getWinnixAntCount(context))
            put("power_dbm", SettingsManager.getWinnixPowerDbm(context))
            put("working_ms", SettingsManager.getWinnixWorkingMs(context))
            put("inventory_mode", SettingsManager.getWinnixInventoryMode(context))
        }
        put("app_version", appVersion(context))
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }
}
