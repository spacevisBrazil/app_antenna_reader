package com.uhflogger.model

data class TagRecord(
    val epc        : String,
    val rssi       : Int,
    val antenna    : Int,
    val androidTs  : Long,
    val latitude   : String = "",
    val longitude  : String = "",
    val bearing    : String = "",
    val temperature: String = "",   // Winnix only: start temp on first tag, stop temp on last tag, empty otherwise
    val gnssSpeed        : String = "",   // location.speed (m/s) — vazio se hasSpeed()==false
    val locationTimestamp: String = "",   // location.time — sempre populado quando há fix, qualquer provider
    val locationProvider : String = ""    // "GNSS" ou "NETWORK"
) {
    fun toCsvLine(): String =
        "$epc,$rssi,$antenna,$androidTs,$latitude,$longitude,$bearing,$temperature,$gnssSpeed,$locationTimestamp,$locationProvider"

    companion object {
        const val CSV_HEADER = "EPC,RSSI,Antenna,Timestamp,Latitude,Longitude,Bearing,Temperature,GNSS Speed,Location Timestamp,Location Provider"
    }
}