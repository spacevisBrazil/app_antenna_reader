package com.uhflogger.spacevis

/**
 * SPACEVIS_PROTOCOL: o protocolo que o ESP32 dos leitores novos fala com o app
 * (Bluetooth Classic SPP). O ESP32 traduz para o módulo RFID — o app nunca fala
 * o protocolo do fabricante do módulo.
 *
 * Genérico de propósito: os próximos leitores também vão falar SPACEVIS_PROTOCOL.
 * O que é específico do RoyalRay é só [MODULE_ROYALRAY] e o nome na tela.
 *
 * Frame (os dois sentidos):
 *   0x55 0xAA | LEN | CMD | PAYLOAD (LEN bytes) | CRC_LSB | CRC_MSB
 * CRC-16/MCRF4XX sobre LEN, CMD e PAYLOAD (sem o 55 AA), LSB primeiro.
 *
 * Tudo aqui é contrato com o firmware. Referência: GUIA_INTEGRACAO_ROYALRAY_SPACEVIS.md.
 */
object SpacevisProtocol {

    const val SYNC_1: Int = 0x55
    const val SYNC_2: Int = 0xAA
    /** Frame = 55 AA + LEN + CMD + payload + CRC(2). */
    const val FRAME_OVERHEAD = 6
    /** O ESP32 descarta (em silêncio) qualquer frame app → leitor maior que isso. */
    const val MAX_APP_PAYLOAD = 16

    const val PROTO_VERSION = 1
    const val MODULE_ROYALRAY = 1

    // Comandos app → leitor. Resposta = CMD or 0x80.
    const val CMD_GET_INFO    = 0x01
    const val CMD_SET_ANTENAS = 0x02
    const val CMD_SET_POTENCIA = 0x03
    const val CMD_SET_MODO    = 0x04
    const val CMD_START       = 0x05
    const val CMD_STOP        = 0x06
    const val CMD_GET_CONFIG  = 0x07
    const val CMD_GET_STATUS  = 0x08

    const val RESPONSE_FLAG = 0x80
    /** Evento de tag, enviado sem pedido enquanto a leitura está ligada. */
    const val EVT_TAG = 0xC0

    // Status (byte [0] de toda resposta).
    const val ST_OK          = 0x00
    const val ST_PARAMETRO   = 0x01
    const val ST_RECUSOU     = 0x02
    const val ST_OFFLINE     = 0x03
    const val ST_DESCONHECIDO = 0x04

    const val MODE_CONTINUOUS = 0
    const val MODE_ADAPTIVE   = 1

    const val POWER_MIN_DBM = 0
    const val POWER_MAX_DBM = 33

    /** temp_C desconhecida. */
    const val TEMP_UNKNOWN = 0x7F
    /** rl de porta inexistente ou não medida. */
    const val RL_UNKNOWN = 0xFF
    /** Abaixo disso a antena/cabo/conector está ruim ou limítrofe. */
    const val RL_GOOD_DB = 10

    const val MAX_PORTS = 4
    const val ALL_PORTS_MASK = 0x0F

    fun responseCmd(cmd: Int): Int = cmd or RESPONSE_FLAG

