package com.command.domain.occurrence

import com.command.domain.dials.Dials
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy

/** Everything that can happen to one occurrence. The label is what the audit log records. */
sealed class OccurrenceCommand(val label: String) {
    /** SCHEDULED to ARMED. Legal only from SCHEDULED, so a finished occurrence can never be re-armed. */
    data class Arm(val mode: ArmMode) : OccurrenceCommand("ARM")

    /** ARMED back to SCHEDULED without ringing: a global pause. Pause is not a state of its own. */
    data object Disarm : OccurrenceCommand("DISARM")

    data object Fire : OccurrenceCommand("FIRE")

    /** A handshake chain succeeded. The accessible-alternative flag is recorded on the challenge row, not here. */
    data class Acknowledge(val accessibleAlternative: Boolean = false) : OccurrenceCommand("ACKNOWLEDGE")

    /** Always legal while ringing and never behind friction; it is not success. */
    data object SafetySilence : OccurrenceCommand("SAFETY_SILENCE")

    /** [lateMs] is how long after its time an alarm that was missed while the phone was off is being recorded; it goes in the event. */
    data class Miss(val reason: MissReason, val lateMs: Long? = null) : OccurrenceCommand("MISS")

    data object StartGuidance : OccurrenceCommand("START_GUIDANCE")

    /** An explicit tap to pick up a missed or silenced occurrence. Never automatic. */
    data object Resume : OccurrenceCommand("RESUME")

    data object ReportStarted : OccurrenceCommand("REPORT_STARTED")
    data object ReportWorking : OccurrenceCommand("REPORT_WORKING")
    data object ReportDone : OccurrenceCommand("REPORT_DONE")
    data object ReportBlocked : OccurrenceCommand("REPORT_BLOCKED")

    /** The user asking for a reset, or the system offering one after silence. */
    data object RequestRecovery : OccurrenceCommand("REQUEST_RECOVERY")
    data class AcceptRecovery(val choice: RecoveryChoice) : OccurrenceCommand("ACCEPT_RECOVERY")

    /**
     * Take the recovery menu's reschedule option. [minutes] must be one of the menu's offered delays, or
     * [TOMORROW_MINUTES]; the same friction as any other delay applies to a rigid mission.
     */
    data class RescheduleRecovery(val minutes: Int, val friction: FrictionEvidence = FrictionEvidence.None) : OccurrenceCommand("RESCHEDULE_RECOVERY") {
        init { require(minutes in 1..MAX_DELAY_MINUTES) { "reschedule must be 1..$MAX_DELAY_MINUTES minutes" } }
    }

    /**
     * A follow-up alarm fired. The index must match the next expected check-in, so stale alarms are ignored. [lateMs] is how long after
     * its time the platform delivered it (a dozing phone may hold an alarm back); it is stored with the question.
     */
    data class CheckInDue(val index: Int, val lateMs: Long = 0) : OccurrenceCommand("CHECK_IN_DUE")

    /** A prompt (a recovery offer) went unanswered. */
    data object NoResponse : OccurrenceCommand("NO_RESPONSE")

    data class Delay(val minutes: Int, val friction: FrictionEvidence = FrictionEvidence.None) : OccurrenceCommand("DELAY") {
        init { require(minutes in 1..MAX_DELAY_MINUTES) { "delay must be 1..$MAX_DELAY_MINUTES minutes" } }
    }

    data class Skip(val reason: SkipReason, val friction: FrictionEvidence = FrictionEvidence.None) : OccurrenceCommand("SKIP")

    companion object {
        const val MAX_DELAY_MINUTES = 24 * 60

        /** "Tomorrow, at this time": the same local time as now on the next calendar day (not 24 hours later: on a day the clocks change that is an hour off). */
        const val TOMORROW_MINUTES = 24 * 60
    }
}

/** What the machine needs to know about the occurrence and its mission to decide. */
data class OccurrenceSnapshot(
    val state: OccurrenceState,
    val dials: Dials,
    /** Friction already derived from the mission flexibility, its override and the rigidity dial. */
    val lockPolicy: LockPolicy,
    val recoveryOffers: Int = 0,
    val checkInsAsked: Int = 0,
    /**
     * When the protected window (sleep, leisure, driving) that contains now ends, or null if now is not protected.
     * While set, follow-up questions are deferred to after it ends instead of being asked or silently lost.
     */
    val restEndsAtUtc: Long? = null,
    /** The zone the occurrence's times are kept in, for what is said in local terms: "tomorrow, at the same time". */
    val zoneId: String = "UTC",
)

sealed interface TransitionResult {
    data class Applied(
        val snapshot: OccurrenceSnapshot,
        val events: List<DomainEvent>,
        val effects: List<Effect>,
    ) : TransitionResult {
        val state: OccurrenceState get() = snapshot.state
    }

    /** The command is not legal from this state (including stale alarms). The repository logs it and changes nothing. */
    data class Illegal(val from: OccurrenceState, val command: String) : TransitionResult

    /** Delaying or skipping a fixed commitment needs more than was supplied. */
    data class NeedsFriction(val required: LockPolicy) : TransitionResult

    /** The finite recovery budget is spent. */
    data class RecoveryExhausted(val offersUsed: Int) : TransitionResult
}
