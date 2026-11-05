package com.command.domain.occurrence

import com.command.domain.dials.DialEffects
import com.command.domain.dials.RecoveryOption
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

/**
 * The only place that decides which state follows which. Pure: a snapshot, a command and the time go in; a
 * result comes out. The repository applies the result atomically and runs the effects.
 */
object OccurrenceStateMachine {
    fun transition(snapshot: OccurrenceSnapshot, command: OccurrenceCommand, nowMs: Long): TransitionResult =
        Run(snapshot, command, nowMs).decide()

    /** States from which the user may delay or skip: everything that has fired and is not finished. */
    private val ACTIVE = setOf(
        FIRING, ACKNOWLEDGED, SAFETY_SILENCED, MISSED, GUIDED_START, STARTED_SELF_REPORTED,
        WORKING_SELF_REPORTED, BLOCKED, UNCONFIRMED, RECOVERY_OFFERED,
    )

    /** States in which follow-up questions are being asked. */
    private val FOLLOW_UP = setOf(GUIDED_START, STARTED_SELF_REPORTED, WORKING_SELF_REPORTED)

    private const val MINUTE_MS = 60_000L

    private class Run(val s: OccurrenceSnapshot, val cmd: OccurrenceCommand, val now: Long) {
        private var state = s.state
        private var recoveryOffers = s.recoveryOffers
        private var asked = s.checkInsAsked
        private val events = ArrayList<DomainEvent>()
        private val effects = ArrayList<Effect>()
        private val schedule = DialEffects.checkIns(s.dials.followUpPersistence)
        private val recovery = DialEffects.recoveryPolicy(s.dials.recoveryStructure)

        fun decide(): TransitionResult {
            val from = s.state
            if (from.isFinished) return illegal()
            return when (cmd) {
                is Arm -> if (from == SCHEDULED) {
                    go(ARMED)
                    events += DomainEvent(EventType.ALARM_ARMED, EventPayload.of("mode" to cmd.mode))
                    effects += Effect.ArmAlarm(cmd.mode)
                    effects += Effect.EnqueueWork(WorkKind.PRERENDER)
                    effects += Effect.EnqueueWork(WorkKind.PREFETCH)
                    applied()
                } else illegal()

                Disarm -> if (from == ARMED) {
                    go(SCHEDULED)
                    events += DomainEvent(EventType.PAUSED)
                    effects += Effect.DisarmAlarm
                    applied()
                } else illegal()

                Fire -> if (from == ARMED) {
                    go(FIRING)
                    events += DomainEvent(EventType.ALARM_FIRED)
                    effects += Effect.ArmCycleEnd(DialEffects.alertPlan(s.dials.alertIntensity).totalMs)
                    effects += Effect.RollWindow
                    applied()
                } else illegal()

                is Acknowledge -> if (from == FIRING) {
                    go(ACKNOWLEDGED)
                    effects += Effect.CancelCycleEnd
                    effects += Effect.StopRinging
                    applied()
                } else illegal()

                SafetySilence -> if (from == FIRING) {
                    go(SAFETY_SILENCED)
                    effects += Effect.CancelCycleEnd
                    effects += Effect.StopRinging
                    applied()
                } else illegal()

                is Miss -> miss(cmd.reason, cmd.lateMs)
                StartGuidance -> if (from == ACKNOWLEDGED) enterGuidance() else illegal()
                Resume -> if (from == MISSED || from == SAFETY_SILENCED) enterGuidance() else illegal()
                ReportStarted -> if (from == GUIDED_START || from == BLOCKED || from == UNCONFIRMED) answer(STARTED_SELF_REPORTED) else illegal()
                ReportWorking -> if (from in FOLLOW_UP || from == UNCONFIRMED) answer(WORKING_SELF_REPORTED) else illegal()
                ReportDone -> if (from in FOLLOW_UP || from == BLOCKED || from == UNCONFIRMED) finish(COMPLETED_SELF_REPORTED) else illegal()
                ReportBlocked -> if (from in FOLLOW_UP || from == UNCONFIRMED) finish(BLOCKED) else illegal()
                RequestRecovery -> requestRecovery()
                is AcceptRecovery -> acceptRecovery(cmd)
                is RescheduleRecovery -> rescheduleRecovery(cmd)
                is CheckInDue -> checkInDue(cmd.index)
                NoResponse -> noResponse()
                is Delay -> delay(cmd)
                is Skip -> skip(cmd)
            }
        }

