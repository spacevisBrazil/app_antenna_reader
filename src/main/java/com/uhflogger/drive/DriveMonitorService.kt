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

        // Schedule periodic background check on service start
        DriveUploadWorker.schedulePeriodic(this)

        // Also try to upload any files that were pending before restart
        DriveUploadWorker.scheduleNow(this)
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
