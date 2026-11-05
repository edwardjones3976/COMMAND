package com.command.schedule

import androidx.compose.ui.test.assert
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
        val answered = ArrayList<String>()
        var reliability = 0
        var consents = 0
        var history = 0
        var settings = 0
        var endPause = 0
    }

    private fun show(state: ScheduleUiState, r: Recorder = Recorder()) {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                ScheduleScreen(
                    state = state,
                    onAdd = { r.added++ },
                    onOpen = { r.opened += it },
                    onOpenOccurrence = { r.answered += it },
                    onOpenReliability = { r.reliability++ },
                    onOpenConsents = { r.consents++ },
                    onOpenHistory = { r.history++ },
                    onOpenSettings = { r.settings++ },
                    onEndPause = { r.endPause++ },
                    zone = ZoneOffset.UTC,
                    today = today,
                )
            }
        }
    }

    private fun ready(
        upcoming: List<ScheduleRow> = emptyList(),
        recent: List<ScheduleRow> = emptyList(),
        problems: Int = 0,
        pausedUntil: Long? = null,
    ) = ScheduleUiState.Ready(upcoming, recent, problems, pausedUntil)

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
    fun `when something can stop an alarm one banner says how many and opens the reliability page`() {
        val r = Recorder()
        show(ready(problems = 2), r)
        compose.onNodeWithTag(ScheduleTags.ATTENTION_BANNER).assertIsDisplayed()
        compose.onNodeWithText("2 things can stop an alarm").assertIsDisplayed()
        compose.onNodeWithTag(ScheduleTags.ATTENTION_ACTION).performClick()
        assertEquals(1, r.reliability)
    }

    @Test
    fun `one problem says thing, not things`() {
        show(ready(problems = 1))
        compose.onNodeWithText("1 thing can stop an alarm").assertIsDisplayed()
    }

    @Test
    fun `with everything fine there is no banner`() {
        show(ready())
        compose.onNodeWithTag(ScheduleTags.ATTENTION_BANNER).assertDoesNotExist()
    }

    @Test
    fun `the schedule links to the reliability page and to the permissions and privacy page`() {
        val r = Recorder()
        show(ready(), r)
        compose.onNodeWithTag(ScheduleTags.LINK_RELIABILITY).performClick()
        compose.onNodeWithTag(ScheduleTags.LINK_CONSENTS).performClick()
        assertEquals(1, r.reliability)
        assertEquals(1, r.consents)
    }

    @Test
    fun `the schedule links to history and to settings`() {
        val r = Recorder()
        show(ready(), r)
        compose.onNodeWithTag(ScheduleTags.LINK_HISTORY).performClick()
        compose.onNodeWithTag(ScheduleTags.LINK_SETTINGS).performClick()
        assertEquals(1, r.history)
        assertEquals(1, r.settings)
    }

    @Test
    fun `an occurrence that is waiting for an answer opens its questions, and any other opens the mission`() {
        val r = Recorder()
        show(
            ready(
                upcoming = listOf(row("a", "Writing", OccurrenceState.GUIDED_START, at(2, 9))),
                recent = listOf(
                    row("b", "Reading", OccurrenceState.WORKING_SELF_REPORTED, at(2, 8)),
                    row("c", "Unanswered", OccurrenceState.UNCONFIRMED, at(2, 7)),
                    row("d", "Reset", OccurrenceState.RECOVERY_OFFERED, at(2, 6)),
                    row("e", "Missed one", OccurrenceState.MISSED, at(2, 5)),
                    row("f", "Finished", OccurrenceState.COMPLETED_SELF_REPORTED, at(2, 4)),
                ),
            ),
            r,
        )
        for (id in listOf("a", "b", "c", "d")) compose.onNodeWithTag(ScheduleTags.row(id)).performClick()
        assertEquals(listOf("a", "b", "c", "d"), r.answered)
        assertEquals(emptyList<String>(), r.opened)

        for (id in listOf("e", "f")) compose.onNodeWithTag(ScheduleTags.row(id)).performClick()
        assertEquals("a missed or finished one opens its mission, as before", listOf("mission-e", "mission-f"), r.opened)
        assertEquals(listOf("a", "b", "c", "d"), r.answered)
    }

    @Test
    fun `a pause says until when and can be ended from the schedule, and what it holds back is not called set`() {
        val r = Recorder()
        show(
            ready(
                upcoming = listOf(row("in", "Standup", OccurrenceState.SCHEDULED, at(2, 11)), row("after", "Gym", OccurrenceState.ARMED, at(3, 7))),
                pausedUntil = at(2, 15),
            ),
            r,
        )
        compose.onNodeWithTag(ScheduleTags.PAUSED_BANNER).assertIsDisplayed()
        val three = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
            .format(java.time.Instant.ofEpochMilli(at(2, 15)).atZone(ZoneOffset.UTC))
        compose.onNodeWithText("Paused until $three").assertIsDisplayed()
        compose.onNodeWithText("On hold, paused").assertIsDisplayed()
        compose.onNodeWithText("Set").assertIsDisplayed() // the one after the pause is untouched

        compose.onNodeWithTag(ScheduleTags.PAUSED_ACTION).performClick()
        assertEquals(1, r.endPause)
    }

    @Test
    fun `without a pause there is no banner, and an occurrence a pause swallowed is told as paused, not missed`() {
        show(ready(recent = listOf(row("p", "Standup", OccurrenceState.MISSED, at(2, 8), miss = MissReason.PAUSED))))
        compose.onNodeWithTag(ScheduleTags.PAUSED_BANNER).assertDoesNotExist()
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithText("Missed").assertDoesNotExist()
    }

    @Test
    fun `an overlap is shown and is part of what the row announces`() {
        val r = row("a", "Standup", OccurrenceState.ARMED, atMs = at(2, 15))
        show(ScheduleUiState.Ready(listOf(r), emptyList(), 0, conflicted = setOf("a"), today = today))
        compose.onNodeWithTag(ScheduleTags.row("a"), useUnmergedTree = false).assert(androidx.compose.ui.test.hasContentDescription("Overlaps with something else", substring = true))
    }
}
