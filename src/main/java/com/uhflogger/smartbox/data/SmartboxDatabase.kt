package com.uhflogger.smartbox.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.uhflogger.smartbox.ble.Reading

/**
 * Banco próprio da smartbox, separado do UploadQueueDatabase de propósito: não
 * mexe na versão/migração do banco que já está em campo.
 */
@Entity(
    tableName = "smartbox_reading",
    primaryKeys = ["module_id", "epc", "antenna", "ts"],
    indices = [Index(value = ["state", "farm_id", "module_id", "ts", "epc", "antenna"])],
)
data class SmartboxReadingEntity(
    @ColumnInfo(name = "module_id") val moduleId: String,
    /** Hex maiúsculo. */
    val epc: String,
    /** Porta 1..4. */
    val antenna: Int,
    /** Unix segundos (UTC). */
    val ts: Long,
    val rssi: Int,
    /** Fazenda que a smartbox anunciava na descarga. */
    @ColumnInfo(name = "farm_id") val farmId: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    val state: Int = ReadingState.PENDING,
    val error: String? = null,
    @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
)

@Entity(tableName = "smartbox_module")
data class SmartboxModuleEntity(
    @PrimaryKey @ColumnInfo(name = "module_id") val moduleId: String,
    @ColumnInfo(name = "registered_farm_id") val registeredFarmId: Long? = null,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long? = null,
    @ColumnInfo(name = "pending_since") val pendingSince: Long? = null,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long? = null,
    @ColumnInfo(name = "downloaded_total") val downloadedTotal: Long = 0,
    @ColumnInfo(name = "accepted_total") val acceptedTotal: Long = 0,
    @ColumnInfo(name = "last_upload_at") val lastUploadAt: Long? = null,
)

data class PendingRow(
    @ColumnInfo(name = "module_id") val moduleId: String,
    val epc: String,
    val antenna: Int,
    val ts: Long,
    val rssi: Int,
)

@Dao
interface SmartboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertReadings(rows: List<SmartboxReadingEntity>): List<Long>

    @Query("INSERT OR IGNORE INTO smartbox_module (module_id, downloaded_total, accepted_total) VALUES (:moduleId, 0, 0)")
    fun ensureModule(moduleId: String)

    @Query("UPDATE smartbox_module SET downloaded_total = downloaded_total + :added WHERE module_id = :moduleId")
    fun addDownloaded(moduleId: String, added: Int)

    @Query("UPDATE smartbox_module SET last_success_at = :now, pending_since = NULL WHERE module_id = :moduleId")
    fun markSuccess(moduleId: String, now: Long)

    @Query(
        """UPDATE smartbox_module SET last_seen_at = :now,
           pending_since = CASE WHEN :pending > 0 THEN COALESCE(pending_since, :now) ELSE NULL END
           WHERE module_id = :moduleId""",
    )
    fun markSeen(moduleId: String, pending: Int, now: Long)

    @Query("SELECT * FROM smartbox_module WHERE module_id = :moduleId")
    fun getModule(moduleId: String): SmartboxModuleEntity?

    @Query("UPDATE smartbox_module SET registered_farm_id = :farmId WHERE module_id = :moduleId")
    fun setRegistered(moduleId: String, farmId: Long?)

    @Query("UPDATE smartbox_module SET registered_farm_id = NULL")
    fun resetRegistrations()

    @Query("SELECT DISTINCT module_id FROM smartbox_reading WHERE state = 0 AND farm_id = :farmId ORDER BY module_id")
    fun modulesWithPending(farmId: Long): List<String>

    @Query(
        """SELECT module_id, epc, antenna, ts, rssi FROM smartbox_reading
           WHERE state = 0 AND module_id = :moduleId AND farm_id = :farmId
           ORDER BY ts, epc, antenna LIMIT :limit""",
    )
    fun nextPending(moduleId: String, farmId: Long, limit: Int): List<PendingRow>

    @Query(
        """UPDATE smartbox_reading SET state = :state, sent_at = :sentAt, error = :error
           WHERE module_id = :moduleId AND epc = :epc AND antenna = :antenna AND ts = :ts""",
    )
    fun setState(moduleId: String, epc: String, antenna: Int, ts: Long, state: Int, sentAt: Long?, error: String?)

    @Query("UPDATE smartbox_module SET accepted_total = accepted_total + :accepted, last_upload_at = :now WHERE module_id = :moduleId")
    fun addAccepted(moduleId: String, accepted: Int, now: Long)

    @Query("SELECT COUNT(*) FROM smartbox_reading WHERE state = 0 AND farm_id <> :farmId")
    fun countPendingOtherFarms(farmId: Long): Int

    @Query("SELECT COUNT(*) FROM smartbox_reading WHERE state = 0 AND farm_id = :farmId")
    fun countPending(farmId: Long): Int

    /** Nunca apaga pendente (state 0) nem recusada (2). */
    @Query("DELETE FROM smartbox_reading WHERE state = 1 AND COALESCE(sent_at, received_at) < :before")
    fun deleteSentBefore(before: Long)

    @Query("SELECT * FROM smartbox_module WHERE pending_since IS NOT NULL")
    fun modulesWithPendingSince(): List<SmartboxModuleEntity>
}

