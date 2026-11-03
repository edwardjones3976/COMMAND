package com.command.followup

import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.RecoveryOption
import com.command.domain.dials.SkipReasonCode
import com.command.reveille.FrictionPrompt
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A tall screen, so that nothing needs scrolling to be found.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class FollowUpScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Taps {
        val log = ArrayList<String>()
        val actions = FollowUpActions(
            onStarted = { log += "started" },
            onWorking = { log += "working" },
            onDone = { log += "done" },
            onNeedReset = { log += "reset" },
            onDelay = { log += "delay $it" },
            onSkip = { log += "skip" },
            onRestart = { log += "restart" },
            onRescheduleTo = { log += "reschedule $it" },
            onNotNow = { log += "not now" },
            onFrictionConfirm = { log += "friction $it" },
            onFrictionDismiss = { log += "friction dismissed" },
        )
        var closed = 0
    }

    private fun show(
        state: FollowUpUiState,
        taps: Taps = Taps(),
        friction: FrictionPrompt? = null,
        notice: FollowUpNotice? = null,
    ): Taps {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                FollowUpScreen(
                    state = state,
                    friction = friction,
                    notice = notice,
                    reasons = SkipReasonCode.values().toList(),
                    actions = taps.actions,
                    onClose = { taps.closed++ },
                )
            }
        }
        return taps
    }

    private val ask = FollowUpUiState.Ask("Write the report", "Open the document", started = false, unconfirmed = false)

    // --- the question -------------------------------------------------------------------------------------------------

    @Test
    fun `a start that was not reported is asked about, with the title and the first step, and all six answers are there`() {
        show(ask)
        compose.onNodeWithText("Write the report").assertIsDisplayed()
        compose.onNodeWithText("Have you started?").assertIsDisplayed()
        compose.onNodeWithText("Your first step: Open the document").assertIsDisplayed()
        for (tag in listOf(FollowUpTags.STARTED, FollowUpTags.WORKING, FollowUpTags.DONE, FollowUpTags.RESET, FollowUpTags.DELAY, FollowUpTags.SKIP)) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun `each answer reports itself, once, and nothing else`() {
        val taps = show(ask)
        compose.onNodeWithTag(FollowUpTags.STARTED).performClick()
        compose.onNodeWithTag(FollowUpTags.WORKING).performClick()
        compose.onNodeWithTag(FollowUpTags.DONE).performClick()
        compose.onNodeWithTag(FollowUpTags.RESET).performClick()
        compose.onNodeWithTag(FollowUpTags.SKIP).performClick()
        assertEquals(listOf("started", "working", "done", "reset", "skip"), taps.log)
    }

    @Test
    fun `once a start was reported the question is how it is going, and the answer that fits leads`() {
        show(ask.copy(started = true))
        compose.onNodeWithText("How is it going?").assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.WORKING).assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.DONE).assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.STARTED).assertDoesNotExist()
    }

    @Test
    fun `questions that went unanswered are said to have been`() {
        show(ask.copy(unconfirmed = true))
        compose.onNodeWithText("You did not answer. Did you start?").assertIsDisplayed()
    }

    @Test
    fun `a delay asks how long, offers three lengths, and gives up when cancelled`() {
        val taps = show(ask)
        compose.onNodeWithTag(FollowUpTags.DELAY).performClick()
        compose.onNodeWithText("Delay for how long?").assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.delayFor(10)).assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.delayFor(30)).assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.delayFor(60)).assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delay for how long?").assertDoesNotExist()
        assertTrue("cancelling delays nothing", taps.log.isEmpty())
    }

    @Test
    fun `choosing a length reports that many minutes and closes the dialog`() {
        val taps = show(ask)
        compose.onNodeWithTag(FollowUpTags.DELAY).performClick()
        compose.onNodeWithTag(FollowUpTags.delayFor(30)).performClick()
        assertEquals(listOf("delay 30"), taps.log)
        compose.onNodeWithText("Delay for how long?").assertDoesNotExist()
    }

    @Test
    fun `when no reset is left the screen says so and offers delaying or skipping instead`() {
        show(ask, notice = FollowUpNotice.RESETS_USED_UP)
        compose.onNodeWithTag(FollowUpTags.NOTICE).assertIsDisplayed()
        compose.onNodeWithText("There are no more resets for this one", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.DELAY).assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.SKIP).assertIsDisplayed()
    }

    // --- the reset --------------------------------------------------------------------------------------------------

    private val menu = FollowUpUiState.Recovery(
        "Write the report", "Open the document", listOf(RecoveryOption.RESET, RecoveryOption.SMALLER, RecoveryOption.RESCHEDULE), listOf(15, 30, 60), offerTomorrow = true,
    )

    @Test
    fun `the reset menu shows a restart and the times to try again, a way out, and no smaller step until something can make one`() {
        val taps = show(menu)
        compose.onNodeWithText("Let us make it easier to start").assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.RESTART).assertIsDisplayed()
        for (minutes in listOf(15, 30, 60)) compose.onNodeWithTag(FollowUpTags.rescheduleTo(minutes)).assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.RESCHEDULE_TOMORROW).assertIsDisplayed()
        compose.onNodeWithText("Make it smaller").assertDoesNotExist()

        compose.onNodeWithTag(FollowUpTags.RESTART).performClick()
        compose.onNodeWithTag(FollowUpTags.rescheduleTo(30)).performClick()
        compose.onNodeWithTag(FollowUpTags.RESCHEDULE_TOMORROW).performClick()
        compose.onNodeWithTag(FollowUpTags.NOT_NOW).performClick()
        assertEquals(listOf("restart", "reschedule 30", "reschedule 1440", "not now"), taps.log)
    }

    @Test
    fun `a menu that has no times to offer shows none`() {
        show(menu.copy(options = listOf(RecoveryOption.RESET), rescheduleMinutes = emptyList(), offerTomorrow = false))
        compose.onNodeWithTag(FollowUpTags.RESTART).assertIsDisplayed()
        compose.onNodeWithText("Or try again later").assertDoesNotExist()
        compose.onNodeWithTag(FollowUpTags.RESCHEDULE_TOMORROW).assertDoesNotExist()
    }

    // --- where it ends ---------------------------------------------------------------------------------------------

    @Test
    fun `a delay that was taken says it will ring again at the new time`() {
        show(FollowUpUiState.Finished("Write the report", FollowUpOutcome.DELAYED))
        compose.onNodeWithText("Delayed").assertIsDisplayed()
        compose.onNodeWithText("It will ring again at the new time.").assertIsDisplayed()
    }

    @Test
    fun `an occurrence that has not started or is ringing says there is nothing to answer yet, and closes`() {
        val taps = show(FollowUpUiState.Finished("Write the report", FollowUpOutcome.WAITING))
        compose.onNodeWithText("Nothing to answer yet").assertIsDisplayed()
        compose.onNodeWithText("Write the report").assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.CLOSE).performClick()
        assertEquals(1, taps.closed)
    }

    @Test
    fun `done says nothing was checked`() {
        show(FollowUpUiState.Finished("Write the report", FollowUpOutcome.DONE))
        compose.onNodeWithText("That is your own record. Nothing is checked.").assertIsDisplayed()
    }

    @Test
    fun `skipped is recorded as skipped and not as done`() {
        show(FollowUpUiState.Finished("Write the report", FollowUpOutcome.SKIPPED))
        compose.onNodeWithText("Recorded as skipped, not as done.").assertIsDisplayed()
    }

    @Test
    fun `an occurrence that is gone says so and closes`() {
        val taps = show(FollowUpUiState.NotFound)
        compose.onNodeWithText("This one is gone").assertIsDisplayed()
        compose.onNodeWithTag(FollowUpTags.CLOSE).performClick()
        assertEquals(1, taps.closed)
    }

    @Test
    fun `the back arrow closes`() {
        val taps = show(ask)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, taps.closed)
    }

    // --- Mission Lock ----------------------------------------------------------------------------------------------

    @Test
    fun `a fixed commitment's skip asks for confirmation, and confirming or backing out is reported`() {
        val taps = show(ask, friction = FrictionPrompt(LockPolicy.CONFIRM) { })
        compose.onNodeWithText("Skip a fixed commitment?").assertIsDisplayed()
        compose.onNodeWithText("Keep going").performClick()
        assertTrue(taps.log.contains("friction dismissed"))
    }

    @Test
    fun `friction that wants a reason lists the reasons`() {
        val taps = show(ask, friction = FrictionPrompt(LockPolicy.REASON) { })
        compose.onNodeWithText("Not relevant today", substring = true).performClick()
        assertTrue(taps.log.any { it.startsWith("friction ") && it.contains(FrictionEvidence.Reason(SkipReasonCode.NOT_RELEVANT_TODAY).toString()) })
    }

    @Test
    fun `the delay dialog is still open after the activity is recreated`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CommandTheme(darkTheme = true) {
                FollowUpScreen(state = ask, friction = null, notice = null, reasons = SkipReasonCode.values().toList(), actions = FollowUpActions(), onClose = {})
            }
        }
        compose.onNodeWithTag(FollowUpTags.DELAY).performClick()
        compose.onNodeWithText("Delay for how long?").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Delay for how long?").assertIsDisplayed()
    }
}
