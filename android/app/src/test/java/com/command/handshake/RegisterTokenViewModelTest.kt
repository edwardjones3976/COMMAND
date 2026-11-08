package com.command.handshake

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.command.data.ConsentStore
import com.command.data.Token
import com.command.data.TokenRepository
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.TagId
import com.command.domain.handshake.TagRead
import com.command.domain.handshake.TokenHash
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentSource
import com.command.reveille.QrDecoder
import com.command.testing.android.awaitUntil
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The registered codes through the view model and a real database: what the list shows, what each answer of a registration says, and the new codes it makes. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class RegisterTokenViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var tokens: TokenRepository
    @Inject lateinit var consents: ConsentStore

    private lateinit var vm: RegisterTokenViewModel

    @Before
    fun setUp() {
        hilt.inject()
        vm = RegisterTokenViewModel(tokens, consents, FakeNfcStatus(), SavedStateHandle())
    }

    private fun nfcViewModel(nfc: NfcStatus = FakeNfcStatus(), saved: SavedStateHandle = SavedStateHandle(mapOf("mode" to "NFC"))) =
        RegisterTokenViewModel(tokens, consents, nfc, saved).also { vm = it }

    private fun ready(): RegisterTokenUiState.Ready {
        awaitUntil(message = "the codes are shown") { vm.state.value is RegisterTokenUiState.Ready }
        return vm.state.value as RegisterTokenUiState.Ready
    }

    private fun awaitReady(message: String, condition: (RegisterTokenUiState.Ready) -> Boolean): RegisterTokenUiState.Ready {
        awaitUntil(message = message) { (vm.state.value as? RegisterTokenUiState.Ready)?.let(condition) == true }
        return vm.state.value as RegisterTokenUiState.Ready
    }

    private fun stored(): List<Token> = runBlocking { tokens.observe(HandshakeMode.QR).first() }

    /** A code is registered only while the camera consent is on, as the person turns it on first on the screen. */
    private fun consented(): RegisterTokenUiState.Ready {
        runBlocking { consents.grant(ConsentKey.CAMERA_QR, ConsentSource.USER) }
        return awaitReady("the consent is on") { it.consentGranted }
    }

    @Test
    fun `with nothing registered the list is empty and the camera consent is off`() {
        val state = ready()
        assertEquals(emptyList<Token>(), state.codes)
        assertFalse(state.consentGranted)
        assertNull(state.outcome)
        assertNull(state.madeCode)
    }

    @Test
    fun `a code that is scanned is registered under its label, and the list and the words say so`() {
        consented()

        vm.register("  Kitchen  ", "command-qr:kitchen")

        val state = awaitReady("the code is listed") { it.codes.isNotEmpty() }
        assertEquals(listOf("Kitchen"), state.codes.map { it.label })
        assertEquals(CodeOutcome.Registered("Kitchen"), awaitReady("the words") { it.outcome != null }.outcome)
        assertTrue(runBlocking { tokens.matches(HandshakeMode.QR, "command-qr:kitchen") })
    }

    @Test
    fun `a code that arrives after the camera consent was withdrawn is not registered, and the words say why`() {
        // A frame can be on its way when the consent is turned off elsewhere; the view model is the last place it can be stopped.
        consented()
        runBlocking { consents.revoke(ConsentKey.CAMERA_QR, ConsentSource.USER) }
        awaitReady("withdrawn") { !it.consentGranted }

        vm.register("Kitchen", "command-qr:kitchen")

        assertEquals(CodeOutcome.NoConsent, awaitReady("the words") { it.outcome != null }.outcome)
        assertTrue("nothing is stored", stored().isEmpty())
    }

    @Test
    fun `each answer of a registration has its own outcome`() {
        consented()

        vm.register("Kitchen", "   ")
        assertEquals(CodeOutcome.Blank, awaitReady("blank") { it.outcome != null }.outcome)

        vm.register("   ", "command-qr:a")
        assertEquals(CodeOutcome.BadLabel, awaitReady("bad label") { it.outcome == CodeOutcome.BadLabel }.outcome)

        vm.register("x".repeat(TokenRepository.MAX_LABEL + 1), "command-qr:a")
        awaitReady("still a bad label") { it.outcome == CodeOutcome.BadLabel }

        vm.register("Kitchen", "command-qr:a")
        awaitReady("registered") { it.outcome == CodeOutcome.Registered("Kitchen") }

        vm.register("Hall", "command-qr:a")
        assertEquals(CodeOutcome.Duplicate("Kitchen"), awaitReady("duplicate") { it.outcome is CodeOutcome.Duplicate }.outcome)
        assertEquals("nothing was added", 1, stored().size)

        runBlocking { for (i in 2..TokenRepository.MAX_PER_MODE) tokens.register(HandshakeMode.QR, "Code $i", "command-qr:$i") }
        vm.register("One more", "command-qr:more")
        assertEquals(CodeOutcome.Full, awaitReady("full") { it.outcome == CodeOutcome.Full }.outcome)
        assertEquals(TokenRepository.MAX_PER_MODE, stored().size)
    }

    @Test
    fun `the words of the last answer can be put away, and a new registration replaces them`() {
        consented()
        vm.register("Kitchen", "  ")
        awaitReady("blank") { it.outcome == CodeOutcome.Blank }

        vm.dismissOutcome()
        awaitReady("put away") { it.outcome == null }

        vm.register("Kitchen", "command-qr:k")
        awaitReady("registered") { it.outcome == CodeOutcome.Registered("Kitchen") }
    }

    /**
     * The screen keeps a typed name, or a rename field, open until the answer comes; an answer left over from before must not be taken for
     * it, even when the new answer is the same one. So a registration or a rename puts the last answer away at once.
     */
    @Test
    fun `a registration or a rename puts the last answer away at once, so that the next answer is always a new one`() {
        consented()
        vm.register("Kitchen", "command-qr:k")
        val id = awaitReady("registered") { it.outcome == CodeOutcome.Registered("Kitchen") && it.codes.size == 1 }.codes.single().id

        vm.register("Hall", "command-qr:k")
        assertNull("put away as the registration starts", (vm.state.value as RegisterTokenUiState.Ready).outcome)
        awaitReady("duplicate") { it.outcome == CodeOutcome.Duplicate("Kitchen") }

        // (A bad name is refused before the database is asked, so that answer comes at once; these two go to the database.)
        vm.rename(id, "Fridge")
        assertNull("put away as the rename starts", (vm.state.value as RegisterTokenUiState.Ready).outcome)
        awaitReady("renamed") { it.outcome == CodeOutcome.Renamed }

        vm.rename(id, "Fridge")
        assertNull("the same answer again is still a new one", (vm.state.value as RegisterTokenUiState.Ready).outcome)
        awaitReady("renamed again") { it.outcome == CodeOutcome.Renamed }
    }

    @Test
    fun `a camera that fails while a code is scanned says so`() {
        ready()

        vm.scanFailed()

        assertEquals(CodeOutcome.CameraFailed, awaitReady("the camera failed") { it.outcome != null }.outcome)
        assertTrue("and nothing is registered", stored().isEmpty())
    }

    @Test
    fun `a code can be renamed, a bad name is refused, and a code that is gone says so`() {
        ready()
        runBlocking { tokens.register(HandshakeMode.QR, "Kitchen", "command-qr:k") }
        val id = awaitReady("listed") { it.codes.size == 1 }.codes.single().id

        vm.rename(id, "  Fridge ")
        awaitReady("renamed") { it.codes.single().label == "Fridge" && it.outcome == CodeOutcome.Renamed }

        vm.rename(id, " ")
        awaitReady("bad name") { it.outcome == CodeOutcome.BadLabel }
        assertEquals("Fridge", stored().single().label)

        runBlocking { tokens.delete(id) }
        vm.rename(id, "Hall")
        awaitReady("gone") { it.outcome == CodeOutcome.Gone }
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a code that is deleted is gone from the list and no longer matches`() {
        ready()
        runBlocking {
            tokens.register(HandshakeMode.QR, "Kitchen", "command-qr:k")
            tokens.register(HandshakeMode.QR, "Hall", "command-qr:h")
        }
        val kitchen = awaitReady("listed") { it.codes.size == 2 }.codes.first { it.label == "Kitchen" }.id

        vm.delete(kitchen)

        assertEquals(listOf("Hall"), awaitReady("deleted") { it.codes.size == 1 }.codes.map { it.label })
        assertFalse(runBlocking { tokens.matches(HandshakeMode.QR, "command-qr:k") })
        assertTrue(runBlocking { tokens.matches(HandshakeMode.QR, "command-qr:h") })
    }

    @Test
    fun `turning the camera consent on records it as the person's, and turning it off withdraws it and keeps the codes`() {
        ready()
        runBlocking { tokens.register(HandshakeMode.QR, "Kitchen", "command-qr:k") }

        vm.setConsent(true)
        awaitReady("granted") { it.consentGranted }
        assertTrue(runBlocking { consents.isGranted(ConsentKey.CAMERA_QR) })
        assertEquals(ConsentSource.USER, runBlocking { consents.history(ConsentKey.CAMERA_QR) }.last().source)

        vm.setConsent(false)
        awaitReady("withdrawn") { !it.consentGranted }
        assertFalse(runBlocking { consents.isGranted(ConsentKey.CAMERA_QR) })
        assertEquals("the codes stay", 1, stored().size)
    }

    @Test
    fun `the consent shown follows the consent center`() {
        ready()

        runBlocking { consents.grant(ConsentKey.CAMERA_QR) }
        awaitReady("granted elsewhere") { it.consentGranted }
        runBlocking { consents.revoke(ConsentKey.CAMERA_QR) }
        awaitReady("withdrawn elsewhere") { !it.consentGranted }
    }

    @Test
    fun `a new code is command-qr and 22 random characters, is never registered by itself, and reads back as itself`() {
        ready()

        vm.makeCode()
        val made = awaitReady("made") { it.madeCode != null }.madeCode!!

        assertTrue(made, Regex("command-qr:[A-Za-z0-9_-]{22}").matches(made))
        assertTrue("nothing is registered until it is scanned", stored().isEmpty())
        val matrix = QrCodes.matrix(made, 300)
        val luma = ByteArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) 0 else -1 }
        assertEquals("the code drawn reads back as the payload", made, QrDecoder.decode(luma, matrix.width, matrix.height, matrix.width))

        vm.closeMadeCode()
        awaitReady("put away") { it.madeCode == null }
        assertTrue(stored().isEmpty())
    }

    // --- tags (M13b): the same screen with mode=NFC, registered by two matching touches --------------------------------------

    private fun tag(hex: String) = TagRead.Seen(TokenHash.ofTag(TagId(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())))

    private fun storedTags(): List<Token> = runBlocking { tokens.observe(HandshakeMode.NFC).first() }

    private fun tagConsented(): RegisterTokenUiState.Ready {
        runBlocking { consents.grant(ConsentKey.NFC, ConsentSource.USER) }
        return awaitReady("the tag consent is on") { it.consentGranted }
    }

    @Test
    fun modeFromSavedStateDefaultQr() {
        assertEquals("no mode is the codes", HandshakeMode.QR, ready().mode)
        assertEquals(HandshakeMode.QR, vm.mode)
        assertEquals(HandshakeMode.QR, RegisterTokenViewModel(tokens, consents, FakeNfcStatus(), SavedStateHandle(mapOf("mode" to "QR"))).mode)
        assertEquals(HandshakeMode.NFC, nfcViewModel().mode)
        assertEquals(HandshakeMode.NFC, ready().mode)
    }

    @Test
    fun unknownModeFallsBackToQr() {
        for (odd in listOf("VOICE", "TYPING", "nfc", "", "{mode}", "x".repeat(300))) {
            assertEquals(odd, HandshakeMode.QR, RegisterTokenViewModel(tokens, consents, FakeNfcStatus(), SavedStateHandle(mapOf("mode" to odd))).mode)
        }
        assertEquals("not a string", HandshakeMode.QR, RegisterTokenViewModel(tokens, consents, FakeNfcStatus(), SavedStateHandle(mapOf("mode" to 7))).mode)
    }

    @Test
    fun nfcUsesNfcConsent() {
        nfcViewModel()
        runBlocking { tokens.register(HandshakeMode.QR, "Code", "command-qr:c") }
        runBlocking { consents.grant(ConsentKey.CAMERA_QR, ConsentSource.USER) }
        assertFalse("the camera consent is not the tag consent", ready().consentGranted)
        assertTrue("and the codes are not listed as tags", ready().codes.isEmpty())

        vm.setConsent(true)
        awaitReady("granted") { it.consentGranted }
        assertTrue(runBlocking { consents.isGranted(ConsentKey.NFC) })
        assertEquals(ConsentSource.USER, runBlocking { consents.history(ConsentKey.NFC) }.last().source)

        vm.setConsent(false)
        awaitReady("withdrawn") { !it.consentGranted }
        assertFalse(runBlocking { consents.isGranted(ConsentKey.NFC) })
        assertTrue("the camera consent is untouched", runBlocking { consents.isGranted(ConsentKey.CAMERA_QR) })
    }

    @Test
    fun firstTouchTouchAgain() {
        nfcViewModel()
        tagConsented()

        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))

        assertEquals(CodeOutcome.TouchAgain, awaitReady("touch again") { it.outcome != null }.outcome)
        assertTrue("nothing is stored after one touch", storedTags().isEmpty())
    }

    @Test
    fun twoMatchingTouchesSave() {
        nfcViewModel()
        tagConsented()
        val read = tag("04a1b2c3d4e5f6")

        vm.tagRead("  Kitchen ", read)
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }
        vm.tagRead("  Kitchen ", tag("04a1b2c3d4e5f6"))

        assertEquals(CodeOutcome.Registered("Kitchen"), awaitReady("registered") { it.outcome is CodeOutcome.Registered }.outcome)
        assertEquals(listOf("Kitchen"), awaitReady("listed") { it.codes.isNotEmpty() }.codes.map { it.label })
        assertTrue(runBlocking { tokens.matchesTag(read.hash) })
        assertTrue("it is not a code", stored().isEmpty())

        // The first touch went with the save: one more touch starts again.
        vm.tagRead("Hall", tag("04a1b2c3d4e5f6"))
        assertEquals(CodeOutcome.TouchAgain, awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }.outcome)
    }

    @Test
    fun mismatchedSecondTouchTagChanged() {
        nfcViewModel()
        tagConsented()

        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }
        vm.tagRead("Kitchen", tag("04ffffffffffff"))

        assertEquals(CodeOutcome.TagChanged, awaitReady("changed") { it.outcome == CodeOutcome.TagChanged }.outcome)
        assertTrue(storedTags().isEmpty())
        // The first touch is forgotten: the next touch is a first touch again, even of the first tag.
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }
        assertTrue(storedTags().isEmpty())
    }

    @Test
    fun changingReadRandomTag() {
        nfcViewModel()
        tagConsented()
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }

        vm.tagRead("Kitchen", TagRead.Changing)

        assertEquals(CodeOutcome.RandomTag, awaitReady("random") { it.outcome == CodeOutcome.RandomTag }.outcome)
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("the first touch was forgotten") { it.outcome == CodeOutcome.TouchAgain }
        assertTrue(storedTags().isEmpty())
    }

    @Test
    fun noIdNoTagId() {
        nfcViewModel()
        tagConsented()
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }

        vm.tagRead("Kitchen", TagRead.NoId)

        assertEquals(CodeOutcome.NoTagId, awaitReady("no id") { it.outcome == CodeOutcome.NoTagId }.outcome)
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("the first touch was forgotten") { it.outcome == CodeOutcome.TouchAgain }
        assertTrue(storedTags().isEmpty())
    }

    private fun touchTwice(label: String, read: TagRead.Seen): CodeOutcome? {
        vm.tagRead(label, read)
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }
        vm.tagRead(label, read)
        return awaitReady("answered") { it.outcome != null && it.outcome != CodeOutcome.TouchAgain }.outcome
    }

    @Test
    fun duplicateAndFullForTags() {
        nfcViewModel()
        tagConsented()
        assertEquals(CodeOutcome.Registered("Kitchen"), touchTwice("Kitchen", tag("04a1b2c3d4e5f6")))

        assertEquals(CodeOutcome.Duplicate("Kitchen"), touchTwice("Hall", tag("04a1b2c3d4e5f6")))
        assertEquals(CodeOutcome.BadLabel, touchTwice(" ", tag("04000000000001")))

        runBlocking { for (i in 2..TokenRepository.MAX_PER_MODE) tokens.registerTag("Tag $i", TokenHash.ofTag(TagId(byteArrayOf(4, i.toByte(), 1, 2, 3, 4, 5)))) }
        assertEquals(CodeOutcome.Full, touchTwice("One more", tag("04000000000002")))
        assertEquals(TokenRepository.MAX_PER_MODE, storedTags().size)
        assertTrue("the codes are counted apart", stored().isEmpty())
    }

    @Test
    fun consentWithdrawnBetweenTouchesNoConsent() {
        nfcViewModel()
        tagConsented()
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }
        runBlocking { consents.revoke(ConsentKey.NFC, ConsentSource.USER) }
        awaitReady("withdrawn") { !it.consentGranted }

        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))

        assertEquals(CodeOutcome.NoConsent, awaitReady("no consent") { it.outcome == CodeOutcome.NoConsent }.outcome)
        assertTrue(storedTags().isEmpty())
    }

    @Test
    fun noMakeCodeForNfc() {
        nfcViewModel()
        ready()

        vm.makeCode()

        assertNull("a tag cannot be made", ready().madeCode)
    }

    @Test
    fun `a tag read on the codes screen is ignored, and the reader stopping says so on the tags screen`() {
        ready()
        runBlocking { consents.grant(ConsentKey.NFC, ConsentSource.USER) }
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        assertNull("a first touch is not held, and asks for nothing", ready().outcome)
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        // A save would finish on the main thread after the database has answered: let it, before looking.
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertNull(ready().outcome)
        assertTrue(storedTags().isEmpty())

        nfcViewModel()
        ready()
        vm.tagReaderOff()
        assertEquals(CodeOutcome.ReaderOff, awaitReady("reader off") { it.outcome != null }.outcome)
    }

    @Test
    fun `cancelling after the first touch forgets it`() {
        nfcViewModel()
        tagConsented()
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }

        vm.cancelTag()

        awaitReady("the words of the touch are put away") { it.outcome == null }
        vm.tagRead("Kitchen", tag("04a1b2c3d4e5f6"))
        awaitReady("a first touch again") { it.outcome == CodeOutcome.TouchAgain }
        assertTrue(storedTags().isEmpty())
    }

    @Test
    fun `the reader as this screen needs it, and Secure NFC`() {
        val nfc = FakeNfcStatus()
        nfcViewModel(nfc)
        for ((now, access) in listOf(
            NfcAvailability.NONE to ReaderAccess.NONE,
            NfcAvailability.OFF to ReaderAccess.OFF,
            NfcAvailability.SECURE_LOCKED to ReaderAccess.READY, // this screen is used unlocked
            NfcAvailability.READY to ReaderAccess.READY,
        )) {
            nfc.now = now
            assertEquals("$now", access, vm.readerAccess())
        }
        assertFalse(vm.secureNfcOn())
        nfc.secure = true
        assertTrue(vm.secureNfcOn())
    }

    @Test
    fun pendingNotInSavedState() {
        val saved = SavedStateHandle(mapOf("mode" to "NFC"))
        nfcViewModel(saved = saved)
        tagConsented()
        val read = tag("04a1b2c3d4e5f6")

        vm.tagRead("Kitchen", read)
        awaitReady("touch again") { it.outcome == CodeOutcome.TouchAgain }

        assertEquals("the saved state holds the mode alone", setOf("mode"), saved.keys())
        assertEquals("NFC", saved.get<String>("mode"))
        assertFalse("the state says nothing of the tag", (vm.state.value as RegisterTokenUiState.Ready).toString().contains(read.hash))
    }

    @Test
    fun `every new code is a different one`() {
        val made = (1..200).map { QrCodes.newPayload() }
        assertEquals(200, made.toSet().size)
        assertTrue(made.all { Regex("command-qr:[A-Za-z0-9_-]{22}").matches(it) })
    }
}
