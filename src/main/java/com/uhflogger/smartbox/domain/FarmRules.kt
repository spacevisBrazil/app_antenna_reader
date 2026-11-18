package com.uhflogger.smartbox.domain

import com.uhflogger.smartbox.ble.Advertisement
import com.uhflogger.smartbox.ble.SmartboxProtocol
import com.uhflogger.smartbox.ble.decodeStateFlags

/**
 * Regras de fazenda e perfil para as ações na smartbox (seções 10, 11.2 e 14.3
 * do guia). Porte de spacevis-mobile/src/features/smartbox/domain/farmRules.ts.
 *
 * A smartbox não sabe qual fazenda é o aparelho (a K é a mesma para todos),
 * então a defesa contra mexer em caixa alheia mora AQUI, na interface:
 * - outra fazenda: nenhuma ação (Transferir é de gestor, e o UHF Logger não tem gestor);
 * - Configurar / Diagnóstico / Reiniciar: só se a caixa é minha ou sem fazenda;
 * - Baixar: só se é minha (o envio só sobe leitura da minha fazenda).
 *
 * "Minha fazenda" = a selecionada no seletor (decisão H4).
 */
object FarmRules {

    /**
     * O UHF Logger entra com a conta do APARELHO, que não tem perfil de pessoa.
     * Transferir, Padrão de fábrica e Tirar da fazenda ficam com o gestor, no
     * SpaceVis Field (decisão H2).
     */
    const val IS_MANAGER = false

    enum class Section { MINE, UNREGISTERED, OTHER }

    fun section(boxFarmId: Long, myFarmId: Long): Section = when {
        boxFarmId == 0L -> Section.UNREGISTERED
        boxFarmId == myFarmId -> Section.MINE
        else -> Section.OTHER
    }

    data class Actions(
        /** proto ≠ 2: nenhuma ação, só "Atualize o app". */
        val needsAppUpdate: Boolean = false,
        val download: Boolean = false,
        val register: Boolean = false,
        val transfer: Boolean = false,
        /** Caixa Wi-Fi de outra fazenda: a fazenda só muda com chave de ativação (status 5). */
        val transferBlockedByWifi: Boolean = false,
        val configure: Boolean = false,
        val diagnose: Boolean = false,
        val reboot: Boolean = false,
        val factoryReset: Boolean = false,
    )

    fun allowedActions(adv: Advertisement, myFarmId: Long, isManager: Boolean = IS_MANAGER): Actions {
        if (adv.proto != SmartboxProtocol.VERSION) return Actions(needsAppUpdate = true)
        // Sem fazenda selecionada no aparelho: nada que mexa em fazenda.
        if (myFarmId <= 0) return Actions()

        val section = section(adv.farmId, myFarmId)
        val wifi = decodeStateFlags(adv.state).wifi
        val mineOrFree = section != Section.OTHER
        return Actions(
            download = section == Section.MINE,
            register = section == Section.UNREGISTERED,
            transfer = section == Section.OTHER && isManager && !wifi,
            transferBlockedByWifi = section == Section.OTHER && wifi,
            configure = mineOrFree,
            diagnose = mineOrFree,
            reboot = mineOrFree,
            factoryReset = mineOrFree && isManager,
        )
    }
}

/**
 * Como citar a fazenda de uma smartbox nos textos: sempre pelo nome, nunca só
 * o id. Fazenda que o aparelho não conhece vira "outra fazenda (código N)".
 */
data class FarmLabel(
    /** "fazenda São José" | "outra fazenda (código 65)" */
    val plain: String,
    /** "da fazenda São José" | "de outra fazenda (código 65)" */
    val of: String,
    /** "na fazenda São José" | "em outra fazenda (código 65)" */
    val inFarm: String,
    /** "Fazenda São José" | "Outra fazenda (código 65)" */
    val title: String,
) {
    companion object {
        fun of(farmId: Long, knownFarms: List<Pair<Int, String>>): FarmLabel {
            val name = knownFarms.firstOrNull { it.first.toLong() == farmId }?.second?.trim()
            if (!name.isNullOrEmpty()) {
                return FarmLabel("fazenda $name", "da fazenda $name", "na fazenda $name", "Fazenda $name")
            }
            val code = "(código $farmId)"
            return FarmLabel("outra fazenda $code", "de outra fazenda $code", "em outra fazenda $code", "Outra fazenda $code")
        }
    }
}
