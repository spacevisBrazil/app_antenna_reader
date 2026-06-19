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
        // Process-wide lock — absolute guarantee that only one upload pass
        // runs at a time, regardless of how many WorkManager workers were triggered.
        // This is the real fix for duplicate folders/files: even if WorkManager
        // schedules a one-time AND a periodic worker at the same instant,
        // only one enters this block at a time.
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

                // Save driveFileId SYNCHRONOUSLY before deleting local file
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
        // Single shared work name — ensures WorkManager NEVER runs two
        // upload workers concurrently, regardless of trigger source
        // (manual scheduleNow, periodic check, or network reconnect).
        const val WORK_NAME = "drive_upload_serial"

        /**
         * Schedule a one-time upload — called immediately when a new CSV is created
         * or when network connectivity is restored.
         * Uses the SAME unique work name as schedulePeriodic to guarantee
         * only one upload worker ever runs at a time.
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
         * Schedule a periodic check every 15 minutes — catches any missed files.
         * Uses a SEPARATE periodic chain, but doWork() itself is protected by
         * a process-wide lock (see UPLOAD_LOCK) so it never overlaps with
         * a one-time scheduleNow() execution.
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

        // Process-wide lock — guarantees doWork() body never runs concurrently
        // even if WorkManager somehow schedules two workers at the same instant
        // (different unique-work chains: one-time vs periodic).
        val UPLOAD_LOCK = Any()
    }
}