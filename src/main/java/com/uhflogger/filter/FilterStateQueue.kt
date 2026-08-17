package com.uhflogger.filter

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.uhflogger.drive.UploadQueueDatabase
import com.uhflogger.model.TagRecord

/**
 * Estado persistido da Camada 2 (consolidação por EPC) — a durabilidade contra
 * SIGKILL é automática sempre que a Camada 2 está ativa, sem toggle próprio.
 * Uma linha por EPC com entrada aberta: guarda a leitura de melhor RSSI vista
 * até agora dentro da janela, e o timestamp da primeira leitura (first_seen),
 * que nunca é alterado depois de criado.
 *
 * Sobrevive a SIGKILL/OOM-kill: no pior caso perde-se só o que ainda não tinha
 * sido persistido no último batch (~2-3s) — nunca a entrada inteira, já que o
 * batch anterior já está em disco. Ao reiniciar, o app relê esta tabela e
 * continua de onde parou (ver TagFilterEngine.start()).
 */
@Entity(tableName = "filter_state")
data class FilterStateEntry(
    @PrimaryKey val epc: String,
    val firstSeenMs: Long,
    val bestRssi: Int,
    val antenna: Int,
    val androidTs: Long,
    val latitude: String = "",
    val longitude: String = "",
    val bearing: String = "",
    val temperature: String = "",
    val gnssSpeed: String = "",
    val locationTimestamp: String = "",
    val locationProvider: String = "",
)

fun FilterStateEntry.toTagRecord(): TagRecord = TagRecord(
    epc = epc,
    rssi = bestRssi,
    antenna = antenna,
    androidTs = androidTs,
    latitude = latitude,
    longitude = longitude,
    bearing = bearing,
    temperature = temperature,
    gnssSpeed = gnssSpeed,
    locationTimestamp = locationTimestamp,
    locationProvider = locationProvider,
)

fun TagRecord.toFilterStateEntry(firstSeenMs: Long): FilterStateEntry = FilterStateEntry(
    epc = epc,
    firstSeenMs = firstSeenMs,
    bestRssi = rssi,
    antenna = antenna,
    androidTs = androidTs,
    latitude = latitude,
    longitude = longitude,
    bearing = bearing,
    temperature = temperature,
    gnssSpeed = gnssSpeed,
    locationTimestamp = locationTimestamp,
    locationProvider = locationProvider,
)

@Dao
interface FilterStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(entries: List<FilterStateEntry>)

    @Query("SELECT * FROM filter_state")
    fun getAll(): List<FilterStateEntry>

    @Query("DELETE FROM filter_state WHERE epc IN (:epcs)")
    fun deleteAll(epcs: List<String>)

    @Query("DELETE FROM filter_state")
    fun clearAll()
}

/**
 * Fachada síncrona sobre o DAO — mesmo arranjo do BackendUploadStore. Chamadas
 * devem rodar fora da UI thread (o TagFilterEngine já roda em executors
 * dedicados do Service, nunca na thread de leitura serial nem na UI thread).
 */
object FilterStateStore {
    private fun dao(context: Context): FilterStateDao =
        UploadQueueDatabase.get(context).filterStateDao()

    fun loadAll(context: Context): List<FilterStateEntry> = dao(context).getAll()

    fun upsertAll(context: Context, entries: List<FilterStateEntry>) {
        if (entries.isEmpty()) return
        dao(context).upsertAll(entries)
    }

    fun deleteAll(context: Context, epcs: List<String>) {
        if (epcs.isEmpty()) return
        dao(context).deleteAll(epcs)
    }

    fun clearAll(context: Context) = dao(context).clearAll()
}
