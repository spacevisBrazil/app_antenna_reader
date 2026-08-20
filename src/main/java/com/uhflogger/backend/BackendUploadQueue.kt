package com.uhflogger.backend

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.uhflogger.drive.UploadQueueDatabase

/**
 * Estado do envio de UM arquivo CSV ao backend.
 *
 * Uma linha por arquivo — não por leitura. As leituras já estão no CSV; o que
 * falta guardar é só o progresso (`linesSent`) e o desfecho. É isso que permite
 * retomar um envio interrompido sem reenviar o que já subiu, e sem manter uma
 * segunda cópia das leituras em banco.
 *
 * `driveDone`/`closed` existem pra coordenar a EXCLUSÃO do arquivo local: com
 * dois destinos, quem terminar primeiro não pode apagar o arquivo debaixo do
 * outro. Ver FileRetention.
 */
@Entity(tableName = "backend_upload")
data class BackendUploadEntry(
    @PrimaryKey val filePath: String,
    val captureClientId: String,
    // Id devolvido pelo backend na abertura da captura. Guardado pra não
    // reabrir a captura a cada ciclo do worker.
    val captureId  : String?  = null,
    // Índice da última linha do arquivo CONFIRMADA pelo servidor (é índice de
    // linha, contando o cabeçalho como 0 — não contagem de leituras).
    val linesSent  : Int      = 0,
    // Captura fechada no backend: nada mais a enviar deste arquivo.
    val closed     : Boolean  = false,
    val driveDone  : Boolean  = false,
    val lastError  : String?  = null,
    val updatedAt  : Long     = System.currentTimeMillis(),
    // Fazenda deste arquivo, congelada quando ele entrou na fila.
    //
    // NÃO se lê a fazenda "atual" na hora de enviar: o operador pode trocar de
    // fazenda no seletor com CSVs ainda pendentes, e aí leitura feita na Santa
    // Martha entraria como se fosse da Demonstração — sem erro, sem aviso, e
    // impossível de descobrir depois olhando o banco. O arquivo carrega a
    // fazenda em que foi capturado, do começo ao fim.
    //
    // 0 = linha criada antes desta coluna existir; o worker adota a fazenda
    // atual na primeira passada e grava (ver processFile).
    val farmId     : Int      = 0,
)

/**
 * Sem valores-padrão nos parâmetros: o Room gera a implementação destes métodos
 * e argumento default em método abstrato de DAO é caminho de atrito conhecido.
 * O carimbo de tempo é preenchido por BackendUploadStore, que é quem o resto do
 * código usa — mesmo arranjo do UploadQueueManager para a fila do Drive.
 */
@Dao
interface BackendUploadDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(entry: BackendUploadEntry): Long

    @Query("SELECT * FROM backend_upload WHERE filePath = :filePath")
    fun get(filePath: String): BackendUploadEntry?

    @Query("UPDATE backend_upload SET captureId = :captureId, updatedAt = :now WHERE filePath = :filePath")
    fun setCaptureId(filePath: String, captureId: String, now: Long)

    @Query("UPDATE backend_upload SET linesSent = :linesSent, lastError = NULL, updatedAt = :now WHERE filePath = :filePath")
    fun setLinesSent(filePath: String, linesSent: Int, now: Long)

    @Query("UPDATE backend_upload SET closed = 1, lastError = NULL, updatedAt = :now WHERE filePath = :filePath")
    fun markClosed(filePath: String, now: Long)

    @Query("UPDATE backend_upload SET driveDone = 1, updatedAt = :now WHERE filePath = :filePath")
    fun markDriveDone(filePath: String, now: Long)

    @Query("UPDATE backend_upload SET lastError = :error, updatedAt = :now WHERE filePath = :filePath")
    fun setError(filePath: String, error: String?, now: Long)

    @Query("UPDATE backend_upload SET farmId = :farmId, updatedAt = :now WHERE filePath = :filePath")
    fun setFarmId(filePath: String, farmId: Int, now: Long)

    @Query("DELETE FROM backend_upload WHERE filePath = :filePath")
    fun delete(filePath: String)

    @Query("SELECT COUNT(*) FROM backend_upload WHERE closed = 0")
    fun openCount(): Int

    // Erro mais recente entre os arquivos que ainda não terminaram. `setLinesSent`
    // limpa o campo quando um lote passa, então o que sobra aqui é sempre uma
    // falha ATUAL — nunca a lembrança de uma que já se resolveu.
    @Query("SELECT lastError FROM backend_upload WHERE closed = 0 AND lastError IS NOT NULL ORDER BY updatedAt DESC LIMIT 1")
    fun lastError(): String?
}

/**
 * Fachada do estado de envio. Todas as chamadas são SÍNCRONAS e devem rodar
 * fora da UI thread — quem usa é o worker (thread do WorkManager) e o
 * FileRetention (chamado de dentro de workers). É de propósito: o progresso
 * precisa estar em disco antes do próximo lote sair, senão uma morte do
 * processo faria reenviar o que já subiu.
 */
object BackendUploadStore {

    private fun dao(context: Context): BackendUploadDao =
        UploadQueueDatabase.get(context).backendDao()

    private fun now() = System.currentTimeMillis()

    /**
     * @param farmId fazenda em que este arquivo foi capturado — gravada na
     *        criação da linha e nunca mais relida do estado global.
     */
    fun ensure(
        context: Context,
        filePath: String,
        captureClientId: String,
        farmId: Int,
    ): BackendUploadEntry? {
        dao(context).insertIfAbsent(
            BackendUploadEntry(filePath = filePath, captureClientId = captureClientId, farmId = farmId)
        )
        return dao(context).get(filePath)
    }

    fun setFarmId(context: Context, filePath: String, farmId: Int) =
        dao(context).setFarmId(filePath, farmId, now())

    fun get(context: Context, filePath: String): BackendUploadEntry? = dao(context).get(filePath)

    /** Arquivos que ainda não terminaram de subir — o que a tela mostra. */
    fun openCount(context: Context): Int = dao(context).openCount()

    /** Por que o envio está parado, se estiver. Ver BackendUploadDao.lastError. */
    fun lastError(context: Context): String? = dao(context).lastError()

    fun setCaptureId(context: Context, filePath: String, captureId: String) =
        dao(context).setCaptureId(filePath, captureId, now())

    fun setLinesSent(context: Context, filePath: String, linesSent: Int) =
        dao(context).setLinesSent(filePath, linesSent, now())

    fun markClosed(context: Context, filePath: String) =
        dao(context).markClosed(filePath, now())

    fun markDriveDone(context: Context, filePath: String) =
        dao(context).markDriveDone(filePath, now())

    fun setError(context: Context, filePath: String, error: String?) =
        dao(context).setError(filePath, error, now())

    fun delete(context: Context, filePath: String) = dao(context).delete(filePath)
}
