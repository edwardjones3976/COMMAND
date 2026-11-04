package com.command.reveille

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.command.domain.handshake.TryId
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The ring screen's challenges with no view model: what is on screen for each, and what each control reports and about which try. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class HandshakeScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder {
        val typed = ArrayList<Pair<TryId, String>>()
        val submitted = ArrayList<Pair<TryId, String>>()
        val tapped = ArrayList<Pair<TryId, Int>>()
        val indicated = ArrayList<TryId>()
        val assistive = ArrayList<TryId>()
        val completed = ArrayList<TryId>()
        val early = ArrayList<TryId>()
        var silenced = 0

        fun actions() = ReveilleActions(
            onHoldCompleted = { at, _, _ -> completed += at },
            onHoldReleasedEarly = { at, _, _ -> early += at },
            onAssistiveConfirmed = { assistive += it },
            onTypingChanged = { at, text -> typed += at to text },
            onTypingSubmitted = { at, text -> submitted += at to text },
            onSequenceTapped = { at, number -> tapped += at to number },
            onTwoStepIndicated = { indicated += it },
            onSilence = { silenced++ },
            onResume = {}, onStarted = {}, onDelay = {}, onSkip = {}, onClose = {},
        )
    }

    private fun firing(challenge: Challenge, progress: ChainProgress? = null) = ReveilleUiState.Firing("occ-1", "Write the report", challenge, progress)

    private fun typing(tryNo: Int = 1, triesLeft: Int = 3, mistaken: Boolean = false, slow: Boolean = false) =
        Challenge.Typing(TryId(0, tryNo), "Up and starting", triesLeft, mistaken, slow)

    private fun sequence(tapped: Int = 0, tryNo: Int = 1, triesLeft: Int = 3, mistaken: Boolean = false, slow: Boolean = false) =
        Challenge.Sequence(TryId(0, tryNo), listOf(3, 1, 4, 2), tapped, triesLeft, mistaken, slow)

    private fun liveRegionOfText(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
    private fun liveRegionOfTag(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)

    private fun show(state: ReveilleUiState.Firing, r: Recorder) {
        compose.setContent {
            CommandTheme(darkTheme = true) { ReveilleContent(state, null, emptyList(), r.actions(), {}, {}) }
        }
    }

    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    // --- typing -------------------------------------------------------------------------------------------------------

    @Test
    fun `typing shows what to type, a field with the keyboard up, a button, and Safety silence`() {
        show(firing(typing()), Recorder())

        compose.onNodeWithText("Write the report").assertIsDisplayed()
        compose.onNodeWithText("Type this to show you are up").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.TYPING_PHRASE).assertIsDisplayed()
        compose.onNodeWithText("Up and starting").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed()
    }

    @Test
    fun `what is typed is reported with the try it is for, key by key`() {
        val r = Recorder()
        show(firing(typing(tryNo = 2)), r)

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("Up and")

        assertEquals(TryId(0, 2), r.typed.last().first)
        assertEquals("Up and", r.typed.last().second)
        assertTrue("nothing is submitted by typing", r.submitted.isEmpty())
    }

    @Test
    fun `the button submits what was typed, with the try`() {
        val r = Recorder()
        show(firing(typing(tryNo = 2)), r)

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("up and starting")
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).performClick()

        assertEquals(listOf(TryId(0, 2) to "up and starting"), r.submitted)
    }

    @Test
    fun `Done on the keyboard submits too`() {
        val r = Recorder()
        show(firing(typing()), r)

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("Up and starting")
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performImeAction()

        assertEquals(listOf(TryId(0, 1) to "Up and starting"), r.submitted)
    }

    @Test
    fun `a wrong try says so and how many tries are left`() {
        show(firing(typing(tryNo = 2, triesLeft = 2, mistaken = true)), Recorder())
        compose.onNodeWithTag(ReveilleTags.MISTAKEN).assertIsDisplayed()
        compose.onNodeWithText("That is not it. 2 tries left.").assertIsDisplayed()
    }

    @Test
    fun `the last try says one try left, in the singular`() {
        show(firing(typing(tryNo = 3, triesLeft = 1, mistaken = true)), Recorder())
        compose.onNodeWithText("That is not it. 1 try left.").assertIsDisplayed()
    }

    @Test
    fun `a first try says nothing about a wrong one`() {
        show(firing(typing()), Recorder())
        compose.onNodeWithTag(ReveilleTags.MISTAKEN).assertDoesNotExist()
    }

    @Test
    fun `a try that ran out is told as time that ran out, and not as a wrong answer`() {
        show(firing(typing(tryNo = 2, triesLeft = 2, mistaken = true, slow = true)), Recorder())
        compose.onNodeWithText("Time ran out. 2 tries left.").assertIsDisplayed()
        compose.onNodeWithText("That is not it. 2 tries left.").assertDoesNotExist()
    }

    @Test
    fun `the last try that ran out says one try left, in the singular`() {
        show(firing(typing(tryNo = 3, triesLeft = 1, mistaken = true, slow = true)), Recorder())
        compose.onNodeWithText("Time ran out. 1 try left.").assertIsDisplayed()
    }

    @Test
    fun `the typing field asks for no capitals and no autocorrect, a plain keyboard, and Done`() {
        assertEquals(KeyboardCapitalization.None, TYPING_KEYBOARD.capitalization)
        assertEquals("an autocorrected word is a wrong try the person did not make", false, TYPING_KEYBOARD.autoCorrectEnabled)
        assertEquals(KeyboardType.Text, TYPING_KEYBOARD.keyboardType)
        assertEquals(ImeAction.Done, TYPING_KEYBOARD.imeAction)
    }

    @Test
    fun `under a screen reader the field is not grabbed before the phrase has been read`() {
        val accessibility = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(AccessibilityManager::class.java)
        shadowOf(accessibility).setTouchExplorationEnabled(true)

        show(firing(typing()), Recorder())

        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).assertIsDisplayed().assertIsNotFocused()
    }

    @Test
    fun `a new try begins with an empty field, and the same try keeps what was typed when the screen is redrawn`() {
        var state by mutableStateOf(firing(typing(tryNo = 1)))
        compose.setContent {
            CommandTheme(darkTheme = true) { ReveilleContent(state, null, emptyList(), Recorder().actions(), {}, {}) }
        }
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("up and")

        state = firing(typing(tryNo = 1, triesLeft = 2)) // the same try, drawn again
        compose.waitForIdle()
        assertEquals("up and", editableText(ReveilleTags.TYPING_FIELD))

        state = firing(typing(tryNo = 2, triesLeft = 2, mistaken = true)) // the next try
        compose.waitForIdle()
        assertEquals("", editableText(ReveilleTags.TYPING_FIELD))
    }

    @Test
    fun `what was typed survives the activity being recreated`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CommandTheme(darkTheme = true) { ReveilleContent(firing(typing()), null, emptyList(), Recorder().actions(), {}, {}) }
        }
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("up and")

        restoration.emulateSavedInstanceStateRestore()

        assertEquals("up and", editableText(ReveilleTags.TYPING_FIELD))
    }

    @Test
    fun `Safety silence is one tap while a phrase is being typed`() {
        val r = Recorder()
        show(firing(typing()), r)
        compose.onNodeWithTag(ReveilleTags.TYPING_FIELD).performTextInput("Up")

        compose.onNodeWithTag(ReveilleTags.SILENCE).performClick()

        assertEquals(1, r.silenced)
        assertTrue("silencing submits nothing", r.submitted.isEmpty())
    }

    // --- the confirmation sequence --------------------------------------------------------------------------------------

    @Test
    fun `the numbers stand in the order given, each one a button with its number, and nothing is done yet`() {
        show(firing(sequence()), Recorder())

        compose.onNodeWithText("Tap the numbers in order, 1 to 4").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.SEQUENCE_PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("0 of 4 done").assertIsDisplayed()
        val x = { n: Int -> compose.onNodeWithTag(ReveilleTags.sequenceTarget(n)).fetchSemanticsNode().boundsInRoot.left }
        val y = { n: Int -> compose.onNodeWithTag(ReveilleTags.sequenceTarget(n)).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("3 then 1 on the first row", x(3) < x(1) && y(3) == y(1))
        assertTrue("4 then 2 on the second", x(4) < x(2) && y(4) == y(2))
        assertTrue("and the second row is below the first", y(3) < y(4))
        for (n in 1..4) {
            compose.onNodeWithTag(ReveilleTags.sequenceTarget(n)).assertIsDisplayed().assertIsEnabled()
            compose.onNode(hasTestTag(ReveilleTags.sequenceTarget(n)) and hasContentDescription("Number $n")).assertExists()
        }
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed()
    }

    @Test
    fun `a tap on a number reports it with the try`() {
        val r = Recorder()
        show(firing(sequence(tryNo = 2)), r)

        compose.onNodeWithTag(ReveilleTags.sequenceTarget(3)).performClick()
        compose.onNodeWithTag(ReveilleTags.sequenceTarget(1)).performClick()

        assertEquals(listOf(TryId(0, 2) to 3, TryId(0, 2) to 1), r.tapped)
    }

    @Test
    fun `numbers that are done cannot be tapped again, and say how many are done`() {
        show(firing(sequence(tapped = 2)), Recorder())

        compose.onNodeWithText("2 of 4 done").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.sequenceTarget(1)).assertIsNotEnabled()
        compose.onNodeWithTag(ReveilleTags.sequenceTarget(2)).assertIsNotEnabled()
        compose.onNodeWithTag(ReveilleTags.sequenceTarget(3)).assertIsEnabled()
        compose.onNodeWithTag(ReveilleTags.sequenceTarget(4)).assertIsEnabled()
    }

    @Test
    fun `a wrong tap says to start again from 1, with the tries left`() {
        show(firing(sequence(tryNo = 2, triesLeft = 2, mistaken = true)), Recorder())
        compose.onNodeWithText("Not that one. Start again from 1. 2 tries left.").assertIsDisplayed()
    }

    @Test
    fun `numbers that ran out of time say so, and to start again from 1`() {
        show(firing(sequence(tryNo = 2, triesLeft = 2, mistaken = true, slow = true)), Recorder())
        compose.onNodeWithText("Time ran out. Start again from 1. 2 tries left.").assertIsDisplayed()
        compose.onNodeWithText("Not that one. Start again from 1. 2 tries left.").assertDoesNotExist()
    }

    // --- what a screen reader is told when something changes -----------------------------------------------------------

    @Test
    fun `the typing challenge announces its way, and that a try was wrong`() {
        show(firing(typing(tryNo = 2, triesLeft = 2, mistaken = true)), Recorder())
        assertEquals("the title of the way, so that a change of way is not silent", LiveRegionMode.Polite, liveRegionOfText("Type this to show you are up"))
        assertEquals(LiveRegionMode.Polite, liveRegionOfTag(ReveilleTags.MISTAKEN))
    }

    @Test
    fun `the numbers announce their way, how many are done, and that a try was wrong`() {
        show(firing(sequence(tapped = 1, tryNo = 2, triesLeft = 2, mistaken = true)), Recorder())
        assertEquals(LiveRegionMode.Polite, liveRegionOfText("Tap the numbers in order, 1 to 4"))
        assertEquals(LiveRegionMode.Polite, liveRegionOfTag(ReveilleTags.SEQUENCE_PROGRESS))
        assertEquals(LiveRegionMode.Polite, liveRegionOfTag(ReveilleTags.MISTAKEN))
    }

    @Test
    fun `the two-step announces its way`() {
        show(firing(Challenge.TwoStep(TryId(1, 1), "Open the document")), Recorder())
        assertEquals(LiveRegionMode.Polite, liveRegionOfText("Say what you will do first"))
    }

    @Test
    fun `the end of the road is announced`() {
        show(firing(Challenge.OutOfTries), Recorder())
        assertEquals(LiveRegionMode.Polite, liveRegionOfTag(ReveilleTags.OUT_OF_TRIES))
    }

    @Test
    fun `the three taps are announced as they are counted`() {
        show(firing(Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = true, acknowledging = false)), Recorder())
        assertEquals(LiveRegionMode.Polite, liveRegionOfTag(ReveilleTags.ACCESSIBLE))
    }

    // --- every control a challenge adds is a real target ---------------------------------------------------------------

    @Test
    fun `every control the challenges add is as big as a target should be`() {
        show(firing(typing()), Recorder())
        compose.onNodeWithTag(ReveilleTags.TYPING_DONE).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `each of the four numbers is a big target`() {
        show(firing(sequence()), Recorder())
        for (n in 1..4) compose.onNodeWithTag(ReveilleTags.sequenceTarget(n)).assertHeightIsAtLeast(88.dp).assertWidthIsAtLeast(88.dp)
    }

    @Test
    fun `the two-step's button is a big target`() {
        show(firing(Challenge.TwoStep(TryId(1, 1), "Open the document")), Recorder())
        compose.onNodeWithTag(ReveilleTags.TWO_STEP_INDICATE).assertHeightIsAtLeast(72.dp)
    }

    @Test
    fun `the three taps are a big target when they stand in for the press, and a smaller one under it`() {
        show(firing(Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = true, acknowledging = false)), Recorder())
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertHeightIsAtLeast(96.dp)
    }

    // --- the two-step -------------------------------------------------------------------------------------------------

    @Test
    fun `the two-step puts what the person will do first in front of them, and one tap stands behind it`() {
        val r = Recorder()
        show(firing(Challenge.TwoStep(TryId(1, 1), "Open the document"), ChainProgress(2, 2)), r)

        compose.onNodeWithText("Say what you will do first").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("Step 2 of 2").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.TWO_STEP_INDICATE).assertIsDisplayed()
        compose.onNodeWithText("I am doing: Open the document").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed()

        compose.onNodeWithTag(ReveilleTags.TWO_STEP_INDICATE).performClick()

        assertEquals(listOf(TryId(1, 1)), r.indicated)
    }

    // --- a chain of several steps, a hold that acknowledges, no tries left --------------------------------------------------

    @Test
    fun `a chain of several steps that are all needed says which one this is`() {
        show(firing(typing(), ChainProgress(1, 2)), Recorder())
        compose.onNodeWithText("Step 1 of 2").assertIsDisplayed()
    }

    @Test
    fun `a single step says nothing about steps`() {
        show(firing(typing()), Recorder())
        compose.onNodeWithTag(ReveilleTags.PROGRESS).assertDoesNotExist()
    }

    @Test
    fun `a hold that is the first part of a two-step says what it is for`() {
        show(firing(Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = false, acknowledging = true), ChainProgress(1, 1)), Recorder())

        compose.onNodeWithTag(ReveilleTags.ACKNOWLEDGING).assertIsDisplayed()
        compose.onNodeWithText("First, hold to show you are up").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.HOLD).assertIsDisplayed()
    }

    @Test
    fun `when every way is used up it is said plainly, and Safety silence is still there`() {
        val r = Recorder()
        show(firing(Challenge.OutOfTries), r)

        compose.onNodeWithTag(ReveilleTags.OUT_OF_TRIES).assertIsDisplayed()
        compose.onNodeWithText("No tries are left. The alarm keeps going until its time is up. You can silence it, or press Back to put it off or skip it.").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.HOLD).assertDoesNotExist()
        compose.onNodeWithTag(ReveilleTags.SILENCE).performClick()
        assertEquals(1, r.silenced)
    }

    // --- the hold's assistive confirm ------------------------------------------------------------------------------------

    @Test
    fun `when the press is used up three deliberate taps take its place, counted aloud, and the last one confirms`() {
        val r = Recorder()
        show(firing(Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = true, acknowledging = false)), r)

        compose.onNodeWithTag(ReveilleTags.HOLD).assertDoesNotExist()
        compose.onNodeWithText("I cannot hold: confirm instead").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick()
        compose.onNodeWithText("Tap to confirm (2 of 3)").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick()
        compose.onNodeWithText("Tap to confirm (3 of 3)").assertIsDisplayed()
        assertTrue("two taps are not a confirmation", r.assistive.isEmpty())
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick()

        assertEquals(listOf(TryId(0, 1)), r.assistive)
    }

    @Test
    fun `a press that began on one try's hold target does not finish on the next try's`() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(firing(Challenge.Hold(TryId(0, 1), 3, earlyRelease = false, assistive = false, acknowledging = false)))
        val r = Recorder()
        compose.setContent {
            CommandTheme(darkTheme = true) { ReveilleContent(state, null, emptyList(), r.actions(), {}, {}) }
        }
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(2_000)

        state = firing(Challenge.Hold(TryId(0, 2), 3, earlyRelease = true, assistive = false, acknowledging = false)) // another try on the same ring
        compose.mainClock.advanceTimeBy(2_500) // long enough for the first try's three seconds to have passed
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)

        assertTrue("nothing was held on the try that is on the screen: ${r.completed}", r.completed.isEmpty())
    }

    @Test
    fun `a hold reports the try it is for`() {
        compose.mainClock.autoAdvance = false
        val r = Recorder()
        show(firing(Challenge.Hold(TryId(0, 3), 3, earlyRelease = false, assistive = false, acknowledging = false)), r)

        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)

        assertEquals(listOf(TryId(0, 3)), r.completed)
    }
}
