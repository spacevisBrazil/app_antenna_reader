package com.uhflogger.smartbox.ble

import com.uhflogger.analytics.ConnectionTelemetry

/**
 * Eventos da smartbox no Mixpanel, iguais aos do spacevis-mobile, mais
 * reader_state e collect_mode (regras do Igor). Montagem pura, testada; o
 * envio é pela fachada Analytics.
 */
object SmartboxTelemetry {
    const val EVENT_OPENED = "Smartbox Session Opened"
    const val EVENT_FAILED = "Smartbox Session Failed"
    const val EVENT_DROPPED = "Smartbox Session Dropped"
    const val EVENT_SCAN_FAILED = "Smartbox Scan Failed"
    const val EVENT_COLLECT_YIELDED = "Smartbox Collect Yielded"

    /** Para que a conexão foi aberta. */
    object Purpose {
        const val DOWNLOAD = "download"
        const val AUTO_COLLECT = "auto_collect"
        const val ASSIGN_FARM = "assign_farm"
        const val DIAGNOSE = "diagnose"
        const val REBOOT = "reboot"
        const val CONFIGURE = "configure"
    }

    /** Onde a abertura parou (`action` = já conectado, falhou no meio do trabalho). */
    object Step {
        const val PRECHECK = "precheck"
        const val CONNECT = "connect"
        const val MTU = "mtu"
        const val DISCOVER = "discover"
        const val NOTIFY = "notify"
        const val HANDSHAKE = "handshake"
        const val ACTION = "action"
    }

    /** Quem fez a coleta ceder o rádio. */
    object YieldTrigger {
        const val STOP_BUTTON = "stop_button"
        const val START_CAPTURE = "start_capture"
    }

    private val STATUS_IN_TEXT = Regex("status[\\s:=]*(\\d+)", RegexOption.IGNORE_CASE)

    /** Status GATT lido do texto do erro quando o campo numérico não veio. */
    fun gattStatusFromText(vararg texts: String?): Int? {
        for (t in texts) {
            if (t.isNullOrEmpty()) continue
            STATUS_IN_TEXT.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /** Status GATT do erro: o campo da [GattException] ou, se faltar, o do texto (até 5 causas abaixo). */
    fun gattStatus(error: Throwable?): Int? {
        var cur = error
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur is GattException && cur.status != null) return cur.status
            cur = cur.cause
            depth++
        }
        cur = error
        depth = 0
        while (cur != null && depth < 5) {
            gattStatusFromText(cur.message)?.let { return it }
            cur = cur.cause
            depth++
        }
        return null
    }

    /** "Externa<Interna>" quando o erro embrulha outro, como no mobile. */
    fun errorName(error: Throwable?): String? {
        if (error == null) return null
        val outer = error.javaClass.simpleName
        var inner: Throwable = error
        var depth = 0
        while (inner.cause != null && inner.cause !== inner && depth < 5) {
            inner = inner.cause!!
            depth++
        }
        return if (inner === error) outer else "$outer<${inner.javaClass.simpleName}>"
    }

    fun errorMessage(error: Throwable?): String? {
        if (error == null) return null
        val parts = LinkedHashSet<String>()
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 5) {
            cur.message?.takeIf { it.isNotBlank() }?.let { parts += it }
            cur = cur.cause
            depth++
        }
        return ConnectionTelemetry.truncate(parts.joinToString(" | "))
    }

    /** Base de todo evento de sessão. */
    fun base(
        moduleId: String,
        purpose: String,
        collectMode: String,
        readerState: String,
        advRssiDbm: Int?,
        advAgeMs: Long?,
    ): Map<String, Any?> = mapOf(
        "module_id" to moduleId,
        "purpose" to purpose,
        "collect_mode" to collectMode,
        "reader_state" to readerState,
        "adv_rssi_dbm" to advRssiDbm,
        "adv_age_ms" to advAgeMs,
    )

    fun opened(
        base: Map<String, Any?>,
        connectAttempts: Int,
        durationMs: Long,
        mtu: Int,
        rssiDbm: Int?,
        mode: String,
    ): Map<String, Any?> = base + mapOf(
        "connect_attempts" to connectAttempts,
        "duration_ms" to durationMs.coerceAtLeast(0),
        "mtu" to mtu,
        "rssi_dbm" to rssiDbm,
        "mode" to mode,
    )

    fun failed(
        base: Map<String, Any?>,
        step: String,
        error: Throwable?,
        connectAttempts: Int,
        attemptErrors: List<String>,
        durationMs: Long,
    ): Map<String, Any?> = base + mapOf(
        "step" to step,
        "error_name" to errorName(error),
        "error_message" to errorMessage(error),
        "android_error_code" to gattStatus(error),
        "attempt_errors" to attemptErrors.joinToString(","),
        "connect_attempts" to connectAttempts,
        "duration_ms" to durationMs.coerceAtLeast(0),
    )

    fun dropped(
        moduleId: String,
        purpose: String,
        collectMode: String,
        readerState: String,
        reason: String,
        openMs: Long,
    ): Map<String, Any?> = mapOf(
        "module_id" to moduleId,
        "purpose" to purpose,
        "collect_mode" to collectMode,
        "reader_state" to readerState,
        "reason" to reason.take(120),
        "open_ms" to openMs.coerceAtLeast(0),
    )

    fun scanFailed(errorCode: Int, readerState: String, scanOpenMs: Long): Map<String, Any?> = mapOf(
        "error_message" to scanErrorText(errorCode),
        "ble_error_code" to errorCode,
        "reader_state" to readerState,
        "scan_open_ms" to scanOpenMs.coerceAtLeast(0),
    )

    fun collectYielded(
        moduleId: String,
        purpose: String,
        collectMode: String,
        trigger: String,
        downloaded: Int,
        remaining: Long?,
        waitedMs: Long,
        forcedDisconnect: Boolean,
    ): Map<String, Any?> = mapOf(
        "module_id" to moduleId,
        "purpose" to purpose,
        "collect_mode" to collectMode,
        "trigger" to trigger,
        "downloaded" to downloaded,
        "remaining" to remaining,
        "waited_ms" to waitedMs.coerceAtLeast(0),
        "forced_disconnect" to forcedDisconnect,
    )

    /** Códigos de ScanCallback.onScanFailed. */
    fun scanErrorText(code: Int): String = when (code) {
        1 -> "SCAN_FAILED_ALREADY_STARTED"
        2 -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
        3 -> "SCAN_FAILED_INTERNAL_ERROR"
        4 -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
        5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
        6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
        else -> "SCAN_FAILED_$code"
    }
}

/** Falha numa operação GATT, com o status do callback quando houver. */
class GattException(val step: String, val status: Int?, message: String, cause: Throwable? = null) :
    Exception(message, cause)