        private fun illegal(): TransitionResult = TransitionResult.Illegal(s.state, cmd.label)

        private fun go(to: OccurrenceState) {
            events += DomainEvent(
                EventType.STATE_CHANGED,
                EventPayload.of("from" to s.state, "to" to to, "command" to cmd.label),
            )
            state = to
        }

        private fun applied(): TransitionResult = TransitionResult.Applied(
            snapshot = s.copy(state = state, recoveryOffers = recoveryOffers, checkInsAsked = asked),
            events = events,
            effects = effects,
        )

        private fun miss(reason: MissReason, lateMs: Long?): TransitionResult {
            val from = s.state
            val legal = when (reason) {
                MissReason.TIMEOUT -> from == FIRING
                MissReason.DOWNTIME -> from == ARMED || from == SCHEDULED
                MissReason.PAUSED -> from == SCHEDULED
            }
            if (!legal) return illegal()
            go(MISSED)
            if (reason == MissReason.TIMEOUT) {
                effects += Effect.CancelCycleEnd
                effects += Effect.StopRinging
            }
            if (reason != MissReason.TIMEOUT && from == ARMED) effects += Effect.DisarmAlarm
            if (reason == MissReason.DOWNTIME) {
                events += DomainEvent(EventType.MISSED_WHILE_OFF, if (lateMs == null) EventPayload.EMPTY else EventPayload.of("late_s" to lateMs / 1000))
            }
            return applied()
        }

        /** Direction is spoken and the follow-up questions start. Also the target of every restart. */
        private fun enterGuidance(): TransitionResult {
            go(GUIDED_START)
            asked = 0
            scheduleNextCheckIn()
            return applied()
        }

        /** Replace any pending follow-up with the next question that is still owed. */
        private fun scheduleNextCheckIn() {
            effects += Effect.CancelCheckIns
            if (asked >= schedule.count) return
            scheduleCheckIn(asked)
        }

        /**
         * Schedules question [index]. Inside protected time (sleep, leisure, driving) it is pushed back to after the
         * window ends, so a question is neither asked at 3 a.m. nor silently lost.
         */
        private fun scheduleCheckIn(index: Int) {
            val gap = schedule.gapsMs[minOf(index, schedule.count - 1)]
            val restEnds = s.restEndsAtUtc
            if (restEnds != null) {
                events += DomainEvent(EventType.FOLLOWUP_SUPPRESSED, EventPayload.of("index" to index))
                effects += Effect.ScheduleCheckIn(index, restEnds + gap)
            } else {
                effects += Effect.ScheduleCheckIn(index, now + gap)
            }
        }

        /** A self-report that keeps the occurrence going (started, still working). */
        private fun answer(to: OccurrenceState): TransitionResult {
            if (to != s.state) go(to) else events += DomainEvent(EventType.CHECK_IN_ANSWERED, EventPayload.of("answer" to to))
            scheduleNextCheckIn()
            return applied()
        }

        /** A self-report that ends follow-up (done, blocked). */
        private fun finish(to: OccurrenceState): TransitionResult {
            go(to)
            effects += Effect.CancelCheckIns
            return applied()
        }

        private fun requestRecovery(): TransitionResult {
            val from = s.state
            if (from !in FOLLOW_UP && from != BLOCKED && from != UNCONFIRMED) return illegal()
            if (recoveryOffers >= recovery.maxOffers) return TransitionResult.RecoveryExhausted(recoveryOffers)
            recoveryOffers += 1
            go(RECOVERY_OFFERED)
            events += DomainEvent(EventType.RECOVERY_OFFERED, EventPayload.of("offer" to recoveryOffers))
            effects += Effect.CancelCheckIns
            return applied()
        }

        private fun acceptRecovery(command: AcceptRecovery): TransitionResult {
            if (s.state != RECOVERY_OFFERED) return illegal()
            val option = when (command.choice) {
                RecoveryChoice.RESET -> RecoveryOption.RESET
                RecoveryChoice.SMALLER -> RecoveryOption.SMALLER
            }
            if (option !in recovery.menu) return illegal()
            events += DomainEvent(EventType.RECOVERY_ACCEPTED, EventPayload.of("choice" to command.choice))
            return enterGuidance()
        }

