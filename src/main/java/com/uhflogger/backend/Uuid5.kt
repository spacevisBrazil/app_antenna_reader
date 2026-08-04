package com.uhflogger.backend

import java.security.MessageDigest
import java.util.UUID

/**
 * UUID v5 (SHA-1, RFC 4122) — o mesmo algoritmo que o backend usa em
 * `services/mobile/v1/readingService.js`.
 *
 * POR QUE DETERMINÍSTICO
 * O identificador de cada leitura precisa ser SEMPRE o mesmo pro mesmo dado,
 * porque é ele que garante a idempotência do envio: o aparelho fica dias em
 * campo, perde sinal, reenvia lote, é morto pelo Android no meio do upload.
 * Um UUID aleatório obrigaria a guardar em disco o id de CADA leitura (são
 * centenas de milhares) só pra não duplicar no reenvio. Derivando o id do
 * próprio dado, não há estado nenhum a guardar — qualquer execução futura
 * chega no mesmo id sozinha.
 */
object Uuid5 {

    // Namespace fixo do módulo de antena. Não pode mudar: mudá-lo faria toda
    // leitura já enviada virar "leitura nova" no próximo reenvio.
    private val NAMESPACE: UUID = UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f")

    fun from(name: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(toBytes(NAMESPACE))
        md.update(name.toByteArray(Charsets.UTF_8))
        val hash = md.digest()

        // Version 5 e variante RFC 4122, exatamente como o backend faz.
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()

        val hex = hash.take(16).joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun toBytes(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        var msb = uuid.mostSignificantBits
        var lsb = uuid.leastSignificantBits
        for (i in 7 downTo 0) { out[i] = (msb and 0xFF).toByte(); msb = msb shr 8 }
        for (i in 15 downTo 8) { out[i] = (lsb and 0xFF).toByte(); lsb = lsb shr 8 }
        return out
    }
}
