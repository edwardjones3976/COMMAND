package com.command.reveille

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.TryId
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ring screen's code to scan with no view model and no camera: a fake scanner stands in for it and reads what the test says it reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class QrChallengeScreenTest {

    @get:Rule val compose = createComposeRule()

    /** A viewfinder that is an empty box: the test reads codes through it and fails its camera when it wants. */
    private class FakeScanner : QrScanner {
        var onDecoded: (String) -> Unit = {}
        var onError: () -> Unit = {}
        var drawn = 0

        @Composable
        override fun Viewfinder(modifier: Modifier, onDecoded: (String) -> Unit, onError: () -> Unit) {
            this.onDecoded = onDecoded
            this.onError = onError
            drawn++
            Box(modifier)
        }
    }

    private class Recorder {
        val scanned = ArrayList<Pair<TryId, String>>()
        var unavailable = 0
        val switched = ArrayList<Int>()
        var silenced = 0

        fun actions() = ReveilleActions(
            onHoldCompleted = { _, _, _ -> },
            onHoldReleasedEarly = { _, _, _ -> },
            onAssistiveConfirmed = {},
            onSwitch = { switched += it },
            onQrScanned = { at, payload -> scanned += at to payload },
            onQrUnavailable = { unavailable++ },
            onSilence = { silenced++ },
            onResume = {}, onStarted = {}, onDelay = {}, onSkip = {}, onClose = {},
        )
    }

    private fun qr(tryNo: Int = 1, triesLeft: Int = 3, mistaken: Boolean = false, slow: Boolean = false) = Challenge.Qr(TryId(0, tryNo), triesLeft, mistaken, slow)

    private fun firing(challenge: Challenge, alternatives: List<Alternative> = emptyList(), unspokenLine: String? = null) =
        ReveilleUiState.Firing("occ-1", "Write the report", challenge, alternatives = alternatives, prompt = "Up you get.", unspokenLine = unspokenLine)

    private fun show(state: ReveilleUiState.Firing, r: Recorder, scanner: FakeScanner) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalQrScanner provides scanner) { ReveilleContent(state, null, emptyList(), r.actions(), {}, {}) }
            }
        }
    }

    @Test
    fun `a code to scan shows what to do, the viewfinder, and Safety silence, and says nothing is wrong yet`() {
        show(firing(qr()), Recorder(), FakeScanner())

        compose.onNodeWithText("Write the report").assertIsDisplayed()
        compose.onNodeWithText("Scan your registered code").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.QR_VIEWFINDER).assertIsDisplayed().assertHeightIsAtLeast(120.dp)
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.MISTAKEN).assertDoesNotExist()
    }

    @Test
    fun `the viewfinder tells a screen reader what it is`() {
        show(firing(qr()), Recorder(), FakeScanner())

        compose.onNodeWithTag(ReveilleTags.QR_VIEWFINDER).assertContentDescriptionContains("Point it at your registered code", substring = true)
    }

    @Test
    fun `a code that was not registered is said so, with the tries left`() {
        show(firing(qr(tryNo = 2, triesLeft = 2, mistaken = true)), Recorder(), FakeScanner())
        compose.onNodeWithText("That is not a registered code. 2 tries left.").assertIsDisplayed()
    }

    @Test
    fun `the last try left is said in the singular`() {
        show(firing(qr(tryNo = 3, triesLeft = 1, mistaken = true)), Recorder(), FakeScanner())
        compose.onNodeWithText("That is not a registered code. 1 try left.").assertIsDisplayed()
    }

    @Test
    fun `a code the camera reads is reported with the try on screen, and so is a camera that cannot be used`() {
        val r = Recorder()
        val scanner = FakeScanner()
        var challenge by mutableStateOf<Challenge>(qr())
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalQrScanner provides scanner) { ReveilleContent(firing(challenge), null, emptyList(), r.actions(), {}, {}) }
            }
        }

        compose.runOnIdle { scanner.onDecoded("command-qr:abc") }
        challenge = qr(tryNo = 2, triesLeft = 2, mistaken = true)
        compose.waitForIdle()
        compose.runOnIdle { scanner.onDecoded("command-qr:def") }
        compose.runOnIdle { scanner.onError() }

        assertEquals(listOf(TryId(0, 1) to "command-qr:abc", TryId(0, 2) to "command-qr:def"), r.scanned)
        assertEquals(1, r.unavailable)
    }

    @Test
    fun `a camera that shows nothing readable can always be passed over to typing, beside Safety silence and never instead of it`() {
        val r = Recorder()
        show(firing(qr()), r, FakeScanner())

        compose.onNodeWithTag(ReveilleTags.QR_CANNOT_SCAN).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Cannot scan? Type a phrase instead").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.QR_CANNOT_SCAN).performClick()

        assertEquals(1, r.unavailable)
        assertEquals(0, r.silenced)
        assertTrue(r.scanned.isEmpty())
    }

    @Test
    fun `the other ways are offered under the words, and Safety silence is one tap`() {
        val r = Recorder()
        show(firing(qr(), alternatives = listOf(Alternative(1, HandshakeMode.TYPING, instead = true))), r, FakeScanner())

        compose.onNodeWithTag(ReveilleTags.alternative(1)).performClick()
        compose.onNodeWithTag(ReveilleTags.SILENCE).performClick()

        assertEquals(listOf(1), r.switched)
        assertEquals(1, r.silenced)
    }

    @Test
    @Config(qualifiers = "w360dp-h320dp-land-xhdpi")
    fun `in a short window with a lot to say, Safety silence keeps its full height and the viewfinder keeps room of its own`() {
        val r = Recorder()
        val long = "The ring's own line, which was not heard, and is long enough to fill the room above the viewfinder several times over. ".repeat(4)
        show(firing(qr(tryNo = 2, triesLeft = 2, mistaken = true), listOf(Alternative(1, HandshakeMode.TYPING, instead = true)), unspokenLine = long), r, FakeScanner())

        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(ReveilleTags.QR_VIEWFINDER).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(ReveilleTags.QR_CANNOT_SCAN).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals(1, r.silenced)
    }

    @Test
    fun `the ring screen's camera is the phone's own unless something else is provided`() {
        var scanner: QrScanner? = null
        compose.setContent { scanner = LocalQrScanner.current }
        compose.waitForIdle()
        assertSame(CameraXQrScanner, scanner)
    }

    @Test
    fun `the viewfinder is drawn only while a code is asked for`() {
        val scanner = FakeScanner()
        show(firing(Challenge.Typing(TryId(0, 1), "Up and starting", 3, mistaken = false)), Recorder(), scanner)
        compose.waitForIdle()
        assertTrue(scanner.drawn == 0)
        compose.onNodeWithTag(ReveilleTags.QR_VIEWFINDER).assertDoesNotExist()
    }
}
