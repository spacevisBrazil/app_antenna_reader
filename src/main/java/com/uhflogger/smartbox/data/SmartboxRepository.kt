package com.uhflogger.smartbox.data

import com.uhflogger.smartbox.ble.Reading

/** Estados da leitura na fila local (seção 8 do guia). */
object ReadingState {
    const val PENDING = 0
    const val SENT = 1
    /** 400/422: lote mal formado. Nunca reenviada nem apagada. */
    const val REJECTED = 2
}

/** Leitura pendente de envio, com a chave única (módulo, epc, porta, instante). */
data class PendingReading(
    val moduleId: String,
    val epc: String,
    val antenna: Int,
    val ts: Long,
    val rssi: Int,
)

data class ModuleInfo(
    val moduleId: String,
    /** Fazenda onde o backend já tem o módulo (201/409); null = falta cadastrar. */
    val registeredFarmId: Long?,
    val lastSeenAt: Long?,
    val pendingSince: Long?,
    val lastSuccessAt: Long?,
    val downloadedTotal: Long,
    val acceptedTotal: Long,
)

/**
 * Banco local da smartbox. Bloqueante: chamar fora da main (Dispatchers.IO /
 * worker). A implementação é Room ([RoomSmartboxRepository]); os testes usam
 * um fake em memória.
 *
 * Regras que moram na implementação:
 * - lote inteiro numa transação, INSERT OR IGNORE pela chave única: reenviar o
 *   mesmo lote (desconectou antes do ACK) não duplica;
 * - leitura PENDENTE nunca é apagada; só as enviadas, depois de 7 dias.
 */
interface SmartboxRepository {
    /**
     * Grava o lote e só retorna depois do commit. [farmId] = fazenda que a
     * smartbox anunciava na descarga: só ela pode subir para o backend.
     * @return quantas leituras eram novas
     */
    fun persistBatch(moduleId: String, farmId: Long, readings: List<Reading>, now: Long): Int

    /** Descarga terminou (sucesso): zera o "pendente desde" do aviso de 24 h. */
    fun markDownloadSuccess(moduleId: String, now: Long)

    /** Vista por perto (só caixas da minha fazenda). */
    fun markSeen(moduleId: String, pending: Int, now: Long)

    fun getModule(moduleId: String): ModuleInfo?

    fun setRegistered(moduleId: String, farmId: Long?)

    /** A fazenda do aparelho mudou: o cadastro no backend é por fazenda. */
    fun resetRegistrations()

    fun modulesWithPending(farmId: Long): List<String>

    fun nextPendingBatch(moduleId: String, farmId: Long, limit: Int): List<PendingReading>

    fun markSent(batch: List<PendingReading>, moduleId: String, accepted: Int, now: Long)

    fun markRejected(batch: List<PendingReading>, error: String)

    fun countPendingOtherFarms(farmId: Long): Int

    fun countPending(farmId: Long): Int

    /** Apaga as enviadas há mais de 7 dias. */
    fun cleanupSent(now: Long)

    /** Módulos para o aviso de 24 h. */
    fun staleModules(now: Long): List<String>

    companion object {
        const val SENT_RETENTION_MS = 7 * 24 * 60 * 60_000L
    }
}
