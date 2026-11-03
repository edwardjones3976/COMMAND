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

    data class Miss(val reason: MissReason) : OccurrenceCommand("MISS")

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

    /** A follow-up alarm fired. The index must match the next expected check-in, so stale alarms are ignored. */
    data class CheckInDue(val index: Int) : OccurrenceCommand("CHECK_IN_DUE")

    /** A prompt (a recovery offer) went unanswered. */
    data object NoResponse : OccurrenceCommand("NO_RESPONSE")

    data class Delay(val minutes: Int, val friction: FrictionEvidence = FrictionEvidence.None) : OccurrenceCommand("DELAY") {
        init { require(minutes in 1..MAX_DELAY_MINUTES) { "delay must be 1..$MAX_DELAY_MINUTES minutes" } }
    }

    data class Skip(val reason: SkipReason, val friction: FrictionEvidence = FrictionEvidence.None) : OccurrenceCommand("SKIP")

    companion object {
        const val MAX_DELAY_MINUTES = 24 * 60
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
    /** Inside a protected window (sleep, leisure, driving): follow-up questions are suppressed. */
    val inRestWindow: Boolean = false,
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
