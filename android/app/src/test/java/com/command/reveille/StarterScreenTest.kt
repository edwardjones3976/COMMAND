package com.command.reveille

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.command.domain.handshake.TryId
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The starter's screen, the offer of it, and where each line of M10c stands on the lock-screen flow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StarterScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Taps {
        var started = 0
        var beginStarter = 0
        val delays = ArrayList<Int>()
        var skipped = 0
        var silenced = 0

        fun actions() = ReveilleActions(
            onHoldCompleted = { _, _, _ -> },
            onHoldReleasedEarly = { _, _, _ -> },
            onAssistiveConfirmed = {},
            onSilence = { silenced++ },
            onResume = {},
            onStarted = { started++ },
            onBeginStarter = { beginStarter++ },
            onDelay = { delays += it },
            onSkip = { skipped++ },
            onClose = {},
        )
    }

    private fun show(state: ReveilleUiState, taps: Taps = Taps()): Taps {
        compose.setContent { CommandTheme(darkTheme = true) { ReveilleContent(state, null, emptyList(), taps.actions(), {}, {}) } }
        return taps
    }

    private fun starter(seconds: Int = 272, line: String = "Clock is running.") =
        ReveilleUiState.Starter("Write the report", "Open the document", seconds, line)

    private fun direction(offer: Boolean = true, praise: String = "", coaching: String = "") =
        ReveilleUiState.Direction("Write the report", "Open the document", 5, coaching, praise, offer)

    private val hold = Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = false, acknowledging = false)

    // --- the starter ---------------------------------------------------------------------------------------------

    @Test
    fun `the countdown shows minutes and seconds, with the first step and the line`() {
        show(starter())
        compose.onNodeWithTag(ReveilleTags.STARTER_COUNTDOWN).assertTextEquals("4:32")
        compose.onNodeWithText("Open the document").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.STARTER_LINE).assertTextEquals("Clock is running.")
    }

    @Test
    fun `the seconds keep two figures`() {
        show(starter(seconds = 65))
        compose.onNodeWithTag(ReveilleTags.STARTER_COUNTDOWN).assertTextEquals("1:05")
    }

    @Test
    fun `a screen reader hears the time left in words and not the figures`() {
        show(starter(seconds = 272))
        val said = compose.onNodeWithTag(ReveilleTags.STARTER_COUNTDOWN).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("5 minutes left"), said)
    }

    @Test
    fun `the line is announced when it changes, and the clock is not`() {
        show(starter())
        val line = compose.onNodeWithTag(ReveilleTags.STARTER_LINE).fetchSemanticsNode().config
        assertTrue(line.contains(SemanticsProperties.LiveRegion))
        val clock = compose.onNodeWithTag(ReveilleTags.STARTER_COUNTDOWN).fetchSemanticsNode().config
        assertTrue(!clock.contains(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `an empty clock says it is over, in place of the line, and leaves the question to the person`() {
        val taps = show(starter(seconds = 0))
        compose.onNodeWithTag(ReveilleTags.STARTER_COUNTDOWN).assertTextEquals("0:00")
        compose.onNodeWithTag(ReveilleTags.STARTER_OVER).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.STARTER_LINE).assertDoesNotExist()
        compose.onNodeWithTag(ReveilleTags.STARTED).performClick()
        assertEquals(1, taps.started)
    }

    @Test
    fun `the answers are all there during the starter`() {
        val taps = show(starter())
        compose.onNodeWithTag(ReveilleTags.STARTED).performClick()
        compose.onNodeWithTag(ReveilleTags.DELAY).performClick()
        compose.onNodeWithTag(ReveilleTags.SKIP).performClick()
        assertEquals(1, taps.started)
        assertEquals(listOf(10), taps.delays)
        assertEquals(1, taps.skipped)
    }

    // --- the offer -----------------------------------------------------------------------------------------------

    @Test
    fun `the direction offers the starter by its length, and the tap asks for it`() {
        val taps = show(direction())
        compose.onNodeWithTag(ReveilleTags.STARTER_BEGIN).assertIsDisplayed()
        compose.onNodeWithText("Set a 5-minute clock").performClick()
        assertEquals(1, taps.beginStarter)
    }

    @Test
    fun `no offer, no button`() {
        show(direction(offer = false))
        compose.onNodeWithTag(ReveilleTags.STARTER_BEGIN).assertDoesNotExist()
    }

    // --- the lines -----------------------------------------------------------------------------------------------

    @Test
    fun `the direction shows the praise above the line`() {
        show(direction(praise = "Handshake done.", coaching = "First move."))
        compose.onNodeWithTag(ReveilleTags.DIRECTION_PRAISE).assertTextEquals("Handshake done.")
        compose.onNodeWithTag(ReveilleTags.DIRECTION_COACHING).assertTextEquals("First move.")
        val praise = compose.onNodeWithTag(ReveilleTags.DIRECTION_PRAISE).fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag(ReveilleTags.DIRECTION_COACHING).fetchSemanticsNode().boundsInRoot
        assertTrue(praise.bottom <= line.top)
    }

    @Test
    fun `with no praise there is no empty line`() {
        show(direction())
        compose.onNodeWithTag(ReveilleTags.DIRECTION_PRAISE).assertDoesNotExist()
        compose.onNodeWithTag(ReveilleTags.DIRECTION_COACHING).assertDoesNotExist()
    }

    @Test
    fun `a skip shows the coach's words under what is recorded`() {
        show(ReveilleUiState.Closed("Write the report", ClosedOutcome.SKIPPED, "Skipped. Understood."))
        compose.onNodeWithText("Skipped. It is recorded as skipped.").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.CLOSED_COACHING).assertTextEquals("Skipped. Understood.")
    }

    @Test
    fun `a close with no words has no empty line`() {
        show(ReveilleUiState.Closed("Write the report", ClosedOutcome.STARTED))
        compose.onNodeWithTag(ReveilleTags.CLOSED_COACHING).assertDoesNotExist()
    }

    @Test
    fun `the ring shows its prompt`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", hold, prompt = "Show me you are up!"))
        compose.onNodeWithTag(ReveilleTags.HANDSHAKE_PROMPT).assertTextEquals("Show me you are up!")
    }

    @Test
    fun `the prompt asks for nothing when no try is left`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", Challenge.OutOfTries, prompt = "Show me you are up!"))
        compose.onNodeWithTag(ReveilleTags.HANDSHAKE_PROMPT).assertDoesNotExist()
    }

    // --- the line in large type ----------------------------------------------------------------------------------

    private fun fontSizeOf(tag: String) = ArrayList<TextLayoutResult>().let { results ->
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        results.single().layoutInput.style.fontSize
    }

    @Test
    fun `a line that was not heard stands on the ring screen in large type, beside a hold`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", hold, prompt = "Hold.", unspokenLine = "It is time. Open the document."))
        compose.onNodeWithTag(ReveilleTags.UNSPOKEN_LINE).assertTextEquals("It is time. Open the document.")
        compose.onNodeWithTag(ReveilleTags.UNSPOKEN_LINE).assertIsDisplayed()
        assertTrue("large type, not a caption: ${fontSizeOf(ReveilleTags.UNSPOKEN_LINE)}", fontSizeOf(ReveilleTags.UNSPOKEN_LINE) >= 28.sp)
        assertEquals("and larger than the prompt", true, fontSizeOf(ReveilleTags.UNSPOKEN_LINE) > fontSizeOf(ReveilleTags.HANDSHAKE_PROMPT))
    }

    @Test
    fun `it stands there beside every other challenge too`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", Challenge.TwoStep(TryId(0, 1), "Open the document"), unspokenLine = "It is time."))
        compose.onNodeWithTag(ReveilleTags.UNSPOKEN_LINE).assertTextEquals("It is time.")
    }

    @Test
    fun `it is announced to a screen reader when it appears`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", hold, unspokenLine = "It is time."))
        assertTrue(compose.onNodeWithTag(ReveilleTags.UNSPOKEN_LINE).fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `a line that was heard adds nothing`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", hold))
        compose.onNodeWithTag(ReveilleTags.UNSPOKEN_LINE).assertDoesNotExist()
    }

    @Test
    fun `the silence stays on the ring screen beside the line`() {
        val taps = show(ReveilleUiState.Firing("occ-1", "Write the report", hold, unspokenLine = "It is time."))
        compose.onNodeWithTag(ReveilleTags.SILENCE).performClick()
        assertEquals(1, taps.silenced)
    }
}
