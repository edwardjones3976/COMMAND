package com.command.domain

import com.command.domain.handshake.TagId
import com.command.domain.handshake.TokenHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TokenHashTest {

    @Test
    fun `the hash is the lowercase hex SHA-256 of the versioned prefix and the payload, written out so a change is a change of this test`() {
        // printf 'COMMAND-QR-v1\nhello' | sha256sum
        assertEquals("e8b797ffafcf7c001896d898bc5d9d10bd99b2ae65cb6dabf25cf834112884f2", TokenHash.of("hello"))
        // printf 'COMMAND-QR-v1\ncommand-qr:AbC_-9' | sha256sum
        assertEquals("2d66f19de8c5a1433f6259117570d53a8e976a6f78925c02f03f882959444dc0", TokenHash.of("command-qr:AbC_-9"))
    }

    @Test
    fun `a payload outside ASCII is hashed as its UTF-8 bytes, pinned by a vector computed outside the code`() {
        // printf 'COMMAND-QR-v1\ncafé ✓' | sha256sum   (UTF-8: 63 61 66 c3 a9 20 e2 9c 93)
        assertEquals("7e8c0b2bdbff8821de7a171201722ab34c53e216f3d6c3e606540b3179e2bc2e", TokenHash.of("café ✓"))
    }

    @Test
    fun `whitespace around a payload is not part of it, and whitespace inside is`() {
        assertEquals(TokenHash.of("hello"), TokenHash.of("  hello\n"))
        assertEquals(TokenHash.of("hello"), TokenHash.of("\thello \r\n"))
        assertNotEquals(TokenHash.of("hello"), TokenHash.of("hel lo"))
        assertNotEquals("the case of a payload is part of it", TokenHash.of("hello"), TokenHash.of("Hello"))
    }

    @Test
    fun `a hash is 64 lowercase hex characters and is not the payload`() {
        for (payload in listOf("a", "command-qr:0123456789abcdefghijkl", "é ünïcode ✓", "x".repeat(4000))) {
            val hash = TokenHash.of(payload)
            assertTrue(hash, hash.matches(Regex("[0-9a-f]{64}")))
            assertTrue(!hash.contains(payload.trim()) || payload.length == 1)
        }
        assertNotEquals("UTF-8 bytes, not a single-byte charset", TokenHash.of("é"), TokenHash.of("e"))
    }

    @Test
    fun `an empty or blank payload is not a code`() {
        for (blank in listOf("", " ", "\n\t ", "\r\n")) {
            try {
                TokenHash.of(blank)
                fail("'$blank' must not hash")
            } catch (expected: IllegalArgumentException) {
                // A blank payload is refused, never hashed as though it were a code.
            }
        }
    }

    // --- tags (M13b) -------------------------------------------------------------------------------------------------

    private fun tag(vararg bytes: Int) = TagId(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun qrVectorsUnchanged() {
        assertEquals("COMMAND-QR-v1\n", TokenHash.PREFIX)
        assertEquals("e8b797ffafcf7c001896d898bc5d9d10bd99b2ae65cb6dabf25cf834112884f2", TokenHash.of("hello"))
        assertEquals("2d66f19de8c5a1433f6259117570d53a8e976a6f78925c02f03f882959444dc0", TokenHash.of("command-qr:AbC_-9"))
        assertEquals("7e8c0b2bdbff8821de7a171201722ab34c53e216f3d6c3e606540b3179e2bc2e", TokenHash.of("café ✓"))
    }

    @Test
    fun tagHashPinnedVector() {
        // printf 'COMMAND-NFC-v1\n04a1b2c3d4e5f6' | sha256sum
        assertEquals("COMMAND-NFC-v1\n", TokenHash.NFC_PREFIX)
        assertEquals(
            "d3a2ef130c08fe3e7a5e354254f0f2df17506f057db94abf038ad4b24839089e",
            TokenHash.ofTag(tag(0x04, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6)),
        )
    }

    @Test
    fun tagHashIsLowercase64Hex() {
        for (id in listOf(tag(0x00), tag(0xff, 0xfe, 0xfd, 0xfc), tag(0x04, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6), tag(*IntArray(10) { 0xab }))) {
            val hash = TokenHash.ofTag(id)
            assertTrue(hash, hash.matches(Regex("[0-9a-f]{64}")))
        }
        assertNotEquals(TokenHash.ofTag(tag(0x04, 0x01)), TokenHash.ofTag(tag(0x04, 0x02)))
    }

    @Test
    fun emptyTagIdRefused() {
        try {
            TokenHash.ofTag(TagId(ByteArray(0)))
            fail("a tag with no id must not hash")
        } catch (expected: IllegalArgumentException) {
            // An empty id is refused, never hashed as though it were a tag.
        }
    }

    @Test
    fun tagHashDiffersFromQrHashOfSameHex() {
        // printf 'COMMAND-QR-v1\n04a1b2c3d4e5f6' | sha256sum
        val qr = TokenHash.of("04a1b2c3d4e5f6")
        assertEquals("ae6662648adec5b062ca2a95c76d63f2d1c1fd32feb000c02dc517b2f1232a54", qr)
        assertNotEquals(qr, TokenHash.ofTag(tag(0x04, 0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6)))
    }

    @Test
    fun isHashAcceptsOnly64LowerHex() {
        assertTrue(TokenHash.isHash(TokenHash.of("hello")))
        assertTrue(TokenHash.isHash(TokenHash.ofTag(tag(0x04, 0x01))))
        assertTrue(TokenHash.isHash("0".repeat(64)))
        for (bad in listOf("", "abc", "0".repeat(63), "0".repeat(65), "A".repeat(64), "g".repeat(64), " " + "0".repeat(63), "0".repeat(63) + "\n")) {
            assertFalse("'$bad'", TokenHash.isHash(bad))
        }
    }
}
