package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.CalendarSourceRow
import com.command.domain.calendar.CalendarInstance
import com.command.domain.calendar.CalendarRead
import com.command.domain.calendar.DeviceCalendar
import com.command.domain.dials.Flexibility
import com.command.domain.model.ConsentKey
import com.command.domain.model.MissionSource
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.testing.FakeCalendarReader
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val HOUR = 3_600_000L

/** The engine behind the worker, on a real database: what a read does to rows and alarms, and that a failed read does nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarSyncTest {
    private lateinit var f: Fixture
    private lateinit var reader: FakeCalendarReader
    private lateinit var missions: MissionRepository
    private lateinit var consents: ConsentStore
    private lateinit var sync: CalendarSync
    private val work = DeviceCalendar(7, "me@example.test", "Work")

    @Before
    fun setUp() {
        f = Fixture(ApplicationProvider.getApplicationContext<Context>())
        reader = FakeCalendarReader()
        missions = MissionRepository(f.db, f.repo, f.scheduler, f.clock, f.pauseEffects)
        consents = ConsentStore(f.db, f.clock, f.repo, NoOpConsentRevocationListener)
        sync = CalendarSync(f.db, f.repo, missions, consents, reader, f.clock)
    }

    @After
    fun tearDown() = f.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private fun instance(begin: Long = f.t0 + HOUR, event: Long = 100, title: String = "Standup", cancelled: Boolean = false, original: Long? = null, originalAt: Long? = null) =
        CalendarInstance(7, event, begin, begin + HOUR / 2, title, false, "America/New_York", original, originalAt, cancelled)

    private fun reads(vararg instances: CalendarInstance, gone: Set<Long> = emptySet()) {
        reader.result = CalendarRead.Ok(listOf(work), instances.toList(), gone)
    }

    private suspend fun switchOn(flexibility: Flexibility = Flexibility.RIGID) {
        consents.grant(ConsentKey.CALENDAR_READ)
        f.db.calendarDao().insertSource(CalendarSourceRow(7, work.account, work.displayName, true, null, flexibility, null))
    }

    private suspend fun rows() = f.db.occurrences().all()

    @Test
    fun `nothing is read without the consent or without a selected calendar`() = run {
        reads(instance())
        assertEquals(CalendarSyncOutcome.OFF, sync.run())
        consents.grant(ConsentKey.CALENDAR_READ)
        assertEquals(CalendarSyncOutcome.OFF, sync.run())
        f.db.calendarDao().insertSource(CalendarSourceRow(7, work.account, work.displayName, false, null, Flexibility.FLEXIBLE, null))
        assertEquals(CalendarSyncOutcome.OFF, sync.run())
        assertEquals(0, reader.reads)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `an instance becomes one armed occurrence of a read-only mission with the calendar's defaults`() = run {
        switchOn(Flexibility.RIGID)
        reads(instance())
        assertEquals(CalendarSyncOutcome.DONE, sync.run())
        val occ = rows().single()
        assertEquals(OccurrenceState.ARMED, occ.state)
        assertEquals(f.t0 + HOUR, occ.scheduledAtUtc)
        assertEquals("America/New_York", occ.zoneId)
        val mission = f.db.missions().get(occ.missionId)!!
        assertEquals(MissionSource.DEVICE_CALENDAR, mission.source)
        assertEquals(Flexibility.RIGID, mission.flexibility)
        assertEquals("Open: Standup", mission.firstAction)
        assertEquals(30, mission.durationMin)
        assertEquals(1, f.scheduler.armed.size)
    }

    @Test
    fun `syncing twice changes nothing`() = run {
        switchOn()
        reads(instance())
        sync.run()
        val before = rows()
        sync.run()
        assertEquals(before.map { it.id }, rows().map { it.id })
        assertEquals(1, f.db.calendarDao().linkCount())
    }

    @Test
    fun `an exception moves the armed occurrence and its alarm, keeping its identity`() = run {
        switchOn()
        reads(instance())
        sync.run()
        val id = rows().single().id
        val moved = f.t0 + 3 * HOUR
        reads(instance(begin = moved, event = 555, original = 100, originalAt = f.t0 + HOUR))
        sync.run()
        val occ = rows().single()
        assertEquals(id, occ.id)
        assertEquals(moved, occ.scheduledAtUtc)
        assertEquals(moved, f.scheduler.armed.values.single().atUtcMs)
        assertEquals(0, f.db.calendarDao().links().single().misses)
    }

    @Test
    fun `a whole series moved later lands each instance on the next one's old slot without a clash`() = run {
        switchOn()
        reads(instance(), instance(begin = f.t0 + 2 * HOUR))
        sync.run()
        reads(instance(begin = f.t0 + 2 * HOUR), instance(begin = f.t0 + 3 * HOUR))
        sync.run()
        assertEquals(setOf(f.t0 + 2 * HOUR, f.t0 + 3 * HOUR), rows().map { it.scheduledAtUtc }.toSet())
        assertEquals(2, rows().size)
    }

    @Test
    fun `a cancellation takes the alarm down and is told as the calendar's`() = run {
        switchOn()
        reads(instance())
        sync.run()
        reads(instance(cancelled = true))
        sync.run()
        val occ = rows().single()
        assertEquals(OccurrenceState.SKIPPED, occ.state)
        assertEquals(SkipReason.CALENDAR_CANCELLED, occ.skipReason)
        assertTrue(f.scheduler.armed.isEmpty())
    }

    @Test
    fun `two successful syncs without it remove it, one does not`() = run {
        switchOn()
        reads(instance())
        sync.run()
        reads()
        sync.run()
        assertEquals(OccurrenceState.ARMED, rows().single().state)
        sync.run()
        assertEquals(SkipReason.CALENDAR_REMOVED, rows().single().skipReason)
    }

    @Test
    fun `a failed read changes nothing, counts no miss, and is logged`() = run {
        switchOn()
        reads(instance())
        sync.run()
        reader.result = CalendarRead.Failed(com.command.domain.calendar.ReadFailure.NO_CURSOR)
        assertEquals(CalendarSyncOutcome.RETRY, sync.run())
        assertEquals(CalendarSyncOutcome.RETRY, sync.run())
        assertEquals(OccurrenceState.ARMED, rows().single().state)
        assertEquals(0, f.db.calendarDao().links().single().misses)
        assertEquals(2, f.db.events().all().count { it.type == EventType.CALENDAR_READ_FAILED })
    }

    @Test
    fun `no calendars at all while some are known is a failed read`() = run {
        switchOn()
        reads(instance())
        sync.run()
        reader.result = CalendarRead.Ok(emptyList(), emptyList(), emptySet())
        assertEquals(CalendarSyncOutcome.RETRY, sync.run())
        assertEquals(OccurrenceState.ARMED, rows().single().state)
        assertTrue(f.db.calendarDao().anySelected())
    }

    @Test
    fun `the permission taken away mid-window leaves everything armed and is logged once`() = run {
        switchOn()
        reads(instance())
        sync.run()
        reader.permission = false
        assertEquals(CalendarSyncOutcome.PERMISSION_LOST, sync.run())
        assertEquals(CalendarSyncOutcome.PERMISSION_LOST, sync.run())
        assertEquals(OccurrenceState.ARMED, rows().single().state)
        assertEquals(1, f.scheduler.armed.size)
        assertEquals(1, f.db.calendarDao().linkCount())
        assertEquals(1, f.db.events().all().count { it.type == EventType.CALENDAR_PERMISSION_LOST })
    }

    @Test
    fun `withdrawing the consent skips what is imported and a later sync brings nothing back`() = run {
        switchOn()
        reads(instance())
        sync.run()
        consents.revoke(ConsentKey.CALENDAR_READ)
        assertEquals(SkipReason.CALENDAR_REMOVED, rows().single().skipReason)
        assertEquals(CalendarSyncOutcome.OFF, sync.run())
        assertEquals(1, rows().size)
    }

    @Test
    fun `a mission the person deleted does not come back`() = run {
        switchOn()
        reads(instance())
        sync.run()
        missions.delete(rows().single().missionId)
        assertTrue(rows().isEmpty())
        reads(instance(), instance(begin = f.t0 + 2 * HOUR))
        sync.run()
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a pause holds an imported occurrence back like any other`() = run {
        switchOn()
        missions.pause(2 * HOUR)
        reads(instance())
        sync.run()
        assertEquals(OccurrenceState.SCHEDULED, rows().single().state)
        assertTrue(f.scheduler.armed.isEmpty())
    }

    @Test
    fun `an imported mission cannot be edited`() = run {
        switchOn()
        reads(instance())
        sync.run()
        val mission = f.db.missions().get(rows().single().missionId)!!
        val result = missions.save(missions.draftOf(mission)!!.copy(title = "Mine now"))
        assertEquals(SaveResult.Invalid(setOf(DraftProblem.READ_ONLY)), result)
        assertEquals("Standup", f.db.missions().get(mission.id)!!.title)
    }

    @Test
    fun `overlaps are persisted, and cleared when the overlap goes away`() = run {
        switchOn()
        reads(instance(), instance(begin = f.t0 + HOUR + 60_000, event = 200, title = "Review"))
        sync.run()
        val ids = rows().map { it.id }.toSet()
        val conflict = f.db.calendarDao().conflicts().single()
        assertEquals(ids, setOf(conflict.occA, conflict.occB))
        assertEquals(1, missions.conflictsOf(rows().first().missionId))
        reads(instance(), instance(begin = f.t0 + 5 * HOUR, event = 200, title = "Review"))
        sync.run()
        assertFalse(f.db.calendarDao().conflicts().isNotEmpty())
    }

    private suspend fun assertOneArmedAt(at: Long) {
        val live = rows().filter { it.state == OccurrenceState.ARMED }
        assertEquals(listOf(at), live.map { it.scheduledAtUtc })
        assertEquals(listOf(at), f.scheduler.armed.values.map { it.atUtcMs })
    }

    @Test
    fun `a calendar deselected and selected again gets its alarm back`() = run {
        switchOn()
        reads(instance())
        sync.run()
        sync.setSelected(7, false)
        sync.run()
        assertTrue(f.scheduler.armed.isEmpty())
        sync.setSelected(7, true)
        sync.run()
        assertOneArmedAt(f.t0 + HOUR)
    }

    @Test
    fun `consent withdrawn and given again brings the coming alarms back`() = run {
        switchOn()
        reads(instance())
        sync.run()
        consents.revoke(ConsentKey.CALENDAR_READ)
        consents.grant(ConsentKey.CALENDAR_READ)
        sync.setSelected(7, true)
        sync.run()
        assertOneArmedAt(f.t0 + HOUR)
    }

    @Test
    fun `an instance the provider lost for two syncs and then shows again rings again`() = run {
        switchOn()
        reads(instance())
        sync.run()
        reads()
        sync.run()
        sync.run()
        assertTrue(f.scheduler.armed.isEmpty())
        reads(instance())
        sync.run()
        assertOneArmedAt(f.t0 + HOUR)
    }

    @Test
    fun `an instance can move onto the slot of a sibling the calendar took away`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        reads(instance(begin = a), instance(begin = b))
        sync.run()
        reads(instance(begin = a), instance(begin = b, cancelled = true))
        sync.run()
        reads(instance(begin = b, event = 555, original = 100, originalAt = a))
        sync.run()
        assertOneArmedAt(b)
    }

    /** The owner's decision: the event really is at a new time, and a missing alarm costs more than one more. */
    @Test
    fun `an event that has rung and is then moved later rings again at its new time, once`() = run {
        switchOn()
        val first = f.t0 + HOUR
        val later = f.t0 + 5 * HOUR
        reads(instance(begin = first))
        sync.run()
        val rung = rows().single()
        f.repo.apply(rung.id, com.command.domain.occurrence.OccurrenceCommand.Fire, com.command.domain.model.EventSource.SERVICE)

        sync.run()
        assertEquals("unmoved, what has rung is not imported again", 1, rows().size)

        reads(instance(begin = later))
        repeat(2) { sync.run() }

        assertEquals("what rang stays as it was", OccurrenceState.FIRING, f.db.occurrences().get(rung.id)!!.state)
        assertEquals(first, f.db.occurrences().get(rung.id)!!.scheduledAtUtc)
        val again = rows().filter { it.id != rung.id }
        assertEquals(listOf(later), again.map { it.scheduledAtUtc })
        assertEquals(OccurrenceState.ARMED, again.single().state)
        assertEquals(later, f.scheduler.armed.getValue("command://occurrence/${again.single().id}").atUtcMs)
    }

    @Test
    fun `a move onto a slot the person already answered gives no alarm at the old time`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        reads(instance(begin = a), instance(begin = b))
        sync.run()
        val taken = rows().single { it.scheduledAtUtc == b }
        f.repo.apply(taken.id, com.command.domain.occurrence.OccurrenceCommand.Skip(SkipReason.USER), com.command.domain.model.EventSource.USER)
        reads(instance(begin = b, event = 555, original = 100, originalAt = a))
        sync.run()
        assertTrue(f.scheduler.armed.isEmpty())
        assertEquals(SkipReason.USER, f.db.occurrences().get(taken.id)!!.skipReason)
    }

    @Test
    fun `a move onto a sibling that stays armed leaves no alarm at the old time, however often it syncs`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        reads(instance(begin = a), instance(begin = b))
        sync.run()
        val moving = rows().single { it.scheduledAtUtc == a }
        val sibling = rows().single { it.scheduledAtUtc == b }

        reads(instance(begin = b, event = 555, original = 100, originalAt = a), instance(begin = b))
        sync.run()

        assertOneArmedAt(b)
        assertEquals("the sibling rings for both", OccurrenceState.ARMED, f.db.occurrences().get(sibling.id)!!.state)
        assertEquals(SkipReason.CALENDAR_REMOVED, f.db.occurrences().get(moving.id)!!.skipReason)

        sync.run()
        assertOneArmedAt(b)
    }

    @Test
    fun `and when that sibling goes, the instance that moved there still rings`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        reads(instance(begin = a), instance(begin = b))
        sync.run()
        reads(instance(begin = b, event = 555, original = 100, originalAt = a), instance(begin = b))
        sync.run()

        reads(instance(begin = b, event = 555, original = 100, originalAt = a))
        repeat(3) { sync.run() }

        assertOneArmedAt(b)
    }

    @Test
    fun `a chain of moves that ends on a sibling that stays lands all it can in one sync`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        val c = f.t0 + 3 * HOUR
        reads(instance(begin = a), instance(begin = b), instance(begin = c))
        sync.run()

        reads(
            instance(begin = b, event = 555, original = 100, originalAt = a),
            instance(begin = c, event = 556, original = 100, originalAt = b),
            instance(begin = c),
        )
        sync.run()

        val live = rows().filter { it.state == OccurrenceState.ARMED }.map { it.scheduledAtUtc }.sorted()
        assertEquals(listOf(b, c), live)
        assertEquals(listOf(b, c), f.scheduler.armed.values.map { it.atUtcMs }.sorted())
    }

    @Test
    fun `two instances swapping times keep both alarms, and neither is given up`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        reads(instance(begin = a), instance(begin = b))
        sync.run()

        reads(
            instance(begin = b, event = 555, original = 100, originalAt = a),
            instance(begin = a, event = 556, original = 100, originalAt = b),
        )
        sync.run()

        assertEquals(listOf(a, b), rows().filter { it.state == OccurrenceState.ARMED }.map { it.scheduledAtUtc }.sorted())
        assertFalse(rows().any { it.state == OccurrenceState.SKIPPED })
    }

    @Test
    fun `two instances swapping times ring at both times, the later one after the earlier has rung`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        reads(instance(begin = a), instance(begin = b))
        sync.run()
        reads(
            instance(begin = b, event = 555, original = 100, originalAt = a),
            instance(begin = a, event = 556, original = 100, originalAt = b),
        )
        repeat(2) { sync.run() }
        val first = rows().single { it.scheduledAtUtc == a }
        val second = rows().single { it.scheduledAtUtc == b }

        f.clock.advance(HOUR + 1)
        f.repo.apply(first.id, com.command.domain.occurrence.OccurrenceCommand.Fire, com.command.domain.model.EventSource.SERVICE)
        repeat(2) { sync.run() }

        assertEquals("the later time still has its alarm", OccurrenceState.ARMED, f.db.occurrences().get(second.id)!!.state)
        assertEquals(b, f.db.occurrences().get(second.id)!!.scheduledAtUtc)
        assertEquals(b, f.scheduler.armed.getValue("command://occurrence/${second.id}").atUtcMs)
        assertEquals("what rang stays as it was", OccurrenceState.FIRING, f.db.occurrences().get(first.id)!!.state)
        assertEquals("nothing extra is made", 2, rows().size)
    }

    @Test
    fun `an instance moved again after a swap takes the alarm at its time, not the one it started with`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        val c = f.t0 + 3 * HOUR
        reads(instance(begin = a), instance(begin = b))
        sync.run()
        reads(
            instance(begin = b, event = 555, original = 100, originalAt = a),
            instance(begin = a, event = 556, original = 100, originalAt = b),
        )
        sync.run()

        reads(
            instance(begin = c, event = 555, original = 100, originalAt = a),
            instance(begin = a, event = 556, original = 100, originalAt = b),
        )
        sync.run()

        assertEquals(listOf(a, c), rows().filter { it.state == OccurrenceState.ARMED }.map { it.scheduledAtUtc }.sorted())
        assertEquals(listOf(a, c), f.scheduler.armed.values.map { it.atUtcMs }.sorted())
    }

    @Test
    fun `three instances going round all keep their alarms after the first has rung`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        val c = f.t0 + 3 * HOUR
        reads(instance(begin = a), instance(begin = b), instance(begin = c))
        sync.run()
        reads(
            instance(begin = b, event = 555, original = 100, originalAt = a),
            instance(begin = c, event = 556, original = 100, originalAt = b),
            instance(begin = a, event = 557, original = 100, originalAt = c),
        )
        sync.run()
        val first = rows().single { it.scheduledAtUtc == a }

        f.clock.advance(HOUR + 1)
        f.repo.apply(first.id, com.command.domain.occurrence.OccurrenceCommand.Fire, com.command.domain.model.EventSource.SERVICE)
        repeat(2) { sync.run() }

        assertEquals(listOf(b, c), rows().filter { it.state == OccurrenceState.ARMED }.map { it.scheduledAtUtc }.sorted())
        assertEquals(3, rows().size)
    }

    @Test
    fun `a move onto a swapped pair is given up, and the pair keeps its alarms`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        val c = f.t0 + 3 * HOUR
        reads(instance(begin = a), instance(begin = b), instance(begin = c))
        sync.run()
        val third = rows().single { it.scheduledAtUtc == c }
        reads(
            instance(begin = b, event = 555, original = 100, originalAt = a),
            instance(begin = a, event = 556, original = 100, originalAt = b),
            instance(begin = a, event = 557, original = 100, originalAt = c),
        )
        sync.run()

        assertEquals(listOf(a, b), rows().filter { it.state == OccurrenceState.ARMED }.map { it.scheduledAtUtc }.sorted())
        assertEquals(OccurrenceState.SKIPPED, f.db.occurrences().get(third.id)!!.state)
        assertEquals(setOf(a, b), f.scheduler.armed.values.map { it.atUtcMs }.toSet())
    }

    @Test
    fun `instances moved along a chain all land`() = run {
        switchOn()
        val a = f.t0 + HOUR
        val b = f.t0 + 2 * HOUR
        val c = f.t0 + 3 * HOUR
        reads(instance(begin = a), instance(begin = b))
        sync.run()
        reads(instance(begin = b, event = 501, original = 100, originalAt = a), instance(begin = c, event = 502, original = 100, originalAt = b))
        sync.run()
        assertEquals(setOf(b, c), f.scheduler.armed.values.map { it.atUtcMs }.toSet())
    }

    @Test
    fun `a selected calendar is still not read without the consent`() = run {
        f.db.calendarDao().insertSource(CalendarSourceRow(7, work.account, work.displayName, true, null, Flexibility.FLEXIBLE, null))
        reads(instance())
        assertEquals(CalendarSyncOutcome.OFF, sync.run())
        assertEquals(0, reader.reads)
    }

    @Test
    fun `the calendar list is not read without the consent`() = run {
        reads()
        sync.refreshSources()
        assertTrue(f.db.calendarDao().sources().isEmpty())
    }

    @Test
    fun `an exception at the same time survives further syncs and reaches the stored link`() = run {
        switchOn()
        reads(instance())
        sync.run()
        reads(instance(event = 555, original = 100, originalAt = f.t0 + HOUR))
        repeat(3) { sync.run() }
        assertOneArmedAt(f.t0 + HOUR)
        assertEquals(100L, f.db.calendarDao().links().single().originalId)
    }

    @Test
    fun `a new instance on the slot another one is leaving gets its own alarm`() = run {
        switchOn()
        val ten = f.t0 + HOUR
        val eleven = f.t0 + 2 * HOUR
        reads(instance(begin = ten))
        sync.run()
        // The one at ten moves to eleven; a new instance of the same event appears, with its own identity, at ten.
        reads(instance(begin = eleven, event = 555, original = 100, originalAt = ten), instance(begin = ten, event = 556, original = 100, originalAt = f.t0 + 5 * HOUR))
        sync.run()
        assertEquals(setOf(ten, eleven), f.scheduler.armed.values.map { it.atUtcMs }.toSet())
        assertEquals(2, f.db.calendarDao().linkCount())
    }

    @Test
    fun `a choice made during a read waits until the sync that read is over`() = run {
        switchOn()
        reads(instance())
        var selectedDuringRead = false
        var change: kotlinx.coroutines.Job? = null
        reader.duringRead = {
            change = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { sync.setSelected(7, false) }
            kotlinx.coroutines.delay(300)
            selectedDuringRead = f.db.calendarDao().anySelected()
            reader.duringRead = {}
        }
        sync.run()
        change!!.join()
        assertTrue(selectedDuringRead)
        assertFalse(f.db.calendarDao().anySelected())
    }

    @Test
    fun `consent withdrawn while the calendar list is read stores no calendar`() = run {
        consents.grant(ConsentKey.CALENDAR_READ)
        reads()
        reader.duringRead = { consents.revoke(ConsentKey.CALENDAR_READ) }
        sync.refreshSources()
        assertTrue(f.db.calendarDao().sources().isEmpty())
    }

    @Test
    fun `allowing lists the calendars once the consent is written`() = run {
        reads()
        sync.allow()
        assertTrue(consents.isGranted(ConsentKey.CALENDAR_READ))
        assertEquals(listOf("Work"), f.db.calendarDao().sources().map { it.displayName })
    }

    @Test
    fun `a move reaches the alarm even if the reconcile after it fails`() = run {
        switchOn()
        reads(instance())
        sync.run()
        // A reconcile that dies when it asks the platform about an alarm; the sync's own repository still reaches the real one.
        val failing = object : com.command.domain.alarm.AlarmScheduler by f.scheduler {
            override fun isArmed(request: com.command.domain.alarm.AlarmRequest): Boolean = error("the platform went away")
        }
        val broken = CalendarSync(f.db, f.repo, MissionRepository(f.db, f.repo, failing, f.clock, f.pauseEffects), consents, reader, f.clock)
        val moved = f.t0 + 3 * HOUR
        reads(instance(begin = moved, event = 555, original = 100, originalAt = f.t0 + HOUR))
        assertTrue(runCatching { broken.run() }.isFailure)
        assertEquals(moved, rows().single().scheduledAtUtc)
        assertEquals(moved, f.scheduler.armed.values.single().atUtcMs)
    }
}
