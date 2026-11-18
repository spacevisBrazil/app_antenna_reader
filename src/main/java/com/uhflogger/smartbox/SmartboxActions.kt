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
