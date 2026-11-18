package com.uhflogger.smartbox.ble

import com.uhflogger.smartbox.ble.Bytes.readI16
import com.uhflogger.smartbox.ble.Bytes.readU16
import com.uhflogger.smartbox.ble.Bytes.readU24
import com.uhflogger.smartbox.ble.Bytes.readU32
import com.uhflogger.smartbox.ble.Bytes.u8
import com.uhflogger.smartbox.ble.SmartboxProtocol as P

/**
 * Mensagens do protocolo BLE da smartbox (seções 3, 4.5, 5 e 6 do guia).
 * Puro: parse e montagem de bytes, sem rádio.
 */

/** Violação de protocolo: quem recebe derruba o link. */
class ProtocolException(message: String) : Exception(message)

// ── Estado (seção 3.2) ──────────────────────────────────────────────────────

enum class ActivationState { NONE, SAVED, ACTIVATED }

data class StateFlags(
    val configMode: Boolean,
    val realTime: Boolean,
    val wifi: Boolean,
    val activation: ActivationState,
    val farmFromKey: Boolean,
    val memoryFull: Boolean,
)

fun decodeStateFlags(state: Int): StateFlags {
    val activation = when ((state shr StateBit.ACTIVATION_SHIFT) and StateBit.ACTIVATION_MASK) {
        2 -> ActivationState.ACTIVATED
        1 -> ActivationState.SAVED
        else -> ActivationState.NONE
    }
    return StateFlags(
        configMode = state and StateBit.CONFIG_MODE != 0,
        realTime = state and StateBit.REAL_TIME != 0,
        wifi = state and StateBit.WIFI != 0,
        activation = activation,
        farmFromKey = state and StateBit.FARM_FROM_KEY != 0,
        memoryFull = state and StateBit.MEMORY_FULL != 0,
    )
}

/** Texto do motivo do modo configuração (seção 3.3). */
fun configReasonText(reason: Int): String = when (reason) {
    1 -> "sem configuração salva (de fábrica)"
    2 -> "botão apertado"
    3 -> "a rede Wi-Fi nova não conectou"
    else -> "Modo configuração"
}

// ── Anúncio (seção 3.1) ─────────────────────────────────────────────────────

data class Advertisement(
    val proto: Int,
    /** MAC dos dados (12 hex maiúsculos). É o `moduleId`, nunca o endereço BLE. */
    val moduleId: String,
    val state: Int,
    val reason: Int,
    /** Leituras guardadas esperando descarga (u24, satura). */
    val pending: Int,
    /** 0 = sem fazenda. */
    val farmId: Long,
    val configCounter: Int,
)

/** `d` começa no company ID (`E5 02`). Devolve null se não for uma smartbox. */
fun parseAdvertisement(d: ByteArray): Advertisement? {
    if (d.size < P.ADV_MIN_LENGTH ||
        u8(d, 0) != 0xE5 || u8(d, 1) != 0x02 ||
        d[2] != P.ADV_SIGNATURE[0] || d[3] != P.ADV_SIGNATURE[1]
    ) return null
    return Advertisement(
        proto = u8(d, 4),
        moduleId = Bytes.toHex(d.copyOfRange(5, 11), upper = true),
        state = u8(d, 11),
        reason = u8(d, 12),
        pending = readU24(d, 13),
        farmId = readU32(d, 16),
        configCounter = readU16(d, 20),
    )
}

/**
 * O Android entrega o manufacturer data SEM o company ID na frente
 * (ScanRecord.getManufacturerSpecificData(id)). Remonta o formato do guia.
 */
fun withCompanyId(dataAfterCompanyId: ByteArray): ByteArray = Bytes.concat(
    byteArrayOf((P.COMPANY_ID and 0xFF).toByte(), (P.COMPANY_ID shr 8).toByte()),
    dataAfterCompanyId,
)

/** Nome humano: `SPV-<6 últimos hex do MAC>`. */
fun displayName(moduleId: String): String = "SPV-${moduleId.takeLast(6)}"

// ── Respostas (seções 4.5 / 5.2) ────────────────────────────────────────────

