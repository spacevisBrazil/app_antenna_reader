package com.uhflogger.smartbox.sync

import com.uhflogger.smartbox.ble.Reading
import com.uhflogger.smartbox.data.ModuleInfo
import com.uhflogger.smartbox.data.PendingReading
import com.uhflogger.smartbox.data.ReadingState
import com.uhflogger.smartbox.data.SmartboxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repositório em memória com as mesmas regras da implementação Room. */
class FakeRepo : SmartboxRepository {
    data class Row(val moduleId: String, val epc: String, val antenna: Int, val ts: Long, val rssi: Int, val farmId: Long, var state: Int = 0, var error: String? = null)

    val rows = LinkedHashMap<String, Row>()
    val registered = HashMap<String, Long?>()
    var accepted = 0

    private fun key(m: String, e: String, a: Int, t: Long) = "$m|$e|$a|$t"

    override fun persistBatch(moduleId: String, farmId: Long, readings: List<Reading>, now: Long): Int {
        var added = 0
        readings.forEach {
            val k = key(moduleId, it.epc, it.antenna, it.ts)
            if (k !in rows) { rows[k] = Row(moduleId, it.epc, it.antenna, it.ts, it.rssi, farmId); added++ }
        }
        return added
    }
    override fun markDownloadSuccess(moduleId: String, now: Long) = Unit
    override fun markSeen(moduleId: String, pending: Int, now: Long) = Unit
    override fun getModule(moduleId: String): ModuleInfo? =
        ModuleInfo(moduleId, registered[moduleId], null, null, null, 0, 0)
    override fun setRegistered(moduleId: String, farmId: Long?) { registered[moduleId] = farmId }
    override fun resetRegistrations() = registered.clear()
    override fun modulesWithPending(farmId: Long) =
        rows.values.filter { it.state == 0 && it.farmId == farmId }.map { it.moduleId }.distinct().sorted()
    override fun nextPendingBatch(moduleId: String, farmId: Long, limit: Int) =
        rows.values.filter { it.state == 0 && it.moduleId == moduleId && it.farmId == farmId }
            .sortedWith(compareBy({ it.ts }, { it.epc }, { it.antenna })).take(limit)
            .map { PendingReading(it.moduleId, it.epc, it.antenna, it.ts, it.rssi) }
    override fun markSent(batch: List<PendingReading>, moduleId: String, accepted: Int, now: Long) {
        batch.forEach { rows[key(it.moduleId, it.epc, it.antenna, it.ts)]!!.state = ReadingState.SENT }
        this.accepted += accepted
    }
    override fun markRejected(batch: List<PendingReading>, error: String) {
        batch.forEach { rows[key(it.moduleId, it.epc, it.antenna, it.ts)]!!.apply { state = ReadingState.REJECTED; this.error = error } }
    }
    override fun countPendingOtherFarms(farmId: Long) = rows.values.count { it.state == 0 && it.farmId != farmId }
    override fun countPending(farmId: Long) = rows.values.count { it.state == 0 && it.farmId == farmId }
    override fun cleanupSent(now: Long) = Unit
    override fun staleModules(now: Long) = emptyList<String>()

    fun pending(moduleId: String) = rows.values.count { it.moduleId == moduleId && it.state == 0 }
}

/** Backend roteirizado: cada chamada consome a próxima resposta da fila do módulo. */
class FakeBackend : SmartboxBackend {
    val registerScript = HashMap<String, ArrayDeque<Int>>()
    val sendScript = HashMap<String, ArrayDeque<Int>>()
    val calls = mutableListOf<String>()

    override fun registerModule(farmId: Long, moduleId: String): HttpOutcome {
        calls += "register $moduleId"
        val status = registerScript[moduleId]?.removeFirstOrNull() ?: 201
        return HttpOutcome(status, "HTTP $status")
    }

    override fun sendVisits(farmId: Long, moduleId: String, readings: List<PendingReading>): HttpOutcome {
        calls += "send $moduleId ${readings.size}"
        val status = sendScript[moduleId]?.removeFirstOrNull() ?: 201
        return HttpOutcome(status, "HTTP $status", if (status in 200..299) readings.size else 0)
    }
}

class SmartboxUploaderTest {
    private val farm = 85L

    private fun repoWith(vararg modules: Pair<String, Int>, farmId: Long = farm): FakeRepo = FakeRepo().apply {
        modules.forEach { (m, n) -> persistBatch(m, farmId, (1..n).map { Reading("E%04d".format(it), 1, -60, 1_762_000_000L + it) }, 0) }
    }

    private fun upload(repo: FakeRepo, api: FakeBackend) = SmartboxUploader.uploadPending(farm, repo, api) { 1L }

    @Test
    fun `classifica como o firmware`() {
        assertEquals(SmartboxUploader.Verdict.SENT, SmartboxUploader.classify(201))
        assertEquals(SmartboxUploader.Verdict.REJECTED, SmartboxUploader.classify(400))
        assertEquals(SmartboxUploader.Verdict.REJECTED, SmartboxUploader.classify(422))
        assertEquals(SmartboxUploader.Verdict.NOT_REGISTERED, SmartboxUploader.classify(404))
        assertEquals(SmartboxUploader.Verdict.STOP, SmartboxUploader.classify(401))
        assertEquals(SmartboxUploader.Verdict.STOP, SmartboxUploader.classify(0))
        assertEquals(SmartboxUploader.Verdict.STOP, SmartboxUploader.classify(429))
        assertEquals(SmartboxUploader.Verdict.STOP, SmartboxUploader.classify(503))
        assertEquals(SmartboxUploader.Verdict.STOP, SmartboxUploader.classify(302))
        assertEquals(SmartboxUploader.Verdict.SKIP_MODULE, SmartboxUploader.classify(403))
    }

