package com.uhflogger.service

import com.uhflogger.model.TagRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Simulação de bancada (não depende de Android/Gradle-instrumentado) do
 * redesenho proposto para o pipeline de escrita: eliminar o `tagBuffer` em
 * RAM e escrever cada lote de tags direto no CSV assim que chega, deixando
 * "por quantidade"/"por tempo" responsáveis só pela ROTAÇÃO de arquivo, não
 * pela durabilidade.
 *
 * `SimCsvWriter` é uma transcrição fiel da lógica real de CsvExporter.kt
 * (mesmo algoritmo de pendingLastTag/appendTags/finalizeSession), só trocando
 * Context por um File de pasta direto — CsvExporter.kt não pode ser
 * instanciado num teste JVM puro porque depende de android.content.Context.
 * `SimFilterEngine` é a mesma transcrição para TagFilterEngine.kt (process/
 * sweepExpired/flushAllNow), sem a persistência em Room (irrelevante aqui:
 * o que está sendo validado é o pipeline de escrita a jusante do filtro).
 */
class ProposedAutoSaveSimulationTest {

    // ---- transcrição fiel de CsvExporter.kt -------------------------------
    private class SimCsvWriter(private val folder: File) {
        var writer: BufferedWriter? = null
        var file: File? = null
        var pendingLastTag: TagRecord? = null

        fun startSession(prefix: String, seq: Int): File {
            val f = File(folder, "${prefix}_$seq.csv")
            writer = BufferedWriter(FileWriter(f, false))
            writer!!.write(TagRecord.CSV_HEADER); writer!!.newLine(); writer!!.flush()
            file = f
            pendingLastTag = null
            return f
        }

        fun appendTags(tags: List<TagRecord>): Boolean {
            if (tags.isEmpty()) return true
            val w = writer ?: return false
            pendingLastTag?.let { w.write(it.toCsvLine()); w.newLine() }
            pendingLastTag = null
            for (i in 0 until tags.size - 1) { w.write(tags[i].toCsvLine()); w.newLine() }
            pendingLastTag = tags.last()
            w.flush()
            return true
        }

        fun finalizeSession(tags: List<TagRecord>, stopTemperature: String = ""): File? {
            val w = writer ?: return null
            if (tags.isEmpty()) {
                val pending = pendingLastTag
                val record = if (stopTemperature.isNotEmpty() && pending != null) pending.copy(temperature = stopTemperature) else pending
                record?.let { w.write(it.toCsvLine()); w.newLine() }
                pendingLastTag = null
            } else {
                pendingLastTag?.let { w.write(it.toCsvLine()); w.newLine() }
                pendingLastTag = null
                for (i in 0 until tags.size - 1) { w.write(tags[i].toCsvLine()); w.newLine() }
                val lastTag = if (stopTemperature.isNotEmpty()) tags.last().copy(temperature = stopTemperature) else tags.last()
                w.write(lastTag.toCsvLine()); w.newLine()
            }
            w.flush()
            val f = file
            w.close()
            writer = null; file = null; pendingLastTag = null
            return f
        }

        /** Simula um SIGKILL: nada mais é chamado, só olhamos o que já está no disco. */
        fun linesOnDiskNow(): List<String> {
            val f = file ?: return emptyList()
            return f.readLines()
        }
    }

    // ---- transcrição fiel de TagFilterEngine.kt (sem persistência Room) --
    private class SimFilterEngine(private val l2Enabled: Boolean, private val l2WindowMs: Long) {
        private data class OpenEntry(val firstSeenMs: Long, var best: TagRecord)
        private val open = LinkedHashMap<String, OpenEntry>()

        fun process(tags: List<TagRecord>, now: Long): List<TagRecord> {
            if (!l2Enabled) return tags
            val passThrough = ArrayList<TagRecord>()
            for (tag in tags) {
                val existing = open[tag.epc]
                if (existing == null) open[tag.epc] = OpenEntry(now, tag)
                else if (tag.rssi > existing.best.rssi) existing.best = tag
            }
            return passThrough
        }

