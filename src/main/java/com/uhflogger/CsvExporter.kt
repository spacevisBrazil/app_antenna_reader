package com.uhflogger

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.uhflogger.model.TagRecord

object CsvExporter {

    private const val TAG = "CsvExporter"

    fun export(context: Context, tags: List<TagRecord>): String? {
        if (tags.isEmpty()) {
            Log.w(TAG, "Empty tag list, no file generated.")
            return null
        }

        val fileName = "RFID_CAPTURE_${System.currentTimeMillis()}.csv"

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null.also { Log.e(TAG, "Failed to create MediaStore entry") }

        return try {
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(TagRecord.CSV_HEADER)
                writer.newLine()
                tags.forEach { tag ->
                    writer.write(tag.toCsvLine())
                    writer.newLine()
                }
            }
            Log.i(TAG, "CSV exported: $fileName (${tags.size} tags)")
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error writing CSV", e)
            resolver.delete(uri, null, null)
            null
        }
    }
}
