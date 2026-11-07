package com.command.reveille

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.center
import androidx.compose.ui.semantics.SemanticsActions
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.SkipReasonCode
import com.command.domain.handshake.TryId
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
        val holdStarted = ArrayList<TryId>()
        val holdStartedAt = ArrayList<Long>()
        val completed = ArrayList<Pair<Long, Long>>()
        val early = ArrayList<Pair<Long, Long>>()
        val assistive = ArrayList<TryId>()
        var silenced = 0
        var resumed = 0
        var started = 0
        val delays = ArrayList<Int>()
        var skipped = 0
        var closed = 0
        val frictionGiven = ArrayList<FrictionEvidence>()
        var frictionDismissed = 0

        fun actions() = ReveilleActions(
            onHoldStarted = { at, startedAt ->
                holdStarted += at
                holdStartedAt += startedAt
            },
            onHoldCompleted = { _, a, b -> completed += a to b },
            onHoldReleasedEarly = { _, a, b -> early += a to b },
            onAssistiveConfirmed = { assistive += it },
            onSilence = { silenced++ },
            onResume = { resumed++ },
            onStarted = { started++ },
            onDelay = { delays += it },
            onSkip = { skipped++ },
            onClose = { closed++ },
        )
    }

    private fun hold(tryNo: Int = 1, earlyRelease: Boolean = false, assistive: Boolean = false, acknowledging: Boolean = false) =
        Challenge.Hold(TryId(0, tryNo), 3, earlyRelease, assistive, acknowledging)

    private val firing = ReveilleUiState.Firing("occ-1", "Write the report", hold())

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
        assertEquals(listOf(TryId(0, 1)), r.holdStarted)
        assertEquals("the press says when it began, and the completion says the same", r.completed.single().first, r.holdStartedAt.single())
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
    fun `the hold target has no one-tap confirm of its own, the assistive confirm is three deliberate taps`() {
        show(firing, Recorder())
        assertFalse(SemanticsActions.CustomActions in compose.onNodeWithTag(ReveilleTags.HOLD).fetchSemanticsNode().config)
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
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick()
        assertTrue("two taps are not enough", r.assistive.isEmpty())
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick()
        assertEquals("the third confirms, and says which try it was about", listOf(TryId(0, 1)), r.assistive)
    }

    @Test
    fun `assistive technology that clicks gets an action on the hold target that shows the three taps, and it does not pass the hold`() {
        val r = Recorder()
        show(firing, r)
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertDoesNotExist()
        val action = compose.onNodeWithTag(ReveilleTags.HOLD).fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals("I cannot hold: confirm instead", action.label)

        compose.onNodeWithTag(ReveilleTags.HOLD).performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.HOLD).assertIsDisplayed()
        assertTrue("showing the alternative passes nothing", r.assistive.isEmpty() && r.completed.isEmpty() && r.early.isEmpty())
        repeat(3) { compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).performClick() }
        assertEquals("three deliberate taps pass it, and say which try", listOf(TryId(0, 1)), r.assistive)
    }

    @Test
    fun `a finger on the hold target is not affected by that action, a quick tap is an early release and shows nothing`() {
        val r = Recorder()
        compose.mainClock.autoAdvance = false
        show(firing, r)

        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)

        assertEquals(1, r.early.size)
        assertTrue(r.completed.isEmpty() && r.assistive.isEmpty())
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertDoesNotExist()
    }

    @Test
    fun `the three taps that were asked for are still there when the screen is recreated`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CommandTheme(darkTheme = true) { ReveilleContent(firing, null, emptyList(), Recorder().actions(), {}, {}) }
        }
        compose.onNodeWithTag(ReveilleTags.HOLD).performSemanticsAction(SemanticsActions.OnClick)

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertIsDisplayed()
    }

    @Test
    fun `a plain hold does not say that it is the first of two parts`() {
        show(firing, Recorder())
        compose.onNodeWithTag(ReveilleTags.ACKNOWLEDGING).assertDoesNotExist()
        compose.onNodeWithText("First, hold to show you are up").assertDoesNotExist()
    }

    @Test
    fun `the hint about letting go early is not shown beside the three taps that stand in for the press`() {
        show(firing.copy(challenge = hold(earlyRelease = true, assistive = true)), Recorder())
        compose.onNodeWithText("Released early. Hold the full 3 seconds.").assertDoesNotExist()
        compose.onNodeWithTag(ReveilleTags.ACCESSIBLE).assertIsDisplayed()
    }

    @Test
    fun `the early-release hint names the seconds`() {
        show(firing.copy(challenge = hold(earlyRelease = true)), Recorder())
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

    /** The screen behind the dialog has a Skip button of its own, so this looks in the dialog only. */
    private fun assertDialogDoesNotSaySkip() {
        compose.onNodeWithText("Keep going").assertIsDisplayed() // the dialog is there to look in
        assertEquals(0, compose.onAllNodes(hasText("Skip", substring = true, ignoreCase = true) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size)
    }

    @Test
    fun `a delay asks to confirm in its own words, with no skip in them`() {
        val r = Recorder()
        show(ReveilleUiState.Direction("x", "y", 5), r, FrictionPrompt(LockPolicy.CONFIRM, FrictionAction.DELAY) { })
        compose.onNodeWithText("Put off a fixed commitment?").assertIsDisplayed()
        compose.onNodeWithText("Confirm that you want to put it off", substring = true).assertIsDisplayed()
        assertDialogDoesNotSaySkip()
        compose.onNodeWithText("Put it off anyway").performClick()
        assertEquals(listOf<FrictionEvidence>(FrictionEvidence.Confirmed), r.frictionGiven)
    }

    @Test
    fun `a delay asks for a reason in its own words`() {
        val r = Recorder()
        show(ReveilleUiState.Direction("x", "y", 5), r, FrictionPrompt(LockPolicy.REASON, FrictionAction.DELAY) { })
        compose.onNodeWithText("Why put it off?").assertIsDisplayed()
        assertDialogDoesNotSaySkip()
        compose.onNodeWithText("Need rest").performClick()
        assertEquals(listOf<FrictionEvidence>(FrictionEvidence.Reason(SkipReasonCode.NEED_REST)), r.frictionGiven)
    }

    @Test
    fun `a delay's recovery offer is in its own words too, and reports the decline`() {
        val r = Recorder()
        show(ReveilleUiState.Direction("x", "y", 5), r, FrictionPrompt(LockPolicy.RECOVERY_OFFER, FrictionAction.DELAY) { })
        compose.onNodeWithText("Before you put it off").assertIsDisplayed()
        compose.onNodeWithText("Put it off anyway?", substring = true).assertIsDisplayed()
        assertDialogDoesNotSaySkip()
        compose.onNodeWithText("Put it off anyway").performClick()
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
    private fun showWithBack(state: ReveilleUiState, r: Recorder, friction: FrictionPrompt? = null): () -> Unit {
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, friction, SkipReasonCode.values().toList(), r.actions(), { r.frictionGiven += it }, { r.frictionDismissed++ })
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

    // --- the way out, after review: two dialogs, one ring ------------------------------------------------------

    @Test
    fun `a Mission Lock question asked over the way out carries Safety silence, one tap, with nothing asked first`() {
        val r = Recorder()
        val back = showWithBack(firing, r, FrictionPrompt(LockPolicy.CONFIRM) { })
        back()

        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertExists()
        compose.onNodeWithText("Skip a fixed commitment?").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).performClick()

        assertEquals(1, r.silenced)
        assertTrue("silencing is not a confirmation", r.frictionGiven.isEmpty())
        assertEquals(0, r.frictionDismissed)
    }

    @Test
    fun `the Safety silence of the way out and of the question over it is in the row of actions, which never scrolls, and the choices are in what does`() {
        showWithBack(firing, Recorder(), FrictionPrompt(LockPolicy.REASON) { })()

        compose.onNodeWithTag(ReveilleTags.ESCAPE_SILENCE).assert(hasAnyAncestor(hasScrollAction()).not())
        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).assert(hasAnyAncestor(hasScrollAction()).not())
        // The matcher is not blind: what the dialogs let scroll is the choices.
        compose.onNodeWithTag(ReveilleTags.ESCAPE_SKIP).assert(hasAnyAncestor(hasScrollAction()))
        compose.onNodeWithText("Other").assert(hasAnyAncestor(hasScrollAction()))
    }

    @Test
    fun `a Mission Lock question that asks for a reason carries Safety silence in its row of actions`() {
        val r = Recorder()
        showWithBack(firing, r, FrictionPrompt(LockPolicy.REASON) { })()

        compose.onNodeWithText("Why skip?").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.FRICTION_SILENCE).performClick()
        assertEquals(1, r.silenced)
        assertTrue(r.frictionGiven.isEmpty())
    }

    @Test
    fun `a Mission Lock question on a screen that is not ringing has no silence to offer`() {
        show(ReveilleUiState.Direction("Write the report", "Open the document", 5), Recorder(), FrictionPrompt(LockPolicy.CONFIRM) { })

        compose.onNodeWithText("Skip a fixed commitment?").assertIsDisplayed()
        compose.onAllNodesWithTagCount(ReveilleTags.FRICTION_SILENCE, 0)
    }

    @Test
    fun `cancelling the Mission Lock question leaves the way out open and nothing decided`() {
        val r = Recorder()
        showWithBack(firing, r, FrictionPrompt(LockPolicy.CONFIRM) { })()

        compose.onNodeWithText("Keep going").performClick()

        assertEquals(1, r.frictionDismissed)
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()
        assertEquals(0, r.silenced + r.skipped + r.delays.size)
    }

    @Test
    fun `answered by any route, the way out starts closed for the next alarm`() {
        var dispatcher: OnBackPressedDispatcher? = null
        var state by mutableStateOf<ReveilleUiState>(firing)
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, null, SkipReasonCode.values().toList(), Recorder().actions(), {}, {})
            }
        }
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()

        state = ReveilleUiState.Silenced("Write the report")
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertDoesNotExist()
        state = firing.copy(occurrenceId = "occ-2", title = "Stretch")

        compose.onNodeWithText("Stretch").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertDoesNotExist()
    }

    @Test
    fun `when another alarm takes over, the way out opened for the first does not carry over to it`() {
        var dispatcher: OnBackPressedDispatcher? = null
        var state by mutableStateOf<ReveilleUiState>(firing)
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, null, SkipReasonCode.values().toList(), Recorder().actions(), {}, {})
            }
        }
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()

        state = firing.copy(occurrenceId = "occ-2", title = "Stretch")

        compose.onNodeWithText("Stretch").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertDoesNotExist()
    }

    @Test
    fun `the same ring keeps its way out open while the screen is redrawn with the same alarm`() {
        var dispatcher: OnBackPressedDispatcher? = null
        var state by mutableStateOf<ReveilleUiState>(firing)
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, null, SkipReasonCode.values().toList(), Recorder().actions(), {}, {})
            }
        }
        compose.runOnIdle { dispatcher!!.onBackPressed() }

        state = firing.copy(challenge = hold(tryNo = 2, earlyRelease = true)) // a failed hold redraws the ring screen; it is still the same alarm

        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()
    }

    @Test
    fun `what was open before the activity was recreated is not opened over another alarm`() {
        var dispatcher: OnBackPressedDispatcher? = null
        var shown: ReveilleUiState = firing
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CommandTheme(darkTheme = true) {
                ReveilleContent(shown, null, SkipReasonCode.values().toList(), Recorder().actions(), {}, {})
            }
        }
        compose.runOnIdle { dispatcher!!.onBackPressed() }
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertIsDisplayed()

        shown = firing.copy(occurrenceId = "occ-2", title = "Stretch") // by the time the screen is back, another alarm is the one ringing
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Stretch").assertIsDisplayed()
        compose.onNodeWithTag(ReveilleTags.ESCAPE).assertDoesNotExist()
    }

    @Test
    fun `a press that began on one ring's hold target does not finish on the ring that took over`() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf<ReveilleUiState>(firing)
        val r = Recorder()
        compose.setContent {
            CommandTheme(darkTheme = true) {
                ReveilleContent(state, null, SkipReasonCode.values().toList(), r.actions(), {}, {})
            }
        }
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(2_000)

        state = firing.copy(occurrenceId = "occ-2", title = "Stretch") // another ring takes over while the finger is down
        compose.mainClock.advanceTimeBy(2_500) // long enough for the first ring's three seconds to have passed
        compose.onNodeWithTag(ReveilleTags.HOLD).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)

        assertTrue("nothing was held on the ring that is on the screen: ${r.completed}", r.completed.isEmpty())
    }

    @Test
    fun `Keep ringing is as easy to hit as the rest`() {
        showWithBack(firing, Recorder())()

        compose.onNodeWithTag(ReveilleTags.ESCAPE_KEEP).assertHeightIsAtLeast(56.dp)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
    assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size)
}
