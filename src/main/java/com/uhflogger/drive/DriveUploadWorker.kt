package com.uhflogger.drive

import android.content.Context
import android.util.Log
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit

class DriveUploadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        // Trava de processo — garante que apenas um upload rode por vez,
        // independentemente de quantos workers o WorkManager tiver acionado.
        // Previne duplicatas de arquivos no Drive mesmo com workers concorrentes.
        synchronized(UPLOAD_LOCK) {
            return doUploadPass()
        }
    }

    private fun doUploadPass(): Result {
        if (!DriveHelper.isSignedIn(context)) {
            Log.w(TAG, "Not signed in — skipping upload")
            return Result.failure()
        }

        val drive = DriveHelper.getDriveService(context) ?: run {
            Log.e(TAG, "Could not build Drive service")
            return Result.retry()
        }

        val pending = UploadQueueManager.getPending(context)
        if (pending.isEmpty()) {
            Log.i(TAG, "No pending files to upload")
            return Result.success()
        }

        Log.i(TAG, "Uploading ${pending.size} pending file(s)")
        var allOk = true

        for (entry in pending) {
            val file = File(entry.filePath)
            if (!file.exists()) {
                Log.w(TAG, "File not found, removing from queue: ${entry.filePath}")
                UploadQueueDatabase.get(context).dao().delete(entry.id)
                continue
            }
            try {
                val driveFileId = DriveHelper.uploadCsv(context, drive, file)

                // Persiste o driveFileId antes de apagar o arquivo local —
                // garante que um crash entre os dois passos não perca o registro do upload.
                UploadQueueDatabase.get(context).dao().setDriveFileId(entry.id, driveFileId)

                file.delete()
                Log.i(TAG, "Upload OK + local deleted: ${file.name}")

                UploadQueueDatabase.get(context).dao().delete(entry.id)

            } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                if (e.statusCode == 404) {
                    Log.w(TAG, "Drive folder not found (404) — clearing cache for rebuild")
                    DriveHelper.clearFolderCache(context)
                }
                Log.e(TAG, "Upload failed for ${file.name}: ${e.message}")
                UploadQueueDatabase.get(context).dao()
                    .updateStatus(entry.id, UploadStatus.FAILED)
                allOk = false
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed for ${file.name}: ${e.message}")
                UploadQueueDatabase.get(context).dao()
                    .updateStatus(entry.id, UploadStatus.FAILED)
                allOk = false
            }
        }

        return if (allOk) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG       = "DriveUploadWorker"
        // Nome único compartilhado — garante que o WorkManager nunca rode dois
        // workers de upload ao mesmo tempo, independente da origem do disparo.
        const val WORK_NAME = "drive_upload_serial"

        /**
         * Agenda um upload imediato — chamado quando um novo CSV é criado
         * ou quando a conectividade de rede é restaurada.
         */
        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Log.d(TAG, "Upload work scheduled")
        }

        /**
         * Agenda uma verificação periódica a cada 15 minutos — captura arquivos
         * que possam ter ficado pendentes entre uploads imediatos.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DriveUploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Periodic upload work scheduled (15 min)")
        }

        private const val WORK_NAME_PERIODIC = "drive_upload_periodic"

        // Trava de processo — garante que o corpo do doWork() nunca rode concorrentemente,
        // mesmo que o WorkManager agende dois workers ao mesmo instante
        // (cadeias distintas: one-time vs periodic).
        val UPLOAD_LOCK = Any()
    }
}