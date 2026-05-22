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

/**
 * Gerencia a escrita incremental de um arquivo CSV durante a captura.
 *
 * Fluxo:
 *   startSession()        — chamado no startCapture, cria o arquivo
 *   appendTags(tags)      — chamado pelo auto-save, adiciona lote ao arquivo
 *   finalizeSession(tags) — chamado no stop, grava o último lote e fecha
 *   export(ctx, tags)     — método legado mantido para compatibilidade
 */
object CsvExporter {

    private const val TAG = "CsvExporter"

    // Estado da sessão atual
    private var sessionUri    : Uri?           = null
    private var sessionWriter : BufferedWriter? = null
    private var sessionFile   : String?        = null
    private var sessionContext: Context?       = null

    // -------------------------------------------------------------------------
    // API de sessão incremental
    // -------------------------------------------------------------------------

    /** Abre um novo arquivo CSV para a sessão de captura. Chame no startCapture. */
    fun startSession(context: Context): String? {
        closeWriter() // garante que sessão anterior foi fechada

        val fileName = "RFID_CAPTURE_${System.currentTimeMillis()}.csv"

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: run { Log.e(TAG, "Failed to create MediaStore entry"); return null }

        return try {
            val outputStream = resolver.openOutputStream(uri, "wa") // "wa" = write+append
                ?: run { Log.e(TAG, "Failed to open output stream"); return null }

            val writer = BufferedWriter(OutputStreamWriter(outputStream))
            writer.write(TagRecord.CSV_HEADER)
            writer.newLine()
            writer.flush()

            sessionUri     = uri
            sessionWriter  = writer
            sessionFile    = fileName
            sessionContext = context

            Log.i(TAG, "Session started: $fileName")
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error starting session", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Grava um lote de tags no arquivo aberto.
     * Chamado pelo auto-save — não bloqueia a leitura pois roda em thread separada.
     */
    fun appendTags(tags: List<TagRecord>): Boolean {
        if (tags.isEmpty()) return true
        val writer = sessionWriter ?: run {
            Log.e(TAG, "appendTags: no active session")
            return false
        }
        return try {
            tags.forEach { tag ->
                writer.write(tag.toCsvLine())
                writer.newLine()
            }
            writer.flush()
            Log.i(TAG, "Appended ${tags.size} tags to $sessionFile")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error appending tags", e)
            false
        }
    }

    /**
     * Grava o último lote pendente e fecha o arquivo.
     * Chame no stopCapture. Retorna o nome do arquivo salvo.
     */
    fun finalizeSession(tags: List<TagRecord>): String? {
        appendTags(tags)   // grava o que sobrou
        val fileName = sessionFile
        closeWriter()
        Log.i(TAG, "Session finalized: $fileName")
        return fileName
    }

    /** Fecha o writer sem gravar nada extra (usado em cancelamentos). */
    fun cancelSession() {
        closeWriter()
    }

    // -------------------------------------------------------------------------
    // Método legado — mantido para compatibilidade
    // -------------------------------------------------------------------------
    fun export(context: Context, tags: List<TagRecord>): String? {
        if (tags.isEmpty()) { Log.w(TAG, "Empty tag list"); return null }

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
                tags.forEach { tag -> writer.write(tag.toCsvLine()); writer.newLine() }
            }
            Log.i(TAG, "CSV exported: $fileName (${tags.size} tags)")
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error writing CSV", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Interno
    // -------------------------------------------------------------------------
    private fun closeWriter() {
        try { sessionWriter?.close() } catch (_: Exception) {}
        sessionWriter  = null
        sessionUri     = null
        sessionFile    = null
        sessionContext = null
    }
}