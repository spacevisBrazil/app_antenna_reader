package com.uhflogger.spacevis

import com.uhflogger.spacevis.SpacevisProtocol as P
import com.uhflogger.spacevis.SpacevisSession.Validation
import com.uhflogger.spacevis.SpacevisSession.Warning

/** Textos mostrados ao operador (guia, seção 11). Puro, para teste na JVM. */
object SpacevisMessages {

    const val READER_LABEL = "RoyalRay"

    fun connectFailed(device: String) = "$device: falha ao conectar"

    fun usbNotSupported() = "$READER_LABEL só funciona por Bluetooth."

    /** null = validação passou. */
    fun validation(device: String, v: Validation): String? = when (v) {
        is Validation.Valid -> null
        is Validation.FirmwareNewer -> "$device: firmware do leitor é mais novo que o app. Atualize o app."
        Validation.NoResponse, is Validation.WrongModule ->
            "$device: leitor não respondeu como $READER_LABEL. Confira o tipo de leitor."
        is Validation.ConnectionLost -> connectFailed(device)
    }

    fun warning(w: Warning): String = when (w) {
        is Warning.PortsReduced ->
            "Leitor tem ${w.readerPorts} porta(s). Lendo em: ${P.portsLabel(w.effectiveMask)}."
        is Warning.PortRefused ->
            "Módulo recusou a ${P.portsLabel(w.refusedMask).lowercase()} (porta sem antena?). " +
                "Lendo em: ${P.portsLabel(w.effectiveMask)}."
        is Warning.NoPortExists ->
            "Nenhuma porta escolhida existe neste leitor (${w.readerPorts} porta(s))."
        Warning.ModuleOffline ->
            "Módulo RFID sem resposta. A leitura começa sozinha quando ele voltar."
        is Warning.BadAntenna ->
            "Antena da porta ${w.port} com sinal ruim (RL ${w.returnLossDb} dB). Confira antena, cabo e conector."
    }
}
