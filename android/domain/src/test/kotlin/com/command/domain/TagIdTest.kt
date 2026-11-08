package com.command.domain

import com.command.domain.handshake.TagId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tag's id is held only to hash it: it is copied, never shown, and compared by content. */
class TagIdTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun toStringRedacted() {
        val id = TagId(bytes(0x04, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6))
        assertEquals("TagId(redacted)", id.toString())
        assertFalse(id.toString().contains("a1b2"))
        assertEquals("a nested toString says no more", "[TagId(redacted)]", listOf(id).toString())
    }

    @Test
    fun copiesInput() {
        val source = bytes(0x04, 0x01, 0x02, 0x03)
        val id = TagId(source)
        val before = id.hex()
        source[1] = 0x7f
        assertEquals("04010203", before)
        assertEquals("mutating the source array changes nothing", before, id.hex())
        assertEquals(TagId(bytes(0x04, 0x01, 0x02, 0x03)), id)
    }

    @Test
    fun random4ByteWith08Detected() {
        assertTrue(TagId(bytes(0x08, 0x12, 0x34, 0x56)).isRandom)
        assertTrue(TagId(bytes(0x08, 0x00, 0x00, 0x00)).isRandom)
    }

    @Test
    fun fourByteWith04NotRandom() {
        assertFalse(TagId(bytes(0x04, 0x12, 0x34, 0x56)).isRandom)
        assertFalse(TagId(bytes(0x88, 0x12, 0x34, 0x56)).isRandom)
    }

    @Test
    fun sevenByteNotRandom() {
        assertFalse(TagId(bytes(0x08, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6)).isRandom)
        assertFalse("a short id that starts 0x08 is not the 4-byte random form", TagId(bytes(0x08, 0x01)).isRandom)
    }

    @Test
    fun emptyDetected() {
        assertTrue(TagId(ByteArray(0)).isEmpty)
        assertFalse(TagId(ByteArray(0)).isRandom)
        assertFalse(TagId(bytes(0x00)).isEmpty)
    }

    @Test
    fun equalByContent() {
        val a = TagId(bytes(0x04, 0xa1, 0xb2))
        val b = TagId(bytes(0x04, 0xa1, 0xb2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, TagId(bytes(0x04, 0xa1, 0xb3)))
        assertNotEquals(a, TagId(bytes(0x04, 0xa1)))
        assertEquals("lowercase hex, no separators", "04a1b2", a.hex())
        assertEquals("00ff10", TagId(bytes(0x00, 0xff, 0x10)).hex())
    }
}
