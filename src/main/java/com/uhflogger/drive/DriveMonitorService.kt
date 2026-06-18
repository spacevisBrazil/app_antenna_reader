package com.uhflogger.drive

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.uhflogger.MainActivity

class DriveMonitorService : Service() {

    private var fileObserver: CsvFileObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        val folder = CsvFileObserver.getCsvFolder(this)
        fileObserver = CsvFileObserver(this, folder).also { it.startWatching() }
        Log.i(TAG, "Monitoring: ${folder.absolutePath}")

        // Scan folder for any CSV files that exist but were never enqueued.
        // This handles: DB migration wipe, FileObserver miss, crash after write.
        // Uses IGNORE conflict strategy so already-queued files are not duplicated.
        scanAndEnqueueExistingFiles(folder)

        // Schedule periodic background check
        DriveUploadWorker.schedulePeriodic(this)
        // Try to upload anything pending immediately
        DriveUploadWorker.scheduleNow(this)
    }

    /**
     * Scans the CSV folder and enqueues any file not already in the upload queue.
     * Safe to call on every startup — insert uses IGNORE on conflict.
     */
    private fun scanAndEnqueueExistingFiles(folder: java.io.File) {
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
        if (files.isNullOrEmpty()) return
        Log.i(TAG, "Startup scan: found ${files.size} CSV file(s) in folder")
        for (file in files) {
            UploadQueueManager.enqueue(this, file.absolutePath)
            Log.i(TAG, "  Enqueued (startup scan): ${file.name}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // restart automatically if killed
    }

    override fun onDestroy() {
        fileObserver?.stopWatching()
        fileObserver = null
        Log.i(TAG, "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Drive Sync", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Monitorando CSVs para upload automático"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("UHF Logger — Drive Sync")
            .setContentText("Monitorando novos CSVs para upload automático")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG       = "DriveMonitorService"
        private const val CHANNEL_ID= "drive_monitor_channel"
        private const val NOTIF_ID  = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DriveMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveMonitorService::class.java))
        }
    }
}