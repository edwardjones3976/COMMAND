package com.command.data

import androidx.test.core.app.ApplicationProvider
import com.command.data.db.ScheduleRow
import com.command.domain.dials.Flexibility
import com.command.domain.model.EventSource
import com.command.domain.model.StartKind
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.schedule.Frequency
import com.command.domain.schedule.RRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MissionRepositoryTest {

    private lateinit var f: Fixture
    private lateinit var missions: MissionRepository

    private val hour = 3_600_000L
    private val day = 24 * hour

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext())
        missions = MissionRepository(f.db, f.repo, f.scheduler, f.clock)
    }

    @After
    fun tearDown() = f.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    /** A local date-time on the (UTC) fixture clock, [offsetMs] after the fixed "now" of Monday 2026-11-02 09:00. */
    private fun at(offsetMs: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(f.t0 + offsetMs), ZoneOffset.UTC)

    private fun draft(
        title: String = "Write the report",
        firstAction: String = "Open the document",
        flexibility: Flexibility = Flexibility.FLEXIBLE,
        schedule: ScheduleDraft = ScheduleDraft.Once(at(2 * hour)),
        id: String? = null,
    ) = MissionDraft(id = id, title = title, firstAction = firstAction, flexibility = flexibility, schedule = schedule)

    private fun daily(timeOfDay: LocalTime = LocalTime.of(10, 0)) =
        ScheduleDraft.Repeating(LocalDate.of(2026, 11, 2), timeOfDay, RRule(Frequency.DAILY))

    private suspend fun saved(d: MissionDraft): String = (missions.save(d) as SaveResult.Saved).missionId

    private suspend fun occurrencesOf(missionId: String) = f.db.occurrences().forMission(missionId)

    private fun fireIdentity(occurrenceId: String) = "command://occurrence/$occurrenceId"

    // --- creating ----------------------------------------------------------------------------------------------

    @Test
    fun `saving a one-off stores the mission, one occurrence, and arms its alarm`() = run {
        val result = missions.save(draft())
        assertTrue(result is SaveResult.Saved)
        val id = (result as SaveResult.Saved).missionId
        assertEquals(1, result.scheduled)

        val mission = f.db.missions().get(id)!!
        assertEquals("Write the report", mission.title)
        assertEquals(StartKind.ONE_OFF, mission.startKind)
        assertTrue(mission.enabled)

        val occurrence = occurrencesOf(id).single()
        assertEquals(OccurrenceState.ARMED, occurrence.state)
        assertEquals(f.t0 + 2 * hour, occurrence.scheduledAtUtc)
        assertEquals(ArmMode.EXACT, occurrence.armMode)
        assertNotNull(f.scheduler.armed[fireIdentity(occurrence.id)])
    }

    @Test
    fun `a mission with nothing to say or in the past is refused with every reason, and nothing is stored`() = run {
        val result = missions.save(draft(title = "  ", firstAction = "", schedule = ScheduleDraft.Once(at(-hour))))
        assertEquals(
            setOf(DraftProblem.TITLE_EMPTY, DraftProblem.FIRST_ACTION_EMPTY, DraftProblem.IN_THE_PAST),
            (result as SaveResult.Invalid).problems,
        )
        assertEquals(0, f.db.missions().count())
        assertTrue(f.scheduler.armed.isEmpty())
    }

    @Test
    fun `a weekly mission needs at least one day`() = run {
        val weekly = ScheduleDraft.Repeating(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), RRule(Frequency.WEEKLY))
        val result = missions.save(draft(schedule = weekly))
        assertEquals(setOf(DraftProblem.NO_DAYS_SELECTED), (result as SaveResult.Invalid).problems)
    }

    @Test
    fun `a daily mission gets the next seven days, each armed`() = run {
        val id = saved(draft(schedule = daily()))
        val all = occurrencesOf(id)
        assertEquals(7, all.size)
        assertTrue(all.all { it.state == OccurrenceState.ARMED })
        assertEquals(7, f.scheduler.armed.keys.count { it.startsWith("command://occurrence/") })
        assertEquals(f.t0 + hour, all.first().scheduledAtUtc) // today at 10:00, an hour from the fixed "now"
        assertEquals(f.t0 + hour + 6 * day, all.last().scheduledAtUtc)
    }

    @Test
    fun `a weekly mission rings only on its days`() = run {
        val rule = RRule(Frequency.WEEKLY, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        val id = saved(draft(schedule = ScheduleDraft.Repeating(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), rule)))
        val days = occurrencesOf(id).map { Instant.ofEpochMilli(it.scheduledAtUtc).atZone(ZoneOffset.UTC).dayOfWeek }
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), days)
    }

    @Test
    fun `a one-off far in the future is scheduled too`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Once(at(30 * day))))
        val occurrence = occurrencesOf(id).single()
        assertEquals(OccurrenceState.ARMED, occurrence.state)
        assertEquals(f.t0 + 30 * day, occurrence.scheduledAtUtc)
    }

    @Test
    fun `the editor can read back what was saved`() = run {
        val weekly = ScheduleDraft.Repeating(
            LocalDate.of(2026, 11, 2), LocalTime.of(7, 30),
            RRule(Frequency.WEEKLY, byDay = setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)),
        )
        val id = saved(draft(title = "Run", firstAction = "Put on shoes", flexibility = Flexibility.RIGID, schedule = weekly))
        val back = missions.draftOf(missions.get(id)!!)!!
        assertEquals(id, back.id)
        assertEquals("Run", back.title)
        assertEquals("Put on shoes", back.firstAction)
        assertEquals(Flexibility.RIGID, back.flexibility)
        assertEquals(weekly, back.schedule)
    }

    // --- editing -----------------------------------------------------------------------------------------------

    @Test
    fun `changing only the title leaves the armed alarm exactly as it was`() = run {
        val id = saved(draft())
        val before = occurrencesOf(id).single()
        f.scheduler.calls.clear()

        missions.save(draft(title = "Write the long report", id = id))

        val after = occurrencesOf(id).single()
        assertEquals("the same occurrence", before.id, after.id)
        assertEquals(OccurrenceState.ARMED, after.state)
        assertTrue("the alarm was not touched", f.scheduler.calls.isEmpty())
        assertEquals("Write the long report", f.db.missions().get(id)!!.title)
    }

    @Test
    fun `changing the time cancels the old alarm and arms the new one`() = run {
        val id = saved(draft())
        val old = occurrencesOf(id).single()

        missions.save(draft(schedule = ScheduleDraft.Once(at(3 * hour)), id = id))

        val now = occurrencesOf(id).single()
        assertNotEquals(old.id, now.id)
        assertEquals(f.t0 + 3 * hour, now.scheduledAtUtc)
        assertNull("the old alarm is gone", f.scheduler.armed[fireIdentity(old.id)])
        assertNotNull(f.scheduler.armed[fireIdentity(now.id)])
        assertTrue(f.db.events().ofType(EventType.OCCURRENCE_DISCARDED).any { it.occurrenceId == old.id })
    }

    @Test
    fun `changing the time and changing it back arms the original time again`() = run {
        val id = saved(draft())
        missions.save(draft(schedule = ScheduleDraft.Once(at(3 * hour)), id = id))
        missions.save(draft(schedule = ScheduleDraft.Once(at(2 * hour)), id = id))

        val only = occurrencesOf(id).single()
        assertEquals(f.t0 + 2 * hour, only.scheduledAtUtc)
        assertEquals("the undone edit left nothing in the way", OccurrenceState.ARMED, only.state)
        assertNotNull(f.scheduler.armed[fireIdentity(only.id)])
        assertEquals(1, f.scheduler.armed.keys.count { it.startsWith("command://occurrence/") })
    }

    @Test
    fun `an edit leaves a ring that has already started alone`() = run {
        val id = saved(draft())
        val occurrence = occurrencesOf(id).single()
        f.repo.apply(occurrence.id, OccurrenceCommand.Fire, EventSource.SERVICE)

        missions.save(draft(schedule = ScheduleDraft.Once(at(5 * hour)), id = id))

        assertEquals(OccurrenceState.FIRING, f.db.occurrences().get(occurrence.id)!!.state)
        assertEquals("and the new time got its own occurrence", 2, occurrencesOf(id).size)
    }

    @Test
    fun `a delayed alarm survives an edit but not a deletion`() = run {
        val id = saved(draft())
        val parent = occurrencesOf(id).single()
        f.repo.apply(parent.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        f.repo.apply(parent.id, OccurrenceCommand.Delay(15), EventSource.USER)
        val child = occurrencesOf(id).single { it.parentOccurrenceId == parent.id }
        assertEquals(OccurrenceState.ARMED, child.state)

        missions.save(draft(title = "Renamed", schedule = ScheduleDraft.Once(at(6 * hour)), id = id))
        assertEquals("the delay the person asked for stays", OccurrenceState.ARMED, f.db.occurrences().get(child.id)!!.state)

        missions.delete(id)
        assertNull("deleting the mission removes it too", f.db.occurrences().get(child.id))
    }

    // --- deleting ----------------------------------------------------------------------------------------------

    @Test
    fun `deleting hides the mission and discards what has not rung, but keeps what has`() = run {
        val id = saved(draft(schedule = daily()))
        val all = occurrencesOf(id)
        val first = all.first()
        f.repo.apply(first.id, OccurrenceCommand.Fire, EventSource.SERVICE)

        missions.delete(id)

        assertNull(missions.get(id))
        val left = occurrencesOf(id)
        assertEquals("only the one that rang remains, as history", listOf(first.id), left.map { it.id })
        assertTrue("the others have no alarm left", all.drop(1).all { f.scheduler.armed[fireIdentity(it.id)] == null })
        assertTrue("and it does not show on the schedule", missions.observeSchedule().first().isEmpty())
        assertFalse(f.db.missions().get(id)!!.enabled)
    }

    // --- the test ring -----------------------------------------------------------------------------------------

    @Test
    fun `a test ring arms a real alarm a few seconds away, with what was typed, and shows nowhere`() = run {
        val occurrenceId = missions.testRing(TestRingSpec("Standup", "Open the notes", Flexibility.RIGID, 10))

        val occurrence = f.db.occurrences().get(occurrenceId)!!
        assertEquals(OccurrenceState.ARMED, occurrence.state)
        assertEquals(f.t0 + 5_000, occurrence.scheduledAtUtc)
        assertNotNull(f.scheduler.armed[fireIdentity(occurrenceId)])

        val mission = f.db.missions().get(occurrence.missionId)!!
        assertEquals("Standup", mission.title)
        assertEquals("Open the notes", mission.firstAction)
        assertEquals("a fixed mission is tested with its fixed friction", Flexibility.RIGID, mission.flexibility)
        assertEquals(10, mission.starterMinutes)
        assertEquals(MissionRepository.TEST_CATEGORY, mission.category)

        assertTrue(f.db.events().ofType(EventType.TEST_RING).any { it.occurrenceId == occurrenceId })
        assertTrue("it is not on the schedule", missions.observeSchedule().first().isEmpty())
        assertNull("nor in the list of missions", missions.get(mission.id))
    }

    @Test
    fun `test rings reuse one hidden mission and fall back to plain defaults`() = run {
        val a = f.db.occurrences().get(missions.testRing(TestRingSpec("First")))!!
        f.clock.advance(1_000)
        val b = f.db.occurrences().get(missions.testRing())!!
        assertEquals(a.missionId, b.missionId)
        assertEquals(1, f.db.missions().count())
        assertEquals("Test alarm", f.db.missions().get(b.missionId)!!.title)
    }

    // --- the schedule list -------------------------------------------------------------------------------------

    @Test
    fun `the schedule lists what is coming and what happened in the last day, newest information first`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Once(at(2 * hour))))
        val rows: List<ScheduleRow> = missions.observeSchedule().first()
        assertEquals(1, rows.size)
        assertEquals("Write the report", rows.single().title)
        assertEquals(id, rows.single().missionId)
        assertEquals(OccurrenceState.ARMED, rows.single().state)
    }

    // --- opening the app ---------------------------------------------------------------------------------------

    @Test
    fun `opening the app puts back an alarm the system dropped`() = run {
        val id = saved(draft())
        val occurrence = occurrencesOf(id).single()
        f.scheduler.armed.clear() // a reboot or a force-stop

        missions.refreshWindow()

        assertNotNull(f.scheduler.armed[fireIdentity(occurrence.id)])
        assertEquals("nothing was duplicated", 1, occurrencesOf(id).size)
    }

    @Test
    fun `opening the app tops the week up as days pass`() = run {
        val id = saved(draft(schedule = daily()))
        assertEquals(7, occurrencesOf(id).size)
        f.clock.advance(day)

        missions.refreshWindow()

        val all = occurrencesOf(id)
        assertEquals("a new day was added at the far end", 8, all.size)
        assertEquals(f.t0 + hour + 7 * day, all.last().scheduledAtUtc)
    }

    @Test
    fun `an alarm that came due while the phone was off is recorded as missed, not rung hours late`() = run {
        val id = saved(draft())
        val occurrence = occurrencesOf(id).single()
        f.clock.advance(5 * hour) // the alarm was at +2 h; it is now three hours overdue

        missions.refreshWindow()

        val after = f.db.occurrences().get(occurrence.id)!!
        assertEquals(OccurrenceState.MISSED, after.state)
        assertEquals(MissReason.DOWNTIME, after.missReason)
    }

    @Test
    fun `an alarm only just overdue is put back to ring now`() = run {
        val id = saved(draft())
        val occurrence = occurrencesOf(id).single()
        f.clock.advance(2 * hour + 5 * 60_000) // five minutes late: inside the grace period
        f.scheduler.armed.clear()

        missions.refreshWindow()

        assertEquals(OccurrenceState.ARMED, f.db.occurrences().get(occurrence.id)!!.state)
        assertNotNull("it is armed for its original time, which is already past, so it rings at once", f.scheduler.armed[fireIdentity(occurrence.id)])
    }
}
