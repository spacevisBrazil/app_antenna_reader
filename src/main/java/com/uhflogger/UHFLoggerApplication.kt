package com.uhflogger

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import java.util.concurrent.Executors

/**
 * Custom Application class — configures WorkManager to run with a
 * SINGLE-THREADED executor.
 *
 * This is the definitive fix for duplicate Drive uploads/folders:
 * by default WorkManager can run multiple workers concurrently
 * (e.g. a one-time scheduleNow() and a periodic check at the same instant).
 * Forcing a single-thread executor guarantees DriveUploadWorker.doWork()
 * NEVER runs in parallel with itself, eliminating the race condition
 * that created duplicate folders and duplicate file uploads.
 *
 * Combined with the synchronized locks in DriveUploadWorker and DriveHelper,
 * this provides defense-in-depth against any future concurrent-trigger scenario.
 */
class UHFLoggerApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .build()
}
