package com.command.reveille

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.ApplyExtras
import com.command.data.ApplyResult
import com.command.data.ChallengeRecord
import com.command.data.OccurrenceRepository
import com.command.data.db.OccurrenceRow
import com.command.domain.dials.DialEffects
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.SkipReasonCode
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.EventSource
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.domain.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the screen has nothing more to do. */
enum class ClosedOutcome { STARTED, SKIPPED, DELAYED, MISSED, DONE, NOT_RINGING }

sealed interface ReveilleUiState {
    data object Loading : ReveilleUiState
    data object NotFound : ReveilleUiState

    /** The alarm is ringing: hold to acknowledge, or silence it. [occurrenceId] says which one, so that nothing the screen remembers about one ring is taken for another's. */
    data class Firing(
        val occurrenceId: String,
        val title: String,
        val holdSeconds: Int,
        val earlyRelease: Boolean,
        val offerAccessibleConfirm: Boolean,
    ) : ReveilleUiState

    /** Silenced or missed: nothing was started. The user can pick it up again or let it go. */
    data class Silenced(val title: String, val missed: Boolean = false) : ReveilleUiState

    /** Acknowledged: the one concrete first step, and the one-tap answers. */
    data class Direction(val title: String, val firstAction: String, val starterMinutes: Int) : ReveilleUiState

    data class Closed(val title: String, val outcome: ClosedOutcome) : ReveilleUiState
}

/** A skip or delay that needs the user to do a little more first (Mission Lock), and what. */
data class FrictionPrompt(val required: LockPolicy, val retry: (FrictionEvidence) -> Unit)

/**
 * Drives the lock-screen flow for one occurrence. The screen is a projection of the stored occurrence: every tap becomes a
 * command to [OccurrenceRepository], so a relaunch after the process died lands on the right step.
 */
