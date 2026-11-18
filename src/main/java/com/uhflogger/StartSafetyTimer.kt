package com.uhflogger

/**
 * Timeout de segurança do botão "Iniciar leitura" (ver MainActivity.onStartClicked):
 * reabilita o botão se a captura não começou em [timeoutMs]. Ele existe para
 * impedir duas conexões do leitor ao mesmo tempo (uma derrubando a outra).
 *
 * Com a smartbox ocupada, o início espera ela ceder o rádio (até ~5 s + o
 * corte). Esse tempo NÃO pode sair do prazo do connect do leitor (~12 s no
 * BT), senão o botão volta com o BT ainda conectando. Por isso o timer é
 * PAUSADO durante o yield e REARMADO com o prazo cheio logo antes do
 * startCapture de fato ([pause] / [resume]).
 *
 * Puro (o agendamento é injetado) para testar sem Android.
 */
class StartSafetyTimer(
    private val timeoutMs: Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    onExpire: () -> Unit,
) {
    @Volatile var isArmed = false
        private set

    private val task = Runnable {
        isArmed = false
        onExpire()
    }

    /** Começa (ou recomeça do zero) a contagem. */
    fun arm() {
        cancel(task)
        isArmed = true
        schedule(task, timeoutMs)
    }

    /** Suspende a contagem. @return se estava armado (passe para [resume]). */
    fun pause(): Boolean {
        val was = isArmed
        cancel(task)
        isArmed = false
        return was
    }

    /** Volta a contar com o prazo CHEIO, se estava armado antes do [pause]. */
    fun resume(wasArmed: Boolean) {
        if (wasArmed) arm()
    }
}
