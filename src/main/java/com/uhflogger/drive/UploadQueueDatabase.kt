package com.uhflogger.drive

import android.content.Context
import androidx.room.*

// ─── Entity ──────────────────────────────────────────────────────────────────

enum class UploadStatus { PENDING, IN_PROGRESS, DONE, FAILED }

@Entity(tableName = "upload_queue")
data class UploadEntry(
    @PrimaryKey(autoGenerate = true) val id          : Long   = 0,
    val filePath   : String,
    val status     : UploadStatus = UploadStatus.PENDING,
    val driveFileId: String       = "",   // preenchido após upload bem-sucedido — usado para detectar duplicatas
    val retryCount : Int          = 0,
    val createdAt  : Long         = System.currentTimeMillis()
)

// ─── DAO ─────────────────────────────────────────────────────────────────────

@Dao
interface UploadQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entry: UploadEntry): Long

    @Query("SELECT * FROM upload_queue WHERE status IN ('PENDING','FAILED') ORDER BY createdAt ASC")
    fun getPending(): List<UploadEntry>

    @Query("UPDATE upload_queue SET status = :status, retryCount = retryCount + 1 WHERE id = :id")
    fun updateStatus(id: Long, status: UploadStatus)

    @Query("UPDATE upload_queue SET driveFileId = :driveFileId WHERE id = :id")
    fun setDriveFileId(id: Long, driveFileId: String)

    @Query("DELETE FROM upload_queue WHERE id = :id")
    fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status IN ('PENDING','FAILED')")
    fun pendingCount(): Int
}

// ─── Converters ──────────────────────────────────────────────────────────────

class Converters {
    @TypeConverter fun fromStatus(s: UploadStatus): String = s.name
    @TypeConverter fun toStatus(s: String): UploadStatus   = UploadStatus.valueOf(s)
}

// ─── Database ─────────────────────────────────────────────────────────────────

// v3 acrescenta `backend_upload` (envio ao backend SpaceVis). Mesmo banco de
// propósito: os dois destinos falam do MESMO arquivo local, e é a exclusão dele
// que precisa ser coordenada entre eles (ver FileRetention). Bancos separados
// tornariam essa coordenação uma transação distribuída sem necessidade nenhuma.
// v4 acrescenta `filter_state` (Camada 2/3 do filtro de tags) — mesma lógica:
// é só mais um estado local que sobrevive a SIGKILL, sem motivo pra banco à parte.
@Database(
    entities = [
        UploadEntry::class,
        com.uhflogger.backend.BackendUploadEntry::class,
        com.uhflogger.filter.FilterStateEntry::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class UploadQueueDatabase : RoomDatabase() {
    abstract fun dao(): UploadQueueDao
    abstract fun backendDao(): com.uhflogger.backend.BackendUploadDao
    abstract fun filterStateDao(): com.uhflogger.filter.FilterStateDao

    companion object {
        @Volatile private var INSTANCE: UploadQueueDatabase? = null

        fun get(context: Context): UploadQueueDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UploadQueueDatabase::class.java,
                    "upload_queue.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // WAL: leituras não bloqueiam escritas — importante pro filtro,
                    // que grava em lote a cada poucos segundos enquanto outros
                    // workers podem estar lendo a fila de upload ao mesmo tempo.
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    // fallbackToDestructiveMigration removido intencionalmente.
                    // Se a migração falhar, preferimos crash a perder a fila silenciosamente.
                    // O scan de inicialização em DriveMonitorService recupera arquivos órfãos.
                    .build().also { INSTANCE = it }
            }

        // Migração: a fazenda passa a ser propriedade do ARQUIVO, não do app.
        //
        // Sem ela, trocar de fazenda no seletor com envio pendente mandaria as
        // leituras da fazenda anterior para a nova. DEFAULT 0 marca as linhas
        // que já existiam: o worker adota a fazenda atual nelas e grava, o que
        // preserva o comportamento de antes para quem já tinha fila.
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE backend_upload ADD COLUMN farmId INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migração: adiciona coluna driveFileId
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_queue ADD COLUMN driveFileId TEXT NOT NULL DEFAULT ''")
            }
        }

        // Migration: tabela de envio ao backend SpaceVis. Só acrescenta — a fila
        // do Drive não é tocada, e um aparelho que nunca configurar o backend
        // segue com a tabela vazia e o comportamento de sempre.
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS backend_upload (
                        filePath        TEXT    NOT NULL PRIMARY KEY,
                        captureClientId TEXT    NOT NULL,
                        captureId       TEXT,
                        linesSent       INTEGER NOT NULL DEFAULT 0,
                        closed          INTEGER NOT NULL DEFAULT 0,
                        driveDone       INTEGER NOT NULL DEFAULT 0,
                        lastError       TEXT,
                        updatedAt       INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // Migration: tabela de estado do filtro (Camada 2 — consolidação por EPC).
        // Só acrescenta — sem o filtro ativado nas configurações, a tabela fica
        // vazia e o comportamento é idêntico ao de antes.
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS filter_state (
                        epc               TEXT    NOT NULL PRIMARY KEY,
                        firstSeenMs       INTEGER NOT NULL,
                        bestRssi          INTEGER NOT NULL,
                        antenna           INTEGER NOT NULL,
                        androidTs         INTEGER NOT NULL,
                        latitude          TEXT    NOT NULL DEFAULT '',
                        longitude         TEXT    NOT NULL DEFAULT '',
                        bearing           TEXT    NOT NULL DEFAULT '',
                        temperature       TEXT    NOT NULL DEFAULT '',
                        gnssSpeed         TEXT    NOT NULL DEFAULT '',
                        locationTimestamp TEXT    NOT NULL DEFAULT '',
                        locationProvider  TEXT    NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

// ─── Queue Manager ────────────────────────────────────────────────────────────

object UploadQueueManager {
    fun enqueue(context: Context, filePath: String) {
        val db = UploadQueueDatabase.get(context)
        Thread {
            db.dao().insert(UploadEntry(filePath = filePath))
        }.start()
    }

    fun getPending(context: Context): List<UploadEntry> =
        UploadQueueDatabase.get(context).dao().getPending()

    fun markDone(context: Context, id: Long) {
        Thread { UploadQueueDatabase.get(context).dao().delete(id) }.start()
    }

    fun setDriveFileId(context: Context, id: Long, driveFileId: String) {
        Thread { UploadQueueDatabase.get(context).dao().setDriveFileId(id, driveFileId) }.start()
    }

    fun markFailed(context: Context, id: Long) {
        Thread {
            UploadQueueDatabase.get(context).dao()
                .updateStatus(id, UploadStatus.FAILED)
        }.start()
    }

    fun pendingCount(context: Context): Int =
        UploadQueueDatabase.get(context).dao().pendingCount()
}