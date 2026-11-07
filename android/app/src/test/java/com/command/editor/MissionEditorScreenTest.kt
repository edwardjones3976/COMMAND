package com.command.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.command.data.DraftProblem
import com.command.domain.dials.Flexibility
import com.command.domain.model.RestKind
import com.command.domain.schedule.RestWindow
import com.command.ui.theme.CommandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MissionEditorScreenTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder {
        var back = 0
        val titles = ArrayList<String>()
        val actions = ArrayList<String>()
        val flexibility = ArrayList<Flexibility>()
        val repeats = ArrayList<RepeatKind>()
        val days = ArrayList<DayOfWeek>()
        val minutes = ArrayList<Int>()
        val categories = ArrayList<String>()
        val profiles = ArrayList<String?>()
        var saved = 0
        var tested = 0
        var deleted = 0

        fun actions() = EditorActions(
            onBack = { back++ },
            onTitle = { titles += it },
            onFirstAction = { actions += it },
            onFlexibility = { flexibility += it },
            onRepeat = { repeats += it },
            onDate = {},
            onTime = {},
            onDay = { days += it },
            onStarterMinutes = { minutes += it },
            onCategory = { categories += it },
            onProfile = { profiles += it },
            onSave = { saved++ },
            onTest = { tested++ },
            onDelete = { deleted++ },
        )
    }

    private fun state(
        title: String = "",
        firstAction: String = "",
        repeat: RepeatKind = RepeatKind.ONCE,
        flexibility: Flexibility = Flexibility.FLEXIBLE,
        days: Set<DayOfWeek> = emptySet(),
        problems: Set<DraftProblem> = emptySet(),
        editing: Boolean = false,
        loading: Boolean = false,
        notFound: Boolean = false,
        profileId: String? = null,
        category: String = "General",
        profiles: List<ProfileChoice> = listOf(ProfileChoice("builtin_nudge", "Nudge"), ProfileChoice("builtin_command", "Command")),
        imported: Boolean = false,
    ) = EditorState(
        loading = loading, notFound = notFound, editing = editing, title = title, firstAction = firstAction, flexibility = flexibility,
        repeat = repeat, date = LocalDate.of(2026, 11, 2), time = LocalTime.of(10, 0), days = days, problems = problems,
        profileId = profileId, category = category, profiles = profiles, imported = imported,
    )

    private fun show(s: EditorState, r: Recorder = Recorder()) {
        compose.setContent { CommandTheme(darkTheme = true) { MissionEditorScreen(s, r.actions()) } }
    }

    @Test
    fun `a new mission shows the fields, the time, and no delete`() {
        show(state())
        compose.onNodeWithText("New mission").assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.TITLE).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.ACTION).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.TIME).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.DELETE).assertDoesNotExist()
    }

    @Test
    fun `typing reports each field`() {
        val r = Recorder()
        show(state(), r)
        compose.onNodeWithTag(EditorTags.TITLE).performTextInput("Run")
        compose.onNodeWithTag(EditorTags.ACTION).performTextInput("Put on shoes")
        // The screen holds no text itself (the view model does), so the field may report its reset to empty afterwards.
        assertEquals("Run", r.titles.first())
        assertEquals("Put on shoes", r.actions.first())
    }

    @Test
    fun `the profile picker offers automatic and every profile, shows the one in force and reports a choice`() {
        val r = Recorder()
        show(state(profileId = "builtin_command"), r)
        compose.onNodeWithTag(EditorTags.profile("builtin_command")).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(EditorTags.PROFILE_AUTO).performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag(EditorTags.profile("builtin_nudge")).performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithTag(EditorTags.PROFILE_AUTO).performScrollTo().performClick()
        assertEquals(listOf("builtin_nudge", null), r.profiles)
    }

    @Test
    fun `a mission with no profile, or one naming a profile that is gone, shows automatic, which is what it will ring with`() {
        show(state(profileId = "builtin_ghost"))
        compose.onNodeWithTag(EditorTags.PROFILE_AUTO).performScrollTo().assertIsSelected()
    }

    @Test
    fun `the category field shows what is stored and reports what is typed, and the word kept for test alarms is explained`() {
        val r = Recorder()
        show(state(category = "Work", problems = setOf(DraftProblem.CATEGORY_RESERVED)), r)
        compose.onNodeWithTag(EditorTags.CATEGORY).performScrollTo().assertIsDisplayed().assertTextContains("Work")
        compose.onNodeWithText("That word is kept for test alarms. Choose another.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.CATEGORY).performTextInput("x")
        assertEquals("xWork", r.categories.first())
    }

    @Test
    fun `a calendar copy offers neither, because only the calendar can change it`() {
        show(state(imported = true))
        compose.onNodeWithTag(EditorTags.IMPORTED).assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.CATEGORY).assertDoesNotExist()
        compose.onNodeWithTag(EditorTags.PROFILE_AUTO).assertDoesNotExist()
    }

    @Test
    fun `problems are said next to the field they belong to`() {
        show(state(problems = setOf(DraftProblem.TITLE_EMPTY, DraftProblem.FIRST_ACTION_EMPTY, DraftProblem.IN_THE_PAST)))
        compose.onNodeWithText("Give the mission a name.").assertIsDisplayed()
        compose.onNodeWithText("Say what the first step is.").assertIsDisplayed()
        compose.onNodeWithText("That time has already passed.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the repeat choice is reported and the days only appear for a weekly mission`() {
        val r = Recorder()
        show(state(), r)
        compose.onNodeWithTag(EditorTags.day(DayOfWeek.MONDAY)).assertDoesNotExist()
        compose.onNodeWithTag(EditorTags.REPEAT_WEEKLY).performScrollTo().performClick()
        compose.onNodeWithTag(EditorTags.REPEAT_DAILY).performScrollTo().performClick()
        assertEquals(listOf(RepeatKind.WEEKLY, RepeatKind.DAILY), r.repeats)
    }

    @Test
    fun `a weekly mission offers the seven days and reports a tap`() {
        val r = Recorder()
        show(state(repeat = RepeatKind.WEEKLY, days = setOf(DayOfWeek.MONDAY)), r)
        compose.onNodeWithTag(EditorTags.day(DayOfWeek.MONDAY)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(EditorTags.day(DayOfWeek.FRIDAY)).performScrollTo().performClick()
        assertEquals(listOf(DayOfWeek.FRIDAY), r.days)
    }

    @Test
    fun `a weekly mission with no day says so`() {
        show(state(repeat = RepeatKind.WEEKLY, problems = setOf(DraftProblem.NO_DAYS_SELECTED)))
        compose.onNodeWithText("Pick at least one day.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `firmness is a choice between flexible and fixed with the consequence spelled out`() {
        val r = Recorder()
        show(state(flexibility = Flexibility.FLEXIBLE), r)
        compose.onNodeWithText("You can delay or skip it with one tap.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A real commitment", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.FIXED).performScrollTo().performClick()
        assertEquals(listOf(Flexibility.RIGID), r.flexibility)
    }

    @Test
    fun `the first block length is chosen from a short list`() {
        val r = Recorder()
        show(state(), r)
        compose.onNodeWithTag(EditorTags.starter(10)).performScrollTo().performClick()
        assertEquals(listOf(10), r.minutes)
    }

    @Test
    fun `save and test this alarm are one tap each`() {
        val r = Recorder()
        show(state(title = "Run", firstAction = "Put on shoes"), r)
        compose.onNodeWithTag(EditorTags.TEST).performScrollTo().performClick()
        compose.onNodeWithTag(EditorTags.SAVE).performScrollTo().performClick()
        assertEquals(1, r.tested)
        assertEquals(1, r.saved)
    }

    @Test
    fun `deleting asks first, and cancelling changes nothing`() {
        val r = Recorder()
        show(state(editing = true, title = "Run"), r)
        compose.onNodeWithText("Edit mission").assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.DELETE).performScrollTo().performClick()
        compose.onNodeWithText("Delete this mission?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, r.deleted)

        compose.onNodeWithTag(EditorTags.DELETE).performScrollTo().performClick()
        compose.onNodeWithTag(EditorTags.DELETE_CONFIRM).performClick()
        assertEquals(1, r.deleted)
    }

    @Test
    fun `a mission that no longer exists says so and offers the way back`() {
        val r = Recorder()
        show(state(notFound = true), r)
        compose.onNodeWithText("That mission no longer exists.").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, r.back)
    }

    // --- the rest window warning ------------------------------------------------------------------------------------

    private val nights = RestWindow(DayOfWeek.MONDAY, LocalTime.of(22, 0), LocalTime.of(6, 0), RestKind.SLEEP)

    @Test
    fun `an alarm inside a rest window is warned about, says which day, and says it still rings`() {
        // 2 November 2026 is a Monday.
        show(state().copy(time = LocalTime.of(23, 0), restWindows = listOf(nights)))
        compose.onNodeWithTag(EditorTags.REST_WARNING).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(EditorTags.REST_WARNING).assertTextContains("Mon", substring = true)
        compose.onNodeWithTag(EditorTags.REST_WARNING).assertTextContains("It still rings", substring = true)
    }

    @Test
    fun `an alarm outside every rest window has no warning`() {
        show(state().copy(time = LocalTime.of(12, 0), restWindows = listOf(nights)))
        compose.onNodeWithTag(EditorTags.REST_WARNING).assertDoesNotExist()
    }

    @Test
    fun `a calendar copy is warned about as well`() {
        val coming = listOf(LocalDate.of(2026, 11, 2).atTime(23, 0))
        show(state(imported = true).copy(restWindows = listOf(nights), comingAlarms = coming))
        compose.onNodeWithTag(EditorTags.REST_WARNING).assertIsDisplayed()
    }

    @Test
    fun `a calendar copy goes by its coming alarms and not by its one start`() {
        val tuesdays = RestWindow(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(11, 0), RestKind.LEISURE)
        val base = state(imported = true).copy(restWindows = listOf(tuesdays))
        val monday = LocalDate.of(2026, 11, 2).atTime(10, 0)
        assertEquals(setOf(DayOfWeek.TUESDAY), base.copy(comingAlarms = listOf(monday, monday.plusDays(1))).restWarningDays)
        assertEquals("it does not ring on the Tuesday any more", emptySet<DayOfWeek>(), base.copy(comingAlarms = listOf(monday)).restWarningDays)
    }

    @Test
    fun `saving is not held back by the warning`() {
        val r = Recorder()
        show(state(title = "t", firstAction = "a").copy(time = LocalTime.of(23, 0), restWindows = listOf(nights)), r)
        compose.onNodeWithTag(EditorTags.SAVE).performScrollTo().performClick()
        assertEquals(1, r.saved)
    }

    @Test
    fun `which days are warned about follows the repeat`() {
        val base = state().copy(time = LocalTime.of(23, 0), restWindows = listOf(nights))
        assertEquals("once, on its own date", setOf(DayOfWeek.MONDAY), base.restWarningDays)
        assertEquals("once, on another date", emptySet<DayOfWeek>(), base.copy(date = LocalDate.of(2026, 11, 3)).restWarningDays)
        assertEquals("daily is every day, and the window is on one", setOf(DayOfWeek.MONDAY), base.copy(repeat = RepeatKind.DAILY).restWarningDays)
        assertEquals(
            "weekly looks only at its chosen days",
            setOf(DayOfWeek.MONDAY),
            base.copy(repeat = RepeatKind.WEEKLY, days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)).restWarningDays,
        )
        assertEquals("not the date, when it repeats", emptySet<DayOfWeek>(), base.copy(repeat = RepeatKind.WEEKLY, days = setOf(DayOfWeek.WEDNESDAY)).restWarningDays)
        assertEquals(
            "early Tuesday belongs to Monday night, and it is Tuesday's alarm that falls inside",
            setOf(DayOfWeek.TUESDAY),
            base.copy(time = LocalTime.of(5, 0), repeat = RepeatKind.DAILY).restWarningDays,
        )
    }
}
