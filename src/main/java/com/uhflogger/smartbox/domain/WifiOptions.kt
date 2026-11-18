package com.uhflogger.smartbox.domain

import com.uhflogger.smartbox.ble.WifiNetwork

/**
 * Lista de redes para a tela Configurar (seção 12.3 do guia), das duas fontes:
 * - pela smartbox (WIFI_SCAN, só em modo configuração): bytes exatos do SSID;
 * - pelo aparelho (WifiManager): marca como INCOMPATÍVEL o que a smartbox não
 *   conecta, com o motivo, na ordem 5 GHz > Enterprise > WPA3.
 */
object WifiOptions {

    data class Option(
        /** Texto para a tela. */
        val ssid: String,
        /** Forma `%XX` exata dos bytes, para o SET_CONFIG. */
        val ssidEncoded: String,
        val rssi: Int,
        val open: Boolean,
        /** null = compatível. */
        val incompatibleReason: String? = null,
    ) {
        val compatible: Boolean get() = incompatibleReason == null
    }

    /** O que o aparelho viu (ScanResult resumido, para testar sem Android). */
    data class PhoneScan(val ssid: String, val frequencyMhz: Int, val capabilities: String, val rssi: Int)

    const val REASON_5GHZ = "só 5 GHz: a smartbox só conecta em 2,4 GHz"
    const val REASON_ENTERPRISE = "rede corporativa (usuário e senha): a smartbox não conecta"
    const val REASON_WPA3 = "segurança WPA3: a smartbox só conecta em WPA2 ou rede aberta"

    fun fromSmartbox(list: List<WifiNetwork>): List<Option> = list.map {
        Option(UrlForm.utf8Decode(it.ssid), UrlForm.percentEncodeBytes(it.ssid), it.rssi, it.open)
    }

    private fun isEnterprise(caps: String) = caps.contains("EAP")
    private fun isOpen(caps: String) =
        listOf("WPA", "RSN", "WEP", "SAE", "EAP", "OWE", "PSK").none { caps.contains(it) }
    private fun hasWpa2Psk(caps: String) = caps.contains("WPA2-PSK") || (caps.contains("RSN-PSK"))
    private fun isWpa3Only(caps: String) = caps.contains("SAE") && !hasWpa2Psk(caps)

    /** Motivo de uma entrada (null = compatível). */
    fun reasonOf(scan: PhoneScan): String? = when {
        scan.frequencyMhz >= 3000 -> REASON_5GHZ
        isEnterprise(scan.capabilities) -> REASON_ENTERPRISE
        isWpa3Only(scan.capabilities) -> REASON_WPA3
        isOpen(scan.capabilities) || hasWpa2Psk(scan.capabilities) || scan.capabilities.contains("WPA-PSK") -> null
        else -> REASON_WPA3
    }

    /**
     * Agrupa por SSID (vazio é ignorado). Se alguma entrada do SSID é
     * compatível, fica a compatível de sinal mais forte; senão, a de motivo mais
     * prioritário. Compatíveis primeiro, depois pelo sinal.
     */
    fun fromPhone(scans: List<PhoneScan>): List<Option> {
        val priority = listOf(REASON_5GHZ, REASON_ENTERPRISE, REASON_WPA3)
        return scans.filter { it.ssid.isNotEmpty() }
            .groupBy { it.ssid }
            .map { (ssid, entries) ->
                val withReason = entries.map { it to reasonOf(it) }
                val ok = withReason.filter { it.second == null }.maxByOrNull { it.first.rssi }
                if (ok != null) {
                    Option(ssid, UrlForm.formEncode(ssid), ok.first.rssi, isOpen(ok.first.capabilities))
                } else {
                    val best = withReason.minWith(compareBy({ priority.indexOf(it.second) }, { -it.first.rssi }))
                    Option(ssid, UrlForm.formEncode(ssid), best.first.rssi, isOpen(best.first.capabilities), best.second)
                }
            }
            .sortedWith(compareByDescending<Option> { it.compatible }.thenByDescending { it.rssi })
    }
}
