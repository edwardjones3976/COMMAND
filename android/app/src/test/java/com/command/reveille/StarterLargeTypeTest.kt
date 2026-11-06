package com.command.reveille

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.command.domain.handshake.TryId
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The new words at twice the type size on a small phone, measured for real: they may scroll, and nothing that answers the alarm is pushed away or squeezed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2f)
class StarterLargeTypeTest {

    @get:Rule val compose = createComposeRule()
    private var silenced = 0
    private var begun = 0
    private var started = 0

    private fun show(state: ReveilleUiState) {
        val actions = ReveilleActions(
            onHoldCompleted = { _, _, _ -> }, onHoldReleasedEarly = { _, _, _ -> }, onAssistiveConfirmed = {},
            onSilence = { silenced++ }, onResume = {}, onStarted = { started++ }, onBeginStarter = { begun++ }, onDelay = {}, onSkip = {}, onClose = {},
        )
        compose.setContent { CommandTheme(darkTheme = true) { ReveilleContent(state, null, emptyList(), actions, {}, {}) } }
    }

    private val longLine = "It is time. Your next commitment is to write the whole of the quarterly report. First action: open the document on the shared drive and read the last page again."
    private val hold = Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = false, acknowledging = false)

    @Test
    fun `a long unspoken line leaves the silence in the window, as tall as a button, and the hold target usable`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", hold, prompt = "Show me you are up!", unspokenLine = longLine))

        val window = compose.onRoot().fetchSemanticsNode().size
        val silence = compose.onNodeWithTag(ReveilleTags.SILENCE).fetchSemanticsNode().boundsInRoot
        assertTrue("$silence in $window", silence.top >= 0f && silence.bottom <= window.height)
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, silenced)
        compose.onNodeWithTag(ReveilleTags.HOLD).assertIsDisplayed().assertHeightIsAtLeast(96.dp)
    }

    @Test
    fun `the end of a long unspoken line can be scrolled to, beside a press and beside a question`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", hold, unspokenLine = longLine))
        compose.onNodeWithTag(ReveilleTags.UNSPOKEN_LINE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `beside a typing challenge the line scrolls with the words`() {
        show(ReveilleUiState.Firing("occ-1", "Write the report", Challenge.Typing(TryId(0, 1), "Feet on the floor", 3, false), unspokenLine = longLine))
        compose.onNodeWithTag(ReveilleTags.UNSPOKEN_LINE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `the offer of the starter can be reached and keeps its size`() {
        show(ReveilleUiState.Direction("Write the report", "Open the document", 5, "Right. Give me 5 minutes.", "Handshake done.", offerStarter = true))
        compose.onNodeWithTag(ReveilleTags.STARTER_BEGIN).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp).performClick()
        assertEquals(1, begun)
    }

    @Test
    fun `the countdown and the report of a start can be reached`() {
        show(ReveilleUiState.Starter("Write the report", "Open the document", 272, "Clock is running. Give me 5 minutes!"))
        compose.onNodeWithTag(ReveilleTags.STARTER_COUNTDOWN).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.STARTED).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(72.dp).performClick()
        assertEquals(1, started)
    }
}
