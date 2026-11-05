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
import kotlinx.coroutines.launch
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
        missions = MissionRepository(f.db, f.repo, f.scheduler, f.clock, f.pauseEffects)
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
    fun `a delay that lands on another coming occurrence shows as an overlap while the screen watches, and goes when it is answered`() = run {
        val delayed = occurrencesOf(saved(draft(schedule = ScheduleDraft.Once(at(2 * hour))))).single()
        val other = occurrencesOf(saved(draft(title = "The other one", schedule = ScheduleDraft.Once(at(20 * 60_000L))))).single()
        assertTrue("they do not overlap yet", f.db.calendarDao().conflicts().isEmpty())

        val gone = kotlinx.coroutines.CompletableDeferred<Unit>()
        val watching = kotlinx.coroutines.CompletableDeferred<Unit>()
        val appeared = kotlinx.coroutines.CompletableDeferred<Set<String>>()
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            missions.observeConflicted().collect { ids ->
                watching.complete(Unit)
                if (ids.isNotEmpty()) appeared.complete(ids)
                if (appeared.isCompleted && ids.isEmpty()) gone.complete(Unit)
            }
        }
        try {
            kotlinx.coroutines.withTimeout(10_000) { watching.await() }
            f.repo.apply(delayed.id, OccurrenceCommand.Fire, EventSource.SERVICE)
            f.repo.apply(delayed.id, OccurrenceCommand.Delay(15), EventSource.USER)
            val child = occurrencesOf(delayed.missionId).single { it.parentOccurrenceId == delayed.id }

            assertEquals(setOf(child.id, other.id), kotlinx.coroutines.withTimeout(10_000) { appeared.await() })

            f.repo.apply(child.id, OccurrenceCommand.Skip(com.command.domain.occurrence.SkipReason.USER), EventSource.USER)
            kotlinx.coroutines.withTimeout(10_000) { gone.await() }
        } finally {
            watcher.cancel()
        }
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
        assertTrue("and it does not show on the schedule", missions.observeSchedule().first().upcoming.isEmpty())
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
        assertTrue("it is not on the schedule", missions.observeSchedule().first().let { it.upcoming.isEmpty() && it.recent.isEmpty() })
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
        val rows: List<ScheduleRow> = missions.observeSchedule().first().upcoming
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

        missions.reconcile(ReconcileTrigger.OPEN)

        assertNotNull(f.scheduler.armed[fireIdentity(occurrence.id)])
        assertEquals("nothing was duplicated", 1, occurrencesOf(id).size)
    }

    @Test
    fun `opening the app tops the week up as days pass`() = run {
        val id = saved(draft(schedule = daily()))
        assertEquals(7, occurrencesOf(id).size)
        f.clock.advance(day)

        missions.reconcile(ReconcileTrigger.OPEN)

        val all = occurrencesOf(id)
        assertEquals("a new day was added at the far end", 8, all.size)
        assertEquals(f.t0 + hour + 7 * day, all.last().scheduledAtUtc)
    }

    @Test
    fun `an alarm that came due while the phone was off is recorded as missed, not rung hours late`() = run {
        val id = saved(draft())
        val occurrence = occurrencesOf(id).single()
        f.clock.advance(5 * hour) // the alarm was at +2 h; it is now three hours overdue

        missions.reconcile(ReconcileTrigger.OPEN)

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

        missions.reconcile(ReconcileTrigger.OPEN)

        assertEquals(OccurrenceState.ARMED, f.db.occurrences().get(occurrence.id)!!.state)
        assertNotNull("it is armed for its original time, which is already past, so it rings at once", f.scheduler.armed[fireIdentity(occurrence.id)])
    }

    // --- review fixes ------------------------------------------------------------------------------------------------

    @Test
    fun `an alarm the system has not delivered yet is cancelled when its mission is deleted`() = run {
        val id = saved(draft())
        val occurrence = occurrencesOf(id).single()
        f.clock.advance(2 * hour + 60_000) // its time has passed by a minute, but it is still armed and has not rung

        missions.delete(id)

        assertNull("the row is gone", f.db.occurrences().get(occurrence.id))
        assertNull("and so is the platform alarm, which could otherwise still deliver a deleted mission", f.scheduler.armed[fireIdentity(occurrence.id)])
    }

    @Test
    fun `renaming a repeating mission whose alarm is only just overdue does not lose that alarm`() = run {
        val id = saved(draft(schedule = daily(LocalTime.of(10, 0))))
        val first = occurrencesOf(id).first()
        f.clock.advance(hour + 3 * 60_000) // 10:03: the 10:00 alarm is three minutes late and not yet delivered

        missions.save(draft(title = "Renamed", schedule = daily(LocalTime.of(10, 0)), id = id))

        assertEquals(OccurrenceState.ARMED, f.db.occurrences().get(first.id)!!.state)
        assertNotNull(f.scheduler.armed[fireIdentity(first.id)])
    }

    @Test
    fun `a repeating mission that starts after the first week still has a row to open, edit or delete`() = run {
        val far = ScheduleDraft.Repeating(LocalDate.of(2026, 11, 22), LocalTime.of(10, 0), RRule(Frequency.DAILY))
        val id = saved(draft(schedule = far))

        val rows = occurrencesOf(id)
        assertEquals("just the next one, however far away", 1, rows.size)
        assertEquals(OccurrenceState.ARMED, rows.single().state)
        assertEquals(id, missions.observeSchedule().first().upcoming.single().missionId)

        missions.delete(id)
        assertTrue(occurrencesOf(id).isEmpty())
    }

    @Test
    fun `an occurrence created but never armed, and only just due, is armed when the app next opens`() = run {
        val mission = Fixture.missionRow(startAt = f.t0 + 2 * hour)
        f.db.missions().insert(mission)
        val occurrence = f.repo.createOccurrence(mission.id, f.t0 + 2 * hour, "UTC").occurrence // the app stopped before arming it
        f.clock.advance(2 * hour + 5 * 60_000)

        val report = missions.reconcile(ReconcileTrigger.OPEN)

        assertEquals(OccurrenceState.ARMED, f.db.occurrences().get(occurrence.id)!!.state)
        assertNotNull("so it rings now instead of never", f.scheduler.armed[fireIdentity(occurrence.id)])
        assertEquals("a repair is a change, so even an open says so", 1, report.repaired)
        val summary = f.db.events().ofType(EventType.BOOT_RECONCILE).single()
        assertTrue(summary.payloadJson.contains("\"repaired\":\"1\""))
    }

    @Test
    fun `an occurrence stored but never armed, not yet due, is armed and counted as a repair too`() = run {
        val mission = Fixture.missionRow(startAt = f.t0 + 2 * hour)
        f.db.missions().insert(mission)
        val occurrence = f.repo.createOccurrence(mission.id, f.t0 + 2 * hour, "UTC").occurrence // the app stopped before arming it
        assertEquals(OccurrenceState.SCHEDULED, occurrence.state)

        val report = missions.reconcile(ReconcileTrigger.OPEN)

        assertEquals(OccurrenceState.ARMED, f.db.occurrences().get(occurrence.id)!!.state)
        assertEquals(1, report.repaired)
        assertEquals("it already existed, so it was not created", 0, report.created)
        assertEquals(1, f.db.events().ofType(EventType.BOOT_RECONCILE).size)
        assertFalse("and the next open has nothing left to say", missions.reconcile(ReconcileTrigger.OPEN).changedAnything)
    }

    @Test
    fun `a time zone change replaces a daily mission's schedule instead of adding a second one`() = run {
        val id = saved(draft(schedule = daily(LocalTime.of(10, 0))))
        assertEquals(7, occurrencesOf(id).size)

        f.clock.zone = java.time.ZoneId.of("America/New_York")
        missions.reconcile(ReconcileTrigger.OPEN)

        val local = occurrencesOf(id).map { Instant.ofEpochMilli(it.scheduledAtUtc).atZone(f.clock.zone).toLocalTime() }
        assertEquals("seven days, every one at 10:00 where the phone now is, and nothing left over", List(7) { LocalTime.of(10, 0) }, local)
    }

    @Test
    fun `a second test ring replaces the first, so the hidden mission's text always matches what rings`() = run {
        val first = missions.testRing(TestRingSpec("First"))
        f.clock.advance(1_000)
        val second = missions.testRing(TestRingSpec("Second"))

        assertNull("the first test alarm is gone", f.db.occurrences().get(first))
        assertNull(f.scheduler.armed[fireIdentity(first)])
        assertEquals(OccurrenceState.ARMED, f.db.occurrences().get(second)!!.state)
        assertEquals("Second", f.db.missions().get(MissionRepository.TEST_MISSION_ID)!!.title)
    }

    @Test
    fun `the schedule keeps every upcoming row however many there are, and bounds history separately`() = run {
        repeat(30) { i -> saved(draft(title = "Mission $i", schedule = daily(LocalTime.of(10, 0)))) } // 210 upcoming rows
        val snapshot = missions.observeSchedule().first()
        assertEquals("the list is capped, soonest first, not cut off at an arbitrary place", 200, snapshot.upcoming.size)
        assertEquals(snapshot.upcoming.sortedBy { it.scheduledAtUtc }.map { it.occurrenceId }, snapshot.upcoming.map { it.occurrenceId })
        assertTrue(snapshot.recent.isEmpty())
    }

    // --- reconciling after something disturbed the platform's alarms ------------------------------------------

    @Test
    fun `after a reboot every armed alarm that the system dropped is logged as lost and put back, with one summary line`() = run {
        val a = saved(draft(title = "First", schedule = ScheduleDraft.Once(at(2 * hour))))
        val b = saved(draft(title = "Second", schedule = ScheduleDraft.Once(at(3 * hour))))
        f.scheduler.armed.clear()

        val report = missions.reconcile(ReconcileTrigger.BOOT)

        assertEquals(2, report.lost)
        assertEquals(2, report.rearmed)
        assertEquals(0, report.created)
        assertEquals(2, f.db.events().ofType(EventType.ALARM_LOST).size)
        for (id in listOf(a, b)) assertNotNull(f.scheduler.armed[fireIdentity(occurrencesOf(id).single().id)])
        val summary = f.db.events().ofType(EventType.BOOT_RECONCILE).single()
        assertTrue(summary.payloadJson.contains("BOOT") && summary.payloadJson.contains("\"lost\":\"2\""))
    }

    @Test
    fun `opening the app when nothing is wrong leaves the audit log alone, but a boot always leaves its line`() = run {
        saved(draft())
        val before = f.db.events().all().size

        val quiet = missions.reconcile(ReconcileTrigger.OPEN)
        assertFalse(quiet.changedAnything)
        assertEquals("a quiet open writes nothing", before, f.db.events().all().size)

        missions.reconcile(ReconcileTrigger.BOOT)
        assertEquals("a boot says it ran", 1, f.db.events().ofType(EventType.BOOT_RECONCILE).size)
    }

    @Test
    fun `an exact alarm that can no longer be exact comes back inexact, and the log says so, once`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Once(at(2 * hour))))
        val occurrence = occurrencesOf(id).single()
        assertEquals(ArmMode.EXACT, occurrence.armMode)

        f.scheduler.exactAllowed = false
        val report = missions.reconcile(ReconcileTrigger.OPEN)

        assertEquals(1, report.precisionChanged)
        assertEquals(ArmMode.INEXACT, f.db.occurrences().get(occurrence.id)!!.armMode)
        val summary = f.db.events().ofType(EventType.BOOT_RECONCILE).single()
        assertTrue(summary.payloadJson.contains("\"precision_changed\":\"1\""))

        assertFalse("nothing else changed, so the next open is quiet", missions.reconcile(ReconcileTrigger.OPEN).changedAnything)
        assertEquals(1, f.db.events().ofType(EventType.BOOT_RECONCILE).size)

        f.scheduler.exactAllowed = true
        assertEquals("and it is exact again once the permission is back", 1, missions.reconcile(ReconcileTrigger.EXACT_ALARM_CHANGED).precisionChanged)
        assertEquals(ArmMode.EXACT, f.db.occurrences().get(occurrence.id)!!.armMode)
    }

    @Test
    fun `a time zone change keeps a daily mission at the same local time`() = run {
        val id = saved(draft(schedule = daily(LocalTime.of(10, 0))))
        assertEquals(7, occurrencesOf(id).size)

        f.clock.zone = java.time.ZoneId.of("America/New_York")
        val report = missions.reconcile(ReconcileTrigger.ZONE_CHANGED)

        val local = occurrencesOf(id).map { Instant.ofEpochMilli(it.scheduledAtUtc).atZone(f.clock.zone).toLocalTime() }
        assertEquals("seven days, every one at 10:00 where the phone now is", List(7) { LocalTime.of(10, 0) }, local)
        assertTrue("the ones at the old zone's 10:00 are gone", report.discarded > 0)
        assertEquals(7, occurrencesOf(id).count { it.state == OccurrenceState.ARMED })
    }

    @Test
    fun `a one-off is an instant, so a zone change does not move it`() = run {
        val id = saved(draft())
        val before = occurrencesOf(id).single()

        f.clock.zone = java.time.ZoneId.of("Asia/Tokyo")
        missions.reconcile(ReconcileTrigger.ZONE_CHANGED)

        val after = occurrencesOf(id).single()
        assertEquals(before.id, after.id)
        assertEquals(before.scheduledAtUtc, after.scheduledAtUtc)
    }

    @Test
    fun `a delayed alarm is left alone by a reconcile`() = run {
        val id = saved(draft())
        val parent = occurrencesOf(id).single()
        f.repo.apply(parent.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        f.repo.apply(parent.id, OccurrenceCommand.Delay(15), EventSource.USER)
        val child = occurrencesOf(id).single { it.parentOccurrenceId == parent.id }

        missions.reconcile(ReconcileTrigger.TIME_CHANGED)

        assertEquals(OccurrenceState.ARMED, f.db.occurrences().get(child.id)!!.state)
    }

    @Test
    fun `an alarm that came due while the phone was off says how late it was`() = run {
        val id = saved(draft())
        val occurrence = occurrencesOf(id).single()
        f.clock.advance(5 * hour) // due at +2 h, so three hours late

        val report = missions.reconcile(ReconcileTrigger.BOOT)

        assertEquals(1, report.missed)
        val event = f.db.events().ofType(EventType.MISSED_WHILE_OFF).single()
        assertEquals(occurrence.id, event.occurrenceId)
        assertTrue(event.payloadJson.contains("\"late_s\":\"10800\""))
    }

    @Test
    fun `a test ring that was never answered is cleaned up too, without ever showing`() = run {
        val occurrenceId = missions.testRing()
        f.clock.advance(20 * 60_000)

        missions.reconcile(ReconcileTrigger.OPEN)

        assertEquals(OccurrenceState.MISSED, f.db.occurrences().get(occurrenceId)!!.state)
        val schedule = missions.observeSchedule().first()
        assertTrue(schedule.upcoming.isEmpty() && schedule.recent.isEmpty())
    }

    // --- the window of history ---------------------------------------------------------------------------------------

    private suspend fun due(title: String, atOffsetMs: Long, state: OccurrenceState, deleted: Boolean = false): String {
        val mission = Fixture.missionRow(title = title, startAt = f.t0 + atOffsetMs).copy(deletedAt = if (deleted) f.t0 else null)
        f.db.missions().insert(mission)
        val o = f.repo.createOccurrence(mission.id, f.t0 + atOffsetMs, "UTC").occurrence
        when (state) {
            OccurrenceState.SCHEDULED -> Unit
            OccurrenceState.MISSED -> {
                val system = com.command.domain.model.EventSource.SYSTEM
                f.repo.apply(o.id, com.command.domain.occurrence.OccurrenceCommand.Arm(com.command.domain.occurrence.ArmMode.EXACT), system)
                f.repo.apply(o.id, com.command.domain.occurrence.OccurrenceCommand.Fire, system)
                f.repo.apply(o.id, com.command.domain.occurrence.OccurrenceCommand.Miss(com.command.domain.occurrence.MissReason.TIMEOUT), system)
            }
            else -> error("not needed here: $state")
        }
        return o.id
    }

    @Test
    fun `history is the last days as the person's own local days, newest first, without what is still to come`() = run {
        // The clock stands at Monday 2026-11-02 09:00 UTC.
        val today = due("This morning", -2 * hour, OccurrenceState.MISSED)
        val yesterday = due("Yesterday", -26 * hour, OccurrenceState.MISSED)
        val old = due("Eight days ago", -8 * 24 * hour, OccurrenceState.MISSED)
        val later = due("This afternoon", 5 * hour, OccurrenceState.SCHEDULED)

        val seven = missions.observeHistory(7).first().map { it.occurrenceId }
        assertEquals("newest first, and not what is due later today", listOf(today, yesterday), seven)
        assertTrue(old !in seven)
        assertTrue(later !in seven)

        val thirty = missions.observeHistory(30).first().map { it.occurrenceId }
        assertEquals(listOf(today, yesterday, old), thirty)
    }

    @Test
    fun `the fetch starts at the person's midnight less the most two zones can differ, so that a mission pinned to a zone ahead is not cut off`() = run {
        f.clock.zone = java.time.ZoneId.of("Pacific/Auckland") // UTC+13 on that date: it is already 22:00 on Monday there
        val sinceMidnightThere = due("Monday there, early", 0 - 5 * hour, OccurrenceState.MISSED) // 04:00 UTC = 17:00 Monday in Auckland
        val sundayThere = due("Sunday there", -23 * hour, OccurrenceState.MISSED) // 10:00 UTC Sunday = 23:00 Sunday in Auckland
        val longBefore = due("Three days before", -72 * hour, OccurrenceState.MISSED)
        val one = missions.observeHistory(1).first().map { it.occurrenceId }
        // Midnight on Monday in Auckland is 11:00 UTC on Sunday. The fetch reaches 26 hours further back, and the rule decides the rest.
        assertEquals(listOf(sinceMidnightThere, sundayThere), one)
        assertTrue(longBefore !in one)
    }

    @Test
    fun `history leaves out missions that were deleted`() = run {
        val kept = due("Kept", -hour, OccurrenceState.MISSED)
        due("Deleted", -2 * hour, OccurrenceState.MISSED, deleted = true)
        assertEquals(listOf(kept), missions.observeHistory(7).first().map { it.occurrenceId })
    }

    @Test
    fun `history is not cut at a few hundred rows, because its counts are made from them`() = run {
        val mission = Fixture.missionRow(title = "Often").also { f.db.missions().insert(it) }
        for (i in 1..600) f.repo.createOccurrence(mission.id, f.t0 - i * hour, "UTC")
        assertEquals(600, missions.observeHistory(30).first().size)
    }
}
