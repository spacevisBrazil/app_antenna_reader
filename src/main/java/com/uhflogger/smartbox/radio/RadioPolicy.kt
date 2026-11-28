package com.uhflogger.smartbox.radio

/**
 * Convivência da smartbox com o leitor de brincos (decisão H3 do Igor).
 *
 * Princípio: a leitura de brincos SEMPRE tem prioridade. A smartbox nunca
 * interrompe nem atrasa o início de uma captura. A regra mora toda em
 * [RadioPolicy.decide], uma função pura, para trocar com uma linha se o campo
 * pedir (ex.: USB passar a bloquear também).
 */

/** O que o leitor está fazendo agora, do ponto de vista do rádio. */
enum class ReaderState(val telemetry: String) {
    IDLE("idle"),
    /** Captura por Bluetooth Classic ativa ou conectando. */
    BT_CAPTURING("bt_capturing"),
    BT_CONNECTING("bt_connecting"),
    /** Sessão BT pausada, com o loop de reconexão tentando voltar. */
    BT_RECONNECTING("bt_reconnecting"),
    USB_CAPTURING("usb_capturing"),
    USB_RECONNECTING("usb_reconnecting"),
}

/** Como a coleta da smartbox pode rodar agora. */
enum class RadioDecision {
    /** Nem busca nem conecta: o rádio é do leitor. */
    BLOCK,
    /** Roda sem tomar a tela (captura por USB em andamento). */
    BACKGROUND,
    /** Leitor parado: pode mostrar a tela de progresso. */
    FOREGROUND,
}

/** Para a telemetria: em que modo a coleta rodou. */
enum class CollectMode(val telemetry: String) {
    BLOCKING("blocking"),
    BACKGROUND("background"),
    /** Tela de técnico (Buscar, Diagnóstico…), fora da coleta automática. */
    MANUAL("manual"),
}

object RadioPolicy {

    /**
     * Janela depois de um startCapture() em que uma sessão BT conta como
     * ocupando o rádio mesmo sem isRunning/isPaused: durante o connect() do
     * socket (até ~12 s) e o probe, os dois ficam false.
     */
    const val BT_CONNECT_GRACE_MS = 20_000L

    fun decide(state: ReaderState): RadioDecision = when (state) {
        ReaderState.BT_CONNECTING, ReaderState.BT_RECONNECTING -> RadioDecision.BLOCK
        ReaderState.BT_CAPTURING, ReaderState.USB_CAPTURING, ReaderState.USB_RECONNECTING -> RadioDecision.BACKGROUND
        ReaderState.IDLE -> RadioDecision.FOREGROUND
    }

    /**
     * Estado do leitor a partir dos flags do UHFReaderService.
     *
     * @param paused sessão pausada esperando reconexão (isPaused ou um loop de reconexão ativo)
     * @param msSinceStartRequested tempo desde o último startCapture(); Long.MAX_VALUE se nunca
     */
    fun deriveReaderState(
        running: Boolean,
        paused: Boolean,
        bluetooth: Boolean,
        msSinceStartRequested: Long,
    ): ReaderState = if (bluetooth) {
        when {
            running -> ReaderState.BT_CAPTURING
            paused -> ReaderState.BT_RECONNECTING
            msSinceStartRequested in 0 until BT_CONNECT_GRACE_MS -> ReaderState.BT_CONNECTING
            else -> ReaderState.IDLE
        }
    } else {
        when {
            running -> ReaderState.USB_CAPTURING
            paused -> ReaderState.USB_RECONNECTING
            else -> ReaderState.IDLE
        }
    }
}

/**
 * Ponte do UHFReaderService para a smartbox, sem acoplar um ao outro: o
 * serviço registra um provedor no onCreate e tira no onDestroy. Sem serviço
 * vivo, o leitor está parado.
 */
object ReaderRadio {
    @Volatile var provider: (() -> ReaderState)? = null

    fun current(): ReaderState = try {
        provider?.invoke() ?: ReaderState.IDLE
    } catch (_: Exception) {
        ReaderState.IDLE
    }

    fun decision(): RadioDecision = RadioPolicy.decide(current())
}
