package com.uhflogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSafetyTimerTest {

    /** Relógio e agenda falsos: só o último agendamento vale, como o Handler. */
    private class FakeClock {
        var now = 0L
        val pending = mutableMapOf<Runnable, Long>()
        fun schedule(r: Runnable, delay: Long) { pending[r] = now + delay }
        fun cancel(r: Runnable) { pending.remove(r) }
        fun advance(ms: Long) {
            now += ms
            pending.filterValues { it <= now }.keys.toList().forEach { pending.remove(it); it.run() }
        }
    }

    private fun timer(clock: FakeClock, onExpire: () -> Unit) =
        StartSafetyTimer(15_000, clock::schedule, clock::cancel, onExpire)

    @Test
    fun `sem smartbox o botao volta 15 s depois do clique, como antes`() {
        val clock = FakeClock()
        var expired = 0
        val t = timer(clock) { expired++ }
        t.arm()
        clock.advance(14_999)
        assertEquals(0, expired)
        clock.advance(1)
        assertEquals(1, expired)
        assertFalse(t.isArmed)
    }

    @Test
    fun `o tempo do yield nao conta - o prazo cheio comeca antes do startCapture`() {
        val clock = FakeClock()
        var expired = 0
        val t = timer(clock) { expired++ }
        t.arm()                       // clique
        val wasArmed = t.pause()      // smartbox ocupada: começa o yield
        assertTrue(wasArmed)
        clock.advance(6_000)          // yield levou 6 s (teto 5 s + corte)
        t.resume(wasArmed)            // logo antes do startCapture
        clock.advance(12_000)         // connect BT do leitor ainda em curso
        assertEquals(0, expired)      // antes: 6 + 12 = 18 s > 15 s e o botão voltava
        clock.advance(3_000)
        assertEquals(1, expired)      // 15 s depois do startCapture
    }

    @Test
    fun `retomada automatica sem clique nao arma o timer`() {
        val clock = FakeClock()
        var expired = 0
        val t = timer(clock) { expired++ }
        val wasArmed = t.pause()      // reconexão USB/BT: ninguém armou
        t.resume(wasArmed)
        clock.advance(60_000)
        assertFalse(wasArmed)
        assertEquals(0, expired)
    }

    @Test
    fun `armar de novo recomeca do zero`() {
        val clock = FakeClock()
        var expired = 0
        val t = timer(clock) { expired++ }
        t.arm()
        clock.advance(10_000)
        t.arm()
        clock.advance(10_000)
        assertEquals(0, expired)
        clock.advance(5_000)
        assertEquals(1, expired)
    }
}
