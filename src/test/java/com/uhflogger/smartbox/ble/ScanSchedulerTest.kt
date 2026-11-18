package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.ScanScheduler.Action
import com.uhflogger.smartbox.ble.ScanScheduler.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** Limites do Android para o scan (seção 7.1 do guia). */
class ScanSchedulerTest {
    private val t0 = 1_000_000L

    @Test
    fun `comeca quando alguem quer e nada impede`() {
        assertEquals(Action.Start, ScanScheduler.onTick(State(), t0, wanted = true))
        assertEquals(Action.None, ScanScheduler.onTick(State(), t0, wanted = false))
    }

    @Test
    fun `respeita 6 s entre dois inicios`() {
        val s = State(lastStartAt = t0)
        assertEquals(Action.None, ScanScheduler.onTick(s, t0 + 5_999, wanted = true))
        assertEquals(Action.Start, ScanScheduler.onTick(s, t0 + 6_000, wanted = true))
    }

    @Test
    fun `para quando ninguem quer ou algo impede`() {
        val s = State(scanning = true, startedAt = t0, lastStartAt = t0, lastAdvAt = t0)
        assertEquals(Action.Stop, ScanScheduler.onTick(s, t0 + 1_000, wanted = false))
    }

    @Test
    fun `30 s sem anuncio para e espera 30 s`() {
        val s = State(scanning = true, startedAt = t0, lastStartAt = t0, lastAdvAt = t0)
        assertEquals(Action.None, ScanScheduler.onTick(s, t0 + 30_000, wanted = true))
        assertEquals(Action.StopAndHold(t0 + 30_001 + 30_000), ScanScheduler.onTick(s, t0 + 30_001, wanted = true))
        // Durante a espera não reabre.
        val held = State(lastStartAt = t0, holdUntil = t0 + 60_001)
        assertEquals(Action.None, ScanScheduler.onTick(held, t0 + 50_000, wanted = true))
        assertEquals(Action.Start, ScanScheduler.onTick(held, t0 + 60_001, wanted = true))
    }

    @Test
    fun `reabre o scan depois de 25 min para nao virar oportunista`() {
        val s = State(scanning = true, startedAt = t0, lastStartAt = t0, lastAdvAt = t0 + 25 * 60_000L)
        assertEquals(Action.None, ScanScheduler.onTick(s, t0 + 25 * 60_000L, wanted = true))
        assertEquals(Action.Stop, ScanScheduler.onTick(s, t0 + 25 * 60_000L + 1, wanted = true))
    }
}