        fun sweepExpired(now: Long): List<TagRecord> {
            val expired = open.entries.filter { now - it.value.firstSeenMs >= l2WindowMs }
            expired.forEach { open.remove(it.key) }
            return expired.map { it.value.best }
        }

        fun flushAllNow(): List<TagRecord> {
            val results = open.values.map { it.best }
            open.clear()
            return results
        }
    }

    // ---- pipeline NOVO proposto: sem tagBuffer, escreve direto ----------
    // Um único executor sequencial garante a mesma serialização que hoje
    // existe implicitamente (autoSaveExecutor de thread única), já que
    // SimCsvWriter não é thread-safe — igual ao CsvExporter real.
    private class SimService(folder: File, private val filter: SimFilterEngine, autoSaveMode: String) {
        val csv = SimCsvWriter(folder)
        val ioExecutor = Executors.newSingleThreadExecutor()
        var totalWritten = 0
        var fileSeq = 0
        val newFileMode = autoSaveMode == "NEW_FILE"

        init { csv.startSession("rfid", fileSeq) }

        /** Equivale ao onNewData/winnixOnNewData depois do decoder + filtro — chamado na thread serial. */
        fun onTagsArrived(rawTags: List<TagRecord>, now: Long) {
            val passThrough = filter.process(rawTags, now)
            if (passThrough.isNotEmpty()) writeNow(passThrough)
        }

        /** Equivale ao runFilterSweep() — chamado periodicamente pelo scheduler. */
        fun onSweepTick(now: Long) {
            val expired = filter.sweepExpired(now)
            if (expired.isNotEmpty()) writeNow(expired)
        }

        private fun writeNow(tags: List<TagRecord>) {
            ioExecutor.submit {
                csv.appendTags(tags)
                totalWritten += tags.size
            }
        }

        /** Rotação por tempo/quantidade — não carrega mais nenhum lote, só fecha e abre (D2-equivalente). */
        fun rotate() {
            ioExecutor.submit {
                csv.finalizeSession(emptyList())
                fileSeq++
                csv.startSession("rfid", fileSeq)
            }
        }

        fun stopUserInitiated(stopTemp: String = "") {
            val expired = filter.flushAllNow()
            ioExecutor.submit { csv.finalizeSession(expired, stopTemp) }
            awaitIdle()
        }

        /** Bloqueia até que tudo que já foi submetido termine, sem encerrar o executor (FIFO de thread única). */
        fun awaitIdle() {
            ioExecutor.submit {}.get(5, TimeUnit.SECONDS)
        }

