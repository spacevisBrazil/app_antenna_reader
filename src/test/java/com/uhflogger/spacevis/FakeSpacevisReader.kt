package com.uhflogger.spacevis

import com.uhflogger.spacevis.SpacevisProtocol as P
import com.uhflogger.spacevis.SpacevisProtocolTest.Companion.readerFrame

/**
 * Leitor falso: recebe os frames que o app escreve e devolve as respostas pelo
 * mesmo caminho do aparelho real (bytes → parser → canal). Responde de forma
 * síncrona, dentro do write — o canal já registrou a espera antes de escrever.
 */
class FakeSpacevisReader {
    var protoVersion = 1
    var moduleType = P.MODULE_ROYALRAY
    var ports = 1
    var antennaStatus = P.ST_OK
    /** null = efetiva = pedida & portas existentes. */
    var effectiveMaskOverride: Int? = null
    var powerStatus = P.ST_OK
    var modeStatus = P.ST_OK
    var startStatus = P.ST_OK
    var temperature = 36
    var returnLoss = intArrayOf(12, 0xFF, 0xFF, 0xFF)
    /** Comandos que o leitor "não ouve" (sem resposta). */
    val mute = mutableSetOf<Int>()
    /** Quantas vezes ignorar um comando antes de responder (simula perda). */
    val dropFirst = mutableMapOf<Int, Int>()
    var writeFails = false

    val received = mutableListOf<Int>()
    val tags = mutableListOf<P.TagEvent>()

    lateinit var channel: SpacevisCommandChannel
    private val parser = SpacevisFrameParser(onTag = { tags += it }, onResponse = { c, p -> channel.onResponse(c, p) })

    fun channel(timeoutMs: Long = 50, attempts: Int = 3): SpacevisCommandChannel =
        SpacevisCommandChannel(write = ::onWrite, timeoutMs = timeoutMs, attempts = attempts).also { channel = it }

    /** Bytes chegando do leitor (ex.: evento de tag no meio de uma resposta). */
    fun emit(bytes: ByteArray) = parser.feed(bytes)

    private fun onWrite(frame: ByteArray) {
        if (writeFails) throw java.io.IOException("broken pipe")
        val cmd = frame[3].toInt() and 0xFF
        received += cmd
        if (cmd in mute) return
        val drops = dropFirst[cmd] ?: 0
        if (drops > 0) { dropFirst[cmd] = drops - 1; return }
        val payload = frame.copyOfRange(4, frame.size - 2)
        val resp = when (cmd) {
            P.CMD_GET_INFO -> byteArrayOf(0, protoVersion.toByte(), moduleType.toByte(), ports.toByte(), 2, 8)
            P.CMD_SET_ANTENAS -> {
                val req = payload[0].toInt() and 0x0F
                val existing = (1 shl ports) - 1
                val eff = effectiveMaskOverride ?: (req and existing)
                byteArrayOf(antennaStatus.toByte(), eff.toByte())
            }
            P.CMD_SET_POTENCIA -> byteArrayOf(powerStatus.toByte())
            P.CMD_SET_MODO -> byteArrayOf(modeStatus.toByte())
            P.CMD_START -> byteArrayOf(startStatus.toByte())
            P.CMD_STOP -> byteArrayOf(0)
            P.CMD_GET_STATUS -> byteArrayOf(0, 1, temperature.toByte(), 0, 0) +
                ByteArray(4) { returnLoss[it].toByte() }
            else -> byteArrayOf(P.ST_DESCONHECIDO.toByte())
        }
        parser.feed(readerFrame(P.responseCmd(cmd), resp))
    }
}
