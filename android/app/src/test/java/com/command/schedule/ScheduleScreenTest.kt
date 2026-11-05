package com.command.schedule

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.data.db.ScheduleRow
import com.command.domain.dials.Flexibility
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
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
@Config(sdk = [36], qualifiers = "w411dp-h2400dp-xhdpi")
class ScheduleScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 2)

    private fun at(day: Int, hour: Int): Long = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun row(
        id: String,
        title: String,
        state: OccurrenceState,
        atMs: Long,
        flexibility: Flexibility = Flexibility.FLEXIBLE,
        armMode: ArmMode = ArmMode.EXACT,
        skip: SkipReason? = null,
        miss: MissReason? = null,
    ) = ScheduleRow(id, "mission-$id", title, flexibility, atMs, "UTC", state, armMode, skip, miss)

    private class Recorder {
        var added = 0
        val opened = ArrayList<String>()
        var notificationSettings = 0
        var exactAlarms = 0
    }

    private fun show(state: ScheduleUiState, r: Recorder = Recorder(), notificationsOff: Boolean = false) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                ScheduleScreen(
                    state = state,
                    notificationsOff = notificationsOff,
                    onAdd = { r.added++ },
                    onOpen = { r.opened += it },
                    onOpenNotificationSettings = { r.notificationSettings++ },
                    onAllowExactAlarms = { r.exactAlarms++ },
                    zone = ZoneOffset.UTC,
                    today = today,
                )
            }
        }
    }

    private fun ready(upcoming: List<ScheduleRow> = emptyList(), recent: List<ScheduleRow> = emptyList(), exact: Boolean = true) =
        ScheduleUiState.Ready(upcoming, recent, exact)

    @Test
    fun `with nothing scheduled it invites the first mission and the button adds one`() {
        val r = Recorder()
        show(ready(), r)
        compose.onNodeWithTag(ScheduleTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Nothing scheduled yet").assertIsDisplayed()
        compose.onNodeWithTag(ScheduleTags.ADD).performClick()
        assertEquals(1, r.added)
    }

    @Test
    fun `an upcoming mission shows its title, today with the time, its state and how firm it is`() {
        show(ready(upcoming = listOf(row("a", "Standup", OccurrenceState.ARMED, at(2, 11), Flexibility.RIGID))))
        compose.onNodeWithText("Standup").assertIsDisplayed()
        compose.onNodeWithText("Today", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Set").assertIsDisplayed()
        compose.onNodeWithText("Fixed").assertIsDisplayed()
    }

    @Test
    fun `tomorrow and later days are named plainly`() {
        show(ready(upcoming = listOf(row("a", "Gym", OccurrenceState.ARMED, at(3, 7)), row("b", "Review", OccurrenceState.ARMED, at(5, 14)))))
        compose.onNodeWithText("Tomorrow", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nov", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping a mission opens it`() {
        val r = Recorder()
        show(ready(upcoming = listOf(row("a", "Standup", OccurrenceState.ARMED, at(2, 11)))), r)
        compose.onNodeWithTag(ScheduleTags.row("a")).performClick()
        assertEquals(listOf("mission-a"), r.opened)
    }

    @Test
    fun `an alarm that can only be set inexactly says it may be late`() {
        show(ready(upcoming = listOf(row("a", "Standup", OccurrenceState.ARMED, at(2, 11), armMode = ArmMode.INEXACT))))
        compose.onNodeWithText("Set, may be late").assertIsDisplayed()
    }

    @Test
    fun `what happened is told in plain words and silenced or missed is never called started`() {
        show(
            ready(
                recent = listOf(
                    row("a", "Asleep", OccurrenceState.MISSED, at(2, 8), miss = MissReason.DOWNTIME),
                    row("b", "Quiet", OccurrenceState.SAFETY_SILENCED, at(2, 7)),
                    row("c", "Cancelled", OccurrenceState.SKIPPED, at(2, 6), skip = SkipReason.CALENDAR_CANCELLED),
                    row("d", "Dropped", OccurrenceState.SKIPPED, at(2, 5), skip = SkipReason.USER),
                    row("e", "Began", OccurrenceState.STARTED_SELF_REPORTED, at(2, 4)),
                    row("f", "Finished", OccurrenceState.COMPLETED_SELF_REPORTED, at(2, 3)),
                ),
            ),
        )
        compose.onNodeWithText("Missed, the phone was off").assertIsDisplayed()
        compose.onNodeWithText("Silenced").assertIsDisplayed()
        compose.onNodeWithText("Cancelled in the calendar").assertIsDisplayed()
        compose.onNodeWithText("Skipped").assertIsDisplayed()
        compose.onNodeWithText("Started").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText("Recent").assertIsDisplayed()
    }

    @Test
    fun `the notification warning appears when notifications are off and opens settings`() {
        val r = Recorder()
        show(ready(), r, notificationsOff = true)
        compose.onNodeWithTag(ScheduleTags.NOTIFICATIONS_BANNER).assertIsDisplayed()
        compose.onNodeWithText("Open settings").performClick()
        assertEquals(1, r.notificationSettings)
    }

    @Test
    fun `the exact alarm warning appears when exact alarms are not allowed and asks for them`() {
        val r = Recorder()
        show(ready(exact = false), r)
        compose.onNodeWithTag(ScheduleTags.EXACT_BANNER).assertIsDisplayed()
        compose.onNodeWithText("Allow exact alarms").performClick()
        assertEquals(1, r.exactAlarms)
    }

    @Test
    fun `with everything fine there are no warnings`() {
        show(ready())
        compose.onNodeWithTag(ScheduleTags.NOTIFICATIONS_BANNER).assertDoesNotExist()
        compose.onNodeWithTag(ScheduleTags.EXACT_BANNER).assertDoesNotExist()
    }
}

