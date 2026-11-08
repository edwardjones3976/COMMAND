package com.command.reveille

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.TagRead
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

/** The ring screen's tag to touch with no view model and no NFC: a fake reader stands in for the phone's and reads what the test says. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class ReveilleNfcScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder {
        val read = ArrayList<Pair<TryId, TagRead>>()
        val unavailable = ArrayList<TryId>()
        val qrUnavailable = ArrayList<TryId>()
        val switched = ArrayList<Int>()
        var silenced = 0

        fun actions() = ReveilleActions(
            onHoldCompleted = { _, _, _ -> },
            onHoldReleasedEarly = { _, _, _ -> },
            onAssistiveConfirmed = {},
            onSwitch = { switched += it },
            onQrUnavailable = { qrUnavailable += it },
            onNfcRead = { at, read -> this.read += at to read },
            onNfcUnavailable = { unavailable += it },
            onSilence = { silenced++ },
            onResume = {}, onStarted = {}, onDelay = {}, onSkip = {}, onClose = {},
        )
    }

    private fun nfc(tryNo: Int = 1, triesLeft: Int = 3, mistaken: Boolean = false) = Challenge.Nfc(TryId(0, tryNo), triesLeft, mistaken)

    private fun firing(challenge: Challenge, alternatives: List<Alternative> = emptyList(), unspokenLine: String? = null) =
        ReveilleUiState.Firing("occ-1", "Write the report", challenge, alternatives = alternatives, prompt = "Up you get.", unspokenLine = unspokenLine)

    private fun show(state: ReveilleUiState.Firing, r: Recorder, reader: FakeNfcReader) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalNfcReader provides reader) { ReveilleContent(state, null, emptyList(), r.actions(), {}, {}) }
            }
        }
    }

    @Test
    fun nfcAreaShowsWordsAndInstruction() {
        show(firing(nfc()), Recorder(), FakeNfcReader())

        compose.onNodeWithTag(ReveilleTags.NFC_AREA).assertIsDisplayed()
        compose.onNodeWithText("Write the report").assertIsDisplayed()
        compose.onNodeWithText("Touch a tag").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.NFC_INSTRUCTION).assertIsDisplayed()
        compose.onNodeWithText("Hold one of your tags flat against the back of the phone. If nothing happens, move it slowly.").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.MISTAKEN).assertDoesNotExist()
    }

    @Test
    fun cannotTouchVisibleWithoutScroll48dp() {
        show(firing(nfc()), Recorder(), FakeNfcReader())

        compose.onNodeWithTag(ReveilleTags.NFC_CANNOT_TOUCH).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Cannot touch a tag? Type a phrase instead").assertIsDisplayed()
    }

    @Test
    fun cannotTouchCallsUnavailableWithTryId() {
        val r = Recorder()
        show(firing(nfc(tryNo = 2, triesLeft = 2, mistaken = true)), r, FakeNfcReader())

        compose.onNodeWithTag(ReveilleTags.NFC_CANNOT_TOUCH).performClick()

        assertEquals("not a try, and about the try on screen", listOf(TryId(0, 2)), r.unavailable)
        assertTrue(r.read.isEmpty())
        assertEquals(0, r.silenced)
        assertTrue("never the camera's report", r.qrUnavailable.isEmpty())
    }

    @Test
    fun aReaderThatFailsReportsTheTryOnScreen() {
        val r = Recorder()
        val reader = FakeNfcReader()
        show(firing(nfc()), r, reader)

        compose.runOnIdle { reader.fail() }

        assertEquals(listOf(TryId(0, 1)), r.unavailable)
    }

    @Test
    fun secondTryReadCarriesNewTryId() {
        val r = Recorder()
        val reader = FakeNfcReader()
        var challenge by mutableStateOf<Challenge>(nfc())
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalNfcReader provides reader) { ReveilleContent(firing(challenge), null, emptyList(), r.actions(), {}, {}) }
            }
        }

        compose.runOnIdle { reader.touch(OTHER) }
        challenge = nfc(tryNo = 2, triesLeft = 2, mistaken = true)
        compose.waitForIdle()
        compose.runOnIdle { reader.touch(MINE) }

        assertEquals(listOf(TryId(0, 1) to OTHER, TryId(0, 2) to MINE), r.read)
        assertEquals("the reader stayed on across the tries", 1, reader.composed)
    }

    @Test
    fun wrongPluralInLiveRegion() {
        show(firing(nfc(tryNo = 2, triesLeft = 2, mistaken = true)), Recorder(), FakeNfcReader())

        compose.onNodeWithText("That is not one of your tags. 2 tries left").assertIsDisplayed()
        val live = compose.onNodeWithTag(ReveilleTags.MISTAKEN).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals("TalkBack announces a wrong tag", LiveRegionMode.Polite, live)
    }

    @Test
    fun theLastTryIsSaidInTheSingular() {
        show(firing(nfc(tryNo = 3, triesLeft = 1, mistaken = true)), Recorder(), FakeNfcReader())
        compose.onNodeWithText("That is not one of your tags. 1 try left").assertIsDisplayed()
    }

    @Test
    fun theOtherWaysAreOfferedAndSafetySilenceIsOneTap() {
        val r = Recorder()
        show(firing(nfc(), alternatives = listOf(Alternative(1, HandshakeMode.TYPING, instead = true))), r, FakeNfcReader())

        compose.onNodeWithTag(ReveilleTags.alternative(1)).performClick()
        compose.onNodeWithTag(ReveilleTags.SILENCE).performClick()

        assertEquals(listOf(1), r.switched)
        assertEquals(1, r.silenced)
        assertTrue(r.unavailable.isEmpty())
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-xhdpi")
    fun safetySilenceLastAndVisibleOverNfc() = silenceHolds()

    @Test
    @Config(qualifiers = "w320dp-h480dp-xhdpi", fontScale = 2.0f)
    fun safetySilenceLastAndVisibleOverNfcAtLargeType() = silenceHolds()

    @Test
    @Config(qualifiers = "w360dp-h320dp-land-xhdpi")
    fun safetySilenceLastAndVisibleOverNfcInAShortWindow() = silenceHolds()

    /** With a lot to say, Safety silence keeps its full height at the very bottom, and the cannot-touch control keeps room of its own above it. */
    private fun silenceHolds() {
        val r = Recorder()
        val long = "The ring's own line, which was not heard, and is long enough to fill the room above the control several times over. ".repeat(4)
        show(firing(nfc(tryNo = 2, triesLeft = 2, mistaken = true), listOf(Alternative(1, HandshakeMode.TYPING, instead = true)), unspokenLine = long), r, FakeNfcReader())

        compose.onNodeWithTag(ReveilleTags.NFC_CANNOT_TOUCH).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val silence = compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        val silenceTop = silence.getUnclippedBoundsInRoot().top
        val cannotBottom = compose.onNodeWithTag(ReveilleTags.NFC_CANNOT_TOUCH).getUnclippedBoundsInRoot().bottom
        assertTrue("Safety silence is below everything the tag asks, never covered: $cannotBottom <= $silenceTop", cannotBottom <= silenceTop)
        silence.performClick()
        assertEquals(1, r.silenced)
    }

    @Test
    fun listenComposedOnlyForNfc() {
        val reader = FakeNfcReader()
        var challenge by mutableStateOf<Challenge>(Challenge.Typing(TryId(0, 1), "Up and starting", 3, mistaken = false))
        compose.setContent {
            CommandTheme(darkTheme = true) {
                CompositionLocalProvider(LocalNfcReader provides reader) { ReveilleContent(firing(challenge), null, emptyList(), Recorder().actions(), {}, {}) }
            }
        }
        compose.waitForIdle()
        assertEquals(0, reader.composed)

        challenge = nfc()
        compose.waitForIdle()
        assertEquals(1, reader.active)

        challenge = Challenge.OutOfTries
        compose.waitForIdle()
        assertEquals("the reader is let go when the tag is no longer asked for", 0, reader.active)
        compose.onNodeWithTag(ReveilleTags.NFC_AREA).assertDoesNotExist()
    }

    @Test
    fun theRingScreensReaderIsThePhonesOwnUnlessSomethingElseIsProvided() {
        var reader: NfcReader? = null
        compose.setContent { reader = LocalNfcReader.current }
        compose.waitForIdle()
        assertSame(PlatformNfcReader, reader)
    }

    private companion object {
        val MINE = TagRead.of(byteArrayOf(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66))
        val OTHER = TagRead.of(byteArrayOf(0x04, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22))
    }
}