@HiltViewModel
class ReveilleViewModel @Inject constructor(
    private val repository: OccurrenceRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow<ReveilleUiState>(ReveilleUiState.Loading)
    val state: StateFlow<ReveilleUiState> = _state.asStateFlow()

    private val _friction = MutableStateFlow<FrictionPrompt?>(null)
    val friction: StateFlow<FrictionPrompt?> = _friction.asStateFlow()

    private var occurrenceId: String? = null
    private var observing: Job? = null

    /**
     * Advances whenever the screen a question was asked on is gone: another occurrence takes over, the screen changes kind, or the alarm is
     * silenced. A command that was tapped on the old screen and is answered later finds the number changed, and asks nothing.
     */
    private var screen = 0

    /** Runs between a command being decided and its answer being used: a seam for the one test that has to place a screen change in between. */
    internal var beforeAnswer: suspend () -> Unit = {}
    private var holdAttempts = 0
    private var earlyRelease = false
    var accessibilityTouchExploration = false

    fun show(id: String) {
        if (occurrenceId == id) return
        // Another occurrence replaces the one on screen: stop watching the old one, so it can never draw over the new one,
        // and forget anything that belonged to it.
        observing?.cancel()
        screen++
        occurrenceId = id
        holdAttempts = 0
        earlyRelease = false
        _friction.value = null
        _state.value = ReveilleUiState.Loading
        observing = viewModelScope.launch {
            repository.observe(id).collectLatest { row ->
                if (row?.state == OccurrenceState.ACKNOWLEDGED) {
                    // The process died between acknowledging and starting the guidance (the two commit together now, but an
                    // older row or another path can still leave this): finish the step instead of showing a screen whose
                    // button the state machine would refuse.
                    repository.apply(row.id, OccurrenceCommand.StartGuidance, EventSource.USER)
                }
                val projected = project(row)
                // A question belongs to the screen it was asked on. When the alarm is answered some other way (the notification's Safety
                // silence) or runs out while a Mission Lock question is open over it, the screen changes under the question: it goes with
                // the screen, so that a dialog about a ringing alarm is never left over one that has stopped.
                if (projected::class != _state.value::class) {
                    screen++
                    _friction.value = null
                }
                _state.value = projected
            }
        }
    }

    private suspend fun project(row: OccurrenceRow?): ReveilleUiState {
        if (row == null) return ReveilleUiState.NotFound
        val brief = repository.missionBrief(row.missionId) ?: return ReveilleUiState.NotFound
        return when (row.state) {
            OccurrenceState.FIRING -> {
                val chain = DialEffects.defaultChain(repository.dialsOf(row).handshakeDifficulty)
                // Only the hold challenge exists so far; typing, sequences and two-step arrive with the full handshake engine.
                val hold = chain.steps.firstOrNull { it.mode == HandshakeMode.HOLD }?.holdSeconds ?: FALLBACK_HOLD_SECONDS
                ReveilleUiState.Firing(row.id, brief.title, hold, earlyRelease, accessibilityTouchExploration)
            }
            OccurrenceState.SAFETY_SILENCED -> ReveilleUiState.Silenced(brief.title)
            OccurrenceState.MISSED -> ReveilleUiState.Silenced(brief.title, missed = true)
            OccurrenceState.ACKNOWLEDGED, OccurrenceState.GUIDED_START,
            OccurrenceState.UNCONFIRMED, OccurrenceState.BLOCKED, OccurrenceState.RECOVERY_OFFERED ->
                ReveilleUiState.Direction(brief.title, brief.firstAction, brief.starterMinutes)
            OccurrenceState.STARTED_SELF_REPORTED, OccurrenceState.WORKING_SELF_REPORTED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.STARTED)
            OccurrenceState.COMPLETED_SELF_REPORTED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.DONE)
            OccurrenceState.SKIPPED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.SKIPPED)
            OccurrenceState.DELAYED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.DELAYED)
            OccurrenceState.SCHEDULED, OccurrenceState.ARMED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.NOT_RINGING)
        }
    }

    // --- the hold challenge ------------------------------------------------------------------------------------

    /** A new press clears the "released early" hint from the last one. */
    fun holdStarted() {
        if (!earlyRelease) return
        earlyRelease = false
        (_state.value as? ReveilleUiState.Firing)?.let { _state.value = it.copy(earlyRelease = false) }
    }

    fun holdCompleted(startedAtMs: Long, endedAtMs: Long) = acknowledge(ChallengeOutcome.SUCCESS, startedAtMs, endedAtMs)

    /** For people who cannot hold a finger down: an assistive confirm, recorded as such, never as a plain success. */
    fun accessibleConfirm() = acknowledge(ChallengeOutcome.ACCESSIBLE_ALT, clock.nowMs(), clock.nowMs())

    private fun acknowledge(outcome: ChallengeOutcome, startedAtMs: Long, endedAtMs: Long) {
        val id = occurrenceId ?: return
        holdAttempts += 1
        viewModelScope.launch {
            val record = ChallengeRecord(0, HandshakeMode.HOLD, holdAttempts, outcome, startedAtMs, endedAtMs)
            // Acknowledging is not starting: the next step is the direction, which is where starting is reported. Both
            // changes commit together, so a process that dies in between cannot leave a screen with a refused button.
            repository.inTransaction(EventSource.USER) { tx ->
                val result = tx.apply(id, OccurrenceCommand.Acknowledge(accessibleAlternative = outcome == ChallengeOutcome.ACCESSIBLE_ALT), EventSource.USER, ApplyExtras(challenges = listOf(record)))
                if (result is ApplyResult.Applied) tx.apply(id, OccurrenceCommand.StartGuidance, EventSource.USER)
            }
        }
    }

    fun holdReleasedEarly(startedAtMs: Long, endedAtMs: Long) {
        val id = occurrenceId ?: return
        holdAttempts += 1
        earlyRelease = true
        viewModelScope.launch {
            repository.recordChallenge(id, ChallengeRecord(0, HandshakeMode.HOLD, holdAttempts, ChallengeOutcome.FAILED, startedAtMs, endedAtMs))
        }
        (_state.value as? ReveilleUiState.Firing)?.let { _state.value = it.copy(earlyRelease = true) }
    }

    // --- everything else is one tap ---------------------------------------------------------------------------

    /** One tap, from anywhere, including from over a Mission Lock question: that question has nothing left to wait for. */
    fun silence() {
        screen++
        _friction.value = null
        command(OccurrenceCommand.SafetySilence)
    }

    fun resume() = command(OccurrenceCommand.Resume)
    fun started() = command(OccurrenceCommand.ReportStarted)

    fun delay(minutes: Int) = withFriction { evidence -> OccurrenceCommand.Delay(minutes, evidence) }
    fun skip() = withFriction { evidence -> OccurrenceCommand.Skip(SkipReason.USER, evidence) }

    fun dismissFriction() { _friction.value = null }
    fun confirmFriction(evidence: FrictionEvidence) { _friction.value?.retry?.invoke(evidence) }

    private fun command(c: OccurrenceCommand) {
        val id = occurrenceId ?: return
        viewModelScope.launch { repository.apply(id, c, EventSource.USER) }
    }

    /** Tries the command bare; if the mission is fixed and wants more, asks, then tries again with what the user gave. */
    private fun withFriction(build: (FrictionEvidence) -> OccurrenceCommand) {
        val id = occurrenceId ?: return
        val askedOn = screen
        // The screen can change while a command is being decided (another ring takes over, the alarm is silenced from its notification, it
        // runs out). What would be asked then is about nothing the person can see, so it is not asked, and a question that is waiting is
        // not answered for a ring that is no longer on the screen.
        fun moved() = id != occurrenceId || askedOn != screen
        fun attempt(evidence: FrictionEvidence) {
            viewModelScope.launch {
                val result = repository.apply(id, build(evidence), EventSource.USER)
                beforeAnswer()
                if (moved()) return@launch
                when (result) {
                    is ApplyResult.NeedsFriction ->
                        _friction.value = FrictionPrompt(result.required) { given -> if (moved()) _friction.value = null else attempt(given) }
                    else -> _friction.value = null
                }
            }
        }
        attempt(FrictionEvidence.None)
    }

    val reasons: List<SkipReasonCode> = SkipReasonCode.values().toList()

    private companion object {
        /** Used until the full handshake engine can run the user's chosen method. */
        const val FALLBACK_HOLD_SECONDS = 3
    }
}

