package com.uhflogger

import android.content.Context
import android.util.Log
import com.uhflogger.model.TagRecord
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

object CsvExporter {

    private const val TAG = "CsvExporter"

    private var sessionWriter   : BufferedWriter? = null
    private var sessionFile     : String?         = null
    private var sessionFilePath : String?         = null
    // Last tag held in memory (not yet serialized) — flushed with temperature
    // applied when session finalizes. Guardar o TagRecord em vez da linha CSV
    // já pronta evita depender de qual coluna é "a última" (era uma pegadinha:
    // o código antigo concatenava ",$stopTemperature" no fim da string, o que
    // quebraria assim que qualquer coluna fosse adicionada depois de Temperature).
    private var pendingLastTag  : TagRecord?       = null

    /**
     * prefix: "jietong" or "winnix" — used in filename.
     * Files saved to: /sdcard/Android/data/com.uhflogger/files/csv/
     * No storage permission needed (scoped storage, Android 10+).
     * FileObserver watches this folder and triggers Drive upload automatically.
     */
    fun startSession(context: Context, prefix: String = "rfid"): String? {
        closeWriter()
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val fileName = "${prefix}_${timestamp}.csv"
        val folder   = getCsvFolder(context)
        val file     = File(folder, fileName)

        return try {
            val writer = BufferedWriter(FileWriter(file, false))
            writer.write(TagRecord.CSV_HEADER)
            writer.newLine()
            writer.flush()

            sessionWriter   = writer
            sessionFile     = fileName
            sessionFilePath = file.absolutePath
            pendingLastTag  = null
            Log.i(TAG, "Session started: ${file.absolutePath}")
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error starting session", e)
            null
        }
    }

    fun appendTags(tags: List<TagRecord>): Boolean {
        if (tags.isEmpty()) return true
        val writer = sessionWriter ?: return false.also { Log.e(TAG, "No active session") }
        return try {
            pendingLastTag?.let { writer.write(it.toCsvLine()); writer.newLine() }
            pendingLastTag = null

            // Retém a última tag para que a temperatura possa ser aplicada depois
            for (i in 0 until tags.size - 1) {
                writer.write(tags[i].toCsvLine())
                writer.newLine()
            }
            pendingLastTag = tags.last()
            writer.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error appending tags", e)
            false
        }
    }

    /**
     * Finalize session and signal file is ready for Drive upload.
     * If stopTemperature is provided (Winnix only), it is applied to the last tag line.
     */
    fun finalizeSession(tags: List<TagRecord>, stopTemperature: String = ""): String? {
        val writer = sessionWriter
        if (writer != null) {
            try {
                if (tags.isEmpty()) {
                    // Final batch is empty — apply temperature to the pendingLastTag if available
                    val pending = pendingLastTag
                    val record = if (stopTemperature.isNotEmpty() && pending != null)
                        pending.copy(temperature = stopTemperature)
                    else
                        pending
                    record?.let { writer.write(it.toCsvLine()); writer.newLine() }
                    pendingLastTag = null
                } else {
                    // Flush pending tag without temperature (not the last tag overall)
                    pendingLastTag?.let { writer.write(it.toCsvLine()); writer.newLine() }
                    pendingLastTag = null

                    for (i in 0 until tags.size - 1) {
                        writer.write(tags[i].toCsvLine())
                        writer.newLine()
                    }

                    // Last tag — apply stop temperature if provided
                    val lastTag = if (stopTemperature.isNotEmpty())
                        tags.last().copy(temperature = stopTemperature)
                    else
                        tags.last()
                    writer.write(lastTag.toCsvLine())
                    writer.newLine()
                }
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing final batch", e)
            }
        }

        val fileName = sessionFile
        Log.i(TAG, "Session finalized: $fileName — FileObserver will trigger upload")
        closeWriter()
        return fileName
    }

    fun cancelSession() = closeWriter()

    /** Returns (and creates if needed) the folder where CSV files are stored */
    fun getCsvFolder(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "csv")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun closeWriter() {
        try { sessionWriter?.close() } catch (_: Exception) {}
        sessionWriter   = null
        sessionFile     = null
        sessionFilePath = null
        pendingLastTag  = null
    }
}