class Response(val op: Int, val status: Int, val data: ByteArray)

/** `[op|0x80][status][dados]`. Op trocado = link perdido (quem chama derruba). */
fun parseResponse(expectedOp: Int, msg: ByteArray): Response {
    if (msg.size < 2) throw ProtocolException("Resposta curta demais")
    if (u8(msg, 0) != (expectedOp or P.RESPONSE_FLAG)) throw ProtocolException("Resposta de outro pedido")
    return Response(expectedOp, u8(msg, 1), msg.copyOfRange(2, msg.size))
}

/** Erro de status (1–5): NÃO quebra a sessão. */
class SmartboxStatusException(val op: Int, val status: Int, val data: ByteArray) :
    Exception(statusMessage(status, data)) {
    /** Texto do ESP no status 1 (ASCII, sem acento). */
    val text: String get() = if (status == Status.ERROR_TEXT) Bytes.toAscii(data) else ""
}

fun statusMessage(status: Int, data: ByteArray): String = when (status) {
    Status.OK -> "ok"
    Status.ERROR_TEXT -> Bytes.toAscii(data).ifEmpty { "A smartbox recusou o pedido" }
    Status.UNKNOWN_COMMAND -> "Comando desconhecido: atualize o firmware da smartbox"
    Status.UNAVAILABLE_IN_MODE -> "Não disponível neste modo da smartbox"
    Status.DIFFERENT_FARM -> "A smartbox já é de outra fazenda"
    Status.FARM_FROM_KEY -> "A fazenda desta smartbox vem da chave de ativação"
    else -> "Status $status"
}

// ── CHALLENGE (seção 4.5) ───────────────────────────────────────────────────

enum class SmartboxMode { CONFIG, OPERATION }

class Challenge(
    val nonceEsp: ByteArray,
    val moduleId: String,
    val proto: Int,
    val pending: Int,
    val mode: SmartboxMode,
    val state: Int,
    val reason: Int,
    val farmId: Long,
    val configCounter: Int,
    val firmware: String,
)

fun parseChallenge(msg: ByteArray): Challenge {
    if (msg.size < P.CHALLENGE_MIN_LENGTH || msg.size > P.CHALLENGE_MAX_LENGTH) {
        throw ProtocolException("CHALLENGE com tamanho inválido")
    }
    if (u8(msg, 0) != (Op.HI or P.RESPONSE_FLAG) || u8(msg, 1) != Status.OK) {
        throw ProtocolException("CHALLENGE inválido")
    }
    return Challenge(
        nonceEsp = msg.copyOfRange(2, 2 + P.NONCE_LENGTH),
        moduleId = Bytes.toHex(msg.copyOfRange(18, 18 + P.MAC_LENGTH), upper = true),
        proto = u8(msg, 24),
        pending = readU24(msg, 25),
        mode = if (u8(msg, 28) == 1) SmartboxMode.OPERATION else SmartboxMode.CONFIG,
        state = u8(msg, 29),
        reason = u8(msg, 30),
        farmId = readU32(msg, 31),
        configCounter = readU16(msg, 35),
        firmware = Bytes.toAscii(msg.copyOfRange(37, msg.size)),
    )
}

// ── LOTE / ACK (seção 6) ────────────────────────────────────────────────────

data class Reading(
    /** Hex maiúsculo dos `epc_len` primeiros bytes. */
    val epc: String,
    /** Porta 1..4. */
    val antenna: Int,
    val rssi: Int,
    /** Unix segundos (UTC). */
    val ts: Long,
)

data class LotePart(
    /** Tamanho do lote inteiro. 0 = acabou. */
    val total: Int,
    val lastSeq: Long,
    val part: Int,
    /** `k` da resposta: registros nesta parte (inclusive os descartados). */
    val count: Int,
    val readings: List<Reading>,
)

fun buildLoteRequest(part: Int): ByteArray = byteArrayOf(part.toByte())

