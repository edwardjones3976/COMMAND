package com.command.reveille

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.center
import androidx.compose.ui.semantics.SemanticsActions
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.SkipReasonCode
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lock-screen screens with no view model: what is on screen, and what each control reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReveilleScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder {
        var holdStarted = 0
        val completed = ArrayList<Pair<Long, Long>>()
        val early = ArrayList<Pair<Long, Long>>()
        var accessible = 0
        var silenced = 0
        var resumed = 0
        var started = 0
        val delays = ArrayList<Int>()
        var skipped = 0
        var closed = 0
        val frictionGiven = ArrayList<FrictionEvidence>()
        var frictionDismissed = 0

        fun actions() = ReveilleActions(
            onHoldStarted = { holdStarted++ },
            onHoldCompleted = { a, b -> completed += a to b },
            onHoldReleasedEarly = { a, b -> early += a to b },
            onAccessibleConfirm = { accessible++ },
            onSilence = { silenced++ },
            onResume = { resumed++ },
            onStarted = { started++ },
            onDelay = { delays += it },
            onSkip = { skipped++ },
            onClose = { closed++ },
        )
    }

    private val firing = ReveilleUiState.Firing("Write the report", holdSeconds = 3, earlyRelease = false, offerAccessibleConfirm = false)

    private fun show(state: ReveilleUiState, r: Recorder, friction: FrictionPrompt? = null) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, friction, SkipReasonCode.values().toList(), r.actions(), { r.frictionGiven += it }, { r.frictionDismissed++ })
            }
        }
    }

    // --- the ring screen --------------------------------------------------------------------------------------------

    @Test
    fun `while ringing the hold target and Safety silence are both on screen`() {
        show(firing, Recorder())
        compose.onNodeWithText("Write the report").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.HOLD).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertIsDisplayed()
    }

    @Test
    fun `safety silence is one tap`() {
        val r = Recorder()
        show(firing, r)
        compose.onNodeWithTag(ReveilleTags.SILENCE).performClick()
        assertEquals(1, r.silenced)
    }

    @Test
    fun `holding for the full time completes once`() {
        val r = Recorder()
        compose.mainClock.autoAdvance = false
        show(firing, r)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, r.holdStarted)
        assertEquals(1, r.completed.size)
        assertTrue("a completed hold is not also an early release", r.early.isEmpty())
    }

    @Test
    fun `letting go early starts over and reports a failed attempt`() {
        val r = Recorder()
        compose.mainClock.autoAdvance = false
        show(firing, r)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, r.early.size)
        assertTrue(r.completed.isEmpty())
        // A second, full hold afterwards still works: each press is independent.
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, r.completed.size)
        assertEquals(1, r.early.size)
    }

    @Test
    fun `a screen reader can confirm without holding`() {
        val r = Recorder()
        show(firing, r)
        val actions = compose.onNodeWithTag(ReveilleTags.HOLD).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals("Confirm without holding", actions.single().label)
        compose.runOnUiThread { actions.single().action() }
        assertEquals(1, r.accessible)
    }

    @Test
    fun `the alternative button only appears for touch exploration users`() {
        show(firing, Recorder())
        compose.onAllNodesWithTagCount(ReveilleTags.ACCESSIBLE, 0)
    }

    @Test
    fun `the alternative button is shown and works when touch exploration is on`() {
        val r = Recorder()
        show(firing.copy(offerAccessibleConfirm = true), r)
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick()
        assertEquals(1, r.accessible)
    }

    @Test
    fun `the early-release hint names the seconds`() {
        show(firing.copy(earlyRelease = true), Recorder())
        compose.onNodeWithText("Released early. Hold the full 3 seconds.").assertIsDisplayed()
    }

    @Test
    fun `Back is not an exit while the alarm rings, and is free again afterwards`() {
        var dispatcher: OnBackPressedDispatcher? = null
        var state by mutableStateOf<ReveilleUiState>(firing)
        val r = Recorder()
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, null, SkipReasonCode.values().toList(), r.actions(), {}, {})
            }
        }
        compose.runOnIdle { assertTrue("Back is consumed while it rings", dispatcher!!.hasEnabledCallbacks()) }
        state = ReveilleUiState.Direction("Write the report", "Open the document", 5)
        compose.runOnIdle { assertFalse("and free once it is answered", dispatcher!!.hasEnabledCallbacks()) }
    }

    // --- silenced, direction, closed ----------------------------------------------------------------------------

    @Test
    fun `silenced says plainly that nothing was started and offers resume or skip`() {
        val r = Recorder()
        show(ReveilleUiState.Silenced("Write the report"), r)
        compose.onNodeWithText("Silenced is not started. Pick it up when you are ready.").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.RESUME).performClick()
        compose.onNodeWithTag(ReveilleTags.SKIP).performClick()
        assertEquals(1, r.resumed)
        assertEquals(1, r.skipped)
    }

    @Test
    fun `a missed alarm says it was missed, not silenced, and still offers resume`() {
        val r = Recorder()
        show(ReveilleUiState.Silenced("Write the report", missed = true), r)
        compose.onNodeWithText("Alarm missed").assertIsDisplayed()
        compose.onNodeWithText("Nothing was started. Pick it up when you are ready.").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.RESUME).performClick()
        assertEquals(1, r.resumed)
    }

    @Test
    fun `the direction shows one concrete step and three one-tap answers`() {
        val r = Recorder()
        show(ReveilleUiState.Direction("Write the report", "Open the document", 5), r)
        compose.onNodeWithText("Open the document").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.STARTED).performClick()
        compose.onNodeWithTag(ReveilleTags.DELAY).performClick()
        compose.onNodeWithTag(ReveilleTags.SKIP).performClick()
        assertEquals(1, r.started)
        assertEquals(listOf(10), r.delays)
        assertEquals(1, r.skipped)
    }

    @Test
    fun `a finished alarm says how it ended and can be closed`() {
        val r = Recorder()
        show(ReveilleUiState.Closed("Write the report", ClosedOutcome.SKIPPED), r)
        compose.onNodeWithText("Skipped. It is recorded as skipped.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, r.closed)
    }

    // --- Mission Lock ---------------------------------------------------------------------------------------------

    @Test
    fun `a confirm-level lock asks once and reports a plain confirmation`() {
        val r = Recorder()
        show(ReveilleUiState.Direction("x", "y", 5), r, FrictionPrompt(LockPolicy.CONFIRM) { })
        compose.onNodeWithText("Skip a fixed commitment?").assertIsDisplayed()
        compose.onNodeWithText("Skip anyway").performClick()
        assertEquals(listOf<FrictionEvidence>(FrictionEvidence.Confirmed), r.frictionGiven)
    }

    @Test
    fun `a reason-level lock offers every reason and reports the one chosen`() {
        val r = Recorder()
        show(ReveilleUiState.Direction("x", "y", 5), r, FrictionPrompt(LockPolicy.REASON) { })
        compose.onNodeWithText("Why skip?").assertIsDisplayed()
        compose.onNodeWithText("Need rest").performClick()
        assertEquals(listOf<FrictionEvidence>(FrictionEvidence.Reason(SkipReasonCode.NEED_REST)), r.frictionGiven)
    }

    @Test
    fun `a recovery-level lock explains and reports the decline`() {
        val r = Recorder()
        show(ReveilleUiState.Direction("x", "y", 5), r, FrictionPrompt(LockPolicy.RECOVERY_OFFER) { })
        compose.onNodeWithText("Before you skip").assertIsDisplayed()
        compose.onNodeWithText("Skip anyway").performClick()
        assertEquals(listOf<FrictionEvidence>(FrictionEvidence.RecoveryDeclined), r.frictionGiven)
    }

    @Test
    fun `keeping going dismisses the lock prompt`() {
        val r = Recorder()
        show(ReveilleUiState.Direction("x", "y", 5), r, FrictionPrompt(LockPolicy.CONFIRM) { })
        compose.onNodeWithText("Keep going").performClick()
        assertEquals(1, r.frictionDismissed)
    }

    // --- the way out that Back opens ---------------------------------------------------------------------------

    /** Shows [state] with its Back dispatcher in hand, and returns what presses Back. */
    private fun showWithBack(state: ReveilleUiState, r: Recorder): () -> Unit {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, null, SkipReasonCode.values().toList(), r.actions(), {}, {})
            }
        }
        return { compose.runOnIdle { dispatcher!!.onBackPressed() } }
    }

    @Test
    fun `Back opens the way out while it rings, with silence, three delays, skip or keep ringing, and Safety silence stays on the screen`() {
        val r = Recorder()
        val back = showWithBack(firing, r)
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertDoesNotExist()

        back()

        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()
        compose.onNodeWithText("Need to get out of this?").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SILENCE).assertIsDisplayed()
        for (minutes in listOf(10, 30, 60)) compose.onNodeWithTag(ReveilleTags.escapeDelay(minutes)).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SKIP).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_KEEP).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.SILENCE).assertExists()
        assertEquals("opening it decides nothing", 0, r.silenced + r.skipped + r.delays.size)
    }

    @Test
    fun `each way out reports itself, silence and skip once and every delay with its minutes`() {
        val r = Recorder()
        val back = showWithBack(firing, r)
        back()

        compose.onNodeWithTag(ReveilleTags.escapeDelay(10)).performClick()
        compose.onNodeWithTag(ReveilleTags.escapeDelay(30)).performClick()
        compose.onNodeWithTag(ReveilleTags.escapeDelay(60)).performClick()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SKIP).performClick()
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SILENCE).performClick()

        assertEquals(listOf(10, 30, 60), r.delays)
        assertEquals(1, r.skipped)
        assertEquals(1, r.silenced)
    }

    @Test
    fun `keeping it ringing closes the way out and reports nothing, and Back opens it again`() {
        val r = Recorder()
        val back = showWithBack(firing, r)
        back()

        compose.onNodeWithTag(ReveilleTags.ESCAPE_KEEP).performClick()
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertDoesNotExist()
        assertEquals(0, r.silenced + r.skipped + r.delays.size)

        back()
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()
    }

    @Test
    fun `once the alarm is answered the way out is gone, and it does not come back by itself`() {
        var dispatcher: OnBackPressedDispatcher? = null
        var state by mutableStateOf<ReveilleUiState>(firing)
        val r = Recorder()
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, null, SkipReasonCode.values().toList(), r.actions(), {}, {})
            }
        }
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()

        state = ReveilleUiState.Silenced("Write the report")
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertDoesNotExist()
        compose.onNodeWithTag(ReveilleTags.RESUME).assertIsDisplayed()
    }

    @Test
    fun `the way out survives the activity being recreated`() {
        var dispatcher: OnBackPressedDispatcher? = null
        val restoration = StateRestorationTester(compose)
        val r = Recorder()
        restoration.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(firing, null, SkipReasonCode.values().toList(), r.actions(), {}, {})
            }
        }
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
    assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size)
}
