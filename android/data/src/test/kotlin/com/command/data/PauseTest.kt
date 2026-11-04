package com.command.data

import androidx.test.core.app.ApplicationProvider
import com.command.data.db.OccurrenceRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.dials.Flexibility
import com.command.domain.model.EventSource
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** The global pause: nothing due inside it rings, and when it ends what it held back is settled, never blamed on the person. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PauseTest {

    private lateinit var f: Fixture
    private lateinit var missions: MissionRepository

    private val hour = 3_600_000L
    private val minute = 60_000L

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

    private fun draft(title: String = "Write the report", schedule: ScheduleDraft = ScheduleDraft.Once(at(2 * hour))) =
        MissionDraft(title = title, firstAction = "Open the document", flexibility = Flexibility.FLEXIBLE, schedule = schedule)

    private suspend fun saved(d: MissionDraft): String = (missions.save(d) as SaveResult.Saved).missionId

    private suspend fun occurrencesOf(missionId: String) = f.db.occurrences().forMission(missionId)

    private fun fireIdentity(occurrenceId: String) = "command://occurrence/$occurrenceId"

    private fun fields(json: String): Map<String, String> = Codec.decodePayload(json)

    /** A flexible mission with one occurrence [offsetMs] after the fixed "now", not armed. */
    private suspend fun occurrenceAt(offsetMs: Long): OccurrenceRow {
        val mission = f.mission()
        return f.repo.createOccurrence(mission.id, f.t0 + offsetMs, "UTC").occurrence
    }

    private suspend fun apply(o: OccurrenceRow, c: OccurrenceCommand) = f.repo.apply(o.id, c, EventSource.SYSTEM)

    // --- starting ----------------------------------------------------------------------------------------------

    @Test
    fun `pausing takes down the alarms that fall inside it and leaves the ones after it armed`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Repeating(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), RRule(Frequency.DAILY))))

        val pause = missions.pause(12 * hour)

        assertEquals(Pause(f.t0, f.t0 + 12 * hour), pause)
        val all = occurrencesOf(id)
        assertEquals(OccurrenceState.SCHEDULED, all.first().state) // today at 10:00 is inside the pause
        assertEquals(ArmMode.NONE, all.first().armMode)
        assertNull(f.scheduler.armed[fireIdentity(all.first().id)])
        assertTrue(all.drop(1).all { it.state == OccurrenceState.ARMED })
        assertEquals(6, f.scheduler.armed.keys.count { it.startsWith("command://occurrence/") })
        assertEquals(listOf(f.t0 + 12 * hour), f.pauseEffects.startedUntil)
        val global = f.db.events().ofType(EventType.PAUSED).single { it.occurrenceId == null }
        assertEquals("1", fields(global.payloadJson)["disarmed"])
        assertEquals(1, f.db.events().ofType(EventType.PAUSED).count { it.occurrenceId == all.first().id })
    }

    @Test
    fun `a ring that is going is not touched by a pause`() = run {
        val o = occurrenceAt(60_000)
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)

        missions.pause(hour)

        assertEquals(OccurrenceState.FIRING, f.repo.get(o.id)!!.state)
    }

    @Test
    fun `pausing again moves the end and keeps the start`() = run {
        missions.pause(hour)
        f.clock.advance(30 * minute)

        val again = missions.pause(4 * hour)

        assertEquals(Pause(f.t0, f.t0 + 30 * minute + 4 * hour), again)
        assertEquals(again, missions.observePause().first())
        assertEquals(listOf(f.t0 + hour, again.untilUtcMs), f.pauseEffects.startedUntil)
    }

    @Test
    fun `a pause shorter than a minute or longer than a day is refused`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { missions.pause(59_000) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { missions.pause(25 * hour) } }
        assertNull(runBlocking { missions.observePause().first() })
        assertTrue(f.pauseEffects.startedUntil.isEmpty())
    }

    // --- while it lasts ----------------------------------------------------------------------------------------

    @Test
    fun `a mission saved during a pause is not armed for what falls inside it`() = run {
        missions.pause(4 * hour)

        val inside = saved(draft(title = "Inside", schedule = ScheduleDraft.Once(at(2 * hour))))
        val after = saved(draft(title = "After", schedule = ScheduleDraft.Once(at(6 * hour))))

        val held = occurrencesOf(inside).single()
        assertEquals(OccurrenceState.SCHEDULED, held.state)
        assertNull(f.scheduler.armed[fireIdentity(held.id)])
        assertEquals(OccurrenceState.ARMED, occurrencesOf(after).single().state)
    }

    @Test
    fun `a reconcile during a pause neither arms nor misses what the pause holds back`() = run {
        missions.pause(4 * hour)
        val id = saved(draft(schedule = ScheduleDraft.Once(at(2 * hour))))
        f.clock.advance(3 * hour) // the alarm was due an hour ago, inside the pause

        val report = missions.reconcile(ReconcileTrigger.OPEN)

        assertEquals(0, report.missed)
        assertEquals(OccurrenceState.SCHEDULED, occurrencesOf(id).single().state)
        assertTrue(f.scheduler.armed.isEmpty())
    }

    @Test
    fun `a delay that lands inside a pause is held back like any other alarm`() = run {
        val o = occurrenceAt(60_000)
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        missions.pause(2 * hour)

        apply(o, OccurrenceCommand.Delay(30))

        val child = f.db.occurrences().forMission(o.missionId).single { it.parentOccurrenceId == o.id }
        assertEquals(OccurrenceState.SCHEDULED, child.state)
        assertNull(f.scheduler.armed[fireIdentity(child.id)])
    }

    @Test
    fun `a follow-up question that would fall inside a pause waits for its end`() = run {
        val o = occurrenceAt(60_000)
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge())
        missions.pause(2 * hour)

        apply(o, OccurrenceCommand.StartGuidance)

        val firstQuestion = f.scheduler.armed.values.single { it.kind == AlarmKind.CHECK_IN }
        // The steady profile asks 10 minutes after guidance begins; here that is 10 minutes after the pause ends.
        assertEquals(f.t0 + 2 * hour + 10 * minute, firstQuestion.atUtcMs)
        assertTrue(f.db.events().ofType(EventType.FOLLOWUP_SUPPRESSED).isNotEmpty())
    }

    @Test
    fun `a test alarm still rings during a pause`() = run {
        missions.pause(hour)

        val id = missions.testRing()
        assertEquals(OccurrenceState.ARMED, f.repo.get(id)!!.state)
        f.clock.advance(5_000)

        assertTrue("and it rings when it is due", f.repo.apply(id, OccurrenceCommand.Fire, EventSource.SERVICE) is ApplyResult.Applied)
        assertEquals(OccurrenceState.FIRING, f.repo.get(id)!!.state)
    }

    @Test
    fun `an alarm that the platform delivers after the pause began does not ring, whatever the order of the two`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Once(at(hour))))
        val o = occurrencesOf(id).single()
        assertEquals(OccurrenceState.ARMED, o.state)
        // The pause is written, but the alarms it takes down have not been reached yet.
        PauseStore.write(f.db, Pause(f.t0, f.t0 + 4 * hour))

        val delivered = f.repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)

        assertTrue(delivered is ApplyResult.Rejected)
        assertEquals("it did not start to ring", OccurrenceState.ARMED, f.repo.get(o.id)!!.state)
        val report = missions.reconcile(ReconcileTrigger.OPEN)
        assertEquals("the next reconcile takes it down", 1, report.paused)
        assertEquals(OccurrenceState.SCHEDULED, f.repo.get(o.id)!!.state)
    }

    // --- ending ------------------------------------------------------------------------------------------------

    @Test
    fun `ending the pause records what came due inside it as missed because of it and arms what is still to come`() = run {
        val past = saved(draft(title = "Past", schedule = ScheduleDraft.Once(at(hour))))
        val soon = saved(draft(title = "Soon", schedule = ScheduleDraft.Once(at(10 * hour))))
        missions.pause(24 * hour)
        assertEquals(OccurrenceState.SCHEDULED, occurrencesOf(soon).single().state)
        f.clock.advance(5 * hour)

        missions.resume()

        val missed = occurrencesOf(past).single()
        assertEquals(OccurrenceState.MISSED, missed.state)
        assertEquals(MissReason.PAUSED, missed.missReason)
        val armed = occurrencesOf(soon).single()
        assertEquals(OccurrenceState.ARMED, armed.state)
        assertNotNull(f.scheduler.armed[fireIdentity(armed.id)])
        assertNull(missions.observePause().first())
        assertEquals(1, f.pauseEffects.resumptions)
        assertEquals(0, f.pauseEffects.expirations)
        val ended = f.db.events().ofType(EventType.UNPAUSED).single()
        assertEquals("RESUMED", fields(ended.payloadJson)["how"])
        assertEquals("1", fields(ended.payloadJson)["swallowed"])
        assertTrue("nothing was blamed on the phone being off", f.db.events().ofType(EventType.MISSED_WHILE_OFF).isEmpty())
    }

    @Test
    fun `a pause that ran out is closed by the next reconcile`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Once(at(2 * hour))))
        missions.pause(4 * hour)
        f.clock.advance(5 * hour) // past the end, and the alarm was due three hours ago

        val report = missions.reconcile(ReconcileTrigger.PAUSE_ENDED)

        val o = occurrencesOf(id).single()
        assertEquals(OccurrenceState.MISSED, o.state)
        assertEquals(MissReason.PAUSED, o.missReason)
        assertEquals(0, report.missed) // the phone was not off; the pause held it back
        assertEquals(1, report.swallowed)
        assertEquals(0, report.paused)
        assertEquals(1, f.pauseEffects.expirations)
        assertEquals(0, f.pauseEffects.resumptions)
        assertNull(missions.observePause().first())
    }

    @Test
    fun `an alarm that was due before the pause began is not blamed on it`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Once(at(hour))))
        f.clock.advance(2 * hour) // due an hour ago and never delivered
        missions.pause(hour)
        f.clock.advance(30 * minute)

        missions.resume()

        val o = occurrencesOf(id).single()
        assertEquals(OccurrenceState.MISSED, o.state)
        assertEquals(MissReason.DOWNTIME, o.missReason)
    }

    @Test
    fun `resuming when there is no pause changes nothing`() = run {
        val report = missions.resume()

        assertEquals(ReconcileReport(), report)
        assertTrue(f.db.events().ofType(EventType.UNPAUSED).isEmpty())
        assertEquals(0, f.pauseEffects.resumptions)
    }

    // --- what a pause must not get wrong --------------------------------------------------------------------------

    @Test
    fun `shortening a pause arms again what only the longer one held back`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Once(at(2 * hour))))
        missions.pause(12 * hour)
        assertEquals(OccurrenceState.SCHEDULED, occurrencesOf(id).single().state)

        missions.pause(hour)

        val o = occurrencesOf(id).single()
        assertEquals(OccurrenceState.ARMED, o.state)
        assertNotNull(f.scheduler.armed[fireIdentity(o.id)])
    }

    @Test
    fun `a test alarm that is waiting is neither taken down by a pause nor by a reconcile inside it`() = run {
        val id = missions.testRing()

        missions.pause(hour)
        missions.reconcile(ReconcileTrigger.OPEN)

        assertEquals(OccurrenceState.ARMED, f.repo.get(id)!!.state)
        assertNotNull(f.scheduler.armed[fireIdentity(id)])
    }

    @Test
    fun `renaming a mission during a pause keeps what the pause is holding back, to be settled when it ends`() = run {
        val daily = ScheduleDraft.Repeating(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), RRule(Frequency.DAILY))
        val id = saved(draft(schedule = daily))
        missions.pause(12 * hour)
        f.clock.advance(2 * hour) // 11:00: the 10:00 one is due, and held

        missions.save(draft(title = "Renamed", schedule = daily).copy(id = id))

        val held = occurrencesOf(id).first()
        assertEquals(f.t0 + hour, held.scheduledAtUtc)
        assertEquals(OccurrenceState.SCHEDULED, held.state)
        missions.resume()
        val settled = f.repo.get(held.id)!!
        assertEquals(OccurrenceState.MISSED, settled.state)
        assertEquals(MissReason.PAUSED, settled.missReason)
    }

    @Test
    fun `an alarm the pause never got to take down is settled as paused too, and not as the phone being off`() = run {
        val id = saved(draft(schedule = ScheduleDraft.Once(at(2 * hour))))
        PauseStore.write(f.db, Pause(f.t0, f.t0 + 4 * hour)) // as if the app had stopped before taking it down
        f.clock.advance(5 * hour)

        missions.reconcile(ReconcileTrigger.PAUSE_ENDED)

        val o = occurrencesOf(id).single()
        assertEquals(OccurrenceState.MISSED, o.state)
        assertEquals(MissReason.PAUSED, o.missReason)
    }

    @Test
    fun `a reboot clears the notice, so the reconcile after it puts it back, and an ordinary one does not`() = run {
        missions.pause(4 * hour)
        assertEquals(1, f.pauseEffects.startedUntil.size)

        missions.reconcile(ReconcileTrigger.OPEN)
        assertEquals(1, f.pauseEffects.startedUntil.size)

        missions.reconcile(ReconcileTrigger.BOOT)
        assertEquals(listOf(f.t0 + 4 * hour, f.t0 + 4 * hour), f.pauseEffects.startedUntil)
    }
}