        fun shutdown() {
            ioExecutor.shutdown()
            ioExecutor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun tag(epc: String, rssi: Int, seq: Long) =
        TagRecord(epc = epc, rssi = rssi, antenna = 1, androidTs = seq)

    // ------------------------------------------------------------------
    @Test
    fun `filtro desligado - alto volume, kill simulado perde no maximo 1 tag`() {
        val folder = createTempDir()
        val svc = SimService(folder, SimFilterEngine(l2Enabled = false, l2WindowMs = 0), "APPEND")

        // Simula rajada: 500 lotes pequenos chegando rápido, como a serial entregaria.
        var sent = 0
        for (batch in 0 until 500) {
            val tags = (0 until 3).map { tag("EPC$batch-$it", rssi = -40, seq = batch.toLong()) }
            svc.onTagsArrived(tags, now = batch.toLong())
            sent += tags.size
        }
        // Espera o executor único drenar a fila de escrita (o "SIGKILL" acontece
        // só depois que a última tag que a serial entregou já foi processada —
        // é o cenário real: o processo não morre no meio de uma escrita em curso).
        svc.awaitIdle()

        // "Kill": não chamamos finalizeSession. Olhamos só o que já está em disco.
        val lines = svc.csv.linesOnDiskNow()
        val dataLines = lines.size - 1 // desconta header

        // No máximo 1 tag (o pendingLastTag) pode estar fora do arquivo — nunca
        // "minutos de leitura" como no desenho antigo com tagBuffer.
        assertTrue("esperado no minimo ${sent - 1} linhas no disco, achou $dataLines", dataLines >= sent - 1)
        assertTrue("nao pode ter mais linhas em disco do que tags enviadas", dataLines <= sent)
    }

    @Test
    fun `filtro camada 2 ativo - consolida por melhor RSSI e so libera apos a janela`() {
        val folder = createTempDir()
        val windowMs = 1000L
        val filter = SimFilterEngine(l2Enabled = true, l2WindowMs = windowMs)
        val svc = SimService(folder, filter, "APPEND")

        // Mesmo EPC lido 3x dentro da janela, com RSSI variando.
        svc.onTagsArrived(listOf(tag("EPC1", rssi = -60, seq = 1)), now = 0)
        svc.onTagsArrived(listOf(tag("EPC1", rssi = -30, seq = 2)), now = 200) // melhor RSSI
        svc.onTagsArrived(listOf(tag("EPC1", rssi = -50, seq = 3)), now = 400)
        svc.awaitIdle()

        // Sweep ANTES da janela fechar: nada deve ser liberado.
        svc.onSweepTick(now = 500)
        svc.awaitIdle()
        assertEquals("nada deveria ter sido gravado antes da janela expirar",
            1, svc.csv.linesOnDiskNow().size) // só o header

        // Sweep DEPOIS da janela: libera o melhor RSSI (-30), grava na hora.
        svc.onSweepTick(now = 1200)
        svc.awaitIdle()
        svc.stopUserInitiated() // finaliza pra soltar o pendingLastTag pro arquivo

        // stopUserInitiated fecha o writer, então conferimos o arquivo direto.
        val finalLines = File(folder, "rfid_0.csv").readLines()
        assertEquals(2, finalLines.size) // header + 1 linha consolidada
        assertTrue("deveria manter o RSSI -30 (melhor)", finalLines[1].startsWith("EPC1,-30,"))
    }

    @Test
    fun `rotacao por gatilho nao perde nem duplica tags entre arquivos`() {
        val folder = createTempDir()
        val svc = SimService(folder, SimFilterEngine(l2Enabled = false, l2WindowMs = 0), "NEW_FILE")

        svc.onTagsArrived(listOf(tag("A", -40, 1), tag("B", -40, 2)), now = 0)
        svc.rotate() // fecha rfid_0.csv, abre rfid_1.csv
        svc.onTagsArrived(listOf(tag("C", -40, 3), tag("D", -40, 4)), now = 1)
        svc.stopUserInitiated()

        val file0 = File(folder, "rfid_0.csv").readLines()
        val file1 = File(folder, "rfid_1.csv").readLines()
        val totalDataLines = (file0.size - 1) + (file1.size - 1)
        assertEquals(4, totalDataLines)
        assertTrue(file0.any { it.startsWith("A,") } && file0.any { it.startsWith("B,") })
        assertTrue(file1.any { it.startsWith("C,") } && file1.any { it.startsWith("D,") })
    }

    @Test
    fun `temperatura de encerramento Winnix ainda e aplicada corretamente na ultima linha`() {
        val folder = createTempDir()
        val svc = SimService(folder, SimFilterEngine(l2Enabled = false, l2WindowMs = 0), "APPEND")

        svc.onTagsArrived(listOf(tag("A", -40, 1), tag("B", -40, 2), tag("C", -40, 3)), now = 0)
        svc.stopUserInitiated(stopTemp = "23.5")

        val lines = File(folder, "rfid_0.csv").readLines()
        assertEquals(4, lines.size) // header + 3 tags
        val header = TagRecord.CSV_HEADER.split(",")
        val tempIdx = header.indexOf("Temperature")
        val rowA = lines[1].split(",")
        val rowB = lines[2].split(",")
        val rowC = lines[3].split(",")
        assertEquals("A", rowA[0]); assertEquals("", rowA[tempIdx])
        assertEquals("B", rowB[0]); assertEquals("", rowB[tempIdx])
        assertEquals("C", rowC[0]); assertEquals("23.5", rowC[tempIdx])
    }
}
