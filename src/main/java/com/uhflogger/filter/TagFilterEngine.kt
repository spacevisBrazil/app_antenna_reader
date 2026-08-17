package com.uhflogger.filter

import android.content.Context
import android.util.Log
import com.uhflogger.model.TagRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * Núcleo do filtro de 3 camadas. Não gerencia threads/executors próprios — o
 * Service é quem agenda persistência periódica (persistDirtyNow) e o sweep de
 * expiração (sweepExpired), reaproveitando o mesmo executor do auto-save.
 *
 * Camada 1 (família de EPC) e Camada 2 (consolidação por melhor RSSI numa
 * janela fixa a partir de first_seen) e Camada 3 (durabilidade — persiste o
 * estado aberto em Room a cada poucos segundos, pra sobreviver a SIGKILL) são
 * independentes entre si, controladas por Config.
 */
class TagFilterEngine(private val context: Context) {

    data class Config(
        val filterEnabled: Boolean,
        val l1Enabled: Boolean,
        val l1PatternsCsv: String,
        val l2Enabled: Boolean,
        val l2WindowMs: Long,
        val l3Enabled: Boolean,
    )

    private data class OpenEntry(val firstSeenMs: Long, @Volatile var best: TagRecord)

    @Volatile private var config = Config(
        filterEnabled = false, l1Enabled = true, l1PatternsCsv = "",
        l2Enabled = true, l2WindowMs = 30 * 60_000L, l3Enabled = true,
    )

    private val openEntries = ConcurrentHashMap<String, OpenEntry>()
    private val dirtyEpcs   = ConcurrentHashMap.newKeySet<String>()

    /**
     * (Re)inicia o motor para uma nova sessão de captura. Carrega do Room
     * qualquer entrada deixada aberta por uma sessão anterior que morreu sem
     * passar pelo Stop normal (crash recovery) — nunca perde a consolidação
     * em andamento, mesmo que acabe indo parar num arquivo CSV diferente.
     */
    fun start(cfg: Config) {
        config = cfg
        openEntries.clear()
        dirtyEpcs.clear()
        if (cfg.l2Enabled && cfg.l3Enabled) {
            try {
                FilterStateStore.loadAll(context).forEach { e ->
                    openEntries[e.epc] = OpenEntry(e.firstSeenMs, e.toTagRecord())
                }
                if (openEntries.isNotEmpty()) {
                    Log.i(TAG, "Filter: ${openEntries.size} entrada(s) recuperada(s) de sessão anterior")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Filter: falha ao recuperar estado persistido", e)
            }
        }
    }

    /**
     * Processa tags recém-decodificadas. Retorna as que devem seguir direto
     * para o pipeline normal de CSV: tags que passaram pela Camada 1 mas a
     * Camada 2 está desligada (nada a consolidar), ou o filtro geral está
     * desligado. Tags absorvidas pela Camada 2 não aparecem aqui — só saem
     * quando expiram (ver sweepExpired/flushAllNow).
     */
    fun process(tags: List<TagRecord>): List<TagRecord> {
        val cfg = config
        if (!cfg.filterEnabled) return tags
        if (tags.isEmpty()) return tags

        val passThrough = ArrayList<TagRecord>(tags.size)
        for (tag in tags) {
            if (cfg.l1Enabled && !EpcFamilyMatcher.isAllowed(tag.epc, cfg.l1PatternsCsv)) continue
            if (!cfg.l2Enabled) {
                passThrough.add(tag)
                continue
            }
            absorb(tag)
        }
        return passThrough
    }

    private fun absorb(tag: TagRecord) {
        val now = System.currentTimeMillis()
        openEntries.compute(tag.epc) { _, existing ->
            when {
                existing == null -> {
                    dirtyEpcs.add(tag.epc)
                    OpenEntry(now, tag)
                }
                tag.rssi > existing.best.rssi -> {
                    dirtyEpcs.add(tag.epc)
                    existing.best = tag
                    existing
                }
                else -> existing
            }
        }
    }

    /** Grava em lote (Room) as entradas atualizadas desde a última chamada. */
    fun persistDirtyNow() {
        if (!config.l3Enabled) {
            dirtyEpcs.clear()
            return
        }
        val epcs = dirtyEpcs.toList()
        if (epcs.isEmpty()) return
        epcs.forEach { dirtyEpcs.remove(it) }
        val batch = epcs.mapNotNull { epc -> openEntries[epc]?.let { it.best.toFilterStateEntry(it.firstSeenMs) } }
        try {
            FilterStateStore.upsertAll(context, batch)
        } catch (e: Exception) {
            Log.e(TAG, "Filter: falha ao persistir estado", e)
            epcs.forEach { dirtyEpcs.add(it) }  // tenta de novo no próximo ciclo
        }
    }

    /**
     * Varre as entradas abertas e remove (+ retorna) as que já passaram da
     * janela configurada (Camada 2), contada a partir de first_seen.
     */
    fun sweepExpired(): List<TagRecord> {
        val cfg = config
        if (!cfg.l2Enabled || openEntries.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val expiredEpcs = ArrayList<String>()
        val results = ArrayList<TagRecord>()
        openEntries.forEach { (epc, entry) ->
            if (now - entry.firstSeenMs >= cfg.l2WindowMs) {
                expiredEpcs.add(epc)
                results.add(entry.best)
            }
        }
        if (expiredEpcs.isEmpty()) return emptyList()

        expiredEpcs.forEach { openEntries.remove(it); dirtyEpcs.remove(it) }
        if (cfg.l3Enabled) {
            try {
                FilterStateStore.deleteAll(context, expiredEpcs)
            } catch (e: Exception) {
                Log.e(TAG, "Filter: falha ao remover estado expirado", e)
            }
        }
        return results
    }

    /**
     * Força a expiração de TUDO que está aberto, como se a janela tivesse
     * passado — usado no Stop iniciado pelo usuário (D1), para que nenhum
     * dado fique esperando indefinidamente uma janela que nunca vai fechar.
     */
    fun flushAllNow(): List<TagRecord> {
        if (openEntries.isEmpty()) return emptyList()
        val epcs = openEntries.keys.toList()
        val results = epcs.mapNotNull { openEntries[it]?.best }
        openEntries.clear()
        dirtyEpcs.clear()
        if (config.l3Enabled) {
            try {
                FilterStateStore.deleteAll(context, epcs)
            } catch (e: Exception) {
                Log.e(TAG, "Filter: falha ao limpar estado no flush final", e)
            }
        }
        return results
    }

    companion object {
        private const val TAG = "TagFilterEngine"
    }
}
