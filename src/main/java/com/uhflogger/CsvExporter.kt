package com.uhflogger

import android.content.Context
import android.util.Log
import com.uhflogger.model.TagRecord
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private const val TAG = "CsvExporter"

    private var sessionWriter   : BufferedWriter? = null
    private var sessionFile     : String?         = null
    // @Volatile: lido pelo worker de envio ao backend (thread do WorkManager)
    // pra saber qual arquivo AINDA está sendo escrito — esse não pode ser
    // fechado no servidor nem apagado do disco. Escrito pela thread de captura.
    @Volatile private var sessionFilePath : String? = null
    // Prefix/context da sessão aberta, mas ainda sem arquivo em disco — ver startSession().
    private var pendingPrefix   : String?         = null
    private var pendingContext  : Context?        = null
    // Última tag mantida em memória (ainda não serializada) — gravada com temperatura aplicada
    // ao finalizar a sessão. Guardar o TagRecord em vez da linha CSV já pronta evita depender
    // de qual coluna é "a última" (o código antigo concatenava ",$stopTemperature" no fim da
    // string, o que quebrava ao adicionar colunas depois de Temperature).
    private var pendingLastTag  : TagRecord?       = null

    /**
     * prefix: identificador usado no nome do arquivo (ex: MAC do BT sem ":", ou nome do dispositivo).
     * Arquivos salvos em: /sdcard/Android/data/com.uhflogger/files/csv/
     * Não requer permissão de armazenamento (scoped storage, Android 10+).
     * O FileObserver monitora esta pasta e dispara o upload para o Drive automaticamente.
     *
     * Lazy: não toca em disco aqui — só guarda prefix/context. O arquivo só é criado na
     * primeira tag real (appendTags) ou, se o Stop chegar antes de qualquer appendTags,
     * dentro de finalizeSession() (ver caso D1). Isso garante que (1) uma sessão sem
     * nenhuma tag nunca deixa um CSV vazio no disco (CLAUDE.md #16) e (2) o nome do
     * arquivo usa o timestamp da primeira tag real, não o momento em que a captura foi
     * iniciada — evita o descompasso de data quando a captura fica horas sem ler nada
     * antes da primeira tag do dia (CLAUDE.md #17).
     */
    fun startSession(context: Context, prefix: String = "rfid") {
        closeWriter()
        pendingPrefix  = prefix
        pendingContext = context
        Log.i(TAG, "Session prepared (lazy): prefix=$prefix — file created on first tag")
    }

    fun appendTags(tags: List<TagRecord>): Boolean {
        if (tags.isEmpty()) return true
        val writer = sessionWriter ?: createFile(tags.first()) ?: return false.also { Log.e(TAG, "No active session") }
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
     * Finaliza a sessão e sinaliza que o arquivo está pronto para upload no Drive.
     * Se stopTemperature for fornecida (apenas Winnix), é aplicada à última linha de tag.
     *
     * Se nenhuma tag foi gravada nem está pendente (nem appendTags foi chamado, nem `tags`
     * veio com dado agora), nenhum arquivo é criado — retorna null e não sobra CSV vazio
     * no disco. Se `tags` vier não-vazio mas o arquivo ainda não existe (Stop forçado antes
     * de qualquer appendTags), cria o arquivo agora usando o timestamp da primeira tag do lote.
     */
    fun finalizeSession(tags: List<TagRecord>, stopTemperature: String = ""): String? {
        if (sessionWriter == null && tags.isNotEmpty()) {
            createFile(tags.first())
        }

        val writer = sessionWriter
        if (writer == null) {
            Log.i(TAG, "Session finalized — no tags written, no file created")
            closeWriter()
            return null
        }

        try {
            if (tags.isEmpty()) {
                // Lote final vazio — aplica temperatura à pendingLastTag, se houver
                val pending = pendingLastTag
                val record = if (stopTemperature.isNotEmpty() && pending != null)
                    pending.copy(temperature = stopTemperature)
                else
                    pending
                record?.let { writer.write(it.toCsvLine()); writer.newLine() }
                pendingLastTag = null
            } else {
                // Grava pendingLastTag sem temperatura (não é a última tag da sessão)
                pendingLastTag?.let { writer.write(it.toCsvLine()); writer.newLine() }
                pendingLastTag = null

                for (i in 0 until tags.size - 1) {
                    writer.write(tags[i].toCsvLine())
                    writer.newLine()
                }

                // Última tag — aplica temperatura de encerramento, se fornecida
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

        val fileName = sessionFile
        Log.i(TAG, "Session finalized: $fileName — FileObserver will trigger upload")
        closeWriter()
        return fileName
    }

    /** Fecha e, se um arquivo chegou a ser criado nesta sessão, apaga — sessão cancelada não deixa CSV vazio pra trás. */
    fun cancelSession() {
        val path = sessionFilePath
        closeWriter()
        if (path != null) {
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    /**
     * Caminho do arquivo da sessão EM ANDAMENTO, ou null se nenhuma captura
     * está ativa OU se a captura ativa ainda não recebeu nenhuma tag (arquivo
     * ainda não existe — criação lazy). Quem envia pro backend usa isto pra não
     * fechar a captura no servidor (nem deixar apagar o arquivo) enquanto ainda
     * há leitura entrando.
     */
    fun activeFilePath(): String? = sessionFilePath

    /** Retorna (e cria se necessário) a pasta onde os arquivos CSV são armazenados */
    fun getCsvFolder(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "csv")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun createFile(firstTag: TagRecord): BufferedWriter? {
        val ctx    = pendingContext ?: return null.also { Log.e(TAG, "createFile: no context — session not started") }
        val prefix = pendingPrefix  ?: return null.also { Log.e(TAG, "createFile: no prefix — session not started") }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(firstTag.androidTs))
        val fileName  = "${prefix}_${timestamp}.csv"
        val folder    = getCsvFolder(ctx)
        val file      = File(folder, fileName)
        return try {
            val writer = BufferedWriter(FileWriter(file, false))
            writer.write(TagRecord.CSV_HEADER)
            writer.newLine()
            writer.flush()
            sessionWriter   = writer
            sessionFile     = fileName
            sessionFilePath = file.absolutePath
            Log.i(TAG, "File created: ${file.absolutePath}")
            writer
        } catch (e: Exception) {
            Log.e(TAG, "Error creating session file", e)
            null
        }
    }

    private fun closeWriter() {
        try { sessionWriter?.close() } catch (_: Exception) {}
        sessionWriter   = null
        sessionFile     = null
        sessionFilePath = null
        pendingPrefix   = null
        pendingContext  = null
        pendingLastTag  = null
    }
}
