package com.command.history

import com.command.data.MissionRepository
import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.domain.history.HistoryReason
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.SkipReason
import com.command.testing.FakeClock
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** What History is made of: the person's own days, newest first, and an honest count of the days on which they started. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class HistoryViewModelTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var missions: MissionRepository
    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock

    private lateinit var vm: HistoryViewModel
    private lateinit var collector: Job

    @Before
    fun setUp() {
        hilt.inject()
        vm = HistoryViewModel(missions, clock)
        // The state is only live while someone is collecting it, as on screen.
        collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.state.collect { } }
    }

    @After
    fun tearDown() = collector.cancel()

    private val hour = 3_600_000L
    private val today = LocalDate.of(2026, 11, 2) // the fake clock stands at Monday 2026-11-02 09:00 UTC

    private fun ready(): HistoryUiState.Ready? = vm.state.value as? HistoryUiState.Ready

    private val rang = arrayOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire)
    private val guided = rang + arrayOf(OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance)
    private val started = guided + OccurrenceCommand.ReportStarted

    /** An occurrence of a new mission that came due [agoMs] before now, taken through [commands]. */
    private fun occurrence(title: String, agoMs: Long, commands: Array<OccurrenceCommand>, deleted: Boolean = false, zone: String = "UTC"): String = runBlocking {
        val at = FakeAlarmModule.T0 - agoMs
        val mission = testMission(title).copy(startAtUtc = at, deletedAt = if (deleted) FakeAlarmModule.T0 else null)
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, at, zone).occurrence
        for (c in commands) repo.apply(o.id, c, EventSource.SYSTEM)
        o.id
    }

    @Test
    fun `with nothing recorded it is ready, and says there is nothing`() {
        awaitUntil(message = "ready") { ready() != null }
        assertTrue(ready()!!.days.isEmpty())
        assertEquals(0, ready()!!.week.eligibleDays)
        assertEquals(0, ready()!!.month.eligibleDays)
    }

    @Test
    fun `the days are the person's own, newest first, and each day lists its latest first, with what happened`() {
        val morning = occurrence("Morning", 2 * hour, started)
        val dawn = occurrence("Dawn", 4 * hour, rang + OccurrenceCommand.Miss(MissReason.TIMEOUT))
        val yesterday = occurrence("Yesterday", 26 * hour, guided + OccurrenceCommand.ReportDone)

        awaitUntil(message = "three entries") { ready()?.days?.sumOf { it.entries.size } == 3 }
        val days = ready()!!.days
        assertEquals(listOf(today, today.minusDays(1)), days.map { it.date })
        assertEquals("the latest first within a day", listOf(morning, dawn), days[0].entries.map { it.occurrenceId })
        assertEquals(listOf(HistoryReason.STARTED, HistoryReason.TIMED_OUT), days[0].entries.map { it.reason })
        assertEquals(listOf(yesterday), days[1].entries.map { it.occurrenceId })
        assertEquals(HistoryReason.DONE, days[1].entries.single().reason)
        assertEquals("Morning", days[0].entries.first().title)
    }

    @Test
    fun `the summaries count the days with a start among the days that held something that was the person's to answer`() {
        occurrence("Started", 2 * hour, started) // today: a start
        occurrence("Skipped", 26 * hour, guided + OccurrenceCommand.Skip(SkipReason.USER)) // yesterday: the person's choice, no start
        occurrence("Phone off", 50 * hour, arrayOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Miss(MissReason.DOWNTIME))) // not the person's doing

        awaitUntil(message = "three entries") { ready()?.days?.sumOf { it.entries.size } == 3 }
        val week = ready()!!.week
        assertEquals(7, week.days)
        assertEquals("a start on one day", 1, week.daysWithStart)
        assertEquals("two days held something that was the person's to answer; the day the phone was off is left out", 2, week.eligibleDays)
        assertEquals(0.5, week.startRate!!, 0.0001)
        assertEquals(1, ready()!!.month.daysWithStart)
        assertEquals(2, ready()!!.month.eligibleDays)
    }

    @Test
    fun `an old occurrence is in the thirty days and not in the seven`() {
        occurrence("Long ago", 10 * 24 * hour, started)
        awaitUntil(message = "one entry") { ready()?.days?.size == 1 }
        assertEquals(0, ready()!!.week.eligibleDays)
        assertEquals(1, ready()!!.month.eligibleDays)
        assertEquals(1, ready()!!.month.daysWithStart)
    }

    @Test
    fun `what is still to come, and missions that were deleted, are not in it`() {
        occurrence("Later today", -3 * hour, arrayOf(OccurrenceCommand.Arm(ArmMode.EXACT)))
        occurrence("Deleted", 2 * hour, started, deleted = true)
        val kept = occurrence("Kept", 3 * hour, started)

        awaitUntil(message = "the kept one") { ready()?.days?.isNotEmpty() == true }
        assertEquals(listOf(kept), ready()!!.days.flatMap { it.entries }.map { it.occurrenceId })
    }

    @Test
    fun `an occurrence of a mission pinned to a zone that is ahead of the phone is in neither the list nor the counts until the phone reaches its day`() {
        // The phone is in Los Angeles at 04:00 on 2 November. A mission pinned to Auckland rang at 00:30 there, which is already the 3rd.
        collector.cancel()
        clock.zone = ZoneId.of("America/Los_Angeles")
        clock.nowMs = FakeAlarmModule.T0 + 3 * hour
        vm = HistoryViewModel(missions, clock)
        collector = CoroutineScope(Dispatchers.Main.immediate).launch { vm.state.collect { } }

        val ahead = occurrence("Pinned", -5 * hour / 2, started, zone = "Pacific/Auckland")
        val here = occurrence("Here", 2 * hour, started)

        awaitUntil(message = "the one on the phone's own day") { ready()?.days?.flatMap { it.entries }?.any { it.occurrenceId == here } == true }
        assertEquals("only the one that is on the phone's day is listed", listOf(here), ready()!!.days.flatMap { it.entries }.map { it.occurrenceId })
        assertEquals("and only that one is counted", 1, ready()!!.month.eligibleDays)

        // A day later the phone is on the 3rd as well: the pinned one is inside, in the list and in the counts.
        clock.advance(24 * hour)
        occurrence("Touch", 3 * hour, started) // any change makes the screen read its rows again
        awaitUntil(message = "the pinned one") { ready()?.days?.flatMap { it.entries }?.any { it.occurrenceId == ahead } == true }
        assertEquals(listOf(LocalDate.of(2026, 11, 3)), ready()!!.days.filter { d -> d.entries.any { it.occurrenceId == ahead } }.map { it.date })
        assertEquals(2, ready()!!.month.eligibleDays)
    }
}
