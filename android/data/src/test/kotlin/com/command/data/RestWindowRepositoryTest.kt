package com.command.data

import androidx.test.core.app.ApplicationProvider
import com.command.data.db.RestWindowRow
import com.command.domain.model.RestKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalTime

/** The rows the editor writes, read back by it and by what obeys them, on the real schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RestWindowRepositoryTest {
    private val f = Fixture(ApplicationProvider.getApplicationContext())
    private val repo = RestWindowRepository(f.db)
    private val ten = LocalTime.of(22, 0)
    private val six = LocalTime.of(6, 0)

    @After fun tearDown() = f.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }
    private fun rows() = runBlocking { f.db.restWindows().all() }.sortedBy { it.weekday }
    private fun lines() = runBlocking { repo.observe().first() }

    @Test
    fun `a window on two days is one row a day in the stored form, and reads back as one line`() = run {
        assertEquals(SaveRestWindowResult.Saved, repo.save(emptyList(), setOf(MONDAY, TUESDAY), ten, six, RestKind.SLEEP))

        assertEquals(listOf(1, 2), rows().map { it.weekday })
        assertTrue(rows().all { it.startLocal == "22:00" && it.endLocal == "06:00" && it.kind == RestKind.SLEEP })
        val line = lines().single()
        assertEquals(setOf(MONDAY, TUESDAY), line.days)
        assertEquals(ten, line.start)
        assertEquals(six, line.end)
        assertEquals(rows().map { it.id }.toSet(), line.ids.toSet())
    }

    @Test
    fun `no day, or a start equal to the end, is refused and nothing is written`() = run {
        assertEquals(
            SaveRestWindowResult.Rejected(setOf(RestWindowProblem.NO_DAYS)),
            repo.save(emptyList(), emptySet(), ten, six, RestKind.SLEEP),
        )
        assertEquals(
            SaveRestWindowResult.Rejected(setOf(RestWindowProblem.SAME_TIMES)),
            repo.save(emptyList(), setOf(MONDAY), six, six, RestKind.SLEEP),
        )
        assertEquals(
            SaveRestWindowResult.Rejected(setOf(RestWindowProblem.NO_DAYS, RestWindowProblem.SAME_TIMES)),
            repo.save(emptyList(), emptySet(), six, six, RestKind.SLEEP),
        )
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `refusing a change leaves the window as it was`() = run {
        repo.save(emptyList(), setOf(MONDAY), ten, six, RestKind.SLEEP)
        val before = rows()
        repo.save(before.map { it.id }, emptySet(), ten, six, RestKind.SLEEP)
        assertEquals(before, rows())
    }

    @Test
    fun `changing a window replaces all of its rows`() = run {
        repo.save(emptyList(), setOf(MONDAY, TUESDAY), ten, six, RestKind.SLEEP)
        val line = lines().single()

        repo.save(line.ids, setOf(FRIDAY), LocalTime.of(12, 30), LocalTime.of(13, 15), RestKind.DRIVING)

        val row = rows().single()
        assertEquals(5, row.weekday)
        assertEquals("12:30", row.startLocal)
        assertEquals("13:15", row.endLocal)
        assertEquals(RestKind.DRIVING, row.kind)
    }

    @Test
    fun `a window deleted while it was being changed does not come back`() = run {
        repo.save(emptyList(), setOf(MONDAY), ten, six, RestKind.SLEEP)
        val ids = lines().single().ids
        repo.delete(ids)

        assertEquals(SaveRestWindowResult.Gone, repo.save(ids, setOf(MONDAY), ten, six, RestKind.LEISURE))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `saving the same window twice does not stack rows, and a new day joins the line`() = run {
        repo.save(emptyList(), setOf(MONDAY), ten, six, RestKind.SLEEP)
        repo.save(emptyList(), setOf(MONDAY, TUESDAY), ten, six, RestKind.SLEEP)
        assertEquals(listOf(1, 2), rows().map { it.weekday })
        assertEquals(setOf(MONDAY, TUESDAY), lines().single().days)
    }

    @Test
    fun `the same hours for another purpose stay a line of their own, and lines come in week order`() = run {
        repo.save(emptyList(), setOf(SATURDAY), ten, six, RestKind.LEISURE)
        repo.save(emptyList(), setOf(MONDAY), ten, six, RestKind.SLEEP)
        repo.save(emptyList(), setOf(MONDAY), six, ten, RestKind.DRIVING)
        assertEquals(listOf(RestKind.DRIVING, RestKind.SLEEP, RestKind.LEISURE), lines().map { it.kind })
    }

    @Test
    fun `deleting a window deletes all of its rows and leaves the others`() = run {
        repo.save(emptyList(), setOf(MONDAY, TUESDAY), ten, six, RestKind.SLEEP)
        repo.save(emptyList(), setOf(FRIDAY), six, ten, RestKind.LEISURE)
        repo.delete(lines().first { it.kind == RestKind.SLEEP }.ids)
        assertEquals(RestKind.LEISURE, rows().single().kind)
    }

    @Test
    fun `a row that cannot be read is no window, on the list and for the warning`() = run {
        f.db.restWindows().insert(RestWindowRow(weekday = 1, startLocal = "late", endLocal = "06:00", kind = RestKind.SLEEP))
        f.db.restWindows().insert(RestWindowRow(weekday = 9, startLocal = "22:00", endLocal = "06:00", kind = RestKind.SLEEP))
        assertTrue(lines().isEmpty())
        assertTrue(repo.observeWindows().first().isEmpty())
    }

    @Test
    fun `what is saved here is what the rest of the app obeys, overnight too`() = run {
        val quiet = RestWindowQuietPolicy(f.db, f.clock)
        assertFalse(quiet.isQuiet(f.t0))
        // t0 is Monday 09:00 UTC.
        repo.save(emptyList(), setOf(MONDAY), LocalTime.of(22, 0), LocalTime.of(10, 0), RestKind.SLEEP)
        assertFalse("Monday morning is before the window", quiet.isQuiet(f.t0))
        assertTrue("Monday night", quiet.isQuiet(f.t0 + 14 * HOUR))
        assertTrue("Tuesday 09:00, still the night before", quiet.isQuiet(f.t0 + 24 * HOUR))
        assertFalse("Tuesday 10:00, over", quiet.isQuiet(f.t0 + 25 * HOUR))
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
