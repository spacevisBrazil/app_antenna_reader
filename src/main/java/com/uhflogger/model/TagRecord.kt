package com.uhflogger.model

data class TagRecord(
    val epc        : String,
    val rssi       : Int,
    val antenna    : Int,
    val androidTs  : Long,
    val latitude   : String = "",
    val longitude  : String = "",
    val bearing    : String = "",
    val temperature: String = ""   // Winnix only: start temp on first tag, stop temp on last tag, empty otherwise
) {
    fun toCsvLine(): String =
        "$epc,$rssi,$antenna,$androidTs,$latitude,$longitude,$bearing,$temperature"

    companion object {
        const val CSV_HEADER = "EPC,RSSI,Antenna,Timestamp,Latitude,Longitude,Bearing,Temperature"
    }
}