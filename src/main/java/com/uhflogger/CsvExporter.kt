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

    private var sessionUri    : Uri?           = null
    private var sessionWriter : BufferedWriter? = null
    private var sessionFile   : String?        = null

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

            sessionUri    = uri
            sessionWriter = writer
            sessionFile   = fileName
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
            tags.forEach { tag -> writer.write(tag.toCsvLine()); writer.newLine() }
            writer.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error appending tags", e)
            false
        }
    }

    fun finalizeSession(tags: List<TagRecord>): String? {
        appendTags(tags)
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
        sessionWriter = null
        sessionUri    = null
        sessionFile   = null
    }
}