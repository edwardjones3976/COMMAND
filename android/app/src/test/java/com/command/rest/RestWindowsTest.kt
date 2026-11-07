package com.command.rest

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.command.data.RestWindowRepository
import com.command.data.db.CommandDb
import com.command.domain.model.RestKind
import com.command.testing.android.awaitUntil
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalTime
import javax.inject.Inject

/** The editor of `rest_windows`, through its view model and the real database. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class RestWindowsTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Inject lateinit var windows: RestWindowRepository
    @Inject lateinit var db: CommandDb

    @Before fun setUp() = hilt.inject()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.waitUntil(timeoutMillis = 45_000) { exists(tag) }
    private fun waitGone(tag: String) = compose.waitUntil(timeoutMillis = 45_000) { !exists(tag) }
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performClick()

    private fun rows() = runBlocking { db.restWindows().all() }.sortedBy { it.weekday }

    private fun stored(days: Set<DayOfWeek> = setOf(MONDAY), start: LocalTime = LocalTime.of(22, 0), end: LocalTime = LocalTime.of(6, 0), kind: RestKind = RestKind.SLEEP) {
        runBlocking { windows.save(emptyList(), days, start, end, kind) }
    }

    private fun firstId() = rows().first().id

    private fun RestWindowsViewModel.draft() = (state.value as RestWindowsUiState.Ready).draft!!

    private fun show(): RestWindowsViewModel {
        val vm = RestWindowsViewModel(windows)
        compose.setContent { CommandTheme { RestWindowsRoute(onBack = {}, viewModel = vm) } }
        waitFor(RestWindowsTags.LIST)
        return vm
    }

    private fun openForm() {
        tap(RestWindowsTags.ADD)
        waitFor(RestWindowsTags.FORM)
    }

    @Test
    fun `with none saved it says so, and the intro says an alarm still rings`() {
        show()
        compose.onNodeWithTag(RestWindowsTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("An alarm you set still rings", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a window is added from the form, with its days and its kind`() {
        show()
        openForm()
        tap(RestWindowsTags.day(MONDAY))
        tap(RestWindowsTags.day(TUESDAY))
        tap(RestWindowsTags.kind(RestKind.DRIVING))
        compose.onNodeWithTag(RestWindowsTags.kind(RestKind.DRIVING)).assertIsSelected()
        tap(RestWindowsTags.SAVE)

        waitGone(RestWindowsTags.FORM)
        assertEquals(listOf(1, 2), rows().map { it.weekday })
        assertEquals(RestKind.DRIVING, rows().first().kind)
        val line = RestWindowsTags.line(rows().minOf { it.id })
        waitFor(line)
        compose.onNodeWithTag(line).assertTextContains("Mon, Tue", substring = true)
        compose.onNodeWithTag(line).assertTextContains("Driving", substring = true)
    }

    @Test
    fun `each time opens its own question and the answer is kept for that time`() {
        val vm = show()
        openForm()
        val start = vm.draft().start
        val end = vm.draft().end
        tap(RestWindowsTags.START)
        waitFor(RestWindowsTags.TIME_OK)
        tap(RestWindowsTags.TIME_OK)
        waitGone(RestWindowsTags.TIME_OK)
        assertEquals(start, vm.draft().start)
        assertEquals(end, vm.draft().end)

        tap(RestWindowsTags.END)
        waitFor(RestWindowsTags.TIME_OK)
        tap(RestWindowsTags.TIME_OK)
        waitGone(RestWindowsTags.TIME_OK)
        assertEquals(end, vm.draft().end)
    }

    @Test
    fun `no day and equal times are said on the form, nothing is written, and each goes when it is answered`() {
        val vm = show()
        openForm()
        vm.setStart(LocalTime.of(7, 0))
        vm.setEnd(LocalTime.of(7, 0))
        compose.waitForIdle()
        tap(RestWindowsTags.SAVE)

        waitFor(RestWindowsTags.PROBLEM_DAYS)
        waitFor(RestWindowsTags.PROBLEM_TIMES)
        compose.onNodeWithTag(RestWindowsTags.FORM).assertIsDisplayed()
        assertTrue(rows().isEmpty())

        tap(RestWindowsTags.day(MONDAY))
        waitGone(RestWindowsTags.PROBLEM_DAYS)
        assertTrue(exists(RestWindowsTags.PROBLEM_TIMES))
        vm.setEnd(LocalTime.of(8, 0))
        waitGone(RestWindowsTags.PROBLEM_TIMES)
    }

    @Test
    fun `an end at or before the start is said to be the next day, on the form and on the list`() {
        val vm = show()
        openForm()
        vm.setStart(LocalTime.of(9, 0))
        vm.setEnd(LocalTime.of(10, 0))
        waitGone(RestWindowsTags.NEXT_DAY)
        vm.setEnd(LocalTime.of(8, 0))
        waitFor(RestWindowsTags.NEXT_DAY)
        tap(RestWindowsTags.day(MONDAY))
        tap(RestWindowsTags.SAVE)
        waitGone(RestWindowsTags.FORM)
        val line = RestWindowsTags.line(firstId())
        waitFor(line)
        compose.onNodeWithTag(line).assertTextContains("the next day", substring = true)
    }

    @Test
    fun `changing a window opens the form filled in, and saving replaces it`() {
        stored(setOf(MONDAY, TUESDAY))
        val vm = show()
        waitFor(RestWindowsTags.edit(firstId()))
        tap(RestWindowsTags.edit(firstId()))
        waitFor(RestWindowsTags.FORM)
        val draft = vm.draft()
        assertEquals(setOf(MONDAY, TUESDAY), draft.days)
        assertEquals(LocalTime.of(22, 0), draft.start)
        assertEquals(LocalTime.of(6, 0), draft.end)
        assertEquals(RestKind.SLEEP, draft.kind)

        tap(RestWindowsTags.day(TUESDAY))
        tap(RestWindowsTags.kind(RestKind.LEISURE))
        tap(RestWindowsTags.SAVE)

        waitGone(RestWindowsTags.FORM)
        val row = rows().single()
        assertEquals(1, row.weekday)
        assertEquals(RestKind.LEISURE, row.kind)
    }

    @Test
    fun `a window is deleted only after the question, whose first answer keeps it`() {
        stored(setOf(MONDAY, TUESDAY))
        show()
        val id = rows().minOf { it.id }
        waitFor(RestWindowsTags.delete(id))
        tap(RestWindowsTags.delete(id))
        waitFor(RestWindowsTags.DELETE_DIALOG)
        tap(RestWindowsTags.DELETE_KEEP)
        waitGone(RestWindowsTags.DELETE_DIALOG)
        assertEquals(2, rows().size)

        tap(RestWindowsTags.delete(id))
        waitFor(RestWindowsTags.DELETE_DIALOG)
        tap(RestWindowsTags.DELETE_CONFIRM)
        waitFor(RestWindowsTags.EMPTY)
        assertTrue(rows().isEmpty())
    }

    private fun ViewModelStore.holding(make: () -> RestWindowsViewModel): RestWindowsViewModel =
        ViewModelProvider(this, viewModelFactory { initializer { make() } })[RestWindowsViewModel::class.java]

    @Test
    fun `leaving the screen does not lose a window being saved, or one being deleted`() {
        stored(setOf(TUESDAY))
        val store = ViewModelStore()
        val vm = store.holding { RestWindowsViewModel(windows) }
        val gate = CompletableDeferred<Unit>()
        vm.beforeWrite = { gate.await() }
        vm.startNew()
        vm.toggleDay(MONDAY)
        vm.save()
        vm.delete(rows().map { it.id })
        store.clear()
        gate.complete(Unit)

        awaitUntil(message = "the save and the delete to be written") { rows().map { it.weekday } == listOf(1) }
    }

    @Test
    fun `a save that fails says so and the form can be used again`() {
        val vm = show()
        vm.beforeWrite = { throw IllegalStateException("disk") }
        openForm()
        tap(RestWindowsTags.day(MONDAY))
        tap(RestWindowsTags.SAVE)
        waitFor(RestWindowsTags.PROBLEM_FAILED)

        vm.beforeWrite = {}
        tap(RestWindowsTags.SAVE)
        waitGone(RestWindowsTags.FORM)
        assertEquals(listOf(1), rows().map { it.weekday })
    }

    @Test
    fun `while a save is on its way the form does not change, and a second tap writes nothing more`() {
        val vm = show()
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        vm.beforeWrite = { writes++; gate.await() }
        openForm()
        tap(RestWindowsTags.day(MONDAY))
        vm.save()
        vm.save()
        vm.toggleDay(TUESDAY)
        gate.complete(Unit)

        waitGone(RestWindowsTags.FORM)
        assertEquals(1, writes)
        assertEquals(listOf(1), rows().map { it.weekday })
    }

    @Test
    fun `the form stays until its save is done, so the old line never comes back to be changed or deleted`() {
        stored(setOf(MONDAY))
        val vm = show()
        val old = firstId()
        val gate = CompletableDeferred<Unit>()
        vm.beforeWrite = { gate.await() }
        waitFor(RestWindowsTags.edit(old))
        tap(RestWindowsTags.edit(old))
        waitFor(RestWindowsTags.FORM)
        tap(RestWindowsTags.day(TUESDAY))
        vm.save()
        vm.cancel()
        compose.waitForIdle()
        assertTrue("still on the form", exists(RestWindowsTags.FORM))
        assertFalse(exists(RestWindowsTags.delete(old)))

        gate.complete(Unit)
        waitGone(RestWindowsTags.FORM)
        assertEquals(listOf(1, 2), rows().map { it.weekday })
    }

    @Test
    fun `leaving the form keeps what is stored`() {
        stored()
        val vm = show()
        waitFor(RestWindowsTags.edit(firstId()))
        tap(RestWindowsTags.edit(firstId()))
        waitFor(RestWindowsTags.FORM)
        tap(RestWindowsTags.day(TUESDAY))
        vm.cancel()
        waitGone(RestWindowsTags.FORM)
        assertEquals(listOf(1), rows().map { it.weekday })
        assertFalse(exists(RestWindowsTags.FORM))
    }

    @Test
    fun `each change and delete is named for its window, to a screen reader`() {
        stored(setOf(MONDAY))
        show()
        waitFor(RestWindowsTags.edit(firstId()))
        compose.onNodeWithTag(RestWindowsTags.edit(firstId())).assertDescribes("Mon")
        compose.onNodeWithTag(RestWindowsTags.delete(firstId())).assertDescribes("Mon")
    }

    private fun SemanticsNodeInteraction.assertDescribes(text: String) = this.assert(hasContentDescription(text, substring = true))
}
