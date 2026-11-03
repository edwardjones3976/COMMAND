package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.OccurrenceRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.dials.Dials
import com.command.domain.dials.Flexibility
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.LockPolicy
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.domain.occurrence.WorkKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** Runs on API 24 as well as 36: the same SQL must behave on the oldest SQLite the app supports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class OccurrenceRepositoryTest {

    private lateinit var f: Fixture

    @Before fun setUp() { f = Fixture(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun tearDown() { f.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun scheduled(missionTitle: String = "Write the report", atOffsetMs: Long = 60_000): OccurrenceRow {
        val m = f.mission(missionTitle)
        return f.repo.createOccurrence(m.id, f.t0 + atOffsetMs, "UTC").occurrence
    }

    private suspend fun apply(o: OccurrenceRow, c: OccurrenceCommand, extras: ApplyExtras = ApplyExtras.NONE) =
        f.repo.apply(o.id, c, EventSource.USER, extras)

    private suspend fun state(o: OccurrenceRow) = f.repo.get(o.id)!!.state

    // --- creation ----------------------------------------------------------------------------------------------

    @Test
    fun `creating the same occurrence twice returns the first, and request codes are unique and increasing`() = run {
        val m = f.mission()
        val a = f.repo.createOccurrence(m.id, f.t0 + 1_000, "UTC")
        val again = f.repo.createOccurrence(m.id, f.t0 + 1_000, "UTC")
        val b = f.repo.createOccurrence(m.id, f.t0 + 2_000, "UTC")
        assertTrue(a.isNew)
        assertFalse(again.isNew)
        assertEquals(a.occurrence.id, again.occurrence.id)
        assertEquals(a.occurrence.requestCode, again.occurrence.requestCode)
        assertEquals(a.occurrence.requestCode + 1, b.occurrence.requestCode)
        assertEquals(OccurrenceState.SCHEDULED, a.occurrence.state)
        assertEquals(ArmMode.NONE, a.occurrence.armMode)
    }

    @Test
    fun `the database is seeded with the four built-in profiles and the settings the app relies on`() = run {
        val profiles = f.db.profiles().all().map { it.id }
        assertEquals(setOf("builtin_nudge", "builtin_steady", "builtin_command", "builtin_reveille_max"), profiles.toSet())
        assertEquals("\"builtin_steady\"", f.db.settings().get("active_profile_id"))
        assertEquals("1", f.db.settings().get("next_request_code"))
    }

    // --- the alarm lifecycle --------------------------------------------------------------------------------

    @Test
    fun `arming records the alarm, the work and an event`() = run {
        val o = scheduled()
        val r = apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        assertTrue(r is ApplyResult.Applied)
        val row = f.repo.get(o.id)!!
        assertEquals(OccurrenceState.ARMED, row.state)
        assertEquals(1L, row.stateVersion)
        assertEquals(ArmMode.EXACT, row.armMode)
        assertEquals(setOf("command://occurrence/${o.id}"), f.scheduler.armed.keys)
        assertEquals(listOf(WorkKind.PRERENDER to o.id, WorkKind.PREFETCH to o.id), f.work.enqueued)
        assertTrue(f.db.events().forOccurrence(o.id).any { it.type == EventType.ALARM_ARMED })
    }

    @Test
    fun `an inexact result from the platform is stored and logged as adjusted`() = run {
        f.scheduler.exactAllowed = false
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)) // the caller asked for exact; the platform could only do inexact
        assertEquals(ArmMode.INEXACT, f.repo.get(o.id)!!.armMode)
        assertTrue(f.db.events().forOccurrence(o.id).any { it.type == EventType.ALARM_ARMED && it.payloadJson.contains("adjusted") })
    }

    @Test
    fun `firing arms the cycle-end backstop and remembers when the ring plan ends`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        val row = f.repo.get(o.id)!!
        assertEquals(OccurrenceState.FIRING, row.state)
        val backstop = f.scheduler.armed.values.single { it.kind == AlarmKind.CYCLE_END }
        assertEquals(f.t0 + 2 * 60_000L, backstop.atUtcMs)
        assertEquals(f.t0 + 2 * 60_000L, Codec.decodeSession(row.sessionJson).planEndUtc)
        assertEquals(f.clock.elapsedMs + 2 * 60_000L, Codec.decodeSession(row.sessionJson).planEndElapsed)
        assertEquals(1, f.roller.rolls)
    }

    @Test
    fun `acknowledging cancels the backstop and stores the challenge attempts`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        val attempts = listOf(
            ChallengeRecord(0, HandshakeMode.TYPING, 1, ChallengeOutcome.FAILED, f.t0, f.t0 + 4_000),
            ChallengeRecord(0, HandshakeMode.TYPING, 2, ChallengeOutcome.SUCCESS, f.t0 + 4_000, f.t0 + 9_000),
        )
        apply(o, OccurrenceCommand.Acknowledge(), ApplyExtras(challenges = attempts))
        assertEquals(OccurrenceState.ACKNOWLEDGED, state(o))
        assertTrue(f.scheduler.armed.values.none { it.kind == AlarmKind.CYCLE_END })
        val rows = f.db.challenges().forOccurrence(o.id)
        assertEquals(listOf(ChallengeOutcome.FAILED, ChallengeOutcome.SUCCESS), rows.map { it.outcome })
    }

    @Test
    fun `a stale or illegal command changes nothing and is logged`() = run {
        val o = scheduled()
        val r = apply(o, OccurrenceCommand.Fire) // still SCHEDULED, never armed
        assertTrue(r is ApplyResult.Rejected)
        assertEquals(OccurrenceState.SCHEDULED, state(o))
        assertEquals(0L, f.repo.get(o.id)!!.stateVersion)
        assertTrue(f.scheduler.armed.isEmpty())
        val rejected = f.db.events().ofType(EventType.REJECTED_TRANSITION).single()
        assertEquals(o.id, rejected.occurrenceId)
    }

    @Test
    fun `compare-and-set refuses a writer that read an older version`() = run {
        val o = scheduled()
        val dao = f.db.occurrences()
        val first = dao.compareAndSet(o.id, 0, OccurrenceState.ARMED, "{}", null, null, ArmMode.EXACT, null)
        val stale = dao.compareAndSet(o.id, 0, OccurrenceState.SKIPPED, "{}", SkipReason.USER, null, ArmMode.NONE, null)
        assertEquals(1, first)
        assertEquals("a second writer holding version 0 must lose", 0, stale)
        assertEquals(OccurrenceState.ARMED, state(o))
    }

    @Test
    fun `concurrent applies serialise and exactly one of two competing fires wins`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        val results = kotlinx.coroutines.coroutineScope {
            (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { apply(o, OccurrenceCommand.Fire) } }.awaitAll()
        }
        assertEquals(1, results.count { it is ApplyResult.Applied })
        assertEquals(7, results.count { it is ApplyResult.Rejected })
        assertEquals(1, f.db.events().ofType(EventType.ALARM_FIRED).size)
    }

    // --- delay, recovery, follow-up --------------------------------------------------------------------------

    @Test
    fun `delay creates and arms the child in the same step (#8)`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Fire)
        val r = apply(o, OccurrenceCommand.Delay(30))
        assertTrue(r is ApplyResult.Applied)
        assertEquals(OccurrenceState.DELAYED, state(o))
        val children = f.db.occurrences().forMission(o.missionId).filter { it.parentOccurrenceId == o.id }
        assertEquals(1, children.size)
        val child = children.single()
        assertEquals(OccurrenceState.ARMED, child.state)
        assertEquals(f.t0 + 30 * 60_000L, child.scheduledAtUtc)
        assertNotEquals(o.requestCode, child.requestCode)
        assertTrue("child alarm is armed", f.scheduler.armed.containsKey("command://occurrence/${child.id}"))
    }

    @Test
    fun `follow-up questions are stored and answered`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        assertTrue(f.scheduler.armed.containsKey("command://checkin/${o.id}/0"))
        apply(o, OccurrenceCommand.CheckInDue(0))
        assertEquals(listOf(Triple(o.id, o.requestCode, 0)), f.poster.posted)
        var rows = f.db.checkIns().forOccurrence(o.id)
        assertEquals(1, rows.size)
        assertNull(rows.single().answer)
        apply(o, OccurrenceCommand.ReportStarted)
        rows = f.db.checkIns().forOccurrence(o.id)
        assertEquals(CheckInAnswer.STARTED, rows.single().answer)
        assertNotNull(rows.single().answeredAt)
        assertEquals(OccurrenceState.STARTED_SELF_REPORTED, state(o))
    }

    @Test
    fun `protected time suppresses follow-up scheduling`() = run {
        f.restWindowCoveringNow()
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        assertTrue(f.scheduler.armed.values.none { it.kind == AlarmKind.CHECK_IN })
        assertTrue(f.db.events().ofType(EventType.FOLLOWUP_SUPPRESSED).isNotEmpty())
    }

    // --- friction ------------------------------------------------------------------------------------------

    @Test
    fun `a rigid mission needs its friction and a flexible one never does`() = run {
        val rigid = f.mission("Standup", Flexibility.RIGID, lock = LockPolicy.REASON)
        val ro = f.repo.createOccurrence(rigid.id, f.t0 + 1_000, "UTC").occurrence
        apply(ro, OccurrenceCommand.Arm(ArmMode.EXACT))
        val denied = apply(ro, OccurrenceCommand.Skip(SkipReason.USER, FrictionEvidence.Confirmed))
        assertEquals(ApplyResult.NeedsFriction(LockPolicy.REASON), denied)
        assertEquals(OccurrenceState.ARMED, state(ro))
        val ok = apply(ro, OccurrenceCommand.Skip(SkipReason.USER, FrictionEvidence.Reason(com.command.domain.dials.SkipReasonCode.NEED_REST)))
        assertTrue(ok is ApplyResult.Applied)
        assertEquals(SkipReason.USER, f.repo.get(ro.id)!!.skipReason)

        val flexible = scheduled("Stretch", atOffsetMs = 90_000)
        apply(flexible, OccurrenceCommand.Arm(ArmMode.EXACT))
        assertTrue(apply(flexible, OccurrenceCommand.Skip(SkipReason.USER)) is ApplyResult.Applied)
    }

    @Test
    fun `recovery is bounded and reports exhaustion without changing state`() = run {
        val m = f.mission(profileId = "builtin_command") // recovery structure 3 allows two offers
        val o = f.repo.createOccurrence(m.id, f.t0 + 1_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        repeat(2) {
            apply(o, OccurrenceCommand.RequestRecovery)
            apply(o, OccurrenceCommand.AcceptRecovery(com.command.domain.occurrence.RecoveryChoice.RESET))
        }
        val third = apply(o, OccurrenceCommand.RequestRecovery)
        assertTrue(third is ApplyResult.RecoveryExhausted)
        assertEquals(OccurrenceState.GUIDED_START, state(o))
    }

    // --- misses and calendar moves -----------------------------------------------------------------------------

    @Test
    fun `a missed occurrence records why`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        apply(o, OccurrenceCommand.Miss(MissReason.DOWNTIME))
        val row = f.repo.get(o.id)!!
        assertEquals(OccurrenceState.MISSED, row.state)
        assertEquals(MissReason.DOWNTIME, row.missReason)
        assertTrue(f.scheduler.armed.isEmpty())
    }

    @Test
    fun `a moved calendar instance keeps its occurrence and replaces its alarm (#12)`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        val moved = f.repo.reschedule(o.id, f.t0 + 3_600_000, "UTC")
        assertTrue(moved)
        val row = f.repo.get(o.id)!!
        assertEquals(f.t0 + 3_600_000, row.scheduledAtUtc)
        assertEquals(o.requestCode, row.requestCode)
        assertEquals(1, f.scheduler.armed.size)
        assertEquals(f.t0 + 3_600_000, f.scheduler.armed.values.single().atUtcMs)
        assertEquals(1, f.db.occurrences().count())
    }

    @Test
    fun `an occurrence that has fired cannot be moved`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        assertFalse(f.repo.reschedule(o.id, f.t0 + 3_600_000, "UTC"))
        assertEquals(o.scheduledAtUtc, f.repo.get(o.id)!!.scheduledAtUtc)
    }

    // --- audit log ------------------------------------------------------------------------------------------

    @Test
    fun `the event log holds ids and enums only, never the mission title`() = run {
        val title = "Secret Project Plan"
        val o = scheduled(title)
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        apply(o, OccurrenceCommand.Skip(SkipReason.USER))
        val all = f.db.events().all()
        assertTrue(all.size >= 6)
        val token = Regex("[A-Za-z0-9_.:+-]{0,64}")
        for (e in all) {
            assertFalse(e.payloadJson.contains(title, ignoreCase = true))
            for ((k, v) in Codec.decodePayload(e.payloadJson)) {
                assertTrue("key $k", token.matches(k))
                assertTrue("value $v", token.matches(v))
            }
        }
        // And every event carries the monotonic clock reading.
        assertTrue(all.all { it.elapsedRealtime == f.clock.elapsedMs })
    }

    @Test
    fun `dials come from the mission profile, falling back to the active one`() = run {
        val m = f.mission(profileId = "builtin_reveille_max")
        val o = f.repo.createOccurrence(m.id, f.t0 + 1_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        // REVEILLE_MAX alert intensity 5 is nine minutes of ringing plus gaps, ten minutes in all.
        assertEquals(f.t0 + 10 * 60_000L, f.scheduler.armed.values.single { it.kind == AlarmKind.CYCLE_END }.atUtcMs)
        val dials = f.db.profiles().get("builtin_reveille_max")!!
        assertEquals(Dials(5, 5, 5, 4, 4, 4, 4), Dials(dials.firmness, dials.obnoxiousness, dials.alertIntensity, dials.followUpPersistence, dials.handshakeDifficulty, dials.scheduleRigidity, dials.recoveryStructure))
    }

    @Test
    fun `request identities are unique per kind`() {
        val a = AlarmRequest("occ", 7, AlarmKind.FIRE, 0)
        val b = AlarmRequest("occ", 7, AlarmKind.CYCLE_END, 0)
        val c0 = AlarmRequest("occ", 7, AlarmKind.CHECK_IN, 0, index = 0)
        val c1 = AlarmRequest("occ", 7, AlarmKind.CHECK_IN, 0, index = 1)
        assertEquals(4, setOf(a.identity, b.identity, c0.identity, c1.identity).size)
    }
}