fun parseLotePart(data: ByteArray): LotePart {
    if (data.size < P.LOTE_HEADER_LENGTH) throw ProtocolException("LOTE curto demais")
    val total = readU16(data, 0)
    val lastSeq = readU32(data, 2)
    val part = u8(data, 6)
    val count = u8(data, 7)
    if (count > P.LOTE_RECORDS_PER_PART) throw ProtocolException("LOTE com registros demais")
    if (data.size != P.LOTE_HEADER_LENGTH + count * P.LOTE_RECORD_LENGTH) {
        throw ProtocolException("LOTE com tamanho inválido")
    }
    val readings = ArrayList<Reading>(count)
    for (i in 0 until count) {
        val off = P.LOTE_HEADER_LENGTH + i * P.LOTE_RECORD_LENGTH
        val epcLen = u8(data, off)
        if (epcLen > P.EPC_MAX_LENGTH) throw ProtocolException("EPC inválido no LOTE")
        // EPC vazio não identifica animal e o backend recusaria o lote INTEIRO
        // (400 → recusadas). Descarta só o registro; o `count` segue valendo
        // para a conferência do lote, e o ACK tira ele da flash.
        if (epcLen == 0) continue
        readings += Reading(
            epc = Bytes.toHex(data.copyOfRange(off + 1, off + 1 + epcLen), upper = true),
            antenna = u8(data, off + 13),
            rssi = readI16(data, off + 14),
            ts = readU32(data, off + 16),
        )
    }
    return LotePart(total, lastSeq, part, count, readings)
}

fun buildAckRequest(lastSeq: Long): ByteArray = Bytes.u32(lastSeq)

/** Resposta do ACK: `[pendentes u32]`. */
fun parseAckResponse(data: ByteArray): Long {
    if (data.size != 4) throw ProtocolException("ACK com tamanho inválido")
    return readU32(data, 0)
}

// ── Fazenda (seção 10) ──────────────────────────────────────────────────────

fun assertFarmId(farmId: Long) {
    if (farmId < 0 || farmId > P.FARM_ID_MAX) throw ProtocolException("farmId fora do intervalo u32")
}

fun buildSetFarm(farmId: Long, force: Boolean): ByteArray {
    assertFarmId(farmId)
    return Bytes.concat(Bytes.u32(farmId), byteArrayOf(if (force) 1 else 0))
}

data class FarmResult(
    val farmId: Long,
    /** true = a fazenda veio da chave de ativação (Wi-Fi). */
    val fromKey: Boolean,
)

/** `[fazenda u32][origem u8]`: resposta ok e dados dos status 4/5. */
fun parseFarmResult(data: ByteArray): FarmResult {
    if (data.size < 5) throw ProtocolException("Resposta de fazenda curta demais")
    return FarmResult(readU32(data, 0), u8(data, 4) == 1)
}

// ── WIFI_SCAN (seções 5.1 e 12.3) ───────────────────────────────────────────

class WifiNetwork(
    /** Bytes exatos do SSID (pode não ser UTF-8 válido). */
    val ssid: ByteArray,
    val rssi: Int,
    val open: Boolean,
)

/**
 * `[rssi i8][aberta u8][tam u8][ssid tam B]` repetido. Item com tam 0 ou > 32
 * é descartado; lista truncada no meio para ali. O ESP não tira repetidas:
 * agrupa pelos bytes do SSID e fica com o sinal mais forte, mais forte primeiro.
 */
fun parseWifiScan(data: ByteArray): List<WifiNetwork> {
    val byKey = LinkedHashMap<String, WifiNetwork>()
    var p = 0
    while (p + 3 <= data.size) {
        val rssi = data[p].toInt()            // i8: o Byte do Kotlin já tem sinal
        val open = u8(data, p + 1) != 0
        val len = u8(data, p + 2)
        p += 3
        if (p + len > data.size) break
        val ssid = data.copyOfRange(p, p + len)
        p += len
        if (len == 0 || len > 32) continue
        val key = Bytes.toHex(ssid)
        val prev = byKey[key]
        if (prev == null || rssi > prev.rssi) byKey[key] = WifiNetwork(ssid, rssi, open)
    }
    return byKey.values.sortedByDescending { it.rssi }
}
