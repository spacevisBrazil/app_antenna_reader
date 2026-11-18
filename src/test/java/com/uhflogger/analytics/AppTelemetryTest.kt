package com.uhflogger.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTelemetryTest {

    @Test
    fun `aparelho pronto para a smartbox`() {
        val p = AppTelemetry.appStarted(true, 85, true, true, true, "WINNIX", false)
        assertEquals(true, p["smartbox_key_configured"])
        assertEquals(true, p["farm_selected"])
        assertEquals(85, p["farm_id"])
        assertEquals(true, p["bt_connect_granted"])
        assertEquals("WINNIX", p["antenna_type"])
        assertEquals(false, p["was_capturing"])
    }

    @Test
    fun `sem fazenda nao manda farm_id e antena vazia vira nula`() {
        val p = AppTelemetry.appStarted(false, 0, false, true, false, "", true)
        assertEquals(false, p["farm_selected"])
        assertNull(p["farm_id"])
        assertNull(p["antenna_type"])
        assertEquals(true, p["was_capturing"])
    }
}
