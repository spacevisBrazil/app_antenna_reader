package com.uhflogger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.uhflogger.model.TagRecord
import java.io.BufferedWriter
import java.io.OutputStreamWriter

object CsvExporter {

    private const val TAG = "CsvExporter"

    private var sessionUri        : Uri?           = null
    private var sessionWriter     : BufferedWriter? = null
    private var sessionFile       : String?        = null
    // Last tag line held in memory — flushed with temperature when session finalizes
    private var pendingLastLine   : String?        = null

    // -------------------------------------------------------------------------
    // Session API
    // -------------------------------------------------------------------------

    /** prefix: "jietong" or "winnix" — used in filename */
    fun startSession(context: Context, prefix: String = "rfid"): String? {
        closeWriter()
        val fileName = "${prefix}_${System.currentTimeMillis()}.csv"

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null.also { Log.e(TAG, "Failed to create MediaStore entry") }

        return try {
            val outputStream = resolver.openOutputStream(uri, "wa")
                ?: return null.also { Log.e(TAG, "Failed to open output stream") }

            val writer = BufferedWriter(OutputStreamWriter(outputStream))
            writer.write(TagRecord.CSV_HEADER)
            writer.newLine()
            writer.flush()

            sessionUri       = uri
            sessionWriter    = writer
            sessionFile      = fileName
            pendingLastLine  = null
            Log.i(TAG, "Session started: $fileName")
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error starting session", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    fun appendTags(tags: List<TagRecord>): Boolean {
        if (tags.isEmpty()) return true
        val writer = sessionWriter ?: return false.also { Log.e(TAG, "No active session") }
        return try {
            // Flush any previously held last line before writing new tags
            pendingLastLine?.let { writer.write(it); writer.newLine() }
            pendingLastLine = null

            // Write all but the last tag immediately
            // Hold the last tag as pendingLastLine so temperature can be applied later
            for (i in 0 until tags.size - 1) {
                writer.write(tags[i].toCsvLine())
                writer.newLine()
            }
            pendingLastLine = tags.last().toCsvLine()
            writer.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error appending tags", e)
            false
        }
    }

    /**
     * Finalize session.
     * If stopTemperature is provided (Winnix only), it is applied to the last tag line.
     */
    fun finalizeSession(tags: List<TagRecord>, stopTemperature: String = ""): String? {
        val writer = sessionWriter
        if (writer != null) {
            try {
                if (tags.isEmpty()) {
                    // Final batch is empty — apply temperature to the pendingLastLine if available
                    val line = if (stopTemperature.isNotEmpty() && pendingLastLine != null)
                        pendingLastLine!! + ",$stopTemperature"
                    else
                        pendingLastLine
                    line?.let { writer.write(it); writer.newLine() }
                    pendingLastLine = null
                } else {
                    // Flush pending line without temperature (not the last tag overall)
                    pendingLastLine?.let { writer.write(it); writer.newLine() }
                    pendingLastLine = null

                    // Write all but last of the final batch
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
        closeWriter()
        Log.i(TAG, "Session finalized: $fileName")
        return fileName
    }

    fun cancelSession() = closeWriter()

    // -------------------------------------------------------------------------
    // Legacy
    // -------------------------------------------------------------------------
    fun export(context: Context, tags: List<TagRecord>): String? {
        if (tags.isEmpty()) return null
        val fileName = "rfid_${System.currentTimeMillis()}.csv"
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(TagRecord.CSV_HEADER); writer.newLine()
                tags.forEach { writer.write(it.toCsvLine()); writer.newLine() }
            }
            fileName
        } catch (e: Exception) {
            resolver.delete(uri, null, null); null
        }
    }

    private fun closeWriter() {
        try { sessionWriter?.close() } catch (_: Exception) {}
        sessionWriter  = null
        sessionUri     = null
        sessionFile    = null
        pendingLastLine = null
    }
}