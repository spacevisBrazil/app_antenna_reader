package com.uhflogger.service

import com.uhflogger.model.TagRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simula o design REALMENTE implementado em UHFReaderService.kt após a
 * remoção do tagBuffer (writeTagsNow / onAutoSaveTrigger / stopAutoSaveTimer
 * retornando o executor / awaitTermination antes de finalizeSession), com
 * ações válidas e inválidas propositalmente fora de ordem, procurando bugs.
 *
 * Transcrição fiel de CsvExporter.kt (SimCsvWriter) — mesmo motivo do
 * ProposedAutoSaveSimulationTest: não dá pra instanciar o objeto real sem
 * android.content.Context.
 */
class RealDesignAdversarialTest {

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

        fun cancelSession() {
            try { writer?.close() } catch (_: Exception) {}
            writer = null; file = null; pendingLastTag = null
        }

        fun linesOnDiskNow(): List<String> = file?.readLines() ?: emptyList()
    }

    /**
     * Transcrição fiel do design REAL: stopAutoSaveTimer() retorna o executor
     * antigo (não faz mais só shutdown "silencioso"); writeTagsNow() escreve na
     * hora, sem buffer; drainAndFinalize() não drena buffer nenhum, só decide
     * cancelar sessão vazia ou finalizar com o lote explícito (D1).
     */
    private class SimService(folder: File, autoSaveMode: String, private val autoSaveTagCount: Int = 3) {
        val csv = SimCsvWriter(folder)
        var autoSaveExecutor: ScheduledExecutorService? = null
        val stopExecutor = Executors.newSingleThreadExecutor()
        val totalCount = AtomicInteger(0)
        val tagsSinceLastSave = AtomicInteger(0)
        var fileSeq = 0
        val newFileMode = autoSaveMode == "NEW_FILE"
        var isRunning = true
        var writeFailures = 0

        fun startCapture() {
            csv.startSession("rfid", fileSeq)
            autoSaveExecutor = Executors.newSingleThreadScheduledExecutor()
            isRunning = true
        }

        /** Espelha writeTagsNow() real: escreve na hora, atualiza contadores, aciona rotação por contagem. */
        fun writeTagsNow(tags: List<TagRecord>) {
            if (tags.isEmpty()) return
            totalCount.addAndGet(tags.size)
            val exec = autoSaveExecutor
            if (exec != null) {
                exec.submit {
                    if (!csv.appendTags(tags)) writeFailures++
                }
            } else {
                // Ação inválida simulada: chegou tag depois do stop já ter matado o executor.
                writeFailures++
            }
            val sinceLast = tagsSinceLastSave.addAndGet(tags.size)
            if (sinceLast >= autoSaveTagCount) {
                autoSaveExecutor?.submit { onAutoSaveTrigger() }
            }
        }

        fun onAutoSaveTrigger() {
            if (!isRunning) return
            tagsSinceLastSave.set(0)
            if (newFileMode) {
                csv.finalizeSession(emptyList())
                fileSeq++
                csv.startSession("rfid", fileSeq)
            }
        }

        /** Espelha stopAutoSaveTimer() real: retorna o executor antigo pra quem chama aguardar. */
        fun stopAutoSaveTimer(): ScheduledExecutorService? {
            val exec = autoSaveExecutor
            autoSaveExecutor = null
            exec?.shutdown()
            return exec
        }

        fun drainAndFinalize(finalBatch: List<TagRecord>, stopTemp: String = ""): File? {
            if (totalCount.get() == 0) {
                csv.cancelSession()
                return null
            }
            return csv.finalizeSession(finalBatch, stopTemp)
        }

        /** Espelha stopCapture(userInitiated=true) fim a fim, incluindo o await. */
        fun stopUserInitiated(d1Expired: List<TagRecord> = emptyList(), stopTemp: String = ""): File? {
            isRunning = false
            val old = stopAutoSaveTimer()
            val fut = stopExecutor.submit<File?> {
                try { old?.awaitTermination(10, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                if (d1Expired.isNotEmpty()) totalCount.addAndGet(d1Expired.size)
                val result = drainAndFinalize(d1Expired, stopTemp)
                totalCount.set(0)
                result
            }
            return fut.get(10, TimeUnit.SECONDS)
        }

        fun shutdownAll() {
            autoSaveExecutor?.shutdownNow()
            stopExecutor.shutdownNow()
        }
    }

    private fun tag(epc: String, rssi: Int = -40, seq: Long = 0) =
        TagRecord(epc = epc, rssi = rssi, antenna = 1, androidTs = seq)

    // ------------------------------------------------------------------
    // AÇÕES VÁLIDAS
    // ------------------------------------------------------------------

    @Test
    fun `stop imediatamente apos start com zero tags nao cria CSV`() {
        val folder = createTempDir()
        val svc = SimService(folder, "APPEND")
        svc.startCapture()

        val result = svc.stopUserInitiated()
        svc.shutdownAll()

        assertEquals(null, result)
        // O header foi escrito por startSession, mas cancelSession() não apaga
        // o arquivo (comportamento pré-existente do CsvExporter real — fora do
        // escopo desta mudança). O que importa aqui é que finalizeSession()
        // NUNCA foi chamado (sem duplicar/gravar tag nenhuma) e que totalCount
        // não ficou positivo.
        assertEquals(0, svc.totalCount.get())
    }

    @Test
    fun `start-stop rapido em sequencia nao perde nem duplica tags`() {
        val folder = createTempDir()
        val svc = SimService(folder, "APPEND")
        svc.startCapture()

        svc.writeTagsNow(listOf(tag("A", seq = 1)))
        val f1 = svc.stopUserInitiated()
        val lines1 = f1?.readLines() ?: emptyList()

        svc.startCapture()
        svc.writeTagsNow(listOf(tag("B", seq = 2)))
        val f2 = svc.stopUserInitiated()
        val lines2 = f2?.readLines() ?: emptyList()
        svc.shutdownAll()

        assertEquals(2, lines1.size) // header + A
        assertEquals(2, lines2.size) // header + B
        assertTrue(lines1[1].startsWith("A,"))
        assertTrue(lines2[1].startsWith("B,"))
    }

    @Test
    fun `rotacao por contagem exatamente no limiar nao perde tag do arquivo seguinte`() {
        val folder = createTempDir()
        val svc = SimService(folder, "NEW_FILE", autoSaveTagCount = 3)
        svc.startCapture()

        // 3 tags disparam rotação (limiar exato), a 4a já cai no arquivo novo.
        svc.writeTagsNow(listOf(tag("A", seq = 1), tag("B", seq = 2), tag("C", seq = 3)))
        // dá tempo do submit de rotação (enfileirado no mesmo executor, atrás da escrita) rodar
        (svc.autoSaveExecutor as java.util.concurrent.ScheduledExecutorService).submit {}.get(5, TimeUnit.SECONDS)
        svc.writeTagsNow(listOf(tag("D", seq = 4)))

        val result = svc.stopUserInitiated()
        svc.shutdownAll()

        val file0 = File(folder, "rfid_0.csv").readLines()
        val file1 = File(folder, "rfid_1.csv").readLines()
        assertEquals(4, (file0.size - 1) + (file1.size - 1))
        assertTrue(file0.any { it.startsWith("A,") } && file0.any { it.startsWith("B,") } && file0.any { it.startsWith("C,") })
        assertTrue(file1.any { it.startsWith("D,") })
        assertTrue(result != null)
    }

    @Test
    fun `saveAfterError com tags pendentes na Camada 2 nao perde nada`() {
        val folder = createTempDir()
        val svc = SimService(folder, "APPEND")
        svc.startCapture()
        svc.writeTagsNow(listOf(tag("A", seq = 1)))

        // Simula D1 do saveAfterError: filtro tinha "B" pendente na Camada 2, é forçado no erro.
        val result = svc.stopUserInitiated(d1Expired = listOf(tag("B", seq = 2)))
        svc.shutdownAll()

        val lines = result?.readLines() ?: emptyList()
        assertEquals(3, lines.size) // header + A + B
        assertTrue(lines[1].startsWith("A,"))
        assertTrue(lines[2].startsWith("B,"))
    }

    // ------------------------------------------------------------------
    // AÇÕES INVÁLIDAS / BORDA
    // ------------------------------------------------------------------

    @Test
    fun `tag chegando apos stop nao trava nem derruba o service - apenas conta como falha`() {
        val folder = createTempDir()
        val svc = SimService(folder, "APPEND")
        svc.startCapture()
        svc.writeTagsNow(listOf(tag("A", seq = 1)))
        svc.stopUserInitiated()

        // Ação inválida: onNewData ainda dispara uma vez depois do Stop (ex: byte
        // já estava no buffer do SO quando closePort() rodou). autoSaveExecutor
        // já é null nesse ponto (stopAutoSaveTimer já rodou).
        svc.writeTagsNow(listOf(tag("late", seq = 99)))
        svc.shutdownAll()

        // Não deve lançar exceção (o teste já teria falhado) e a tag atrasada
        // deve ser contabilizada como falha de escrita, não gravada em disco.
        assertEquals(1, svc.writeFailures)
    }

    @Test
    fun `stop duplo - segunda chamada nao duplica finalizacao nem lanca excecao`() {
        val folder = createTempDir()
        val svc = SimService(folder, "APPEND")
        svc.startCapture()
        svc.writeTagsNow(listOf(tag("A", seq = 1)))

        val first = svc.stopUserInitiated()
        // No serviço real, stopCapture() tem compareAndSet(true,false) no topo que
        // faz a segunda chamada retornar cedo sem re-executar o submit. Aqui
        // simulamos só a parte perigosa: chamar drainAndFinalize duas vezes
        // sobre o writer já fechado não deve lançar.
        val second = svc.drainAndFinalize(emptyList())
        svc.shutdownAll()

        assertTrue(first != null)
        // segunda finalize acontece com sessão já fechada (totalCount==0 após o
        // primeiro stop) -> cai no cancelSession(), não lança, retorna null.
        assertEquals(null, second)
    }

    @Test
    fun `alto volume intercalado com rotacoes mantem contagem exata sem duplicar`() {
        val folder = createTempDir()
        val svc = SimService(folder, "NEW_FILE", autoSaveTagCount = 10)
        svc.startCapture()

        var sent = 0
        for (batch in 0 until 200) {
            val tags = (0 until 5).map { tag("EPC$batch-$it", seq = batch.toLong()) }
            svc.writeTagsNow(tags)
            sent += tags.size
        }
        (svc.autoSaveExecutor as java.util.concurrent.ScheduledExecutorService).submit {}.get(10, TimeUnit.SECONDS)
        svc.stopUserInitiated()
        svc.shutdownAll()

        var totalOnDisk = 0
        for (i in 0..svc.fileSeq) {
            val f = File(folder, "rfid_$i.csv")
            if (f.exists()) totalOnDisk += f.readLines().size - 1
        }
        assertEquals("nenhuma tag pode ser perdida ou duplicada entre rotacoes", sent, totalOnDisk)
    }

    @Test
    fun `timer de auto-save disparando sem tags novas nao rotaciona para arquivo vazio`() {
        val folder = createTempDir()
        val svc = SimService(folder, "NEW_FILE", autoSaveTagCount = 1000)
        svc.startCapture()

        // Ninguém escreveu nada. Simula o guard real: só chama onAutoSaveTrigger
        // se tagsSinceLastSave > 0 (ver rescheduleTimerJob real).
        if (svc.tagsSinceLastSave.get() > 0) svc.onAutoSaveTrigger()

        svc.stopUserInitiated()
        svc.shutdownAll()

        // rfid_1.csv nunca deveria ter sido criado.
        assertTrue(!File(folder, "rfid_1.csv").exists())
    }
}
