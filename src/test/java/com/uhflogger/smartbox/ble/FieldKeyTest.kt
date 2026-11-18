package com.uhflogger.smartbox.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Só chaves falsas aqui: a K de campo nunca entra em teste. */
class FieldKeyTest {

    @Test
    fun `aceita 32 bytes em hex`() {
        assertEquals(32, FieldKey.parse("ff".repeat(32))?.size)
        assertEquals(32, FieldKey.parse("  " + "AB".repeat(32) + "\n")?.size)
    }

    @Test
    fun `recusa vazio, hex invalido e tamanho errado`() {
        assertNull(FieldKey.parse(null))
        assertNull(FieldKey.parse(""))
        assertNull(FieldKey.parse("abc"))
        assertNull(FieldKey.parse("zz".repeat(32)))
        assertNull(FieldKey.parse("00".repeat(31)))
        assertNull(FieldKey.parse("00".repeat(33)))
    }

    @Test
    fun `recusa a chave de bancada`() {
        assertNull(FieldKey.parse(Bytes.toHex(FakeSmartboxFixtures.BENCH_KEY)))
    }
}