        private fun checkInDue(index: Int): TransitionResult {
            if (s.state !in FOLLOW_UP) return illegal()
            val nextQuestion = index == asked && index < schedule.count
            val finalTimeout = index == schedule.count && asked == schedule.count
            if (!nextQuestion && !finalTimeout) return illegal()
            if (s.restEndsAtUtc != null) {
                // Protected time: neither ask nor count the silence; try again after the window.
                scheduleCheckIn(index)
                return applied()
            }
            if (finalTimeout) return unanswered()
            asked = index + 1
            events += DomainEvent(EventType.CHECK_IN_ASKED, EventPayload.of("index" to index))
            effects += Effect.PostCheckIn(index)
            // After the last question, one more interval of silence is the final timeout.
            scheduleCheckIn(asked)
            return applied()
        }

        /** Nobody answered. Only a start that was never reported becomes UNCONFIRMED. */
        private fun unanswered(): TransitionResult = when (s.state) {
            GUIDED_START -> {
                go(UNCONFIRMED)
                effects += Effect.CancelCheckIns
                applied()
            }
            STARTED_SELF_REPORTED, WORKING_SELF_REPORTED -> {
                events += DomainEvent(EventType.CHECKIN_UNANSWERED, EventPayload.of("asked" to asked))
                applied()
            }
            else -> illegal()
        }

        private fun noResponse(): TransitionResult = when (s.state) {
            RECOVERY_OFFERED -> {
                go(UNCONFIRMED)
                applied()
            }
            GUIDED_START, STARTED_SELF_REPORTED, WORKING_SELF_REPORTED -> unanswered()
            else -> illegal()
        }

        /** The recovery menu's reschedule option: a validated delay out of RECOVERY_OFFERED. */
        private fun rescheduleRecovery(command: RescheduleRecovery): TransitionResult {
            if (s.state != RECOVERY_OFFERED) return illegal()
            val tomorrow = recovery.offerTomorrow && command.minutes == OccurrenceCommand.TOMORROW_MINUTES
            if (RecoveryOption.RESCHEDULE !in recovery.menu || !(command.minutes in recovery.rescheduleMinutes || tomorrow)) return illegal()
            if (!command.friction.satisfies(s.lockPolicy)) return TransitionResult.NeedsFriction(s.lockPolicy)
            events += DomainEvent(EventType.RECOVERY_ACCEPTED, EventPayload.of("choice" to "RESCHEDULE", "minutes" to command.minutes))
            go(DELAYED)
            leaveActive()
            effects += Effect.ArmChild(ChildKind.DELAY, now + command.minutes * MINUTE_MS)
            return applied()
        }

        private fun delay(command: Delay): TransitionResult {
            if (s.state !in ACTIVE) return illegal()
            if (!command.friction.satisfies(s.lockPolicy)) return TransitionResult.NeedsFriction(s.lockPolicy)
            go(DELAYED)
            leaveActive()
            effects += Effect.ArmChild(ChildKind.DELAY, now + command.minutes * MINUTE_MS)
            return applied()
        }

        private fun skip(command: Skip): TransitionResult {
            val from = s.state
            val future = from == SCHEDULED || from == ARMED
            // A calendar change may only remove something that has not fired yet.
            if (command.reason != SkipReason.USER && !future) return illegal()
            if (!future && from !in ACTIVE) return illegal()
            if (command.reason == SkipReason.USER && !command.friction.satisfies(s.lockPolicy)) {
                return TransitionResult.NeedsFriction(s.lockPolicy)
            }
            go(SKIPPED)
            if (future) effects += Effect.DisarmAlarm else leaveActive()
            return applied()
        }

        private fun leaveActive() {
            when (s.state) {
                FIRING -> {
                    effects += Effect.CancelCycleEnd
                    effects += Effect.StopRinging
                }
                GUIDED_START, STARTED_SELF_REPORTED, WORKING_SELF_REPORTED, RECOVERY_OFFERED -> effects += Effect.CancelCheckIns
                else -> Unit
            }
        }
    }
}
