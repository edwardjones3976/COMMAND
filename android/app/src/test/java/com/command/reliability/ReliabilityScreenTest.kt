package com.command.reliability

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.domain.reliability.AlarmOutcome
import com.command.domain.reliability.AlarmOutcomeKind
import com.command.domain.reliability.Fix
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.reliability.Reliability
import com.command.domain.reliability.ReliabilityReport
import com.command.testing.android.FakeReliabilityMonitor
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

// A tall screen, so that a lazy list composes every row and each can be asserted without scrolling.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class ReliabilityScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 2)

    private fun report(vararg changed: Pair<Precondition, String>) =
        Reliability.assess(PreconditionSnapshot(FakeReliabilityMonitor.healthy().values + mapOf(*changed)))

    private fun ready(
        report: ReliabilityReport = report(),
        recent: List<AlarmOutcome> = emptyList(),
        canAsk: Boolean = true,
        maker: String? = null,
    ) = ReliabilityUiState.Ready(report, recent, canAsk, maker)

    private class Recorder {
        val fixes = ArrayList<Fix>()
        var back = 0
        var maker = 0
    }

    private fun show(state: ReliabilityUiState, r: Recorder = Recorder()) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                ReliabilityScreen(
                    state = state,
                    onBack = { r.back++ },
                    onFix = { r.fixes += it },
                    onOpenMaker = { r.maker++ },
                    zone = ZoneOffset.UTC,
                    today = today,
                )
            }
        }
    }

    private fun at(day: Int, hour: Int): Long = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun outcome(id: String, title: String, kind: AlarmOutcomeKind, late: Long? = null, test: Boolean = false) =
        AlarmOutcome(id, title, at(2, 8), kind, late, test)

    @Test
    fun `while the phone is being read there is nothing to report yet`() {
        show(ReliabilityUiState.Loading)
        compose.onNodeWithTag(ReliabilityTags.SUMMARY).assertDoesNotExist()
    }

    @Test
    fun `with everything allowed it says so and lists what works, with nothing to fix`() {
        show(ready())
        compose.onNodeWithText("Your alarms are set up to ring").assertIsDisplayed()
        compose.onNodeWithTag(ReliabilityTags.OK_LIST).assertIsDisplayed()
        for (p in Precondition.values()) compose.onNodeWithTag(ReliabilityTags.item(p)).assertDoesNotExist()
        compose.onNodeWithText("Needs attention").assertDoesNotExist()
    }

    @Test
    fun `what stops an alarm is counted, listed under what it costs, and each has a button that fixes it`() {
        val r = Recorder()
        show(ready(report(Precondition.NOTIFICATIONS to "denied", Precondition.EXACT_ALARMS to "denied")), r)

        compose.onNodeWithText("2 things can stop an alarm").assertIsDisplayed()
        compose.onNodeWithText("Needs attention").assertIsDisplayed()
        compose.onNodeWithTag(ReliabilityTags.item(Precondition.NOTIFICATIONS)).assertIsDisplayed()
        compose.onNodeWithText("Android may deliver an alarm minutes late", substring = true).assertIsDisplayed()

        compose.onNodeWithTag(ReliabilityTags.action(Precondition.EXACT_ALARMS)).performClick()
        compose.onNodeWithTag(ReliabilityTags.action(Precondition.NOTIFICATIONS)).performClick()
        assertEquals(listOf(Fix.OPEN_EXACT_ALARM_SETTINGS, Fix.ASK_NOTIFICATIONS), r.fixes)
    }

    @Test
    fun `one problem says thing, not things`() {
        show(ready(report(Precondition.ALARM_VOLUME to "muted")))
        compose.onNodeWithText("1 thing can stop an alarm").assertIsDisplayed()
        compose.onNodeWithText("Open sound settings").assertIsDisplayed()
    }

    @Test
    fun `until the request has been shown the notification button asks, afterwards it opens settings`() {
        show(ready(report(Precondition.NOTIFICATIONS to "denied"), canAsk = true))
        compose.onNodeWithText("Allow notifications").assertIsDisplayed()
    }

    @Test
    fun `after the request was shown the notification button opens the settings page instead`() {
        show(ready(report(Precondition.NOTIFICATIONS to "denied"), canAsk = false))
        compose.onNodeWithText("Open notification settings").assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").assertDoesNotExist()
    }

    @Test
    fun `a warning and advice are listed apart, and advice alone does not turn the summary into a warning`() {
        show(ready(report(Precondition.BACKGROUND_RESTRICTED to "yes", Precondition.BATTERY_OPTIMIZATION to "optimized")))
        compose.onNodeWithText("Your alarms should ring, with some risk").assertIsDisplayed()
        compose.onNodeWithText("Worth knowing").assertIsDisplayed()
        compose.onNodeWithText("For your information").assertIsDisplayed()
        compose.onNodeWithTag(ReliabilityTags.item(Precondition.BACKGROUND_RESTRICTED)).assertIsDisplayed()
        compose.onNodeWithTag(ReliabilityTags.item(Precondition.BATTERY_OPTIMIZATION)).assertIsDisplayed()
    }

    @Test
    fun `advice alone keeps the healthy summary`() {
        show(ready(report(Precondition.BATTERY_OPTIMIZATION to "optimized")))
        compose.onNodeWithText("Your alarms are set up to ring").assertIsDisplayed()
        compose.onNodeWithText("For your information").assertIsDisplayed()
    }

    @Test
    fun `a rarely used app is told why that matters and is not offered a button that would not help`() {
        show(ready(report(Precondition.STANDBY_BUCKET to "RARE")))
        compose.onNodeWithTag(ReliabilityTags.item(Precondition.STANDBY_BUCKET)).assertIsDisplayed()
        compose.onNodeWithText("Rarely used").assertIsDisplayed()
        compose.onNodeWithTag(ReliabilityTags.action(Precondition.STANDBY_BUCKET)).assertDoesNotExist()
    }

    @Test
    fun `the alarm channel says how low it was set`() {
        show(ready(report(Precondition.ALARM_CHANNEL to "LOW")))
        compose.onNodeWithText("Set too low (low)").assertIsDisplayed()
    }

    @Test
    fun `the question channel switched off is a warning that says what it costs, with a button to its own page`() {
        show(ready(report(Precondition.CHECK_IN_CHANNEL to "NONE")))
        compose.onNodeWithTag(ReliabilityTags.item(Precondition.CHECK_IN_CHANNEL)).assertIsDisplayed()
        compose.onNodeWithText("Follow-up question channel").assertIsDisplayed()
        compose.onNodeWithText("Set too low (off)").assertIsDisplayed()
        compose.onNodeWithText("At this level the questions about how it is going come without a sound, or not at all.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ReliabilityTags.action(Precondition.CHECK_IN_CHANNEL)).assertIsDisplayed()
    }

    @Test
    fun `recent alarms say how each one went, and a test alarm is marked`() {
        show(
            ready(
                recent = listOf(
                    outcome("a", "Standup", AlarmOutcomeKind.ON_TIME),
                    outcome("b", "Gym", AlarmOutcomeKind.LATE, late = 42),
                    outcome("c", "Review", AlarmOutcomeKind.LATE, late = 185),
                    outcome("d", "Asleep", AlarmOutcomeKind.MISSED_WHILE_OFF, late = 10_800),
                    outcome("e", "Dropped", AlarmOutcomeKind.MISSED_WHILE_OFF, late = null),
                    outcome("f", "Try it", AlarmOutcomeKind.ON_TIME, test = true),
                ),
            ),
        )
        compose.onNodeWithText("Recent alarms").assertIsDisplayed()
        compose.onAllNodesWithText("Rang on time", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Rang 42 s late", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Rang 3 min late", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Missed: the phone was off or Android dropped it (found 3 h after its time)", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Missed: the phone was off or Android dropped it", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Test alarm: Try it", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Standup, Today", substring = true).assertExists()
    }

    @Test
    fun `with no alarm yet it says how to get one`() {
        show(ready())
        compose.onNodeWithTag(ReliabilityTags.RECENT_EMPTY).assertIsDisplayed()
    }

    @Test
    fun `no phone maker shortcut is shown when there is none`() {
        show(ready(maker = null))
        compose.onNodeWithTag(ReliabilityTags.MAKER).assertDoesNotExist()
    }

    @Test
    fun `a phone maker with an autostart page offers it`() {
        val r = Recorder()
        show(ready(maker = "Xiaomi"), r)
        compose.onNodeWithText("Xiaomi").assertIsDisplayed()
        compose.onNodeWithTag(ReliabilityTags.MAKER_ACTION).performClick()
        assertEquals(1, r.maker)
    }

    @Test
    fun `the back arrow goes back`() {
        val r = Recorder()
        show(ready(), r)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, r.back)
    }

    @Test
    fun `Do Not Disturb that blocks alarms is a problem that says so and opens the sound settings`() {
        val r = Recorder()
        show(ready(report(Precondition.DO_NOT_DISTURB to "blocks_alarms")), r)
        compose.onNodeWithText("Blocks alarms").assertIsDisplayed()
        compose.onNodeWithText("Do Not Disturb is set so that an alarm would make no sound", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ReliabilityTags.action(Precondition.DO_NOT_DISTURB)).performClick()
        assertEquals(listOf(Fix.OPEN_SOUND_SETTINGS), r.fixes)
    }
}
