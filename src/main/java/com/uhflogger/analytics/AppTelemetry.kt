package com.uhflogger.analytics

/**
 * "App Started": um evento por início de PROCESSO (Application.onCreate).
 *
 * Existe para dar visibilidade de frota: sem ele, um aparelho parado não manda
 * nada ao Mixpanel e não dá para saber se ele já está na versão nova nem se
 * está pronto para a Smartbox. As super properties (app_version, device_id…)
 * já vão junto; aqui ficam só os pré-requisitos do aparelho.
 *
 * O processo também sobe sem tela (WorkManager, boot, Service religado pelo
 * Android), então este evento conta inícios de processo, não aberturas da tela.
 */
object AppTelemetry {
    const val EVENT_APP_STARTED = "App Started"

    fun appStarted(
        smartboxKeyConfigured: Boolean,
        farmId: Int,
        backendEnabled: Boolean,
        smartboxAutoCollect: Boolean,
        btConnectGranted: Boolean,
        antennaType: String?,
        wasCapturing: Boolean,
    ): Map<String, Any?> = mapOf(
        "smartbox_key_configured" to smartboxKeyConfigured,
        "farm_selected" to (farmId > 0),
        "farm_id" to farmId.takeIf { it > 0 },
        "backend_enabled" to backendEnabled,
        "smartbox_auto_collect" to smartboxAutoCollect,
        "bt_connect_granted" to btConnectGranted,
        "antenna_type" to antennaType?.takeIf { it.isNotEmpty() },
        // true = o processo subiu com uma captura em andamento (morte do processo / restart).
        "was_capturing" to wasCapturing,
    )
}
