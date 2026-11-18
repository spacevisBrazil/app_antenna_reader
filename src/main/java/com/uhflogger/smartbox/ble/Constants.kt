package com.uhflogger.smartbox.ble

import java.util.UUID

/**
 * Contrato do protocolo BLE da smartbox (bebedouro), versão 2.
 *
 * Tudo que passa no rádio é contrato com o firmware e NÃO pode mudar do lado do
 * app: UUIDs, opcodes, status, tamanhos e prazos. Fonte: guia de implementação da
 * smartbox (seções 3 a 6). Porte de spacevis-mobile/src/features/smartbox/ble,
 * que está validado em campo.
 *
 * Inteiros no rádio são sempre little-endian.
 */
object SmartboxProtocol {
    const val VERSION = 2

    // ── Anúncio (Manufacturer Specific Data, seção 3.1) ─────────────────────
    /** Espressif. Outros ESP32 usam o mesmo company ID, por isso a assinatura. */
    const val COMPANY_ID = 0x02E5
    /** 'S' 'V' logo depois do company ID. */
    val ADV_SIGNATURE = byteArrayOf(0x53, 0x56)
    /** Tamanho mínimo dos dados a partir do company ID. */
    const val ADV_MIN_LENGTH = 22
    /** Quem anunciou nos últimos 12 s está "por perto". */
    const val NEARBY_WINDOW_MS = 12_000L

    // ── GATT (seção 4.2) ────────────────────────────────────────────────────
    val SERVICE_UUID: UUID = UUID.fromString("5a3c0001-8e2b-4f7d-9c61-2b7e4d1a90f3")
    /** app → ESP, write COM resposta. */
    val RX_UUID: UUID = UUID.fromString("5a3c0002-8e2b-4f7d-9c61-2b7e4d1a90f3")
    /** ESP → app, notify. */
    val TX_UUID: UUID = UUID.fromString("5a3c0003-8e2b-4f7d-9c61-2b7e4d1a90f3")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Bit somado ao opcode na resposta. */
    const val RESPONSE_FLAG = 0x80

    // ── Fragmentação (seção 4.4) ────────────────────────────────────────────
    const val PART_LAST_FLAG = 0x80
    const val PART_INDEX_MASK = 0x7F
    const val MAX_PARTS = 128
    const val MAX_PLAIN_MESSAGE = 1024
    const val GCM_TAG_LENGTH = 16
    const val MAX_WIRE_MESSAGE = MAX_PLAIN_MESSAGE + GCM_TAG_LENGTH
    /** Cabeçalho do ATT (3 B) + o nosso (1 B). */
    const val PART_OVERHEAD = 4
    const val DEFAULT_MTU = 23
    const val REQUESTED_MTU = 517

    // ── Handshake (seção 4.5) ───────────────────────────────────────────────
    const val NONCE_LENGTH = 16
    const val MAC_LENGTH = 6
    const val AUTH_LENGTH = 57
    const val AUTH_OK_LENGTH = 34
    const val CHALLENGE_MIN_LENGTH = 37
    const val CHALLENGE_MAX_LENGTH = 69
    const val SESSION_KEY_LENGTH = 16
    const val FIELD_KEY_LENGTH = 32
    /** Hora anterior a 2024 é ignorada pelo ESP. */
    const val MIN_VALID_TIME_MS = 1_704_067_200_000L

    // ── Descarga (seção 6) ──────────────────────────────────────────────────
    const val LOTE_RECORD_LENGTH = 20
    const val LOTE_HEADER_LENGTH = 8
    const val LOTE_RECORDS_PER_PART = 48
    const val LOTE_MAX_RECORDS = 250
    const val EPC_MAX_LENGTH = 12

    /** Menor e maior `farmId` que cabe na smartbox (u32, 0 = sem fazenda). */
    const val FARM_ID_MIN = 1L
    const val FARM_ID_MAX = 0xFFFF_FFFFL
}

/** Opcodes (seção 5.1). */
object Op {
    const val HI = 0x01
    const val AUTH = 0x02
    const val GET_DIAG = 0x10
    const val GET_EVENTS = 0x11
    const val GET_CONFIG = 0x12
    const val SET_CONFIG = 0x13
    const val SET_TIME = 0x14
    const val WIFI_SCAN = 0x15
    const val REBOOT = 0x16
    const val SET_FARM = 0x17
    const val FACTORY_RESET = 0x18
    const val LOTE = 0x20
    const val ACK = 0x21
}

/** Status, 2º byte da resposta (seção 5.2). */
object Status {
    const val OK = 0
    const val ERROR_TEXT = 1
    const val UNKNOWN_COMMAND = 2
    const val UNAVAILABLE_IN_MODE = 3
    const val DIFFERENT_FARM = 4
    const val FARM_FROM_KEY = 5
}

/** Bits do estado no anúncio (seção 3.2). */
object StateBit {
    const val CONFIG_MODE = 1 shl 0
    const val REAL_TIME = 1 shl 1
    const val WIFI = 1 shl 2
    const val FARM_FROM_KEY = 1 shl 5
    const val MEMORY_FULL = 1 shl 6
    const val ACTIVATION_SHIFT = 3
    const val ACTIVATION_MASK = 0b11
}

/** Prazos (seção 4.3). */
object Timeouts {
    /** Cada espera do handshake. O ESP derruba se o handshake inteiro passar de 5 s. */
    const val HANDSHAKE_MS = 5_000L
    const val CONNECT_MS = 10_000L
    const val RESPONSE_MS = 10_000L
    const val WIFI_SCAN_MS = 15_000L
    /** Entre tentativas de connect. */
    const val CONNECT_RETRY_DELAY_MS = 600L
    const val CONNECT_ATTEMPTS = 3
    /** Leituras do CCCD esperando `01 00`. */
    const val CCCD_READ_ATTEMPTS = 5
    const val CCCD_READ_DELAY_MS = 100L
}
