package com.uhflogger.backend

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.uhflogger.CsvExporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Envia as leituras do CSV local pro backend SpaceVis.
 *
 * ROTEIRO POR ARQUIVO
 *   1. garante a captura aberta no servidor (idempotente por client_id);
 *   2. lê o arquivo a partir da primeira linha ainda não enviada;
 *   3. sobe em lotes de 500, gravando o progresso a cada lote aceito;
 *   4. fecha a captura — mas SÓ se o arquivo não for o da sessão em andamento.
 *
 * O passo 4 é o que separa este worker de um upload de arquivo: enquanto a
 * antena está lendo, o arquivo cresce, e o envio acompanha em vez de esperar o
 * fim. Numa captura de dias, o dado chega ao servidor no mesmo dia — não três
 * dias depois, que é o que acontece com o Drive no modo "atualizar arquivo
 * atual" (lá o arquivo só fecha no Parar).
 *
 * Convive com o Drive sem interferir: destinos independentes, filas separadas,
 * e a exclusão do arquivo local coordenada por FileRetention.
 */
class BackendUploadWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        // Mesmo racional do DriveUploadWorker: uma passada por vez, sempre.
        synchronized(UPLOAD_LOCK) {
            return doUploadPass()
        }
    }

    private fun doUploadPass(): Result {
        if (!BackendSettings.isEnabled(context)) {
            Log.d(TAG, "Envio ao backend desligado — nada a fazer")
            return Result.success()
        }

        val token = DeviceAuthManager.validAccessToken(context)
        if (token == null) {
            // Sem sessão utilizável: não adianta insistir em loop — a tela de
            // configuração precisa de uma chave nova. `retry` faria o
            // WorkManager bater no servidor de novo sem chance de sucesso.
            Log.w(TAG, "Sem sessão válida — reative o aparelho pela chave")
            return Result.failure()
        }

        val folder = CsvExporter.getCsvFolder(context)
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: return Result.success()

        var allOk = true

        for (file in files) {
            if (BackendUploadStore.get(context, file.absolutePath)?.closed == true) continue

            try {
                if (!processFile(file, token)) allOk = false
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao enviar ${file.name}", e)
                BackendUploadStore.setError(context, file.absolutePath, e.message?.take(300))
                allOk = false
            }
        }

        return if (allOk) Result.success() else Result.retry()
    }

    /** @return true se o arquivo terminou esta passada sem erro. */
    private fun processFile(file: File, token: String): Boolean {
        val path = file.absolutePath
        val entry = BackendUploadStore.ensure(
            context, path, CsvReadingParser.captureClientId(file.name)
        ) ?: return false

        // 1. Captura aberta no servidor.
        val captureId = entry.captureId ?: openCapture(file, entry, token) ?: return false
        if (entry.captureId == null) BackendUploadStore.setCaptureId(context, path, captureId)

        // 2/3. Envia o que falta, em lotes.
        //
        // `linesSent` é o índice da última linha CONFIRMADA pelo servidor, e só
        // avança depois que o lote é aceito. O arquivo cresce enquanto lemos (a
        // captura pode estar em andamento), então o avanço é sempre baseado no
        // que a iteração de fato leu — contar linhas de novo no fim marcaria
        // como enviado o que chegou depois.
        val startFrom = entry.linesSent
        var pendingIndex = startFrom   // última linha lida, ainda não confirmada
        var confirmed    = startFrom
        var failed       = false
        val batch = ArrayList<JSONObject>(MAX_BATCH)
        // Tamanho do que já está no lote. Medido ao somar, e não no fim, pra
        // fechar o lote ANTES de passar do teto — depois de montado seria tarde:
        // ou o envio falha, ou a leitura teria que voltar pro lote seguinte.
        var batchBytes = 0

        file.bufferedReader().use { reader ->
            for ((index, line) in reader.lineSequence().withIndex()) {
                if (index == 0 || index <= startFrom) continue  // índice 0 = cabeçalho
                pendingIndex = index
                if (line.isNotBlank()) {
                    CsvReadingParser.parseLine(file.name, index, line)?.let {
                        batch.add(it)
                        batchBytes += it.toString().length + 1   // +1 pela vírgula do array
                    }
                }
                if (batch.size >= MAX_BATCH || batchBytes >= MAX_BATCH_BYTES) {
                    if (!sendBatch(captureId, batch, token, path)) { failed = true; break }
                    batch.clear()
                    batchBytes = 0
                    confirmed = index
                    BackendUploadStore.setLinesSent(context, path, confirmed)
                }
            }
        }
        if (failed) return false

        if (batch.isNotEmpty()) {
            if (!sendBatch(captureId, batch, token, path)) return false
            confirmed = pendingIndex
            BackendUploadStore.setLinesSent(context, path, confirmed)
        } else if (pendingIndex > confirmed) {
            // Sobrou só linha em branco ou inválida no fim: avança mesmo assim,
            // senão essas linhas seriam relidas em toda passada, pra sempre.
            confirmed = pendingIndex
            BackendUploadStore.setLinesSent(context, path, confirmed)
        }

        // 4. Fecha — só quando a captura acabou de verdade.
        val isActive = CsvExporter.activeFilePath() == path
        if (!isActive) {
            if (!closeCapture(captureId, token)) return false
            BackendUploadStore.markClosed(context, path)
            Log.i(TAG, "Captura de ${file.name} fechada no servidor")
            FileRetention.onBackendDone(context, file)
        }
        return true
    }

    private fun openCapture(file: File, entry: BackendUploadEntry, token: String): String? {
        val startedAt = CsvReadingParser.startedAtFromFileName(file.name)
        if (startedAt == null) {
            // Sem instante de início não há captura a abrir — o backend exige
            // started_at.
            //
            // Antes isto marcava o arquivo como `closed`, e aí o FileRetention
            // via "backend terminou" e deixava o Drive apagá-lo: um CSV cujo
            // nome não casasse com o padrão era descartado sem NUNCA ter sido
            // enviado, em silêncio. O arquivo agora fica onde está, com o motivo
            // à vista na tela, e alguém decide o que fazer com ele. Ficar preso
            // é recuperável; apagado não é.
            Log.w(TAG, "Sem started_at em ${file.name}: nome fora do padrão {prefixo}_{yyyyMMdd_HHmmss}.csv")
            BackendUploadStore.setError(
                context, file.absolutePath,
                "nome fora do padrão — o servidor não aceita captura sem data de início",
            )
            return null
        }

        // `transport` (USB/BT) e `temp_start` vão vazios de propósito, não por
        // esquecimento: os dois pertencem ao MOMENTO da captura, e o CSV não os
        // registra. O worker roda depois — às vezes dias depois, com o aparelho
        // já ligado noutro transporte —, então preencher com o estado atual
        // gravaria uma informação errada no lugar de uma ausente. A temperatura
        // não se perde: ela viaja na própria leitura (coluna Temperature).
        val body = JSONObject().apply {
            put("client_id", entry.captureClientId)
            put("started_at", startedAt)
            put("source_file", file.name)
            put("reader_id", DeviceIdentity.readerId(context))
            put("reader_name", DeviceIdentity.readerName(context))
            // O modelo NÃO sai mais do nome do arquivo: o prefixo passou a ser o
            // nome do aparelho (USB) ou o MAC do leitor (BT), que é melhor pra
            // identificar o COLETOR, mas não diz a antena. Vem da configuração,
            // com a mesma ressalva do `transport` — é o estado atual, não o do
            // momento da captura.
            DeviceIdentity.antennaModel(context)?.let { put("antenna_model", it) }
            put("config", DeviceIdentity.captureConfig(context))
        }

        val url = "${BackendSettings.getBaseUrl(context)}/api/antenna/v1/farms/" +
                "${BackendSettings.getFarmId(context)}/captures"
        val response = BackendApi.post(url, body, DeviceAuthManager.authHeaders(context, token))

        if (!response.isSuccess) {
            Log.w(TAG, "Não abriu captura de ${file.name} (${response.status})")
            return null
        }
        return response.json()?.optString("id")?.ifEmpty { null }
    }

    /**
     * O `path` entra aqui só pra gravar o MOTIVO de uma recusa. Sem isso o lote
     * recusado morria num Log.w que ninguém em campo lê: a tela seguia dizendo
     * "Ativado e enviando" com o envio parado havia dias, e descobrir o porquê
     * exigia ir atrás de log de servidor. Lote recusado é a falha mais provável
     * do módulo — tem que dar pra ver no aparelho.
     */
    private fun sendBatch(
        captureId: String,
        batch    : List<JSONObject>,
        token    : String,
        path     : String,
    ): Boolean {
        val body = JSONObject().put("readings", JSONArray(batch))
        val url = "${BackendSettings.getBaseUrl(context)}/api/antenna/v1/farms/" +
                "${BackendSettings.getFarmId(context)}/captures/$captureId/readings/batch"

        val response = BackendApi.post(url, body, DeviceAuthManager.authHeaders(context, token))
        if (!response.isSuccess) {
            val motivo = when (response.status) {
                0    -> "sem conexão"
                401  -> "sessão recusada (401)"
                413  -> "lote grande demais para o servidor (413)"
                else -> "recusado pelo servidor (${response.status})"
            }
            Log.w(TAG, "Lote de ${batch.size} leituras recusado: $motivo")
            BackendUploadStore.setError(context, path, "${batch.size} leituras: $motivo")
            return false
        }
        Log.i(TAG, "Lote enviado: ${batch.size} leituras → captura $captureId")
        return true
    }

    private fun closeCapture(captureId: String, token: String): Boolean {
        val url = "${BackendSettings.getBaseUrl(context)}/api/antenna/v1/farms/" +
                "${BackendSettings.getFarmId(context)}/captures/$captureId/close"
        val response = BackendApi.post(url, JSONObject(), DeviceAuthManager.authHeaders(context, token))
        if (!response.isSuccess) {
            Log.w(TAG, "Não fechou a captura $captureId (${response.status})")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "BackendUploadWorker"
        private const val MAX_BATCH = 500   // mesmo teto do endpoint

        // Teto por TAMANHO, que é por onde o envio realmente quebrava: o
        // endpoint aceita 500 leituras, mas o `bodyParser.json()` do backend
        // corta o corpo em 100 kb, e um lote cheio dá ~125 kb. O 413 vinha do
        // middleware, ANTES do handler — então o teto anunciado nunca era
        // alcançado e nenhuma captura grande subia.
        //
        // Contar item não protege disso; contar byte sim. Com 64 kb o lote cabe
        // com folga em qualquer limite plausível do servidor, inclusive no
        // default de 100 kb — ou seja, o aparelho volta a enviar sem depender de
        // deploy nenhum do lado de lá. O que vier primeiro (itens ou bytes)
        // fecha o lote.
        private const val MAX_BATCH_BYTES = 64 * 1024

        const val WORK_NAME          = "backend_upload_serial"
        private const val WORK_NAME_PERIODIC = "backend_upload_periodic"

        val UPLOAD_LOCK = Any()

        fun scheduleNow(context: Context) {
            if (!BackendSettings.isEnabled(context)) return
            val request = OneTimeWorkRequestBuilder<BackendUploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * A cada 15 min: é isto que faz o envio ACOMPANHAR uma captura longa em
         * vez de esperar o arquivo fechar. Sem ele, o modo "atualizar arquivo
         * atual" só entregaria o dado no Parar.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackendUploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