    @Test
    fun `timestamp ISO UTC sem milissegundos`() {
        assertEquals("2026-11-04T13:05:20Z", SmartboxUploader.toReadingTimestamp(1793797520L))
    }

    @Test
    fun `cadastra o modulo e envia em lotes de 250`() {
        val repo = repoWith("M1" to 600)
        val api = FakeBackend()
        val s = upload(repo, api)
        assertEquals(listOf("register M1", "send M1 250", "send M1 250", "send M1 100"), api.calls)
        assertEquals(600, s.sentReadings)
        assertEquals(0, repo.pending("M1"))
        assertNull(s.stoppedBecause)
        assertEquals(farm, repo.registered["M1"])
    }

    @Test
    fun `so sobe leitura baixada de smartbox desta fazenda`() {
        val repo = repoWith("MINE" to 3)
        repo.persistBatch("ALHEIA", 7, listOf(Reading("E1", 1, -60, 1)), 0)
        val api = FakeBackend()
        val s = upload(repo, api)
        assertFalse(api.calls.any { "ALHEIA" in it })
        assertEquals(1, s.otherFarmPending)
        assertEquals(1, repo.pending("ALHEIA"))
    }

    @Test
    fun `400 recusa o lote e segue`() {
        val repo = repoWith("M1" to 3)
        val api = FakeBackend().apply { sendScript["M1"] = ArrayDeque(listOf(400)) }
        val s = upload(repo, api)
        assertEquals(3, s.rejectedReadings)
        assertEquals(3, repo.rows.values.count { it.state == ReadingState.REJECTED })
    }

    @Test
    fun `404 recadastra e reenvia uma vez`() {
        val repo = repoWith("M1" to 3).apply { registered["M1"] = farm }
        val api = FakeBackend().apply { sendScript["M1"] = ArrayDeque(listOf(404, 201)) }
        upload(repo, api)
        assertEquals(listOf("send M1 3", "register M1", "send M1 3"), api.calls)
        assertEquals(0, repo.pending("M1"))
    }

    @Test
    fun `404 de novo depois do cadastro deixa pendente e segue para o proximo`() {
        val repo = repoWith("M1" to 3, "M2" to 2).apply { registered["M1"] = farm; registered["M2"] = farm }
        val api = FakeBackend().apply { sendScript["M1"] = ArrayDeque(listOf(404, 404)) }
        val s = upload(repo, api)
        assertEquals(3, repo.pending("M1"))
        assertEquals(0, repo.pending("M2"))
        assertTrue(s.errors.single().contains("404"))
    }

    @Test
    fun `sem rede ou 5xx para tudo e pede retry`() {
        val repo = repoWith("M1" to 3, "M2" to 2).apply { registered["M1"] = farm; registered["M2"] = farm }
        val api = FakeBackend().apply { sendScript["M1"] = ArrayDeque(listOf(0)) }
        val s = upload(repo, api)
        assertEquals(listOf("send M1 3"), api.calls)
        assertTrue(s.retryLater)
        assertEquals(5, repo.pending("M1") + repo.pending("M2"))
    }

    @Test
    fun `401 depois de renovar para tudo sem retry automatico`() {
        val repo = repoWith("M1" to 3).apply { registered["M1"] = farm }
        val api = FakeBackend().apply { sendScript["M1"] = ArrayDeque(listOf(401)) }
        val s = upload(repo, api)
        assertEquals("Token recusado (401) mesmo depois de renovar", s.stoppedBecause)
        assertFalse(s.retryLater)
    }

    @Test
    fun `403 deixa pendente e segue para o proximo modulo`() {
        val repo = repoWith("M1" to 3, "M2" to 2).apply { registered["M1"] = farm; registered["M2"] = farm }
        val api = FakeBackend().apply { sendScript["M1"] = ArrayDeque(listOf(403)) }
        upload(repo, api)
        assertEquals(3, repo.pending("M1"))
        assertEquals(0, repo.pending("M2"))
    }

    @Test
    fun `cadastro recusado desiste so do modulo`() {
        val repo = repoWith("M1" to 3, "M2" to 2)
        val api = FakeBackend().apply { registerScript["M1"] = ArrayDeque(listOf(403)) }
        val s = upload(repo, api)
        assertEquals(3, repo.pending("M1"))
        assertEquals(0, repo.pending("M2"))
        assertTrue(s.errors.single().contains("cadastro falhou"))
    }

    @Test
    fun `reenviar o mesmo lote baixado nao duplica`() {
        val repo = repoWith("M1" to 3)
        val again = repo.persistBatch("M1", farm, (1..3).map { Reading("E%04d".format(it), 1, -60, 1_762_000_000L + it) }, 0)
        assertEquals(0, again)
        assertEquals(3, repo.pending("M1"))
    }
}
