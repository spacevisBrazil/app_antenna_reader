package com.uhflogger.drive

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.uhflogger.CsvExporter
import java.io.File

/**
 * Watches the app's CSV folder for new files.
 * When a CSV file is fully written (CLOSE_WRITE event), enqueues it for Drive upload.
 * Uses CLOSE_WRITE (not CREATE) to ensure the file is complete before uploading.
 */
class CsvFileObserver(
    private val context: Context,
    private val folder: File
) : FileObserver(folder.absolutePath, CLOSE_WRITE) {

    override fun onEvent(event: Int, path: String?) {
        if (path == null) return
        if (!path.endsWith(".csv", ignoreCase = true)) return
        if (event and CLOSE_WRITE == 0) return

        val fullPath = File(folder, path).absolutePath
        Log.i(TAG, "New CSV detected: $path — enqueueing upload")

        UploadQueueManager.enqueue(context, fullPath)
        DriveUploadWorker.scheduleNow(context)
    }

    companion object {
        private const val TAG = "CsvFileObserver"

        fun getCsvFolder(context: Context): File =
            CsvExporter.getCsvFolder(context)
    }
}
