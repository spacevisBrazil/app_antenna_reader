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

        file.bufferedReader().use { reader ->
            for ((index, line) in reader.lineSequence().withIndex()) {
                if (index == 0 || index <= startFrom) continue  // índice 0 = cabeçalho
                pendingIndex = index
                if (line.isNotBlank()) {
                    CsvReadingParser.parseLine(file.name, index, line)?.let { batch.add(it) }
                }
                if (batch.size >= MAX_BATCH) {
                    if (!sendBatch(captureId, batch, token)) { failed = true; break }
                    batch.clear()
                    confirmed = index
                    BackendUploadStore.setLinesSent(context, path, confirmed)
                }
            }
        }
        if (failed) return false

        if (batch.isNotEmpty()) {
            if (!sendBatch(captureId, batch, token)) return false
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
            // Sem instante de início não há captura válida a abrir (o backend
            // exige started_at). Arquivo com nome fora do padrão não é nosso.
            Log.w(TAG, "Ignorando ${file.name}: nome fora do padrão {prefixo}_{millis}.csv")
            BackendUploadStore.markClosed(context, file.absolutePath)
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

    private fun sendBatch(captureId: String, batch: List<JSONObject>, token: String): Boolean {
        val body = JSONObject().put("readings", JSONArray(batch))
        val url = "${BackendSettings.getBaseUrl(context)}/api/antenna/v1/farms/" +
                "${BackendSettings.getFarmId(context)}/captures/$captureId/readings/batch"

        val response = BackendApi.post(url, body, DeviceAuthManager.authHeaders(context, token))
        if (!response.isSuccess) {
            Log.w(TAG, "Lote de ${batch.size} leituras recusado (${response.status})")
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
