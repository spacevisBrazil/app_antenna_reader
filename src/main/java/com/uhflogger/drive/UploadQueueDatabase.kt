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

@Database(entities = [UploadEntry::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class UploadQueueDatabase : RoomDatabase() {
    abstract fun dao(): UploadQueueDao

    companion object {
        @Volatile private var INSTANCE: UploadQueueDatabase? = null

        fun get(context: Context): UploadQueueDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UploadQueueDatabase::class.java,
                    "upload_queue.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // fallbackToDestructiveMigration removido intencionalmente.
                    // Se a migração falhar, preferimos crash a perder a fila silenciosamente.
                    // O scan de inicialização em DriveMonitorService recupera arquivos órfãos.
                    .build().also { INSTANCE = it }
            }

        // Migração: adiciona coluna driveFileId
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_queue ADD COLUMN driveFileId TEXT NOT NULL DEFAULT ''")
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