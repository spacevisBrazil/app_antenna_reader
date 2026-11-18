package com.uhflogger.smartbox.sync

import com.uhflogger.smartbox.data.PendingReading
import com.uhflogger.smartbox.data.SmartboxRepository
import java.time.Instant

/** Resposta HTTP já resumida. status 0 = sem conexão (convenção do BackendApi). */
data class HttpOutcome(val status: Int, val message: String, val accepted: Int = 0)

/** O que o envio precisa do backend (facilita o teste). A impl. real renova o token uma vez no 401. */
interface SmartboxBackend {
    /** 409 (já existia) deve chegar aqui como sucesso. */
    fun registerModule(farmId: Long, moduleId: String): HttpOutcome
    fun sendVisits(farmId: Long, moduleId: String, readings: List<PendingReading>): HttpOutcome
}

data class UploadSummary(
    val sentReadings: Int,
    val accepted: Int,
    val rejectedReadings: Int,
    /** Leituras pendentes de smartbox de outra fazenda (ficam paradas). */
    val otherFarmPending: Int,
    val errors: List<String>,
    /** Motivo de ter parado no meio (rede, 401…). null = foi até o fim. */
    val stoppedBecause: String?,
    /** true = parou por rede/servidor: vale tentar de novo depois. */
    val retryLater: Boolean,
)

/**
 * Envio das leituras baixadas ao backend (seções 9.2 e 9.3 do guia). Porte do
 * uploader.ts do mobile.
 *
 * Regra de fazenda: só sobe leitura baixada de smartbox que ANUNCIAVA a fazenda
 * do aparelho. Sem isso, uma descarga numa smartbox de outra fazenda cadastraria
 * o módulo na fazenda errada.
 *
 * Classificação (igual à do firmware):
 *   2xx              → enviado (o lote inteiro, inclusive descartadas/duplicadas)
 *   400 / 422        → recusado (reenviar travaria a fila)
 *   401              → a impl. já renovou e repetiu; ainda 401 = para tudo.
 *                      Inclui "Unauthorized to manage this farm" do farmValidator:
 *                      como TODO módulo aqui é da mesma fazenda, parar é o certo.
 *   404              → módulo não cadastrado: cadastra e reenvia UMA vez (por lote)
 *   403 / outro 4xx  → deixa pendente, segue para o próximo módulo
 *   408/429/5xx/rede → deixa pendente e PARA o envio
 */
object SmartboxUploader {
    const val BATCH_SIZE = 250

    enum class Verdict { SENT, REJECTED, STOP, SKIP_MODULE, NOT_REGISTERED }

    fun classify(status: Int): Verdict = when {
        status == 0 -> Verdict.STOP
        status in 200..299 -> Verdict.SENT
        status == 400 || status == 422 -> Verdict.REJECTED
        status == 404 -> Verdict.NOT_REGISTERED
        status == 401 -> Verdict.STOP
        status == 408 || status == 429 || status >= 500 || status < 400 -> Verdict.STOP
        else -> Verdict.SKIP_MODULE
    }

    /** ISO-8601 UTC sem milissegundos: `2026-11-04T13:05:20Z`. */
    fun toReadingTimestamp(ts: Long): String = Instant.ofEpochSecond(ts).toString()

    private class Stop(val reason: String, val retry: Boolean) : Exception(reason)

    /** Um envio completo para a fazenda do aparelho. Não lança: o resumo diz o que subiu e por que parou. */
    fun uploadPending(farmId: Long, repo: SmartboxRepository, api: SmartboxBackend, now: () -> Long): UploadSummary {
        var sent = 0
        var accepted = 0
        var rejected = 0
        val errors = mutableListOf<String>()
        val otherFarm = repo.countPendingOtherFarms(farmId)

        fun register(moduleId: String): Boolean {
            val r = api.registerModule(farmId, moduleId)
            return when (classify(r.status)) {
                Verdict.SENT -> { repo.setRegistered(moduleId, farmId); true }
                Verdict.STOP -> throw Stop(r.message, retry = r.status != 401)
                else -> {
                    // 400/422/403/404/outros: desiste SÓ deste módulo; as leituras ficam pendentes.
                    errors += "$moduleId: cadastro falhou (${r.message})"
                    false
                }
            }
        }

        var stopped: Stop? = null
        try {
            modules@ for (moduleId in repo.modulesWithPending(farmId)) {
                if (repo.getModule(moduleId)?.registeredFarmId != farmId && !register(moduleId)) continue

                while (true) {
                    val batch = repo.nextPendingBatch(moduleId, farmId, BATCH_SIZE)
                    if (batch.isEmpty()) break
                    var retriedNotFound = false
                    while (true) {
                        val r = api.sendVisits(farmId, moduleId, batch)
                        when (classify(r.status)) {
                            Verdict.SENT -> {
                                repo.markSent(batch, moduleId, r.accepted, now())
                                sent += batch.size
                                accepted += r.accepted
                                break
                            }
                            Verdict.REJECTED -> {
                                repo.markRejected(batch, r.message)
                                rejected += batch.size
                                break
                            }
                            Verdict.NOT_REGISTERED -> {
                                if (!retriedNotFound) {
                                    retriedNotFound = true
                                    repo.setRegistered(moduleId, null)
                                    if (register(moduleId)) continue
                                    continue@modules
                                }
                                errors += "$moduleId: módulo não encontrado nesta fazenda (404) mesmo depois do cadastro"
                                continue@modules
                            }
                            Verdict.STOP -> throw Stop(
                                if (r.status == 401) "Token recusado (401) mesmo depois de renovar" else r.message,
                                retry = r.status != 401,
                            )
                            Verdict.SKIP_MODULE -> {
                                errors += "$moduleId: ${r.message}"
                                continue@modules
                            }
                        }
                    }
                }
            }
            repo.cleanupSent(now())
        } catch (e: Stop) {
            stopped = e
        }
        return UploadSummary(sent, accepted, rejected, otherFarm, errors, stopped?.reason, stopped?.retry ?: false)
    }
}
