package com.uhflogger.spacevis

import com.uhflogger.spacevis.SpacevisCommandChannel.Result
import com.uhflogger.spacevis.SpacevisProtocol as P

/**
 * Fluxo de sessão com um leitor SPACEVIS_PROTOCOL (guia, seção 10), sem Android:
 * validar (GET_INFO) → configurar (SET_ANTENAS, SET_POTENCIA, SET_MODO,
 * GET_STATUS) → START; e STOP → GET_STATUS no fim.
 *
 * Quem chama é responsável por já ter ligado a recepção (parser) antes de
 * [validate] — a resposta chega pelo mesmo stream das tags.
 */
class SpacevisSession(
    private val channel: SpacevisCommandChannel,
    private val expectedModule: Int = P.MODULE_ROYALRAY,
) {
    data class ReaderConfig(val antennaMask: Int, val powerDbm: Int, val mode: Int)

    sealed class Validation {
        data class Valid(val info: P.Info) : Validation()
        /** Sem resposta (ou status != OK): provavelmente o tipo configurado está errado. */
        object NoResponse : Validation()
        data class WrongModule(val info: P.Info) : Validation()
        data class FirmwareNewer(val info: P.Info) : Validation()
        data class ConnectionLost(val error: Throwable) : Validation()
    }

    /** Avisos ao usuário; nenhum deles interrompe a captura. */
    sealed class Warning {
        /** OK, mas o leitor tem menos portas que as pedidas. */
        data class PortsReduced(val readerPorts: Int, val requestedMask: Int, val effectiveMask: Int) : Warning()
        /** O módulo não liga porta sem antena; a máscara anterior continua. */
        data class PortRefused(val refusedMask: Int, val effectiveMask: Int) : Warning()
        /** Nenhuma porta pedida existe no leitor. */
        data class NoPortExists(val readerPorts: Int) : Warning()
        /** Módulo RFID fora; a leitura começa sozinha quando ele voltar. */
        object ModuleOffline : Warning()
        data class BadAntenna(val port: Int, val returnLossDb: Int) : Warning()
    }

    data class StartOutcome(
        val warnings: List<Warning>,
        /** Portas que de fato vão ser lidas (do SET_ANTENAS). */
        val effectiveMask: Int,
        /** null = desconhecida ou GET_STATUS sem resposta útil. */
        val startTemperatureC: Int?,
        /** Respostas inesperadas (PARAMETRO em potência/modo etc.) — só para log. */
        val anomalies: List<String>,
        /** Algum comando ficou sem resposta / escrita falhou: reconectar. */
        val connectionLost: Boolean,
    )

    data class StopOutcome(val stopAcknowledged: Boolean, val endTemperatureC: Int?)

    fun validate(): Validation {
        return when (val r = channel.send(P.getInfo())) {
            is Result.WriteFailed -> Validation.ConnectionLost(r.error)
            Result.NoResponse -> Validation.NoResponse
            is Result.Response -> {
                val info = P.parseInfo(r.payload) ?: return Validation.NoResponse
                when {
                    info.status != P.ST_OK -> Validation.NoResponse
                    info.protoVersion > P.PROTO_VERSION -> Validation.FirmwareNewer(info)
                    info.protoVersion != P.PROTO_VERSION -> Validation.WrongModule(info)
                    info.moduleType != expectedModule -> Validation.WrongModule(info)
                    else -> Validation.Valid(info)
                }
            }
        }
    }

    /**
     * Manda a config inteira, sem comparar com GET_CONFIG (o leitor pode tê-la
     * perdido numa falta de energia), e liga a leitura. Segura a trava de
     * comandos do começo ao fim: um STOP pedido no meio só sai depois do START.
     *
     * @param beforeStart chamado logo antes do START, ainda com a trava, com a
     *   temperatura inicial (null = desconhecida). Ponto para preparar a
     *   recepção de tags e para abortar (ex.: usuário já pediu parar):
     *   devolve false para não mandar o START.
     */
    fun configureAndStart(
        info: P.Info,
        config: ReaderConfig,
        beforeStart: (startTemperatureC: Int?) -> Boolean = { true },
    ): StartOutcome = channel.sequence {
        val warnings = mutableListOf<Warning>()
        val anomalies = mutableListOf<String>()
        var offlineWarned = false
        fun offline() { if (!offlineWarned) { warnings += Warning.ModuleOffline; offlineWarned = true } }
        fun lost(effective: Int, temp: Int? = null) =
            StartOutcome(warnings, effective, temp, anomalies, connectionLost = true)

        // 1. SET_ANTENAS
        val requested = config.antennaMask and P.ALL_PORTS_MASK
        var effective = requested
        val ant = channel.send(P.setAntennas(requested))
        if (ant !is Result.Response) return@sequence lost(effective)
        val antRes = P.parseAntennas(ant.payload)
        if (antRes == null) {
            anomalies += "SET_ANTENAS: resposta curta (${ant.payload.size} bytes)"
        } else {
            effective = antRes.effectiveMask
            when (antRes.status) {
                P.ST_OK -> if (effective != requested) {
                    warnings += Warning.PortsReduced(info.ports, requested, effective)
                }
                P.ST_RECUSOU -> warnings += Warning.PortRefused(requested and effective.inv() and P.ALL_PORTS_MASK, effective)
                P.ST_PARAMETRO -> warnings += Warning.NoPortExists(info.ports)
                P.ST_OFFLINE -> { offline(); effective = requested }
                else -> anomalies += "SET_ANTENAS: status ${antRes.status}"
            }
        }

        // 2. SET_POTENCIA (1 byte: a mesma potência em todas as portas)
        val pow = channel.send(P.setPower(config.powerDbm.coerceIn(P.POWER_MIN_DBM, P.POWER_MAX_DBM)))
        if (pow !is Result.Response) return@sequence lost(effective)
        when (pow.status) {
            P.ST_OK -> Unit
            P.ST_OFFLINE -> offline()
            else -> anomalies += "SET_POTENCIA(${config.powerDbm}): status ${pow.status}"
        }

        // 3. SET_MODO
        val mode = channel.send(P.setMode(config.mode))
        if (mode !is Result.Response) return@sequence lost(effective)
        when (mode.status) {
            P.ST_OK -> Unit
            P.ST_OFFLINE -> offline()
            else -> anomalies += "SET_MODO(${config.mode}): status ${mode.status}"
        }

        // 4. GET_STATUS: temperatura inicial e qualidade das antenas usadas
        val st = channel.send(P.getStatus())
        if (st !is Result.Response) return@sequence lost(effective)
        val status = P.parseStatus(st.payload)
        val startTemp = status?.temperatureC
        if (status == null) {
            anomalies += "GET_STATUS: resposta curta (${st.payload.size} bytes)"
        } else {
            for (port in P.portsOf(effective)) {
                val rl = status.returnLoss.getOrNull(port - 1) ?: continue
                if (rl < P.RL_GOOD_DB) warnings += Warning.BadAntenna(port, rl)
            }
        }

        if (!beforeStart(startTemp)) {
            return@sequence StartOutcome(warnings, effective, startTemp, anomalies + "START não enviado (abortado)", false)
        }

        // 5. START (OFFLINE não é erro: o ESP32 começa sozinho quando o módulo voltar)
        val start = channel.send(P.start())
        if (start !is Result.Response) return@sequence lost(effective, startTemp)
        when (start.status) {
            P.ST_OK -> Unit
            P.ST_OFFLINE -> offline()
            else -> anomalies += "START: status ${start.status}"
        }

        StartOutcome(warnings, effective, startTemp, anomalies, connectionLost = false)
    }

    /** STOP e temperatura final. Sem resposta (BT caído): o chamador loga e finaliza assim mesmo. */
    fun stop(): StopOutcome = channel.sequence {
        val stop = channel.send(P.stop())
        if (stop !is Result.Response) return@sequence StopOutcome(false, null)
        val st = channel.send(P.getStatus())
        val temp = (st as? Result.Response)?.let { P.parseStatus(it.payload)?.temperatureC }
        StopOutcome(true, temp)
    }
}
