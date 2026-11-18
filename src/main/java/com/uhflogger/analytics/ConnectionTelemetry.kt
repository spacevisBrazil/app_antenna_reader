package com.uhflogger.analytics

/**
 * Montagem dos eventos de conexão com o leitor. É lógica pura, sem Android,
 * para ser testada na JVM e para o nome e o formato de cada propriedade ficarem
 * num lugar só. O envio de fato fica em [Analytics].
 *
 * Catálogo e significado de cada campo: docs/analytics.md.
 */
object ConnectionTelemetry {

    const val EVENT_OPENED         = "Reader Connection Opened"
    const val EVENT_FAILED         = "Reader Connection Failed"
    const val EVENT_DROPPED        = "Reader Connection Dropped"
    const val EVENT_RECONNECT_LOOP = "Reader Reconnect Loop"
    const val EVENT_UPLOAD_FAILED  = "Upload Failed"

    const val TRANSPORT_BT  = "bluetooth"
    const val TRANSPORT_USB = "usb"

    /** Passos em que a abertura pode parar, na ordem em que o código os percorre. */
    object Step {
        const val PERMISSION     = "permission"      // BLUETOOTH_CONNECT negada (Android 12+)
        const val ADAPTER        = "adapter"         // aparelho sem adaptador BT
        const val DEVICE_LOOKUP  = "device_lookup"   // leitor fora dos pareados / USB não enumerado
        const val USB_PERMISSION = "usb_permission"  // openDevice() devolveu null
        const val SOCKET_CONNECT = "socket_connect"  // BluetoothSocket.connect() falhou
        const val USB_OPEN       = "usb_open"        // open()/setParameters() da porta USB
        const val PROBE          = "probe"           // porta aberta, módulo não respondeu ao probe
        const val UNEXPECTED     = "unexpected"      // exceção fora dos passos acima
    }

    /** Por que uma conexão que estava aberta caiu. */
    object DropReason {
        const val READ_ERROR       = "read_error"        // leitura do socket/USB lançou (desplugou, BT caiu)
        const val WATCHDOG_SILENCE = "watchdog_silence"  // watchdog fechou a porta por silêncio
        const val CONFIG_ERROR     = "config_error"      // falhou configurando o Winnix logo após abrir
    }

    const val LOOP_RECONNECTED = "reconnected"
    const val LOOP_CANCELLED   = "cancelled"

    const val MAX_MESSAGE_LENGTH = 160

    fun transport(isBluetooth: Boolean): String = if (isBluetooth) TRANSPORT_BT else TRANSPORT_USB

    /** Mensagem cortada: o suficiente para diagnosticar, sem estourar a propriedade. */
    fun truncate(message: String?, max: Int = MAX_MESSAGE_LENGTH): String? {
        if (message == null) return null
        val oneLine = message.replace(Regex("\\s+"), " ").trim()
        if (oneLine.isEmpty()) return null
        return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
    }

    fun errorName(error: Throwable?): String? = error?.javaClass?.simpleName?.ifEmpty { null }

    fun opened(
        transport: String,
        durationMs: Long,
        attempt: Int,
        resuming: Boolean,
        deviceName: String?,
    ): Map<String, Any?> = mapOf(
        "transport" to transport,
        "duration_ms" to durationMs.coerceAtLeast(0),
        "attempt" to attempt,
        "resuming" to resuming,
        "device_name" to deviceName,
    )

    fun failed(
        transport: String,
        step: String,
        error: Throwable?,
        attempt: Int,
        durationMs: Long,
        btEnabled: Boolean?,
        message: String? = null,
    ): Map<String, Any?> = mapOf(
        "transport" to transport,
        "step" to step,
        "error_name" to errorName(error),
        "error_message" to truncate(message ?: error?.message),
        "attempt" to attempt,
        "duration_ms" to durationMs.coerceAtLeast(0),
        "bt_enabled" to btEnabled,
    )

    /**
     * Sem dica de quem fechou a porta, a queda é erro de leitura. A dica
     * (watchdog, configuração) vence porque nesses casos o IOException é efeito
     * do fechamento, não a causa.
     */
    fun dropReason(hint: String?): String = hint ?: DropReason.READ_ERROR

    fun dropped(
        transport: String,
        reason: String,
        error: Throwable?,
        openMs: Long?,
    ): Map<String, Any?> = mapOf(
        "transport" to transport,
        "reason" to reason,
        "error_name" to errorName(error),
        "error_message" to truncate(error?.message),
        "open_ms" to openMs?.coerceAtLeast(0),
    )

    fun reconnectLoop(
        transport: String,
        reconnected: Boolean,
        attempts: Int,
        totalMs: Long,
    ): Map<String, Any?> = mapOf(
        "transport" to transport,
        "outcome" to if (reconnected) LOOP_RECONNECTED else LOOP_CANCELLED,
        "attempts" to attempts,
        "total_ms" to totalMs.coerceAtLeast(0),
    )

    /** @param status HTTP status; 0 = sem conexão (convenção do BackendApi); null = exceção. */
    fun uploadFailed(step: String, status: Int?, error: Throwable?): Map<String, Any?> = mapOf(
        "step" to step,
        "status" to status,
        "error_name" to errorName(error),
        "error_message" to truncate(error?.message),
    )
}
