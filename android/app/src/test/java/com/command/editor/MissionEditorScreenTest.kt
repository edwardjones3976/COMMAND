package com.command.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.command.data.DraftProblem
import com.command.domain.dials.Flexibility
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
    ) = EditorState(
        loading = loading, notFound = notFound, editing = editing, title = title, firstAction = firstAction, flexibility = flexibility,
        repeat = repeat, date = LocalDate.of(2026, 11, 2), time = LocalTime.of(10, 0), days = days, problems = problems,
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
}
