package com.command.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.data.db.OccurrenceRow
import com.command.data.db.SettingRow
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.CheckInPoster
import com.command.domain.alarm.CheckInPrompt
import com.command.domain.dials.ChainOp
import com.command.domain.dials.Dials
import com.command.domain.dials.Flexibility
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.Personality
import com.command.domain.handshake.Attempt
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.EventSource
import com.command.domain.model.SettingKeys
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    // --- what a ring asks of the person, and what it has been told so far -------------------------------------------------

    @Test
    fun `the handshake an occurrence asks for is the default chain of its profile's difficulty, in the words of its personality`() = run {
        val expected = mapOf(
            "builtin_nudge" to Triple(ChainOp.OR, listOf(HandshakeMode.HOLD), Personality.SUPPORTIVE_COACH),
            "builtin_steady" to Triple(ChainOp.OR, listOf(HandshakeMode.HOLD), Personality.EXECUTIVE_DIRECTOR),
            "builtin_command" to Triple(ChainOp.OR, listOf(HandshakeMode.TYPING), Personality.ACCOUNTABILITY_PARTNER),
            "builtin_reveille_max" to Triple(ChainOp.AND, listOf(HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.TYPING), Personality.DRILL_SERGEANT),
        )
        for ((profile, want) in expected) {
            val m = f.mission(profileId = profile)
            val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
            val plan = f.repo.handshakeOf(o)
            assertEquals("$profile: how the steps combine", want.first, plan.chain.op)
            assertEquals("$profile: which steps", want.second, plan.chain.steps.map { it.mode })
            assertEquals("$profile: whose words", want.third, plan.personality)
        }
    }

    @Test
    fun `a mission with no profile of its own gets the active one's handshake`() = run {
        f.db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"builtin_command\""))
        val m = f.mission()
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        val plan = f.repo.handshakeOf(o)
        assertEquals(listOf(HandshakeMode.TYPING), plan.chain.steps.map { it.mode })
        assertEquals(Personality.ACCOUNTABILITY_PARTNER, plan.personality)
    }

    @Test
    fun `the handshake is pinned when the alarm fires, so a profile edited while it sounds changes nothing for the person in the middle of it`() = run {
        val m = f.mission(profileId = "builtin_command") // typing, in the accountability partner's words
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)

        f.db.profiles().upsert(f.db.profiles().get("builtin_command")!!.copy(handshakeDifficulty = 1, personality = Personality.MINIMALIST))

        val plan = f.repo.handshakeOf(f.repo.get(o.id)!!)
        assertEquals("still the typing it was fired with", listOf(HandshakeMode.TYPING), plan.chain.steps.map { it.mode })
        assertEquals("and in the same words", Personality.ACCOUNTABILITY_PARTNER, plan.personality)
        assertEquals("it was the profile that was edited", 1, f.db.profiles().get("builtin_command")!!.handshakeDifficulty)
    }

    @Test
    fun `an alarm that has not fired follows its profile as it is, and one that fires later is pinned to what the profile was then`() = run {
        val m = f.mission(profileId = "builtin_command")
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        f.db.profiles().upsert(f.db.profiles().get("builtin_command")!!.copy(handshakeDifficulty = 1, personality = Personality.MINIMALIST))

        val before = f.repo.handshakeOf(o)
        assertEquals("not fired: the profile as it is", listOf(HandshakeMode.HOLD), before.chain.steps.map { it.mode })
        assertEquals(Personality.MINIMALIST, before.personality)

        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        f.db.profiles().upsert(f.db.profiles().get("builtin_command")!!.copy(handshakeDifficulty = 3, personality = Personality.DRILL_SERGEANT))

        val after = f.repo.handshakeOf(f.repo.get(o.id)!!)
        assertEquals("fired: what the profile was when it fired", listOf(HandshakeMode.HOLD), after.chain.steps.map { it.mode })
        assertEquals(Personality.MINIMALIST, after.personality)
    }

    @Test
    fun `a pinned personality this version does not know gives way to the profile's, and a pinned difficulty still holds`() = run {
        val m = f.mission(profileId = "builtin_command")
        val o = f.repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        val row = f.repo.get(o.id)!!
        f.db.occurrences().compareAndSet(
            id = row.id, expectedVersion = row.stateVersion, state = row.state,
            sessionJson = Codec.encodeSession(SessionData(handshakeDifficulty = 1, handshakePersonality = "ROBOT")),
            skipReason = row.skipReason, missReason = row.missReason, armMode = row.armMode, ringMode = row.ringMode,
        )

        val plan = f.repo.handshakeOf(f.repo.get(o.id)!!)
        assertEquals(listOf(HandshakeMode.HOLD), plan.chain.steps.map { it.mode })
        assertEquals(Personality.ACCOUNTABILITY_PARTNER, plan.personality)
    }

    @Test
    fun `the tries of a ring come back in the order they were written, as the engine reads them`() = run {
        val o = scheduled()
        f.repo.recordChallenge(o.id, ChallengeRecord(0, HandshakeMode.TYPING, 1, ChallengeOutcome.FAILED, f.t0, f.t0 + 1_000))
        f.repo.recordChallenges(
            o.id,
            listOf(
                ChallengeRecord(0, HandshakeMode.TYPING, 2, ChallengeOutcome.TIMEOUT, f.t0 + 1_000, f.t0 + 2_000),
                ChallengeRecord(0, HandshakeMode.TYPING, 2, ChallengeOutcome.FALLBACK, f.t0 + 2_000, f.t0 + 2_000),
            ),
        )
        assertEquals(
            listOf(
                Attempt(0, HandshakeMode.TYPING, 1, ChallengeOutcome.FAILED),
                Attempt(0, HandshakeMode.TYPING, 2, ChallengeOutcome.TIMEOUT),
                Attempt(0, HandshakeMode.TYPING, 2, ChallengeOutcome.FALLBACK),
            ),
            f.repo.attemptsOf(o.id),
        )
        assertEquals("another ring's tries are not this one's", emptyList<Attempt>(), f.repo.attemptsOf(scheduled("Another").id))
    }

    @Test
    fun `a write for an occurrence that does not exist is refused, and an empty one writes nothing`() = run {
        val o = scheduled()
        val good = ChallengeRecord(0, HandshakeMode.CONFIRM_SEQUENCE, 3, ChallengeOutcome.FAILED, f.t0, f.t0 + 1_000)
        val orphan = ChallengeRecord(0, HandshakeMode.CONFIRM_SEQUENCE, 3, ChallengeOutcome.FALLBACK, f.t0, f.t0 + 1_000)
        val failed = runCatching { f.repo.recordChallenges("no-such-occurrence", listOf(good, orphan)) }.isFailure
        assertTrue("a row for an occurrence that does not exist is refused", failed)
        assertEquals(emptyList<Attempt>(), f.repo.attemptsOf("no-such-occurrence"))

        f.repo.recordChallenges(o.id, emptyList())
        assertEquals("no rows is no write", emptyList<Attempt>(), f.repo.attemptsOf(o.id))
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
        val asked = f.poster.posted.single()
        assertEquals(listOf(o.id, o.requestCode, 0), listOf(asked.occurrenceId, asked.requestCode, asked.index))
        assertEquals("Write the report", asked.title)
        assertFalse(asked.started)
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
    fun `protected time defers the follow-up question until the window ends`() = run {
        f.restWindowCoveringNow() // Monday 08:00-10:00 UTC; it is 09:00
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        val firstQuestion = f.scheduler.armed.values.single { it.kind == AlarmKind.CHECK_IN }
        // Steady asks 10 minutes after guidance; here that is 10 minutes after the window closes at 10:00.
        assertEquals(f.t0 + 60 * 60_000L + 10 * 60_000L, firstQuestion.atUtcMs)
        assertTrue(f.db.events().ofType(EventType.FOLLOWUP_SUPPRESSED).isNotEmpty())
    }

    @Test
    fun `every way out of a ring tells the service to stop the sound`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        assertTrue(f.ringer.stopped.isEmpty())
        apply(o, OccurrenceCommand.SafetySilence)
        assertEquals(listOf(o.id), f.ringer.stopped)
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

    @Test
    fun `rearm puts back an armed alarm and leaves anything that has moved on alone`() = run {
        val o = scheduled()
        assertNull("not armed yet", f.repo.rearm(o.id))
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT))
        val afterArm = f.scheduler.calls.size

        assertEquals(ArmMode.EXACT, f.repo.rearm(o.id))
        assertEquals("one more arm call", afterArm + 1, f.scheduler.calls.size)

        apply(o, OccurrenceCommand.Fire)
        val afterFire = f.scheduler.calls.size
        assertNull("it is ringing: a reconcile that read the row a moment earlier must not touch it", f.repo.rearm(o.id))
        assertEquals(afterFire, f.scheduler.calls.size)
    }

    @Test
    fun `a question is taken out of the shade when it is answered, and the next one is asked as a progress question`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        f.poster.cancelled.clear()
        apply(o, OccurrenceCommand.CheckInDue(0))
        assertTrue("asking does not take the question away", f.poster.cancelled.isEmpty())

        apply(o, OccurrenceCommand.ReportStarted)
        assertEquals("answering does", listOf(o.id), f.poster.cancelled.distinct())

        apply(o, OccurrenceCommand.CheckInDue(1))
        assertTrue("after a start was reported the question is about progress", f.poster.posted.last().started)
    }

    @Test
    fun `a question that reached the person late is stored with how late, and noted in the log`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        apply(o, OccurrenceCommand.CheckInDue(0, lateMs = 95_000))
        assertEquals(95_000L, f.db.checkIns().forOccurrence(o.id).single().lateMs)
        val late = f.db.events().forOccurrence(o.id).single { it.type == EventType.CHECKIN_LATE }
        assertTrue(late.payloadJson.contains("\"late_s\":\"95\""))
    }

    @Test
    fun `a question that came on time is stored as on time and leaves no late note`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        apply(o, OccurrenceCommand.CheckInDue(0, lateMs = 800))
        assertEquals(800L, f.db.checkIns().forOccurrence(o.id).single().lateMs)
        assertTrue(f.db.events().forOccurrence(o.id).none { it.type == EventType.CHECKIN_LATE })
    }

    // --- effects of two commands never interleave -------------------------------------------------------------------------------------

    /** Holds the question on its way to the shade until the test lets go of it, and writes down what happened in what order. */
    private class HeldPoster : CheckInPoster {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        override fun post(prompt: CheckInPrompt) {
            order += "post"
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            order += "posted"
        }

        override fun cancel(occurrenceId: String) {
            order += "cancel"
        }
    }

    @Test
    fun `an answer that arrives while the question is still on its way waits for it, so that taking the question away is the last word`() = run {
        val poster = HeldPoster()
        val repo = OccurrenceRepository(f.db, f.clock, f.scheduler, f.work, poster, f.roller, f.ringer)
        val m = f.mission()
        val o = repo.createOccurrence(m.id, f.t0 + 60_000, "UTC").occurrence
        for (c in listOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance)) {
            repo.apply(o.id, c, EventSource.SYSTEM)
        }
        poster.order.clear() // getting here took a cancel or two of its own; what counts is what happens from now on

        val threads = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val question = threads.async { repo.apply(o.id, OccurrenceCommand.CheckInDue(0), EventSource.RECEIVER) }
        assertTrue("the question reached the poster", poster.entered.await(30, TimeUnit.SECONDS))

        // The person answers while that is still going on.
        val answer = threads.async { repo.apply(o.id, OccurrenceCommand.ReportDone, EventSource.USER) }
        assertNull("it has to wait its turn", withTimeoutOrNull(700) { answer.await() })

        poster.release.countDown()
        assertTrue(question.await() is ApplyResult.Applied)
        assertTrue(answer.await() is ApplyResult.Applied)

        assertEquals(OccurrenceState.COMPLETED_SELF_REPORTED, repo.get(o.id)!!.state)
        assertEquals("the question was posted first and taken away last", listOf("post", "posted", "cancel"), poster.order.toList())
        assertTrue(
            "and the follow-up alarm the question armed is gone",
            f.scheduler.armed.keys.none { it.startsWith("command://checkin/${o.id}") },
        )
    }

    @Test
    fun `tomorrow from the recovery menu is the same local time in the occurrence's zone`() = run {
        val ny = java.time.ZoneId.of("America/New_York")
        fun at(y: Int, m: Int, d: Int) = java.time.ZonedDateTime.of(y, m, d, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        val m = f.mission(profileId = "builtin_reveille_max") // its recovery setting offers tomorrow
        f.clock.nowMs = at(2026, 10, 31)
        val o = f.repo.createOccurrence(m.id, f.clock.nowMs + 60_000, "America/New_York").occurrence
        for (c in listOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance, OccurrenceCommand.RequestRecovery)) {
            apply(o, c)
        }
        assertEquals(OccurrenceState.RECOVERY_OFFERED, state(o))

        val r = apply(o, OccurrenceCommand.RescheduleRecovery(OccurrenceCommand.TOMORROW_MINUTES))
        assertTrue(r is ApplyResult.Applied)
        val child = f.db.occurrences().forMission(m.id).single { it.parentOccurrenceId == o.id }
        assertEquals("09:00 the next morning, 25 hours later because the clocks went back that night", at(2026, 11, 1), child.scheduledAtUtc)
    }

    @Test
    fun `a caller that blocks the main thread is never stuck behind a command that was started on it`() = run {
        val o = scheduled()
        for (c in listOf(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance)) apply(o, c)
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())

        // A command that starts on the main looper takes the occurrence's lock and goes into its transaction. To go on it needs the main looper
        // again, so nothing it holds may be waited for by a thread that is blocking the main looper.
        val onMain = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).async { f.repo.apply(o.id, OccurrenceCommand.ReportStarted, EventSource.USER) }
        looper.idle()

        // This is the main thread, and it blocks here until the second command has run, as a test or a caller using runBlocking does.
        val second = withTimeoutOrNull(10_000) { f.repo.apply(o.id, OccurrenceCommand.ReportWorking, EventSource.USER) }
        assertNotNull("the second command ran", second)

        val end = System.currentTimeMillis() + 10_000
        while (!onMain.isCompleted && System.currentTimeMillis() < end) {
            looper.idle()
            Thread.sleep(5)
        }
        assertTrue("and so did the first", onMain.isCompleted)
    }

    @Test
    fun `the detail of an occurrence has its events, its questions and its attempts, and an unknown one has none`() = run {
        val o = scheduled()
        apply(o, OccurrenceCommand.Arm(ArmMode.EXACT)); apply(o, OccurrenceCommand.Fire)
        apply(o, OccurrenceCommand.Acknowledge()); apply(o, OccurrenceCommand.StartGuidance)
        apply(o, OccurrenceCommand.CheckInDue(0))
        apply(o, OccurrenceCommand.ReportStarted)

        val d = f.repo.detail(o.id)!!
        assertEquals("Write the report", d.title)
        assertEquals(OccurrenceState.STARTED_SELF_REPORTED, d.occurrence.state)
        assertTrue(d.events.any { it.type == EventType.ALARM_ARMED })
        assertTrue(d.events.any { it.type == EventType.CHECK_IN_ASKED })
        assertEquals(CheckInAnswer.STARTED, d.checkIns.single().answer)
        assertNull(f.repo.detail("no-such-occurrence"))
    }
}
