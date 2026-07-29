package com.uhflogger.decoder

import com.uhflogger.model.TagRecord

/**
 * Decoder para o protocolo Winnix/RealID HYM750E.
 *
 * Estrutura do frame:
 *   [0-1] 0xA5 0x5A  cabeçalho
 *   [2-3] Length      big-endian, tamanho total do frame
 *   [4]   Command     0x83 = Continue Inventory Response
 *   [5..N-3] Data
 *   [N-2] Check      XOR dos bytes[2..N-3] (Length+Command+Data, sem cabeçalho)
 *   [N-1] 0x0D
 *   [N]   0x0A
 *
 * Dados da resposta de inventário (0x83):
 *   PC(2) + EPC(n) + RSSI(2, int16 com sinal / 10.0 = dBm) + AntNum(1) + [Freq(3)]
 *
 * Cálculo do BCC:
 *   XOR de (bytes de Length + Command + Data), NÃO inclui o cabeçalho 0xA5 0x5A
 */
class WinnixProtocolDecoder {

    private val accumulator = ArrayDeque<Byte>(8192)

    fun feed(
        data     : ByteArray,
        latitude : String = "",
        longitude: String = "",
        bearing  : String = "",
        gnssSpeed        : String = "",
        locationTimestamp: String = "",
        locationProvider : String = ""
    ): List<TagRecord> {
        val results = mutableListOf<TagRecord>()
        for (b in data) accumulator.addLast(b)

        while (accumulator.size >= 8) {
            // Localiza cabeçalho 0xA5 0x5A
            val startIdx = findHeader()
            if (startIdx < 0) {
                val last = accumulator.last()
                accumulator.clear()
                accumulator.addLast(last)
                break
            }
            if (startIdx > 0) repeat(startIdx) { accumulator.removeFirst() }
            if (accumulator.size < 6) break

            val buf    = accumulator.toByteArray()
            val length = (buf[2].toInt() and 0xFF shl 8) or (buf[3].toInt() and 0xFF)

            if (accumulator.size < length) break

            val packet = ByteArray(length) { accumulator.removeFirst() }

            // Processa apenas respostas de inventário (0x83)
            if (packet[4].toInt() and 0xFF != 0x83) continue

            val tag = decodeTag(packet, latitude, longitude, bearing, gnssSpeed, locationTimestamp, locationProvider)
            if (tag != null) results.add(tag)
        }
        return results
    }

    fun reset() = accumulator.clear()

    // -------------------------------------------------------------------------

    private fun findHeader(): Int {
        val arr = accumulator.toByteArray()
        for (i in 0 until arr.size - 1) {
            if (arr[i] == 0xA5.toByte() && arr[i + 1] == 0x5A.toByte()) return i
        }
        return if (arr.last() == 0xA5.toByte()) arr.size - 1 else -1
    }

    private fun decodeTag(
        packet   : ByteArray,
        latitude : String,
        longitude: String,
        bearing  : String,
        gnssSpeed        : String = "",
        locationTimestamp: String = "",
        locationProvider : String = ""
    ): TagRecord? {
        return try {
            // data = packet[5..-3] (remove cabeçalho(2)+length(2)+cmd(1) no início, check(1)+fim(2) no final)
            val data = packet.copyOfRange(5, packet.size - 3)
            if (data.size < 5) return null

            // PC word — 5 bits altos × 2 = comprimento do EPC em bytes
            val pc      = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val epcLen  = ((pc shr 11) and 0x1F) * 2
            if (data.size < 2 + epcLen + 3) return null

            val epc     = data.copyOfRange(2, 2 + epcLen)
                .joinToString("") { "%02X".format(it) }

            // RSSI: int16 com sinal big-endian, valor = raw / 10 dBm
            val rssiRaw = ((data[2 + epcLen].toInt() and 0xFF) shl 8) or
                    (data[2 + epcLen + 1].toInt() and 0xFF)
            val rssiSigned = if (rssiRaw > 32767) rssiRaw - 65536 else rssiRaw
            val rssi    = (rssiSigned / 10.0).toInt()

            val antenna = data[2 + epcLen + 2].toInt() and 0xFF

            TagRecord(
                epc       = epc,
                rssi      = rssi,
                antenna   = antenna,
                androidTs = System.currentTimeMillis(),
                latitude  = latitude,
                longitude = longitude,
                bearing   = bearing,
                gnssSpeed         = gnssSpeed,
                locationTimestamp = locationTimestamp,
                locationProvider  = locationProvider
            )
        } catch (_: Exception) { null }
    }
}