    /** CRC-16/MCRF4XX: poly 0x1021 refletido (0x8408), init 0xFFFF, sem XOR final. */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = 0xFFFF
        for (i in from until to) {
            c = c xor (data[i].toInt() and 0xFF)
            repeat(8) {
                c = if (c and 1 == 1) (c ushr 1) xor 0x8408 else c ushr 1
            }
        }
        return c and 0xFFFF
    }

    fun buildFrame(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_APP_PAYLOAD) { "payload ${payload.size} > $MAX_APP_PAYLOAD" }
        val out = ByteArray(payload.size + FRAME_OVERHEAD)
        out[0] = SYNC_1.toByte()
        out[1] = SYNC_2.toByte()
        out[2] = payload.size.toByte()
        out[3] = cmd.toByte()
        payload.copyInto(out, 4)
        val crc = crc16(out, 2, 4 + payload.size)
        out[4 + payload.size] = (crc and 0xFF).toByte()
        out[5 + payload.size] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    fun getInfo(): ByteArray = buildFrame(CMD_GET_INFO)
    fun setAntennas(mask: Int): ByteArray = buildFrame(CMD_SET_ANTENAS, byteArrayOf((mask and ALL_PORTS_MASK).toByte()))
    fun setPower(dbm: Int): ByteArray {
        require(dbm in POWER_MIN_DBM..POWER_MAX_DBM) { "power $dbm fora de $POWER_MIN_DBM..$POWER_MAX_DBM" }
        return buildFrame(CMD_SET_POTENCIA, byteArrayOf(dbm.toByte()))
    }
    fun setPowerPerPort(dbm: IntArray): ByteArray {
        require(dbm.size == MAX_PORTS) { "esperado $MAX_PORTS valores" }
        require(dbm.all { it in POWER_MIN_DBM..POWER_MAX_DBM })
        return buildFrame(CMD_SET_POTENCIA, ByteArray(MAX_PORTS) { dbm[it].toByte() })
    }
    fun setMode(mode: Int): ByteArray {
        require(mode == MODE_CONTINUOUS || mode == MODE_ADAPTIVE) { "modo $mode inválido" }
        return buildFrame(CMD_SET_MODO, byteArrayOf(mode.toByte()))
    }
    fun start(): ByteArray = buildFrame(CMD_START)
    fun stop(): ByteArray = buildFrame(CMD_STOP)
    fun getConfig(): ByteArray = buildFrame(CMD_GET_CONFIG)
    fun getStatus(): ByteArray = buildFrame(CMD_GET_STATUS)

    // ─── Máscara de portas ───

    /** Portas (1..4) presentes na máscara, em ordem. */
    fun portsOf(mask: Int): List<Int> = (1..MAX_PORTS).filter { mask and (1 shl (it - 1)) != 0 }

    fun maskOf(ports: Collection<Int>): Int =
        ports.filter { it in 1..MAX_PORTS }.fold(0) { acc, p -> acc or (1 shl (p - 1)) }

    fun portsLabel(mask: Int): String {
        val ports = portsOf(mask)
        return when {
            ports.isEmpty() -> "nenhuma porta"
            ports.size == 1 -> "Porta ${ports[0]}"
            else -> "Portas " + ports.joinToString(", ")
        }
    }

    // ─── Respostas decodificadas ───

    data class Info(
        val status: Int,
        val protoVersion: Int,
        val moduleType: Int,
        val ports: Int,
        val fwHi: Int,
        val fwLo: Int,
    ) {
        val firmware: String get() = "$fwHi.$fwLo"
    }

    data class AntennasResult(val status: Int, val effectiveMask: Int)

    data class Config(
        val status: Int,
        val mask: Int,
        val powers: List<Int>,
        val mode: Int,
        val reading: Boolean,
    )

    data class Status(
        val status: Int,
        val moduleOk: Boolean,
        /** null = desconhecida (0x7F). */
        val temperatureC: Int?,
        val thermalLevel: Int,
        val inBurst: Boolean,
        /** Return loss das portas 1..4; null = inexistente/não medida (0xFF). */
        val returnLoss: List<Int?>,
    )

    data class TagEvent(val antenna: Int, val rssi: Int, val epcHex: String)

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    fun parseInfo(p: ByteArray): Info? =
        if (p.size < 6) null else Info(u(p[0]), u(p[1]), u(p[2]), u(p[3]), u(p[4]), u(p[5]))

    fun parseAntennas(p: ByteArray): AntennasResult? =
        if (p.size < 2) null else AntennasResult(u(p[0]), u(p[1]) and ALL_PORTS_MASK)

    fun parseConfig(p: ByteArray): Config? =
        if (p.size < 8) null
        else Config(u(p[0]), u(p[1]), listOf(u(p[2]), u(p[3]), u(p[4]), u(p[5])), u(p[6]), u(p[7]) == 1)

    fun parseStatus(p: ByteArray): Status? {
        if (p.size < 9) return null
        val rawTemp = u(p[2])
        return Status(
            status = u(p[0]),
            moduleOk = u(p[1]) == 1,
            temperatureC = if (rawTemp == TEMP_UNKNOWN) null else p[2].toInt(), // int8 com sinal
            thermalLevel = u(p[3]),
            inBurst = u(p[4]) == 1,
            returnLoss = (5..8).map { i -> u(p[i]).takeIf { it != RL_UNKNOWN } },
        )
    }

    /** Status do 1º byte; -1 se a resposta veio vazia. */
    fun statusOf(p: ByteArray): Int = if (p.isEmpty()) -1 else u(p[0])

    /** null = evento malformado (descartar, a leitura continua). */
    fun parseTagEvent(p: ByteArray): TagEvent? {
        if (p.size < 3) return null
        val epcLen = u(p[2])
        if (epcLen == 0 || epcLen > p.size - 3) return null
        val epc = StringBuilder(epcLen * 2)
        for (i in 3 until 3 + epcLen) epc.append(HEX[u(p[i]) ushr 4]).append(HEX[u(p[i]) and 0x0F])
        return TagEvent(antenna = u(p[0]), rssi = p[1].toInt(), epcHex = epc.toString())
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
