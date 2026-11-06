package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.MissionRow
import com.command.data.db.OccurrenceRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.RecoveryChoice
import com.command.domain.occurrence.SkipReason
import com.command.domain.occurrence.StarterWindow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The starter's deadline in the session, and the reads the screens build on. Runs on API 24 as well as 36: the same SQL must hold on the oldest SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class StarterRepositoryTest {

    private lateinit var f: Fixture
    private val minute = 60_000L

    @Before fun setUp() { f = Fixture(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun tearDown() { f.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun apply(o: OccurrenceRow, c: OccurrenceCommand) = f.repo.apply(o.id, c, EventSource.USER)

    private suspend fun ringing(m: MissionRow, at: Long = f.t0 + minute): OccurrenceRow {
        val o = f.repo.createOccurrence(m.id, at, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        return f.repo.get(o.id)!!
    }

    private suspend fun guided(m: MissionRow, at: Long = f.t0 + minute): OccurrenceRow {
        val o = ringing(m, at)
        apply(o, OccurrenceCommand.Acknowledge())
        apply(o, OccurrenceCommand.StartGuidance)
        return f.repo.get(o.id)!!
    }

    private fun checkInAlarms() = f.scheduler.armed.values.filter { it.kind == AlarmKind.CHECK_IN }.map { it.index to it.atUtcMs }

    // --- the one command ---------------------------------------------------------------------------------------

    @Test
    fun `beginning the starter stores its deadline in the session through the one writer`() = run {
        val o = guided(f.mission())
        assertNull(f.repo.starterOf(o))

        val result = apply(o, OccurrenceCommand.BeginStarter)

        assertTrue(result is ApplyResult.Applied)
        val row = f.repo.get(o.id)!!
        assertEquals(OccurrenceState.GUIDED_START, row.state)
        assertEquals(StarterWindow(f.t0, f.t0 + 5 * minute), f.repo.starterOf(row))
        assertEquals(f.t0 + 5 * minute, Codec.decodeSession(row.sessionJson).starterDeadlineUtc)
        assertEquals(o.stateVersion + 1, row.stateVersion)
        assertEquals("5", f.db.events().forOccurrence(o.id).single { it.type == EventType.STARTER_BEGUN }.let { Codec.decodePayload(it.payloadJson)["minutes"] })
    }

    @Test
    fun `the check-in schedule is exactly what it was before the starter`() = run {
        val o = guided(f.mission())
        val before = checkInAlarms()
        assertTrue("guidance has set the first question", before.isNotEmpty())
        val calls = f.scheduler.calls.size

        f.clock.advance(minute)
        apply(o, OccurrenceCommand.BeginStarter)

        assertEquals(before, checkInAlarms())
        assertEquals("no alarm was touched", calls, f.scheduler.calls.size)
    }

    @Test
    fun `a second tap keeps the first deadline and logs the refusal`() = run {
        val o = guided(f.mission())
        apply(o, OccurrenceCommand.BeginStarter)
        f.clock.advance(minute)

        val second = apply(o, OccurrenceCommand.BeginStarter)

        assertTrue(second is ApplyResult.Rejected)
        assertEquals(f.t0 + 5 * minute, f.repo.starterOf(f.repo.get(o.id)!!)?.deadlineUtc)
    }

    @Test
    fun `the deadline survives the commands that follow, and a recovery restart clears it`() = run {
        val o = guided(f.mission())
        apply(o, OccurrenceCommand.BeginStarter)
        apply(o, OccurrenceCommand.CheckInDue(0))
        assertEquals(f.t0 + 5 * minute, f.repo.starterOf(f.repo.get(o.id)!!)?.deadlineUtc)

        apply(o, OccurrenceCommand.RequestRecovery)
        apply(o, OccurrenceCommand.AcceptRecovery(RecoveryChoice.RESET))

        val row = f.repo.get(o.id)!!
        assertEquals(OccurrenceState.GUIDED_START, row.state)
        assertNull(f.repo.starterOf(row))
    }

    @Test
    fun `the length is the one the mission has when the starter begins`() = run {
        val m = f.mission().copy(starterMinutes = 2)
        f.db.missions().update(m)
        val o = guided(m)
        apply(o, OccurrenceCommand.BeginStarter)
        assertEquals(2, f.repo.starterOf(f.repo.get(o.id)!!)?.minutes)
    }

    @Test
    fun `a session that has only half of the starter has none`() = run {
        val o = guided(f.mission())
        assertNull(f.repo.starterOf(o.copy(sessionJson = Codec.encodeSession(SessionData(starterDeadlineUtc = f.t0)))))
        assertNull(f.repo.starterOf(o.copy(sessionJson = Codec.encodeSession(SessionData(starterStartUtc = f.t0)))))
    }

    // --- acknowledged without a start, N times -------------------------------------------------------------------

    private suspend fun count(m: MissionRow, before: Long = Long.MAX_VALUE) = f.repo.acknowledgedWithoutStart(m.id, before)

    @Test
    fun `each acknowledgement with no start counts`() = run {
        val m = f.mission()
        assertEquals(0, count(m))
        guided(m, f.t0 + minute)
        assertEquals(1, count(m))
        guided(m, f.t0 + 2 * minute)
        assertEquals(2, count(m))
    }

    @Test
    fun `a reported start ends the run, whichever report it was`() = run {
        for (report in listOf(OccurrenceCommand.ReportStarted, OccurrenceCommand.ReportWorking, OccurrenceCommand.ReportDone)) {
            val m = f.mission()
            guided(m, f.t0 + minute)
            val started = guided(m, f.t0 + 2 * minute)
            apply(started, report)
            assertEquals(report.label, 0, count(m))
            guided(m, f.t0 + 3 * minute)
            assertEquals(report.label, 1, count(m))
        }
    }

    @Test
    fun `a start reported on an earlier occurrence does not wipe out what came after it`() = run {
        val m = f.mission()
        apply(guided(m, f.t0 + minute), OccurrenceCommand.ReportStarted)
        guided(m, f.t0 + 2 * minute)
        guided(m, f.t0 + 3 * minute)
        assertEquals(2, count(m))
    }

    @Test
    fun `a silence that was picked up again was never acknowledged`() = run {
        val m = f.mission()
        val o = ringing(m)
        apply(o, OccurrenceCommand.SafetySilence)
        apply(o, OccurrenceCommand.Resume)
        assertEquals(0, count(m))
    }

    @Test
    fun `the starter is not a start, and neither is a skip`() = run {
        val m = f.mission()
        val first = guided(m, f.t0 + minute)
        apply(first, OccurrenceCommand.BeginStarter)
        val second = guided(m, f.t0 + 2 * minute)
        apply(second, OccurrenceCommand.Skip(SkipReason.USER))
        assertEquals(2, count(m))
    }

    @Test
    fun `an acknowledgement put off counts, and so does the delayed ring acknowledged after it`() = run {
        val m = f.mission()
        apply(guided(m), OccurrenceCommand.Delay(10))
        assertEquals(1, count(m))

        val child = f.db.occurrences().find(m.id, f.t0 + 10 * minute)!!
        apply(child, OccurrenceCommand.Fire)
        apply(child, OccurrenceCommand.Acknowledge())
        assertEquals("the delayed ring being asked about has not had its chance", 1, count(m, child.scheduledAtUtc))
        assertEquals(2, count(m))

        apply(child, OccurrenceCommand.StartGuidance)
        apply(child, OccurrenceCommand.ReportStarted)
        assertEquals("a start on the delayed ring ends the run", 0, count(m))
    }

    @Test
    fun `other missions do not count, nor does the occurrence being asked about, nor anything later`() = run {
        val m = f.mission()
        val other = f.mission("Another")
        guided(other)
        guided(m, f.t0 + minute)
        guided(m, f.t0 + 2 * minute)
        guided(m, f.t0 + 3 * minute)
        assertEquals("before the second: only the first", 1, count(m, f.t0 + 2 * minute))
        assertEquals(1, count(other))
    }

    @Test
    fun `a start later than the moment asked about does not end the run before it`() = run {
        val m = f.mission()
        guided(m, f.t0 + minute)
        guided(m, f.t0 + 2 * minute)
        apply(guided(m, f.t0 + 3 * minute), OccurrenceCommand.ReportStarted)
        assertEquals(2, count(m, f.t0 + 3 * minute))
    }

    // --- whether the line was heard --------------------------------------------------------------------------------

    private suspend fun notSpoken(o: OccurrenceRow) = f.repo.observeLineNotSpoken(o.id).first()

    @Test
    fun `a ring with nothing written about its voice is not reported as unspoken`() = run {
        assertFalse(notSpoken(ringing(f.mission())))
    }

    @Test
    fun `the phone that could not speak counts as not spoken`() = run {
        val o = ringing(f.mission())
        f.repo.logEvent(EventType.TTS_UNAVAILABLE, o, EventSource.SERVICE, EventPayload.of("reason" to "NO_ENGINE"))
        assertTrue(notSpoken(o))
    }

    @Test
    fun `a route that said no counts as not spoken, and one that spoke does not`() = run {
        val refused = ringing(f.mission(), f.t0 + minute)
        f.repo.logEvent(EventType.AUDIO_ROUTE, refused, EventSource.SERVICE, EventPayload.of("devices" to "speaker", "policy" to "HEADSET_ONLY", "spoke" to false))
        assertTrue(notSpoken(refused))

        val spoken = ringing(f.mission("Heard"), f.t0 + 2 * minute)
        f.repo.logEvent(EventType.AUDIO_ROUTE, spoken, EventSource.SERVICE, EventPayload.of("devices" to "speaker", "policy" to "ALWAYS", "spoke" to true))
        assertFalse(notSpoken(spoken))
    }

    @Test
    fun `another ring's silence says nothing about this one`() = run {
        val silent = ringing(f.mission("Silent"), f.t0 + minute)
        val other = ringing(f.mission("Other"), f.t0 + 2 * minute)
        f.repo.logEvent(EventType.TTS_UNAVAILABLE, silent, EventSource.SERVICE, EventPayload.of("reason" to "NO_ENGINE"))
        assertFalse(notSpoken(other))
    }

    // --- the handshake line --------------------------------------------------------------------------------------

    @Test
    fun `the handshake counts as just passed after an acknowledgement, not after a resumed silence, and not after a recovery`() = run {
        val passed = guided(f.mission())
        assertTrue(f.repo.handshakeJustPassed(passed))

        val resumed = ringing(f.mission("Silenced"), f.t0 + 2 * minute)
        apply(resumed, OccurrenceCommand.SafetySilence)
        apply(resumed, OccurrenceCommand.Resume)
        assertFalse(f.repo.handshakeJustPassed(f.repo.get(resumed.id)!!))

        apply(passed, OccurrenceCommand.RequestRecovery)
        apply(passed, OccurrenceCommand.AcceptRecovery(RecoveryChoice.RESET))
        assertFalse(f.repo.handshakeJustPassed(f.repo.get(passed.id)!!))
    }
}
