package com.uhflogger.smartbox

import android.content.Context
import com.uhflogger.smartbox.ble.FieldKey
import com.uhflogger.smartbox.ble.NearbySmartbox
import com.uhflogger.smartbox.ble.Op
import com.uhflogger.smartbox.ble.SmartboxBusyException
import com.uhflogger.smartbox.ble.SmartboxConnectException
import com.uhflogger.smartbox.ble.SmartboxConnection
import com.uhflogger.smartbox.ble.SmartboxStatusException
import com.uhflogger.smartbox.ble.SmartboxTarget
import com.uhflogger.smartbox.ble.SmartboxTelemetry
import com.uhflogger.smartbox.ble.Status
import com.uhflogger.smartbox.ble.buildSetFarm
import com.uhflogger.smartbox.ble.displayName
import com.uhflogger.smartbox.ble.parseFarmResult
import com.uhflogger.smartbox.radio.CollectMode
import com.uhflogger.smartbox.sync.SmartboxApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ações manuais nas telas de técnico. */
object SmartboxActions {

    sealed class AssignOutcome {
        /** Gravou a fazenda. [backendError] != null = o cadastro no banco falhou (tenta de novo no próximo envio). */
        data class Registered(val farmId: Long, val backendError: String?) : AssignOutcome()
        /** Status 4: já é de outra fazenda. No UHF Logger não há Transferir (só gestor). */
        data class OtherFarm(val farmId: Long) : AssignOutcome()
        /** Status 5: está em Wi-Fi e a fazenda vem da chave de ativação. */
        data class WifiFarm(val farmId: Long) : AssignOutcome()
        data class Failed(val message: String) : AssignOutcome()
    }

    /**
     * Baixar agora (seção 11.3), só em smartbox desta fazenda: a mesma descarga
     * da coleta automática, ignorando pausa. No fim, avisa o envio.
     * @return texto do resultado e se foi erro
     */
    suspend fun downloadNow(
        context: Context,
        box: NearbySmartbox,
        myFarmId: Int,
        onProgress: (Int, Long?) -> Unit,
    ): Pair<String, Boolean> {
        if (!FieldKey.isConfigured) return FieldKey.NOT_CONFIGURED_MESSAGE to true
        val name = displayName(box.adv.moduleId)
        val repo = com.uhflogger.smartbox.data.RoomSmartboxRepository.get(context)
        return try {
            val result = SmartboxConnection.withSession(
                context = context,
                target = SmartboxTarget(box.address, box.adv.moduleId),
                purpose = SmartboxTelemetry.Purpose.DOWNLOAD,
                collectMode = CollectMode.MANUAL,
                control = com.uhflogger.smartbox.ble.DownloadControl(),
                advRssi = box.rssi,
                advSeenAt = box.seenAt,
            ) { open ->
                if (open.challenge.mode != com.uhflogger.smartbox.ble.SmartboxMode.OPERATION) {
                    throw com.uhflogger.smartbox.ble.DownloadException(
                        "smartbox em modo configuração: a descarga só funciona no modo operação Bluetooth",
                        com.uhflogger.smartbox.ble.DownloadException.Kind.CONFIG_MODE,
                    )
                }
                // O anúncio pode estar velho: só baixa se a smartbox É desta fazenda agora.
                if (open.challenge.farmId != myFarmId.toLong()) {
                    throw SmartboxConnectException("não é mais desta fazenda; nada baixado")
                }
                com.uhflogger.smartbox.collect.SmartboxDownloads.run(open, box.adv.moduleId, myFarmId.toLong(), repo, com.uhflogger.smartbox.ble.DownloadControl()) {
                    onProgress(it.received, it.remaining)
                }
            }
            if (result.readings > 0) com.uhflogger.smartbox.sync.SmartboxUploadWorker.scheduleNow(context)
            "$name: ${result.readings} leitura(s) baixada(s), enviando ao servidor." to false
        } catch (e: SmartboxBusyException) {
            "Outra smartbox está conectada agora. Espere terminar e tente de novo." to true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "$name: ${e.message ?: e.javaClass.simpleName}. Chegue mais perto e tente de novo." to true
        }
    }

    /**
     * Cadastrar nesta fazenda (seção 10.1): SET_FARM SEM force e, se gravou,
     * cadastro do módulo no backend. Se o backend falhar, a smartbox CONTINUA
     * cadastrada; o cadastro no banco é refeito antes do próximo envio (PR 3).
     */
    suspend fun assignFarm(context: Context, box: NearbySmartbox, myFarmId: Int): AssignOutcome {
        if (!FieldKey.isConfigured) return AssignOutcome.Failed(FieldKey.NOT_CONFIGURED_MESSAGE)
        if (myFarmId <= 0) return AssignOutcome.Failed("O app não está ativado numa fazenda. Ative pela chave nas configurações.")
        val name = displayName(box.adv.moduleId)

        val outcome = try {
            SmartboxConnection.withSession(
                context = context,
                target = SmartboxTarget(box.address, box.adv.moduleId),
                purpose = SmartboxTelemetry.Purpose.ASSIGN_FARM,
                collectMode = CollectMode.MANUAL,
                advRssi = box.rssi,
                advSeenAt = box.seenAt,
            ) { open ->
                val res = open.session.call(Op.SET_FARM, buildSetFarm(myFarmId.toLong(), force = false))
                when (res.status) {
                    Status.OK -> AssignOutcome.Registered(parseFarmResult(res.data).farmId, null)
                    Status.DIFFERENT_FARM -> AssignOutcome.OtherFarm(parseFarmResult(res.data).farmId)
                    Status.FARM_FROM_KEY -> AssignOutcome.WifiFarm(parseFarmResult(res.data).farmId)
                    else -> throw SmartboxStatusException(res.op, res.status, res.data)
                }
            }
        } catch (e: SmartboxBusyException) {
            return AssignOutcome.Failed("Outra smartbox está conectada agora. Espere terminar e tente de novo.")
        } catch (e: SmartboxConnectException) {
            return AssignOutcome.Failed("$name: ${e.message}")
        } catch (e: SmartboxStatusException) {
            val why = if (e.status == Status.ERROR_TEXT) "a smartbox recusou: ${e.text}" else e.message
            return AssignOutcome.Failed("$name: não deu para gravar a fazenda ($why).")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return AssignOutcome.Failed("$name: não deu para gravar a fazenda (${e.message ?: e.javaClass.simpleName}). Chegue mais perto e tente de novo.")
        }

        if (outcome !is AssignOutcome.Registered) return outcome
        val backend = withContext(Dispatchers.IO) { SmartboxApi.registerModule(context, myFarmId, box.adv.moduleId) }
        return outcome.copy(backendError = (backend as? SmartboxApi.Result.Failed)?.message)
    }
}
