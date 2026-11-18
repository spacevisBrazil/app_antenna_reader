package com.uhflogger.analytics

import com.uhflogger.analytics.ConnectionTelemetry.DropReason
import com.uhflogger.analytics.ConnectionTelemetry.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConnectionTelemetryTest {

    @Test
    fun `truncate keeps short messages intact`() {
        assertEquals("read failed, socket might closed", ConnectionTelemetry.truncate("read failed, socket might closed"))
    }

    @Test
    fun `truncate cuts long messages to the limit with ellipsis`() {
        val cut = ConnectionTelemetry.truncate("x".repeat(500))!!
        assertEquals(ConnectionTelemetry.MAX_MESSAGE_LENGTH, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun `truncate collapses whitespace and newlines into one line`() {
        assertEquals("a b c", ConnectionTelemetry.truncate("  a\n\tb   c \n"))
    }

    @Test
    fun `truncate returns null for null or blank`() {
        assertNull(ConnectionTelemetry.truncate(null))
        assertNull(ConnectionTelemetry.truncate("   \n"))
    }

    @Test
    fun `errorName uses the exception simple name`() {
        assertEquals("IOException", ConnectionTelemetry.errorName(IOException("x")))
        assertNull(ConnectionTelemetry.errorName(null))
    }

    @Test
    fun `transport maps the session type`() {
        assertEquals("bluetooth", ConnectionTelemetry.transport(true))
        assertEquals("usb", ConnectionTelemetry.transport(false))
    }

    @Test
    fun `failed prefers explicit message over the exception message`() {
        val props = ConnectionTelemetry.failed(
            transport = "bluetooth", step = Step.PROBE, error = null,
            attempt = 3, durationMs = 1200, btEnabled = true,
            message = "no valid probe response after retry",
        )
        assertEquals(Step.PROBE, props["step"])
        assertEquals("no valid probe response after retry", props["error_message"])
        assertNull(props["error_name"])
        assertEquals(3, props["attempt"])
        assertEquals(1200L, props["duration_ms"])
        assertEquals(true, props["bt_enabled"])
    }

    @Test
    fun `failed falls back to the truncated exception message`() {
        val props = ConnectionTelemetry.failed(
            transport = "bluetooth", step = Step.SOCKET_CONNECT,
            error = IOException("read failed, socket might closed or timeout, read ret: -1"),
            attempt = 0, durationMs = 12_000, btEnabled = false,
        )
        assertEquals("IOException", props["error_name"])
        assertEquals("read failed, socket might closed or timeout, read ret: -1", props["error_message"])
    }

    @Test
    fun `durations never go negative when clocks jump`() {
        val failed = ConnectionTelemetry.failed("usb", Step.USB_OPEN, null, 0, -50, null)
        assertEquals(0L, failed["duration_ms"])
        val dropped = ConnectionTelemetry.dropped("usb", DropReason.READ_ERROR, null, -1)
        assertEquals(0L, dropped["open_ms"])
    }

    @Test
    fun `dropReason defaults to read error and honours the hint`() {
        assertEquals(DropReason.READ_ERROR, ConnectionTelemetry.dropReason(null))
        assertEquals(DropReason.WATCHDOG_SILENCE, ConnectionTelemetry.dropReason(DropReason.WATCHDOG_SILENCE))
        assertEquals(DropReason.CONFIG_ERROR, ConnectionTelemetry.dropReason(DropReason.CONFIG_ERROR))
    }

    @Test
    fun `dropped without an open timestamp reports null open_ms`() {
        val props = ConnectionTelemetry.dropped("bluetooth", DropReason.READ_ERROR, IOException("bt socket closed"), null)
        assertNull(props["open_ms"])
        assertEquals("bt socket closed", props["error_message"])
    }

    @Test
    fun `reconnectLoop reports outcome`() {
        assertEquals("reconnected", ConnectionTelemetry.reconnectLoop("usb", true, 4, 15_000)["outcome"])
        assertEquals("cancelled", ConnectionTelemetry.reconnectLoop("usb", false, 4, 15_000)["outcome"])
    }

    @Test
    fun `uploadFailed carries status or exception`() {
        val http = ConnectionTelemetry.uploadFailed("send_batch", 413, null)
        assertEquals(413, http["status"])
        assertNull(http["error_name"])

        val ex = ConnectionTelemetry.uploadFailed("exception", null, IllegalStateException("boom"))
        assertNull(ex["status"])
        assertEquals("IllegalStateException", ex["error_name"])
        assertEquals("boom", ex["error_message"])
    }
}
