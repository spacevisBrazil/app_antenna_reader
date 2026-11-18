package com.uhflogger.smartbox.collect

import com.uhflogger.smartbox.ble.NearbySmartbox
import com.uhflogger.smartbox.ble.SmartboxProtocol
import com.uhflogger.smartbox.ble.decodeStateFlags

/**
 * Regras puras da coleta automática (seções 7.2 e 7.3 do guia). Porte de
 * spacevis-mobile/src/features/smartbox/domain/autoCollect.ts, mais o que o
 * Igor definiu para o UHF Logger (ligada por padrão, cede à captura, aviso de 24 h).
 */
object AutoCollectRules {
    /** Abaixo disso a conexão cai no meio. */
    const val MIN_RSSI = -90

    const val PAUSE_SUCCESS_MS = 5 * 60_000L
    const val PAUSE_CONFIG_MODE_MS = 10 * 60_000L
    const val PAUSE_MAX_FAILURE_MS = 10 * 60_000L
    /** Cedeu o rádio à captura: não é falha da caixa, volta logo. */
    const val PAUSE_YIELDED_MS = 30_000L
    /** Falhas seguidas que tentam de novo sem pausa (só o respiro). */
    const val IMMEDIATE_RETRIES = 3

    /** Aviso persistente: smartbox da fazenda por perto há 24 h sem conseguir baixar. */
    const val STALE_AFTER_MS = 24 * 60 * 60_000L
    /** "Por perto" para o aviso: vista na última hora (fora disso, ninguém passou lá). */
    const val STALE_SEEN_WITHIN_MS = 60 * 60_000L

    data class Pause(val until: Long, val consecutiveFailures: Int, val lastConnectedAt: Long)

    enum class Outcome { SUCCESS, FAILURE, CONFIG_MODE, YIELDED }

    fun nextPause(prev: Pause?, outcome: Outcome, now: Long): Pause = when (outcome) {
        Outcome.SUCCESS -> Pause(now + PAUSE_SUCCESS_MS, 0, now)
        Outcome.CONFIG_MODE -> Pause(now + PAUSE_CONFIG_MODE_MS, prev?.consecutiveFailures ?: 0, now)
        Outcome.YIELDED -> Pause(now + PAUSE_YIELDED_MS, prev?.consecutiveFailures ?: 0, now)
        Outcome.FAILURE -> {
            val failures = (prev?.consecutiveFailures ?: 0) + 1
            // 3 imediatas; depois 1, 2, 4, 8 min… até 10 min.
            val extra = failures - IMMEDIATE_RETRIES
            val wait = if (extra <= 0) 0L else minOf(60_000L shl (extra - 1).coerceAtMost(20), PAUSE_MAX_FAILURE_MS)
            Pause(now + wait, failures, now)
        }
    }

    enum class Block { GONE, PROTOCOL, OTHER_FARM, CONFIG_MODE, PAUSED, NO_READINGS, WEAK_SIGNAL }

    /** Primeiro bloqueio da caixa, na ordem da seção 7.2 (ou null = candidata). */
    fun blockReason(box: NearbySmartbox, myFarmId: Long, pause: Pause?, now: Long): Block? {
        if (now - box.seenAt > SmartboxProtocol.NEARBY_WINDOW_MS) return Block.GONE
        // Anúncio de antes da última conexão é velho (a caixa ainda não anunciou o estado novo).
        if (pause != null && box.seenAt <= pause.lastConnectedAt) return Block.GONE
        if (box.adv.proto != SmartboxProtocol.VERSION) return Block.PROTOCOL
        // Só as da própria fazenda. Sem fazenda e de outra: cadastrar é manual.
        if (box.adv.farmId != myFarmId) return Block.OTHER_FARM
        if (decodeStateFlags(box.adv.state).configMode) return Block.CONFIG_MODE
        if (pause != null && now < pause.until) return Block.PAUSED
        if (box.adv.pending == 0) return Block.NO_READINGS
        if (box.rssi < MIN_RSSI) return Block.WEAK_SIGNAL
        return null
    }

    /** Melhor candidata: memória cheia na frente; depois mais pendentes; empate → sinal mais forte. */
    fun pickCandidate(boxes: Collection<NearbySmartbox>, myFarmId: Long, pauses: Map<String, Pause>, now: Long): NearbySmartbox? =
        boxes.filter { blockReason(it, myFarmId, pauses[it.adv.moduleId], now) == null }
            .sortedWith(
                compareByDescending<NearbySmartbox> { decodeStateFlags(it.adv.state).memoryFull }
                    .thenByDescending { it.adv.pending }
                    .thenByDescending { it.rssi },
            )
            .firstOrNull()

    /**
     * A coleta (scan + FGS) só existe com a chave no build, fazenda selecionada,
     * a preferência ligada (padrão: ligada) e a permissão de conectar. Sem isso,
     * nada sobe e a tela principal fica exatamente como antes.
     */
    fun shouldRun(keyConfigured: Boolean, farmId: Int, enabledPref: Boolean, hasConnectPermission: Boolean): Boolean =
        keyConfigured && farmId > 0 && enabledPref && hasConnectPermission

    /**
     * Aviso das 24 h: a caixa está por perto (vista na última hora) com
     * leituras e não baixa há 24 h. [pendingSince] = primeira vez vista com
     * leituras desde o último sucesso (zera no sucesso).
     */
    fun isStale(pendingSince: Long?, lastSeenAt: Long?, now: Long): Boolean =
        pendingSince != null && lastSeenAt != null &&
            now - pendingSince >= STALE_AFTER_MS &&
            now - lastSeenAt <= STALE_SEEN_WITHIN_MS

    /** "12 s", "3 min", "1 h 05 min" para o tempo estimado. */
    fun formatEta(seconds: Long): String = when {
        seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h %02d min".format(java.util.Locale.ROOT, (seconds % 3600) / 60)
    }
}
