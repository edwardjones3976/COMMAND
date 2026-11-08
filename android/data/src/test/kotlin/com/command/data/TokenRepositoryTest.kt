package com.command.data

import androidx.test.core.app.ApplicationProvider
import com.command.data.db.HandshakeTokenRow
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.TagId
import com.command.domain.handshake.TokenHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The registered codes, on the real schema: only a hash is kept, and nothing the screens read carries it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TokenRepositoryTest {
    private val f = Fixture(ApplicationProvider.getApplicationContext())
    private val repo = TokenRepository(f.db, f.clock)

    @After fun tearDown() = f.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }
    private fun rows() = runBlocking { f.db.handshakeTokens().forMode(HandshakeMode.QR) }
    private fun listed(mode: HandshakeMode = HandshakeMode.QR) = runBlocking { repo.observe(mode).first() }
    private suspend fun saved(label: String, payload: String, mode: HandshakeMode = HandshakeMode.QR): String =
        (repo.register(mode, label, payload) as RegisterResult.Saved).id

    @Test
    fun `a code is saved as the hash of its payload with its label, and is listed without the hash`() = run {
        val id = saved("Fridge", "command-qr:abc")

        val row = rows().single()
        assertEquals(id, row.id)
        assertEquals("Fridge", row.label)
        assertEquals(TokenHash.of("command-qr:abc"), row.hash)
        assertEquals(f.t0, row.createdAt)
        assertEquals(listOf(Token(id, "Fridge", f.t0)), listed())
        assertEquals(1, repo.count(HandshakeMode.QR))
    }

    @Test
    fun `the payload is never stored, and nothing the screens read says the hash or the payload`() = run {
        val result = repo.register(HandshakeMode.QR, "Fridge", "command-qr:secret-payload")
        val hash = TokenHash.of("command-qr:secret-payload")

        assertFalse(rows().any { it.label.contains("secret-payload") || it.hash.contains("secret-payload") })
        assertEquals(listOf("createdAt", "id", "label"), Token::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") }.sorted())
        for (shown in listOf(result.toString(), listed().toString())) {
            assertFalse(shown, shown.contains(hash))
            assertFalse(shown, shown.contains("secret-payload"))
        }
    }

    @Test
    fun `a blank payload is not a code, and nothing is written`() = run {
        assertEquals(RegisterResult.BlankPayload, repo.register(HandshakeMode.QR, "Fridge", ""))
        assertEquals(RegisterResult.BlankPayload, repo.register(HandshakeMode.QR, "Fridge", "  \n\t "))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a label is trimmed, and must be one to forty characters`() = run {
        assertEquals(RegisterResult.BadLabel, repo.register(HandshakeMode.QR, "", "one"))
        assertEquals(RegisterResult.BadLabel, repo.register(HandshakeMode.QR, "   ", "one"))
        assertEquals(RegisterResult.BadLabel, repo.register(HandshakeMode.QR, "x".repeat(41), "one"))
        assertTrue(rows().isEmpty())

        saved("  Desk  ", "one")
        saved(" " + "y".repeat(40) + " ", "two")
        assertEquals(setOf("Desk", "y".repeat(40)), rows().map { it.label }.toSet())
    }

    @Test
    fun `a label is counted in characters, so forty that are not all in one code unit still fit`() = run {
        val forty = "🔑".repeat(40)
        saved(forty, "one")
        assertEquals(RegisterResult.BadLabel, repo.register(HandshakeMode.QR, forty + "🔑", "two"))
        assertEquals(listOf(forty), rows().map { it.label })
    }

    @Test
    fun `the payload is trimmed before it is hashed, so the same code with spaces round it is the same code`() = run {
        saved("Fridge", "  command-qr:abc\n")
        assertEquals(TokenHash.of("command-qr:abc"), rows().single().hash)
        assertEquals(RegisterResult.Duplicate("Fridge"), repo.register(HandshakeMode.QR, "Desk", "command-qr:abc"))
        assertTrue(repo.matches(HandshakeMode.QR, " command-qr:abc "))
    }

    @Test
    fun `a code already registered for the way is a duplicate that names the one there, and nothing is written`() = run {
        saved("Fridge", "command-qr:abc")
        assertEquals(RegisterResult.Duplicate("Fridge"), repo.register(HandshakeMode.QR, "Fridge again", "command-qr:abc"))
        assertEquals(1, rows().size)
    }

    @Test
    fun `duplicates are by way, so the same hash for another way is not one`() = run {
        saved("Fridge", "same")
        val sameHash = TokenHash.of("same")
        assertTrue(repo.registerTag("Fridge tag", sameHash) is RegisterResult.Saved)
        assertEquals(1, repo.count(HandshakeMode.QR))
        assertEquals(1, repo.count(HandshakeMode.NFC))
        assertEquals(listOf("Fridge tag"), listed(HandshakeMode.NFC).map { it.label })
    }

    @Test
    fun `a way holds at most twenty codes, the twenty-first is refused, and another way is counted apart`() = run {
        repeat(20) { saved("Code $it", "payload-$it") }
        assertEquals(RegisterResult.Full, repo.register(HandshakeMode.QR, "One more", "payload-20"))
        assertEquals(20, repo.count(HandshakeMode.QR))
        assertTrue(repo.registerTag("Tag", tagHash(20)) is RegisterResult.Saved)
        assertEquals(1, repo.count(HandshakeMode.NFC))
    }

    // --- tags (M13b): saved and matched by the fingerprint made on the reader thread --------------------------------

    private fun tagHash(n: Int) = TokenHash.ofTag(TagId(byteArrayOf(0x04, 0x10, 0x20, 0x30, 0x40, 0x50, n.toByte())))
    private fun tagRows() = runBlocking { f.db.handshakeTokens().forMode(HandshakeMode.NFC) }

    @Test
    fun registerTagStoresHashOnly() = run {
        val hash = tagHash(1)
        val result = repo.registerTag("  Key ring ", hash)
        val id = (result as RegisterResult.Saved).id

        val row = tagRows().single()
        assertEquals(id, row.id)
        assertEquals(HandshakeMode.NFC, row.mode)
        assertEquals("Key ring", row.label)
        assertEquals(hash, row.hash)
        assertEquals(f.t0, row.createdAt)
        assertTrue("no QR row", rows().isEmpty())
        // No column holds the id: the row has only these fields, and none of them is the id's hex.
        assertEquals(
            listOf("createdAt", "hash", "id", "label", "mode"),
            row.javaClass.declaredFields.map { it.name }.filter { !it.startsWith("$") && it != "Companion" }.sorted(),
        )
        for (value in listOf(row.id, row.label, row.hash)) assertFalse(value, value.contains("04102030405001"))
        assertFalse(result.toString().contains(hash))
        assertTrue(repo.matchesTag(hash))
    }

    @Test
    fun tagAndQrCountedSeparately() = run {
        repeat(20) { saved("Code $it", "payload-$it") }
        repeat(20) { assertTrue(repo.registerTag("Tag $it", tagHash(it)) is RegisterResult.Saved) }
        assertEquals(RegisterResult.Full, repo.registerTag("One more", tagHash(20)))
        assertEquals(RegisterResult.Full, repo.register(HandshakeMode.QR, "One more", "payload-20"))
        assertEquals(20, repo.count(HandshakeMode.QR))
        assertEquals(20, repo.count(HandshakeMode.NFC))
    }

    @Test
    fun duplicateTagByHash() = run {
        repo.registerTag("Fridge", tagHash(1))
        assertEquals(RegisterResult.Duplicate("Fridge"), repo.registerTag("Fridge again", tagHash(1)))
        repeat(19) { repo.registerTag("Tag $it", tagHash(100 + it)) }
        assertEquals(20, repo.count(HandshakeMode.NFC))
        assertEquals("a duplicate is told before a full way", RegisterResult.Duplicate("Fridge"), repo.registerTag("Again", tagHash(1)))
        assertEquals(RegisterResult.BadLabel, repo.registerTag("  ", tagHash(2)))
        assertEquals(RegisterResult.BadLabel, repo.registerTag("x".repeat(TokenRepository.MAX_LABEL + 1), tagHash(2)))
    }

    @Test
    fun malformedTagHashIsBlank() = run {
        for (bad in listOf("", "  ", "abc", tagHash(1).uppercase(), tagHash(1) + "0", " " + tagHash(1), "04a1b2c3d4e5f6")) {
            assertEquals("'$bad'", RegisterResult.BlankPayload, repo.registerTag("Tag", bad))
        }
        assertTrue(tagRows().isEmpty())
    }

    @Test
    fun matchesTagFalseForMalformed() = run {
        repo.registerTag("Tag", tagHash(1))
        assertTrue(repo.matchesTag(tagHash(1)))
        assertFalse(repo.matchesTag(tagHash(2)))
        for (bad in listOf("", "abc", tagHash(1).uppercase(), tagHash(1) + " ")) assertFalse("'$bad'", repo.matchesTag(bad))
    }

    @Test
    fun stringApiWithNfcModeIsBlankAndNeverMatches() = run {
        repo.registerTag("Tag", tagHash(1))
        for (mode in HandshakeMode.values().filter { it != HandshakeMode.QR }) {
            assertEquals("$mode", RegisterResult.BlankPayload, repo.register(mode, "Tag", "command-qr:abc"))
            assertFalse("$mode", repo.matches(mode, "command-qr:abc"))
            assertFalse("$mode: the string API never reaches the tag rows", repo.matches(mode, tagHash(1)))
        }
        assertEquals(1, repo.count(HandshakeMode.NFC))
        assertTrue(rows().isEmpty())

        // Rows of every other way whose hash is exactly the code's own fingerprint (no screen can make these): only the mode guard keeps
        // the string API from finding them.
        for (mode in HandshakeMode.values().filter { it != HandshakeMode.QR }) {
            f.db.handshakeTokens().insert(HandshakeTokenRow("planted-$mode", mode, "Planted", TokenHash.of("command-qr:abc"), 1L))
            assertFalse("$mode: the string API is codes only, whatever the rows hold", repo.matches(mode, "command-qr:abc"))
        }
    }

    @Test
    fun qrHashOfSameTextDoesNotMatchTag() = run {
        // A code whose text is a tag's hex is a different fingerprint, and the two ways never see each other's rows.
        saved("Code", "04102030405001")
        assertFalse(repo.matchesTag(TokenHash.of("04102030405001")))
        assertFalse(repo.matchesTag(tagHash(1)))
        repo.registerTag("Tag", tagHash(1))
        assertFalse(repo.matches(HandshakeMode.QR, tagHash(1)))
        assertTrue(repo.matchesTag(tagHash(1)))
    }

    @Test
    fun `a full way still says a code is a duplicate, since that is what the person needs to know`() = run {
        repeat(20) { saved("Code $it", "payload-$it") }
        assertEquals(RegisterResult.Duplicate("Code 3"), repo.register(HandshakeMode.QR, "Again", "payload-3"))
    }

    @Test
    fun `deleting a code makes room under the limit`() = run {
        val ids = (0 until 20).map { saved("Code $it", "payload-$it") }
        repo.delete(ids.first())
        assertTrue(repo.register(HandshakeMode.QR, "One more", "payload-20") is RegisterResult.Saved)
        assertEquals(20, repo.count(HandshakeMode.QR))
    }

    @Test
    fun `a registered payload matches, any other does not, nor a blank one, nor the same payload for another way`() = run {
        saved("Fridge", "command-qr:abc")
        assertTrue(repo.matches(HandshakeMode.QR, "command-qr:abc"))
        assertFalse(repo.matches(HandshakeMode.QR, "command-qr:abd"))
        assertFalse(repo.matches(HandshakeMode.QR, ""))
        assertFalse(repo.matches(HandshakeMode.QR, "   "))
        assertFalse(repo.matches(HandshakeMode.NFC, "command-qr:abc"))
    }

    @Test
    fun `with no code registered nothing matches`() = run {
        assertFalse(repo.matches(HandshakeMode.QR, "command-qr:abc"))
        assertEquals(0, repo.count(HandshakeMode.QR))
    }

    @Test
    fun `a rename trims the label and keeps the hash, a bad label is refused, and a gone code says so`() = run {
        val id = saved("Fridge", "command-qr:abc")
        val hash = rows().single().hash

        assertEquals(RenameResult.Renamed, repo.rename(id, "  Kitchen "))
        assertEquals(listOf("Kitchen"), listed().map { it.label })
        assertEquals(hash, rows().single().hash)

        assertEquals(RenameResult.BadLabel, repo.rename(id, "  "))
        assertEquals(RenameResult.BadLabel, repo.rename(id, "z".repeat(41)))
        assertEquals(listOf("Kitchen"), listed().map { it.label })

        assertEquals(RenameResult.Gone, repo.rename("no-such-id", "Desk"))
        assertTrue(repo.matches(HandshakeMode.QR, "command-qr:abc"))
    }

    @Test
    fun `a deleted code is gone from the list and no longer matches, and the others stay`() = run {
        val fridge = saved("Fridge", "one")
        saved("Desk", "two")

        repo.delete(fridge)

        assertEquals(listOf("Desk"), listed().map { it.label })
        assertFalse(repo.matches(HandshakeMode.QR, "one"))
        assertTrue(repo.matches(HandshakeMode.QR, "two"))
        repo.delete("no-such-id")
        assertEquals(1, repo.count(HandshakeMode.QR))
    }

    @Test
    fun `codes are listed in the order they were registered`() = run {
        saved("B", "one")
        f.clock.advance(1_000)
        saved("A", "two")
        f.clock.advance(1_000)
        saved("C", "three")
        assertEquals(listOf("B", "A", "C"), listed().map { it.label })
    }

    @Test
    fun `registering the same payload many times at once writes one row, and every other answer is the duplicate`() = runBlocking {
        val results = (0 until 16).map { i ->
            async(Dispatchers.Default) { repo.register(HandshakeMode.QR, "Try $i", "command-qr:same") }
        }.awaitAll()

        assertEquals(1, rows().size)
        assertEquals(1, results.count { it is RegisterResult.Saved })
        val winner = rows().single().label
        assertEquals(15, results.count { it == RegisterResult.Duplicate(winner) })
    }

    @Test
    fun `registering many codes at once never goes past twenty`() = runBlocking {
        val results = (0 until 30).map { i ->
            async(Dispatchers.Default) { repo.register(HandshakeMode.QR, "Code $i", "payload-$i") }
        }.awaitAll()

        assertEquals(20, rows().size)
        assertEquals(20, results.count { it is RegisterResult.Saved })
        assertEquals(10, results.count { it == RegisterResult.Full })
    }
}
