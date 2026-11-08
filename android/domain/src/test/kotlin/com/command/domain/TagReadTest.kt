package com.command.domain

import com.command.domain.handshake.TagId
import com.command.domain.handshake.TagRead
import com.command.domain.handshake.TokenHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What one touch produces: no id and a random id are not tags; a fixed id crosses only as its fingerprint. */
class TagReadTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun nullIdIsNoId() {
        assertSame(TagRead.NoId, TagRead.of(null))
    }

    @Test
    fun emptyIdIsNoId() {
        assertSame(TagRead.NoId, TagRead.of(ByteArray(0)))
    }

    @Test
    fun randomIdIsChanging() {
        assertSame(TagRead.Changing, TagRead.of(bytes(0x08, 0x9a, 0xbc, 0xde)))
    }

    @Test
    fun fixedIdIsSeenWithOfTagHash() {
        val id = bytes(0x04, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6)
        val read = TagRead.of(id)
        assertEquals(TagRead.Seen(TokenHash.ofTag(TagId(id))), read)
        assertEquals("d3a2ef130c08fe3e7a5e354254f0f2df17506f057db94abf038ad4b24839089e", (read as TagRead.Seen).hash)
        assertEquals(TagRead.Seen(read.hash).hashCode(), read.hashCode())
        assertNotEquals(read, TagRead.of(bytes(0x04, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf7)))
        assertEquals("a 4-byte id that is not random is a tag", TagRead.Seen(TokenHash.ofTag(TagId(bytes(0x04, 1, 2, 3)))), TagRead.of(bytes(0x04, 1, 2, 3)))
    }

    @Test
    fun seenToStringRedacted() {
        val read = TagRead.of(bytes(0x04, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6)) as TagRead.Seen
        val shown = read.toString()
        assertEquals("TagRead.Seen(redacted)", shown)
        assertFalse(shown.contains("04a1b2c3d4e5f6"))
        assertFalse(shown.contains(read.hash))
        assertFalse(shown.contains(read.hash.take(8)))
    }
}