@Database(entities = [SmartboxReadingEntity::class, SmartboxModuleEntity::class], version = 1, exportSchema = false)
abstract class SmartboxDatabase : RoomDatabase() {
    abstract fun dao(): SmartboxDao

    companion object {
        @Volatile private var instance: SmartboxDatabase? = null

        fun get(context: Context): SmartboxDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SmartboxDatabase::class.java, "smartbox.db")
                .build()
                .also { instance = it }
        }
    }
}

class RoomSmartboxRepository(private val db: SmartboxDatabase) : SmartboxRepository {
    private val dao = db.dao()

    override fun persistBatch(moduleId: String, farmId: Long, readings: List<Reading>, now: Long): Int {
        var added = 0
        db.runInTransaction {
            dao.ensureModule(moduleId)
            if (readings.isNotEmpty()) {
                val rows = readings.map {
                    SmartboxReadingEntity(moduleId, it.epc.uppercase(), it.antenna, it.ts, it.rssi, farmId, now)
                }
                added = dao.insertReadings(rows).count { it != -1L }
            }
            dao.addDownloaded(moduleId, added)
        }
        return added
    }

    override fun markDownloadSuccess(moduleId: String, now: Long) = db.runInTransaction {
        dao.ensureModule(moduleId)
        dao.markSuccess(moduleId, now)
    }

    override fun markSeen(moduleId: String, pending: Int, now: Long) = db.runInTransaction {
        dao.ensureModule(moduleId)
        dao.markSeen(moduleId, pending, now)
    }

    override fun getModule(moduleId: String): ModuleInfo? = dao.getModule(moduleId)?.let {
        ModuleInfo(it.moduleId, it.registeredFarmId, it.lastSeenAt, it.pendingSince, it.lastSuccessAt, it.downloadedTotal, it.acceptedTotal)
    }

    override fun setRegistered(moduleId: String, farmId: Long?) = db.runInTransaction {
        dao.ensureModule(moduleId)
        dao.setRegistered(moduleId, farmId)
    }

    override fun resetRegistrations() = dao.resetRegistrations()

    override fun modulesWithPending(farmId: Long): List<String> = dao.modulesWithPending(farmId)

    override fun nextPendingBatch(moduleId: String, farmId: Long, limit: Int): List<PendingReading> =
        dao.nextPending(moduleId, farmId, limit).map { PendingReading(it.moduleId, it.epc, it.antenna, it.ts, it.rssi) }

    override fun markSent(batch: List<PendingReading>, moduleId: String, accepted: Int, now: Long) = db.runInTransaction {
        batch.forEach { dao.setState(it.moduleId, it.epc, it.antenna, it.ts, ReadingState.SENT, now, null) }
        dao.addAccepted(moduleId, accepted, now)
    }

    override fun markRejected(batch: List<PendingReading>, error: String) = db.runInTransaction {
        batch.forEach { dao.setState(it.moduleId, it.epc, it.antenna, it.ts, ReadingState.REJECTED, null, error.take(500)) }
    }

    override fun countPendingOtherFarms(farmId: Long): Int = dao.countPendingOtherFarms(farmId)

    override fun countPending(farmId: Long): Int = dao.countPending(farmId)

    override fun cleanupSent(now: Long) = dao.deleteSentBefore(now - SmartboxRepository.SENT_RETENTION_MS)

    override fun staleModules(now: Long): List<String> =
        dao.modulesWithPendingSince()
            .filter { com.uhflogger.smartbox.collect.AutoCollectRules.isStale(it.pendingSince, it.lastSeenAt, now) }
            .map { it.moduleId }

    companion object {
        @Volatile private var instance: RoomSmartboxRepository? = null

        fun get(context: Context): RoomSmartboxRepository = instance ?: synchronized(this) {
            instance ?: RoomSmartboxRepository(SmartboxDatabase.get(context)).also { instance = it }
        }
    }
}
