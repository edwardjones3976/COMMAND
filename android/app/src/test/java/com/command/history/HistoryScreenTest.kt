package com.command.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.command.domain.history.HistoryReason
import com.command.domain.history.WindowSummary
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// A tall screen, so that nothing needs scrolling to be found.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class HistoryScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 2)

    /** The time as the screen writes it (the platform's own format, which differs between Java versions in its spaces). */
    private fun time(hourOfDay: Int): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(today.atTime(hourOfDay, 0).atZone(ZoneOffset.UTC))

    private fun entry(id: String, title: String, hourOfDay: Int, reason: HistoryReason, daysAgo: Long = 0) =
        HistoryEntry(id, title, today.minusDays(daysAgo).atTime(hourOfDay, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), ZoneOffset.UTC, reason)

    private fun ready(
        days: List<HistoryDay> = emptyList(),
        week: WindowSummary = WindowSummary(7, 0, 0),
        month: WindowSummary = WindowSummary(30, 0, 0),
    ) = HistoryUiState.Ready(week, month, days, today)

    private fun show(state: HistoryUiState, opened: MutableList<String> = ArrayList()): MutableList<String> {
        compose.setContent {
            CommandTheme(darkTheme = true) {
                HistoryScreen(state = state, onBack = {}, onOpenOccurrence = { opened += it })
            }
        }
        return opened
    }

    @Test
    fun `with nothing recorded it says so and nothing else is claimed`() {
        show(ready())
        compose.onNodeWithTag(HistoryTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Nothing has come due yet").assertIsDisplayed()
        compose.onAllNodesWithText("Nothing was asked of you in this time.").assertCountEquals(2) // the week and the month
    }

    @Test
    fun `the summaries say on how many of the days that asked something of the person they started`() {
        show(ready(week = WindowSummary(7, 4, 5), month = WindowSummary(30, 12, 20)))
        compose.onNodeWithText("You started on 4 of the 5 days that asked something of you.").assertIsDisplayed()
        compose.onNodeWithText("You started on 12 of the 20 days that asked something of you.").assertIsDisplayed()
    }

    @Test
    fun `one day is said in the singular`() {
        show(ready(week = WindowSummary(7, 1, 1)))
        compose.onNodeWithText("You started on 1 of the 1 day that asked something of you.").assertIsDisplayed()
    }

    @Test
    fun `nothing here is a streak, a score or a mark, and the note says what is left out`() {
        show(ready(week = WindowSummary(7, 0, 3)))
        compose.onNodeWithText("Nothing here adds up to a streak.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Days when your phone was off, COMMAND was paused or your calendar cancelled something are left out.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `days are headed Today, Yesterday or by their date, and each entry says what happened`() {
        show(
            ready(
                days = listOf(
                    HistoryDay(today, listOf(entry("a", "Morning pages", 8, HistoryReason.STARTED), entry("b", "Run", 6, HistoryReason.TIMED_OUT))),
                    HistoryDay(today.minusDays(1), listOf(entry("c", "Taxes", 20, HistoryReason.SKIPPED_BY_USER, daysAgo = 1))),
                    HistoryDay(today.minusDays(3), listOf(entry("d", "Call", 9, HistoryReason.PHONE_WAS_OFF, daysAgo = 3))),
                ),
            ),
        )
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithTag(HistoryTags.day(today.minusDays(3))).assertIsDisplayed()
        compose.onNodeWithContentDescription("Morning pages, ${time(8)}, Started").assertIsDisplayed()
        compose.onNodeWithContentDescription("Run, ${time(6)}, Timed out").assertIsDisplayed()
        compose.onNodeWithContentDescription("Taxes, ${time(20)}, Skipped").assertIsDisplayed()
        compose.onNodeWithContentDescription("Call, ${time(9)}, The phone was off").assertIsDisplayed()
    }

    @Test
    fun `an entry opens its details`() {
        val opened = show(ready(days = listOf(HistoryDay(today, listOf(entry("a", "Morning pages", 8, HistoryReason.DONE))))))
        compose.onNodeWithTag(HistoryTags.entry("a")).performClick()
        assertEquals(listOf("a"), opened)
    }

    @Test
    fun `every reason has its own words`() {
        for (reason in HistoryReason.values()) {
            org.junit.Assert.assertTrue("a label for $reason", reasonLabelRes(reason) != 0)
        }
        assertEquals("no two reasons share a label", HistoryReason.values().size, HistoryReason.values().map { reasonLabelRes(it) }.toSet().size)
    }
}
