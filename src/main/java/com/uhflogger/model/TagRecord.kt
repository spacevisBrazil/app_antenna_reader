package com.uhflogger.model

data class TagRecord(
    val epc: String,
    val rssi: Int,
    val antenna: Int,
    val androidTs: Long
) {
    fun toCsvLine(): String =
        "$epc,$rssi,$antenna,$androidTs"

    companion object {
        const val CSV_HEADER = "EPC,RSSI,Antenna,Timestamp"
    }
}
