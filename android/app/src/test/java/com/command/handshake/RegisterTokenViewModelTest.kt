package com.command.handshake

import com.command.data.ConsentStore
import com.command.data.Token
import com.command.data.TokenRepository
import com.command.domain.dials.HandshakeMode
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
        vm = RegisterTokenViewModel(tokens, consents)
    }

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

    @Test
    fun `every new code is a different one`() {
        val made = (1..200).map { QrCodes.newPayload() }
        assertEquals(200, made.toSet().size)
        assertTrue(made.all { Regex("command-qr:[A-Za-z0-9_-]{22}").matches(it) })
    }
}
