package com.uhflogger.decoder

import com.uhflogger.model.TagRecord

class ProtocolDecoder {

    private val accumulator = ArrayDeque<Byte>(8192)

    fun feed(data: ByteArray): List<TagRecord> {
        val results = mutableListOf<TagRecord>()
        for (b in data) accumulator.addLast(b)

        while (accumulator.size >= 8) {
            val startIdx = findStarter()
            if (startIdx < 0) {
                val last = accumulator.last()
                accumulator.clear()
                accumulator.addLast(last)
                break
            }
            repeat(startIdx) { accumulator.removeFirst() }
            if (accumulator.size < 8) break

            val buf       = accumulator.toByteArray()
            val cmd       = buf[2].toInt() and 0xFF
            val direction = buf[3].toInt() and 0xFF
            val lenLow    = buf[4].toInt() and 0xFF
            val lenHigh   = buf[5].toInt() and 0xFF
            val dataLen   = lenLow + lenHigh * 256
            val totalLen  = 7 + dataLen

            if (accumulator.size < totalLen) break

            val packet = ByteArray(totalLen) { accumulator.removeFirst() }

            if (cmd != 0x02 || direction != 0x03) continue

            val tag = decodeTagBody(packet, offset = 8, available = dataLen - 2)
            if (tag != null) results.add(tag)
        }
        return results
    }

    fun reset() = accumulator.clear()

    private fun findStarter(): Int {
        val arr = accumulator.toByteArray()
        for (i in 0 until arr.size - 1) {
            if (arr[i] == 0x43.toByte() && arr[i + 1] == 0x4D.toByte()) return i
        }
        return if (arr.last() == 0x43.toByte()) arr.size - 1 else -1
    }

    private fun decodeTagBody(packet: ByteArray, offset: Int, available: Int): TagRecord? {
        if (available < 3) return null

        val uhfClass = packet[offset].toInt() and 0xFF
        if (uhfClass != 0x08) return null

        val epcLen  = packet[offset + 1].toInt() and 0xFF
        val optCtrl = packet[offset + 2].toInt() and 0xFF

        if (available < 3 + epcLen) return null

        val epcBytes = packet.copyOfRange(offset + 3, offset + 3 + epcLen)
        val epcHex   = epcBytes.joinToString("") { "%02X".format(it) }

        var pos     = offset + 3 + epcLen
        var antenna = 0
        var rssi    = 0

        // Bit 7 → Antenna ID (1 byte)
        if (optCtrl and 0x80 != 0) {
            if (pos >= packet.size) return null
            antenna = packet[pos].toInt() and 0xFF
            pos += 1
        }

        // Bit 6 → RSSI (1 byte, negative dBm)
        if (optCtrl and 0x40 != 0) {
            if (pos >= packet.size) return null
            rssi = -(packet[pos].toInt() and 0xFF)
            pos += 1
        }

        // Bit 5 → PC value (2 bytes, skip)
        if (optCtrl and 0x20 != 0) pos += 2

        // Bit 4 → Area (1 byte, skip)
        if (optCtrl and 0x10 != 0) pos += 1

        // Bit 3 → Trigger (1 byte, skip)
        if (optCtrl and 0x08 != 0) pos += 1

        // Bit 2 → Private Data (4 bytes, skip)
        if (optCtrl and 0x04 != 0) pos += 4

        // Bit 1 → Reader Timestamp ignored, using Android time
        return TagRecord(
            epc       = epcHex,
            rssi      = rssi,
            antenna   = antenna,
            androidTs = System.currentTimeMillis()
        )
    }
}
