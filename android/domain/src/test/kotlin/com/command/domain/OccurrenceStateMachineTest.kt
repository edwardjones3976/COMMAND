package com.command.domain

import com.command.domain.dials.AlertPlan
import com.command.domain.dials.Dials
import com.command.domain.dials.Flexibility
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.MissionLock
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.Presets
import com.command.domain.dials.SkipReasonCode
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.ChildKind
import com.command.domain.occurrence.DomainEvent
import com.command.domain.occurrence.Effect
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceCommand.AcceptRecovery
import com.command.domain.occurrence.OccurrenceCommand.Acknowledge
import com.command.domain.occurrence.OccurrenceCommand.Arm
import com.command.domain.occurrence.OccurrenceCommand.CheckInDue
import com.command.domain.occurrence.OccurrenceCommand.Delay
import com.command.domain.occurrence.OccurrenceCommand.Disarm
import com.command.domain.occurrence.OccurrenceCommand.Fire
import com.command.domain.occurrence.OccurrenceCommand.Miss
import com.command.domain.occurrence.OccurrenceCommand.NoResponse
import com.command.domain.occurrence.OccurrenceCommand.ReportBlocked
import com.command.domain.occurrence.OccurrenceCommand.ReportDone
import com.command.domain.occurrence.OccurrenceCommand.ReportStarted
import com.command.domain.occurrence.OccurrenceCommand.ReportWorking
import com.command.domain.occurrence.OccurrenceCommand.RequestRecovery
import com.command.domain.occurrence.OccurrenceCommand.RescheduleRecovery
import com.command.domain.occurrence.OccurrenceCommand.Resume
import com.command.domain.occurrence.OccurrenceCommand.SafetySilence
import com.command.domain.occurrence.OccurrenceCommand.Skip
import com.command.domain.occurrence.OccurrenceCommand.StartGuidance
import com.command.domain.occurrence.OccurrenceSnapshot
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.OccurrenceState.ACKNOWLEDGED
import com.command.domain.occurrence.OccurrenceState.ARMED
import com.command.domain.occurrence.OccurrenceState.BLOCKED
import com.command.domain.occurrence.OccurrenceState.COMPLETED_SELF_REPORTED
import com.command.domain.occurrence.OccurrenceState.DELAYED
import com.command.domain.occurrence.OccurrenceState.FIRING
import com.command.domain.occurrence.OccurrenceState.GUIDED_START
import com.command.domain.occurrence.OccurrenceState.MISSED
import com.command.domain.occurrence.OccurrenceState.RECOVERY_OFFERED
import com.command.domain.occurrence.OccurrenceState.SAFETY_SILENCED
import com.command.domain.occurrence.OccurrenceState.SCHEDULED
import com.command.domain.occurrence.OccurrenceState.SKIPPED
import com.command.domain.occurrence.OccurrenceState.STARTED_SELF_REPORTED
import com.command.domain.occurrence.OccurrenceState.UNCONFIRMED
import com.command.domain.occurrence.OccurrenceState.WORKING_SELF_REPORTED
import com.command.domain.occurrence.OccurrenceStateMachine
import com.command.domain.occurrence.RecoveryChoice
import com.command.domain.occurrence.SkipReason
import com.command.domain.occurrence.TransitionResult
import com.command.domain.occurrence.WorkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OccurrenceStateMachineTest {

    private val now = 1_000_000L
    private val command = Presets.dialsFor(PresetOrigin.COMMAND) // persistence 3 (two check-ins 7 min apart), recovery 3
    private val minute = 60_000L

    private fun snap(
        state: OccurrenceState,
        dials: Dials = command,
        lock: LockPolicy = LockPolicy.NONE,
        recoveryOffers: Int = 0,
        asked: Int = 0,
        restEndsAt: Long? = null,
        zoneId: String = "UTC",
        pausedUntil: Long? = null,
    ) = OccurrenceSnapshot(state, dials, lock, recoveryOffers, asked, restEndsAt, zoneId, pausedUntil)

    private fun run(s: OccurrenceSnapshot, c: OccurrenceCommand) = OccurrenceStateMachine.transition(s, c, now)

    private fun applied(s: OccurrenceSnapshot, c: OccurrenceCommand): TransitionResult.Applied {
        val r = run(s, c)
        assertTrue("expected Applied from ${s.state} for ${c.label} but was $r", r is TransitionResult.Applied)
        return r as TransitionResult.Applied
    }

    private fun TransitionResult.Applied.stateChanges(): List<DomainEvent> = events.filter { it.type == EventType.STATE_CHANGED }

    // --- legality matrix ---------------------------------------------------------------------------------------

    private val matrixCommands: Map<String, OccurrenceCommand> = linkedMapOf(
        "ARM" to Arm(ArmMode.EXACT),
        "DISARM" to Disarm,
        "FIRE" to Fire,
        "ACK" to Acknowledge(),
        "SILENCE" to SafetySilence,
        "MISS_TIMEOUT" to Miss(MissReason.TIMEOUT),
        "MISS_DOWNTIME" to Miss(MissReason.DOWNTIME),
        "MISS_PAUSED" to Miss(MissReason.PAUSED),
        "START_GUIDANCE" to StartGuidance,
        "RESUME" to Resume,
        "STARTED" to ReportStarted,
        "WORKING" to ReportWorking,
        "DONE" to ReportDone,
        "BLOCKED" to ReportBlocked,
        "RECOVERY" to RequestRecovery,
        "ACCEPT_RESET" to AcceptRecovery(RecoveryChoice.RESET),
        "RESCHEDULE_RECOVERY" to RescheduleRecovery(30),
        "CHECK_IN_0" to CheckInDue(0),
        "NO_RESPONSE" to NoResponse,
        "DELAY" to Delay(15),
        "SKIP_USER" to Skip(SkipReason.USER),
        "SKIP_CALENDAR" to Skip(SkipReason.CALENDAR_CANCELLED),
    )

    private val expectedLegal: Map<OccurrenceState, Set<String>> = mapOf(
        SCHEDULED to setOf("ARM", "MISS_DOWNTIME", "MISS_PAUSED", "SKIP_USER", "SKIP_CALENDAR"),
        ARMED to setOf("DISARM", "FIRE", "MISS_DOWNTIME", "SKIP_USER", "SKIP_CALENDAR"),
        FIRING to setOf("ACK", "SILENCE", "MISS_TIMEOUT", "DELAY", "SKIP_USER"),
        ACKNOWLEDGED to setOf("START_GUIDANCE", "DELAY", "SKIP_USER"),
        SAFETY_SILENCED to setOf("RESUME", "DELAY", "SKIP_USER"),
        MISSED to setOf("RESUME", "DELAY", "SKIP_USER"),
        GUIDED_START to setOf("STARTED", "WORKING", "DONE", "BLOCKED", "RECOVERY", "CHECK_IN_0", "NO_RESPONSE", "DELAY", "SKIP_USER"),
        STARTED_SELF_REPORTED to setOf("WORKING", "DONE", "BLOCKED", "RECOVERY", "CHECK_IN_0", "NO_RESPONSE", "DELAY", "SKIP_USER"),
        WORKING_SELF_REPORTED to setOf("WORKING", "DONE", "BLOCKED", "RECOVERY", "CHECK_IN_0", "NO_RESPONSE", "DELAY", "SKIP_USER"),
        BLOCKED to setOf("STARTED", "DONE", "RECOVERY", "DELAY", "SKIP_USER"),
        UNCONFIRMED to setOf("STARTED", "WORKING", "DONE", "BLOCKED", "RECOVERY", "DELAY", "SKIP_USER"),
        RECOVERY_OFFERED to setOf("ACCEPT_RESET", "RESCHEDULE_RECOVERY", "NO_RESPONSE", "DELAY", "SKIP_USER"),
        SKIPPED to emptySet(),
        DELAYED to emptySet(),
        COMPLETED_SELF_REPORTED to emptySet(),
    )

    @Test
    fun `every state and command pair is legal exactly where the model says`() {
        assertEquals("the matrix must cover every state", OccurrenceState.values().toSet(), expectedLegal.keys)
        for (state in OccurrenceState.values()) {
            val legal = matrixCommands.filter { (_, c) -> run(snap(state), c) is TransitionResult.Applied }.keys
            assertEquals("legal commands from $state", expectedLegal.getValue(state), legal)
        }
    }

    @Test
    fun `a transition that changes state emits exactly one STATE_CHANGED with from and to`() {
        for (state in OccurrenceState.values()) for ((key, c) in matrixCommands) {
            val r = run(snap(state), c) as? TransitionResult.Applied ?: continue
            val changes = r.stateChanges()
            if (r.state == state) {
                assertTrue("$state/$key kept its state so must not log a state change", changes.isEmpty())
            } else {
                assertEquals("$state/$key", 1, changes.size)
                assertEquals(EventPayload.of("from" to state, "to" to r.state, "command" to c.label), changes.single().payload)
            }
        }
    }

    @Test
    fun `finished states accept nothing`() {
        for (state in listOf(SKIPPED, DELAYED, COMPLETED_SELF_REPORTED)) {
            assertTrue(state.isFinished)
            for (c in matrixCommands.values) assertTrue(run(snap(state), c) is TransitionResult.Illegal)
        }
    }

    // --- alarm lifecycle ---------------------------------------------------------------------------------------

    @Test
    fun `arming is only possible from SCHEDULED so a finished occurrence is never re-armed`() {
        val r = applied(snap(SCHEDULED), Arm(ArmMode.INEXACT))
        assertEquals(ARMED, r.state)
        assertTrue(Effect.ArmAlarm(ArmMode.INEXACT) in r.effects)
        assertTrue(Effect.EnqueueWork(WorkKind.PRERENDER) in r.effects)
        for (done in listOf(COMPLETED_SELF_REPORTED, SKIPPED, DELAYED, MISSED, ARMED, FIRING)) {
            assertTrue(done.name, run(snap(done), Arm(ArmMode.EXACT)) is TransitionResult.Illegal)
        }
    }

    @Test
    fun `firing arms a cycle-end backstop equal to the alert plan for every intensity`() {
        for (intensity in 1..5) {
            val dials = command.copy(alertIntensity = intensity)
            val r = applied(snap(ARMED, dials), Fire)
            assertEquals(FIRING, r.state)
            assertTrue(Effect.ArmCycleEnd(AlertPlan.forIntensity(intensity).totalMs) in r.effects)
            assertTrue(Effect.RollWindow in r.effects)
        }
    }

    @Test
    fun `every way out of FIRING cancels the cycle-end backstop and stops the sound`() {
        val exits = listOf(Acknowledge(), SafetySilence, Miss(MissReason.TIMEOUT), Delay(10), Skip(SkipReason.USER))
        for (c in exits) {
            val r = applied(snap(FIRING), c)
            assertTrue("${c.label} must cancel the backstop", Effect.CancelCycleEnd in r.effects)
            assertTrue("${c.label} must stop the ringing", Effect.StopRinging in r.effects)
        }
    }

    @Test
    fun `nothing else asks for the sound to stop`() {
        for (state in OccurrenceState.values().filter { it != FIRING }) for ((key, c) in matrixCommands) {
            val r = run(snap(state), c) as? TransitionResult.Applied ?: continue
            assertFalse("$state/$key", Effect.StopRinging in r.effects)
        }
    }

    @Test
    fun `pause disarms without ringing and the occurrence can be armed again`() {
        val paused = applied(snap(ARMED), Disarm)
        assertEquals(SCHEDULED, paused.state)
        assertTrue(Effect.DisarmAlarm in paused.effects)
        assertTrue(paused.events.any { it.type == EventType.PAUSED })
        assertEquals(ARMED, applied(paused.snapshot, Arm(ArmMode.EXACT)).state)
    }

    @Test
    fun `past-due rows are missed with the reason that explains them`() {
        val armed = applied(snap(ARMED), Miss(MissReason.DOWNTIME))
        assertEquals(MISSED, armed.state)
        assertTrue(armed.events.any { it.type == EventType.MISSED_WHILE_OFF })
        assertTrue(Effect.DisarmAlarm in armed.effects)
        val late = applied(snap(ARMED), Miss(MissReason.DOWNTIME, lateMs = 10_800_000))
        assertEquals("10800", late.events.single { it.type == EventType.MISSED_WHILE_OFF }.payload.values["late_s"])
        assertEquals(MISSED, applied(snap(SCHEDULED), Miss(MissReason.PAUSED)).state)
        assertTrue(run(snap(ARMED), Miss(MissReason.PAUSED)) is TransitionResult.Illegal)
    }

    // --- honesty of the loop ----------------------------------------------------------------------------------

    @Test
    fun `acknowledging an alarm is not starting or finishing the task`() {
        val ack = applied(snap(FIRING), Acknowledge())
        assertEquals(ACKNOWLEDGED, ack.state)
        assertTrue(run(ack.snapshot, ReportDone) is TransitionResult.Illegal)
        assertTrue(run(ack.snapshot, ReportStarted) is TransitionResult.Illegal)
        val guided = applied(ack.snapshot, StartGuidance)
        assertEquals(GUIDED_START, guided.state)
        assertFalse(guided.state.hasSelfReportedStart)
    }

    @Test
    fun `saying what comes first is its own event, and still only acknowledges`() {
        val plain = applied(snap(FIRING), Acknowledge())
        assertTrue(plain.events.none { it.type == EventType.FIRST_ACTION_INDICATED })

        val said = applied(snap(FIRING), Acknowledge(firstActionIndicated = true))
        assertEquals(ACKNOWLEDGED, said.state)
        assertEquals(listOf(EventType.STATE_CHANGED, EventType.FIRST_ACTION_INDICATED), said.events.map { it.type })
        assertEquals(EventPayload.EMPTY, said.events.last().payload)
        assertEquals(plain.effects, said.effects)

        // The accessible alternative alone is not saying what comes first.
        val assistive = applied(snap(FIRING), Acknowledge(accessibleAlternative = true))
        assertTrue(assistive.events.none { it.type == EventType.FIRST_ACTION_INDICATED })

        // It is independent of the accessible alternative, and neither of them makes the acknowledgement a start.
        val both = applied(snap(FIRING), Acknowledge(accessibleAlternative = true, firstActionIndicated = true))
        assertEquals(ACKNOWLEDGED, both.state)
        assertEquals(1, both.events.count { it.type == EventType.FIRST_ACTION_INDICATED })
        assertTrue(run(said.snapshot, ReportStarted) is TransitionResult.Illegal)
        assertFalse(applied(said.snapshot, StartGuidance).state.hasSelfReportedStart)

        // Only a ringing alarm can be acknowledged, whatever was said.
        for (state in listOf(ARMED, ACKNOWLEDGED, GUIDED_START, SAFETY_SILENCED, MISSED)) {
            assertTrue("$state", run(snap(state), Acknowledge(firstActionIndicated = true)) is TransitionResult.Illegal)
        }
    }

    @Test
    fun `safety silence is always one tap, never friction, never success`() {
        for (lock in LockPolicy.values()) {
            val r = applied(snap(FIRING, lock = lock), SafetySilence)
            assertEquals(SAFETY_SILENCED, r.state)
            assertTrue("no follow-up is scheduled after a silence", r.effects.none { it is Effect.ScheduleCheckIn })
            assertFalse(r.state.hasSelfReportedStart)
        }
        assertEquals(GUIDED_START, applied(snap(SAFETY_SILENCED), Resume).state)
    }

    @Test
    fun `a missed or silenced occurrence only resumes by an explicit command`() {
        for (state in listOf(MISSED, SAFETY_SILENCED)) {
            val legal = matrixCommands.filter { (_, c) -> run(snap(state), c) is TransitionResult.Applied }.keys
            assertTrue("RESUME" in legal)
            assertFalse("START_GUIDANCE" in legal)
        }
    }

    @Test
    fun `self reports move through started, working and done`() {
        var s = applied(snap(GUIDED_START), ReportStarted).snapshot
        assertEquals(STARTED_SELF_REPORTED, s.state)
        s = applied(s, ReportWorking).snapshot
        assertEquals(WORKING_SELF_REPORTED, s.state)
        val again = applied(s, ReportWorking)
        assertEquals(WORKING_SELF_REPORTED, again.state)
        assertTrue(again.events.any { it.type == EventType.CHECK_IN_ANSWERED })
        val done = applied(again.snapshot, ReportDone)
        assertEquals(COMPLETED_SELF_REPORTED, done.state)
        assertTrue(Effect.CancelCheckIns in done.effects)
    }

    // --- Mission Lock ---------------------------------------------------------------------------------------

    @Test
    fun `flexible missions delay and skip with no friction at every rigidity value`() {
        for (rigidity in 1..5) {
            val lock = MissionLock.effectivePolicy(Flexibility.FLEXIBLE, null, rigidity)
            assertEquals(LockPolicy.NONE, lock)
            val dials = command.copy(scheduleRigidity = rigidity)
            assertEquals(DELAYED, applied(snap(GUIDED_START, dials, lock), Delay(30)).state)
            assertEquals(SKIPPED, applied(snap(GUIDED_START, dials, lock), Skip(SkipReason.USER)).state)
        }
    }

    @Test
    fun `rigid missions need the friction their rigidity asks for`() {
        val expected = mapOf(1 to LockPolicy.NONE, 2 to LockPolicy.NONE, 3 to LockPolicy.CONFIRM, 4 to LockPolicy.REASON, 5 to LockPolicy.RECOVERY_OFFER)
        for ((rigidity, policy) in expected) assertEquals(policy, MissionLock.effectivePolicy(Flexibility.RIGID, null, rigidity))

        val confirm = snap(GUIDED_START, lock = LockPolicy.CONFIRM)
        assertEquals(TransitionResult.NeedsFriction(LockPolicy.CONFIRM), run(confirm, Skip(SkipReason.USER)))
        assertEquals(SKIPPED, applied(confirm, Skip(SkipReason.USER, FrictionEvidence.Confirmed)).state)

        val reason = snap(GUIDED_START, lock = LockPolicy.REASON)
        assertEquals(TransitionResult.NeedsFriction(LockPolicy.REASON), run(reason, Delay(15, FrictionEvidence.Confirmed)))
        assertEquals(DELAYED, applied(reason, Delay(15, FrictionEvidence.Reason(SkipReasonCode.NEED_REST))).state)

        val recovery = snap(GUIDED_START, lock = LockPolicy.RECOVERY_OFFER)
        assertEquals(TransitionResult.NeedsFriction(LockPolicy.RECOVERY_OFFER), run(recovery, Skip(SkipReason.USER, FrictionEvidence.Reason(SkipReasonCode.OTHER))))
        assertEquals(SKIPPED, applied(recovery, Skip(SkipReason.USER, FrictionEvidence.RecoveryDeclined)).state)
    }

    @Test
    fun `an explicit per-mission policy overrides the rigidity dial but never makes a flexible mission rigid`() {
        assertEquals(LockPolicy.REASON, MissionLock.effectivePolicy(Flexibility.RIGID, LockPolicy.REASON, 1))
        assertEquals(LockPolicy.NONE, MissionLock.effectivePolicy(Flexibility.FLEXIBLE, LockPolicy.REASON, 5))
    }

    @Test
    fun `a calendar removal of a future occurrence needs no friction but cannot touch one that has fired`() {
        val rigid = LockPolicy.RECOVERY_OFFER
        assertEquals(SKIPPED, applied(snap(ARMED, lock = rigid), Skip(SkipReason.CALENDAR_CANCELLED)).state)
        assertEquals(SKIPPED, applied(snap(SCHEDULED, lock = rigid), Skip(SkipReason.CALENDAR_REMOVED)).state)
        assertTrue(run(snap(FIRING, lock = rigid), Skip(SkipReason.CALENDAR_CANCELLED)) is TransitionResult.Illegal)
        assertTrue(run(snap(GUIDED_START, lock = rigid), Skip(SkipReason.CALENDAR_REMOVED)) is TransitionResult.Illegal)
    }

    @Test
    fun `delay schedules a child occurrence and stops follow-up`() {
        val r = applied(snap(GUIDED_START), Delay(25))
        assertEquals(DELAYED, r.state)
        assertTrue(Effect.ArmChild(ChildKind.DELAY, now + 25 * minute) in r.effects)
        assertTrue(Effect.CancelCheckIns in r.effects)
    }

    // --- follow-up questions ------------------------------------------------------------------------------------

    @Test
    fun `guidance starts the first follow-up at the persistence interval`() {
        val r = applied(snap(ACKNOWLEDGED, command.copy(followUpPersistence = 5)), StartGuidance)
        assertEquals(GUIDED_START, r.state)
        assertTrue(Effect.ScheduleCheckIn(0, now + 5 * minute) in r.effects)
    }

    @Test
    fun `follow-up questions are asked in order and a stale alarm is rejected`() {
        val s0 = applied(snap(ACKNOWLEDGED), StartGuidance).snapshot // persistence 3: two questions, 7 min apart
        assertTrue(run(s0, CheckInDue(1)) is TransitionResult.Illegal)
        val q0 = applied(s0, CheckInDue(0))
        assertTrue(Effect.PostCheckIn(0) in q0.effects)
        assertTrue(Effect.ScheduleCheckIn(1, now + 7 * minute) in q0.effects)
        assertEquals(1, q0.snapshot.checkInsAsked)
        assertTrue("replaying the same alarm is stale", run(q0.snapshot, CheckInDue(0)) is TransitionResult.Illegal)
        val q1 = applied(q0.snapshot, CheckInDue(1))
        assertTrue(Effect.PostCheckIn(1) in q1.effects)
        assertTrue("after the last question one more interval is the final timeout", Effect.ScheduleCheckIn(2, now + 7 * minute) in q1.effects)
    }

    @Test
    fun `silence to the final question leaves a never-reported start UNCONFIRMED`() {
        val s0 = applied(snap(ACKNOWLEDGED, command.copy(followUpPersistence = 1)), StartGuidance).snapshot // one question
        val q0 = applied(s0, CheckInDue(0))
        val end = applied(q0.snapshot, CheckInDue(1))
        assertEquals(UNCONFIRMED, end.state)
        assertTrue(Effect.CancelCheckIns in end.effects)
    }

    @Test
    fun `silence after the user said they started does not rewrite the status`() {
        val started = snap(STARTED_SELF_REPORTED, command.copy(followUpPersistence = 1), asked = 1)
        val r = applied(started, CheckInDue(1))
        assertEquals(STARTED_SELF_REPORTED, r.state)
        assertTrue(r.events.any { it.type == EventType.CHECKIN_UNANSWERED })
    }

    @Test
    fun `an answer replaces the pending follow-up with the next owed question`() {
        val asked1 = snap(GUIDED_START, asked = 1)
        val r = applied(asked1, ReportStarted)
        assertEquals(STARTED_SELF_REPORTED, r.state)
        assertEquals(Effect.CancelCheckIns, r.effects.first())
        assertTrue(Effect.ScheduleCheckIn(1, now + 7 * minute) in r.effects)
        val done = applied(snap(STARTED_SELF_REPORTED, asked = 2), ReportWorking)
        assertTrue("every question was asked, so nothing more is scheduled", done.effects.none { it is Effect.ScheduleCheckIn })
    }

    @Test
    fun `protected time defers the first question to after the window instead of dropping it`() {
        val restEnds = now + 3 * 60 * minute
        val r = applied(snap(ACKNOWLEDGED, restEndsAt = restEnds), StartGuidance)
        assertTrue("asked 7 minutes after the window ends", Effect.ScheduleCheckIn(0, restEnds + 7 * minute) in r.effects)
        assertTrue(r.events.any { it.type == EventType.FOLLOWUP_SUPPRESSED })
    }

    @Test
    fun `a question that falls due in protected time is not asked or counted, and comes back after the window`() {
        val restEnds = now + 2 * 60 * minute
        val due = applied(snap(GUIDED_START, restEndsAt = restEnds), CheckInDue(0))
        assertTrue(due.effects.none { it is Effect.PostCheckIn })
        assertEquals("the question was not used up", 0, due.snapshot.checkInsAsked)
        assertTrue(Effect.ScheduleCheckIn(0, restEnds + 7 * minute) in due.effects)
        assertTrue(due.events.any { it.type == EventType.FOLLOWUP_SUPPRESSED })
    }

    @Test
    fun `silence during protected time never turns a start into UNCONFIRMED`() {
        val restEnds = now + 60 * minute
        val s = snap(GUIDED_START, command.copy(followUpPersistence = 1), asked = 1, restEndsAt = restEnds)
        val r = applied(s, CheckInDue(1)) // the final timeout falls due while asleep
        assertEquals(GUIDED_START, r.state)
        assertTrue(Effect.ScheduleCheckIn(1, restEnds + 10 * minute) in r.effects)
    }

    @Test
    fun `an answer given in protected time schedules the next question after the window`() {
        val restEnds = now + 60 * minute
        val r = applied(snap(GUIDED_START, asked = 1, restEndsAt = restEnds), ReportStarted)
        assertTrue(Effect.ScheduleCheckIn(1, restEnds + 7 * minute) in r.effects)
    }

    // --- recovery -------------------------------------------------------------------------------------------

    @Test
    fun `recovery is offered a finite number of times then stops`() {
        var s = snap(GUIDED_START) // recovery structure 3 allows two offers
        repeat(2) { i ->
            val offered = applied(s, RequestRecovery)
            assertEquals(RECOVERY_OFFERED, offered.state)
            assertEquals(i + 1, offered.snapshot.recoveryOffers)
            val restarted = applied(offered.snapshot, AcceptRecovery(RecoveryChoice.RESET))
            assertEquals(GUIDED_START, restarted.state)
            assertEquals(0, restarted.snapshot.checkInsAsked)
            s = restarted.snapshot
        }
        assertEquals(TransitionResult.RecoveryExhausted(2), run(s, RequestRecovery))
    }

    @Test
    fun `the recovery menu limits what can be accepted`() {
        val minimal = command.copy(recoveryStructure = 1) // only RESET, one offer
        val offered = applied(snap(GUIDED_START, minimal), RequestRecovery).snapshot
        assertTrue(run(offered, AcceptRecovery(RecoveryChoice.SMALLER)) is TransitionResult.Illegal)
        assertEquals(GUIDED_START, applied(offered, AcceptRecovery(RecoveryChoice.RESET)).state)
        assertEquals(TransitionResult.RecoveryExhausted(1), run(applied(offered, AcceptRecovery(RecoveryChoice.RESET)).snapshot, RequestRecovery))
    }

    @Test
    fun `the reschedule option takes only the minutes the menu offers`() {
        val offered = snap(RECOVERY_OFFERED, command.copy(recoveryStructure = 3)) // reset, smaller, reschedule +15/+30/+60
        for (ok in listOf(15, 30, 60)) {
            val r = applied(offered, RescheduleRecovery(ok))
            assertEquals(DELAYED, r.state)
            assertTrue(Effect.ArmChild(ChildKind.DELAY, now + ok * minute) in r.effects)
            assertTrue(r.events.any { it.type == EventType.RECOVERY_ACCEPTED })
        }
        assertTrue("45 is not on the menu", run(offered, RescheduleRecovery(45)) is TransitionResult.Illegal)
        assertTrue("tomorrow is only offered from structure 4", run(offered, RescheduleRecovery(OccurrenceCommand.TOMORROW_MINUTES)) is TransitionResult.Illegal)
    }

    @Test
    fun `tomorrow is a reschedule choice only where the menu offers it`() {
        val four = snap(RECOVERY_OFFERED, command.copy(recoveryStructure = 4))
        val r = applied(four, RescheduleRecovery(OccurrenceCommand.TOMORROW_MINUTES))
        assertTrue(Effect.ArmChild(ChildKind.DELAY, now + 24 * 60 * minute) in r.effects)
    }

    @Test
    fun `a menu without reschedule refuses it`() {
        val resetOnly = snap(RECOVERY_OFFERED, command.copy(recoveryStructure = 1))
        assertTrue(run(resetOnly, RescheduleRecovery(30)) is TransitionResult.Illegal)
        val two = snap(RECOVERY_OFFERED, command.copy(recoveryStructure = 2)) // reset + reschedule +30/+60, no +15
        assertTrue(run(two, RescheduleRecovery(15)) is TransitionResult.Illegal)
        assertTrue(run(two, RescheduleRecovery(30)) is TransitionResult.Applied)
    }

    @Test
    fun `rescheduling a rigid mission from recovery meets the same friction as any delay`() {
        val rigid = snap(RECOVERY_OFFERED, command.copy(recoveryStructure = 3), lock = LockPolicy.CONFIRM)
        assertEquals(TransitionResult.NeedsFriction(LockPolicy.CONFIRM), run(rigid, RescheduleRecovery(30)))
        assertEquals(DELAYED, applied(rigid, RescheduleRecovery(30, FrictionEvidence.Confirmed)).state)
    }

    @Test
    fun `an unanswered recovery offer ends as UNCONFIRMED`() {
        assertEquals(UNCONFIRMED, applied(snap(RECOVERY_OFFERED), NoResponse).state)
    }

    @Test
    fun `a blocked or unconfirmed occurrence can still be picked up by the user`() {
        assertEquals(STARTED_SELF_REPORTED, applied(snap(BLOCKED), ReportStarted).state)
        assertEquals(COMPLETED_SELF_REPORTED, applied(snap(UNCONFIRMED), ReportDone).state)
    }

    // --- audit payloads ---------------------------------------------------------------------------------------

    @Test
    fun `event payloads reject free text`() {
        try {
            EventPayload.of("title" to "Morning gym session")
            fail("a title with spaces must not be storable in the history")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        assertNotNull(EventPayload.of("mission" to "3f2b8e1c-0d4a-4a7e-9b1e-2f6c1d8a9e00", "count" to 3, "at" to 1_700_000_000_000L))
    }

    @Test
    fun `transitions never mutate the snapshot they were given`() {
        val before = snap(GUIDED_START, recoveryOffers = 1, asked = 1)
        val copy = before.copy()
        applied(before, RequestRecovery)
        assertEquals(copy, before)
    }

    @Test
    fun `a question that arrives late says how late, and one that is on time says nothing`() {
        val late = applied(snap(GUIDED_START), CheckInDue(0, lateMs = 95_000))
        val note = late.events.single { it.type == EventType.CHECKIN_LATE }
        assertEquals("95", note.payload.values["late_s"])
        assertEquals("0", note.payload.values["index"])
        assertTrue("it was asked all the same", late.effects.any { it is Effect.PostCheckIn })

        val onTime = applied(snap(GUIDED_START), CheckInDue(0, lateMs = 2_000))
        assertTrue(onTime.events.none { it.type == EventType.CHECKIN_LATE })
    }

    @Test
    fun `a late alarm inside protected time is deferred, and is not noted as a late question because none was asked`() {
        val restEnds = now + 3_600_000
        val deferred = applied(snap(GUIDED_START, restEndsAt = restEnds), CheckInDue(0, lateMs = 95_000))
        assertTrue(deferred.events.none { it.type == EventType.CHECKIN_LATE })
        assertTrue(deferred.effects.none { it is Effect.PostCheckIn })
    }

    @Test
    fun `tomorrow is the same local time on the next calendar day, also across a change of the clocks`() {
        val four = snap(RECOVERY_OFFERED, command.copy(recoveryStructure = 4), zoneId = "America/New_York")
        val ny = java.time.ZoneId.of("America/New_York")
        fun at(y: Int, m: Int, d: Int) = java.time.ZonedDateTime.of(y, m, d, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        fun tomorrowFrom(nowMs: Long): Long {
            val r = OccurrenceStateMachine.transition(four, RescheduleRecovery(OccurrenceCommand.TOMORROW_MINUTES), nowMs) as TransitionResult.Applied
            return r.effects.filterIsInstance<Effect.ArmChild>().single().atUtcMs
        }
        assertEquals("the clocks go back on 2026-11-01, so that day is 25 hours long", at(2026, 11, 1), tomorrowFrom(at(2026, 10, 31)))
        assertEquals("the clocks go forward on 2026-03-08, so that day is 23 hours long", at(2026, 3, 8), tomorrowFrom(at(2026, 3, 7)))
        assertEquals("an ordinary day is 24 hours", at(2026, 6, 11), tomorrowFrom(at(2026, 6, 10)))
    }

    @Test
    fun `a zone nobody has heard of falls back to UTC instead of failing the choice`() {
        val four = snap(RECOVERY_OFFERED, command.copy(recoveryStructure = 4), zoneId = "Not/AZone")
        val r = applied(four, RescheduleRecovery(OccurrenceCommand.TOMORROW_MINUTES))
        assertTrue(Effect.ArmChild(ChildKind.DELAY, now + 24 * 60 * minute) in r.effects)
    }

    @Test
    fun `an alarm delivered while a pause is in force is held back and does not ring, and nothing else is held`() {
        val paused = snap(ARMED, pausedUntil = now + 60 * minute)
        assertTrue(run(paused, Fire) is TransitionResult.Illegal)
        assertTrue("without a pause it rings", run(snap(ARMED), Fire) is TransitionResult.Applied)
        assertTrue("a ring that is going can still be answered", run(snap(FIRING, pausedUntil = now + 60 * minute), SafetySilence) is TransitionResult.Applied)
    }
}
