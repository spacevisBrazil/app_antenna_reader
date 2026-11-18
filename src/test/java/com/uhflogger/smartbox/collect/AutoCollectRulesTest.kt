package com.uhflogger.smartbox.collect

import com.uhflogger.smartbox.ble.Advertisement
import com.uhflogger.smartbox.ble.NearbySmartbox
import com.uhflogger.smartbox.collect.AutoCollectRules.Block
import com.uhflogger.smartbox.collect.AutoCollectRules.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Porte das regras de spacevis-mobile/src/features/smartbox/domain/autoCollect.ts + as do UHF Logger. */
class AutoCollectRulesTest {
    private val now = 10_000_000L
    private val farm = 85L

    private fun box(
        id: String = "AAAAAA000001",
        farmId: Long = farm,
        pending: Int = 100,
        rssi: Int = -60,
        state: Int = 0,
        proto: Int = 2,
        seenAt: Long = now,
    ) = NearbySmartbox(Advertisement(proto, id, state, 0, pending, farmId, 1), "00:11:22:33:44:55", rssi, seenAt)

    @Test
    fun `pausas por desfecho`() {
        assertEquals(now + 5 * 60_000L, AutoCollectRules.nextPause(null, Outcome.SUCCESS, now).until)
        assertEquals(now + 10 * 60_000L, AutoCollectRules.nextPause(null, Outcome.CONFIG_MODE, now).until)
        assertEquals(now + 30_000L, AutoCollectRules.nextPause(null, Outcome.YIELDED, now).until)
    }

    @Test
    fun `falha tres vezes sem pausa e depois 1, 2, 4, 8 ate 10 min`() {
        var p: AutoCollectRules.Pause? = null
        val waits = (1..8).map {
            p = AutoCollectRules.nextPause(p, Outcome.FAILURE, now)
            p!!.until - now
        }
        assertEquals(listOf(0L, 0L, 0L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L), waits)
        // Sucesso zera.
        assertEquals(0, AutoCollectRules.nextPause(p, Outcome.SUCCESS, now).consecutiveFailures)
    }

    @Test
    fun `ceder o rapido nao conta como falha`() {
        val p = AutoCollectRules.nextPause(AutoCollectRules.Pause(0, 2, 0), Outcome.YIELDED, now)
        assertEquals(2, p.consecutiveFailures)
    }

    @Test
    fun `bloqueios na ordem da secao 7_2`() {
        assertEquals(Block.GONE, AutoCollectRules.blockReason(box(seenAt = now - 12_001), farm, null, now))
        assertEquals(Block.GONE, AutoCollectRules.blockReason(box(seenAt = now - 10), farm, AutoCollectRules.Pause(0, 0, now - 10), now))
        assertEquals(Block.PROTOCOL, AutoCollectRules.blockReason(box(proto = 3), farm, null, now))
        assertEquals(Block.OTHER_FARM, AutoCollectRules.blockReason(box(farmId = 0), farm, null, now))
        assertEquals(Block.OTHER_FARM, AutoCollectRules.blockReason(box(farmId = 7), farm, null, now))
        assertEquals(Block.CONFIG_MODE, AutoCollectRules.blockReason(box(state = 1), farm, null, now))
        assertEquals(Block.PAUSED, AutoCollectRules.blockReason(box(), farm, AutoCollectRules.Pause(now + 1, 0, now - 20_000), now))
        assertEquals(Block.NO_READINGS, AutoCollectRules.blockReason(box(pending = 0), farm, null, now))
        assertEquals(Block.WEAK_SIGNAL, AutoCollectRules.blockReason(box(rssi = -91), farm, null, now))
        assertNull(AutoCollectRules.blockReason(box(rssi = -90), farm, null, now))
    }

    @Test
    fun `candidata - memoria cheia, depois mais pendentes, depois sinal`() {
        val a = box("A00000000001", pending = 500, rssi = -50)
        val b = box("B00000000002", pending = 900, rssi = -80)
        val c = box("C00000000003", pending = 10, rssi = -85, state = 0b0100_0000)
        val d = box("D00000000004", pending = 900, rssi = -40)
        assertEquals("C00000000003", AutoCollectRules.pickCandidate(listOf(a, b, c, d), farm, emptyMap(), now)?.adv?.moduleId)
        assertEquals("D00000000004", AutoCollectRules.pickCandidate(listOf(a, b, d), farm, emptyMap(), now)?.adv?.moduleId)
        assertNull(AutoCollectRules.pickCandidate(listOf(box(farmId = 7)), farm, emptyMap(), now))
    }

    @Test
    fun `sem chave, sem fazenda, desligada ou sem permissao, nada sobe`() {
        assertTrue(AutoCollectRules.shouldRun(keyConfigured = true, farmId = 85, enabledPref = true, hasConnectPermission = true))
        assertFalse(AutoCollectRules.shouldRun(keyConfigured = false, farmId = 85, enabledPref = true, hasConnectPermission = true))
        assertFalse(AutoCollectRules.shouldRun(keyConfigured = true, farmId = 0, enabledPref = true, hasConnectPermission = true))
        assertFalse(AutoCollectRules.shouldRun(keyConfigured = true, farmId = 85, enabledPref = false, hasConnectPermission = true))
        assertFalse(AutoCollectRules.shouldRun(keyConfigured = true, farmId = 85, enabledPref = true, hasConnectPermission = false))
    }

    @Test
    fun `aviso de 24 h so com a caixa por perto e pendente ha 24 h`() {
        val day = AutoCollectRules.STALE_AFTER_MS
        assertTrue(AutoCollectRules.isStale(pendingSince = now - day, lastSeenAt = now - 60_000, now = now))
        assertFalse(AutoCollectRules.isStale(pendingSince = now - day + 1, lastSeenAt = now, now = now))
        // Ninguém passa lá há mais de 1 h: não é problema de coleta.
        assertFalse(AutoCollectRules.isStale(pendingSince = now - 2 * day, lastSeenAt = now - 61 * 60_000L, now = now))
        assertFalse(AutoCollectRules.isStale(pendingSince = null, lastSeenAt = now, now = now))
    }

    @Test
    fun `tempo estimado legivel`() {
        assertEquals("12 s", AutoCollectRules.formatEta(12))
        assertEquals("3 min", AutoCollectRules.formatEta(200))
        assertEquals("1 h 05 min", AutoCollectRules.formatEta(3_900))
    }
}
