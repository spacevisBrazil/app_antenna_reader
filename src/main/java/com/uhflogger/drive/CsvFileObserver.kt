package com.uhflogger.drive

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.uhflogger.CsvExporter
import java.io.File

/**
 * Monitora a pasta de CSVs do app em busca de novos arquivos.
 * Ao detectar o evento CLOSE_WRITE (arquivo completamente gravado), enfileira para upload no Drive.
 * Usa CLOSE_WRITE (não CREATE) para garantir que o arquivo está completo antes do upload.
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
        // Segundo destino, independente: se estiver desligado, scheduleNow é
        // no-op e nada muda. Os dois leem o mesmo arquivo sem se coordenar —
        // só a exclusão dele é combinada (FileRetention).
        com.uhflogger.backend.BackendUploadWorker.scheduleNow(context)
    }

    companion object {
        private const val TAG = "CsvFileObserver"

        fun getCsvFolder(context: Context): File =
            CsvExporter.getCsvFolder(context)
    }
}
