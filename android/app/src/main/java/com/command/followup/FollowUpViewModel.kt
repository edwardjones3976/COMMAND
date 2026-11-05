package com.command.followup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.ApplyResult
import com.command.data.OccurrenceRepository
import com.command.data.db.OccurrenceRow
import com.command.domain.dials.DialEffects
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.RecoveryOption
import com.command.domain.dials.SkipReasonCode
import com.command.domain.model.EventSource
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.RecoveryChoice
import com.command.domain.occurrence.SkipReason
import com.command.reveille.FrictionPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why there is nothing left to answer. */
enum class FollowUpOutcome { DONE, SKIPPED, DELAYED, WAITING }

sealed interface FollowUpUiState {
    data object Loading : FollowUpUiState
    data object NotFound : FollowUpUiState

    /**
     * Waiting for an answer: whether it was started, or how it is going. [started] is whether a start was ever reported (the question
     * differs), [unconfirmed] that the questions went unanswered.
     */
    data class Ask(val title: String, val firstAction: String, val started: Boolean, val unconfirmed: Boolean) : FollowUpUiState

    /** A reset was asked for or offered: one finite menu, from the person's recovery setting. */
    data class Recovery(
        val title: String,
        val firstAction: String,
        val options: List<RecoveryOption>,
        val rescheduleMinutes: List<Int>,
        val offerTomorrow: Boolean,
    ) : FollowUpUiState

    data class Finished(val title: String, val outcome: FollowUpOutcome) : FollowUpUiState
}

/** The states in which the follow-up screen has something to ask: a start to report, a question to answer, a reset on offer. */
fun OccurrenceState.isFollowUp(): Boolean = this in FOLLOW_UP_STATES

private val FOLLOW_UP_STATES = setOf(
    OccurrenceState.ACKNOWLEDGED, OccurrenceState.GUIDED_START, OccurrenceState.STARTED_SELF_REPORTED, OccurrenceState.WORKING_SELF_REPORTED,
    OccurrenceState.UNCONFIRMED, OccurrenceState.BLOCKED, OccurrenceState.RECOVERY_OFFERED,
)

/** Something to tell the person that is not a state of the occurrence. */
enum class FollowUpNotice { RESETS_USED_UP }

/**
 * The follow-up questions for one occurrence: how is it going, a reset when it is not, and where it ends. The screen is a projection of
 * the stored occurrence, and every tap is a command to [OccurrenceRepository], so a relaunch after the process died lands where the
 * occurrence is, and an answer given from a notification shows here without anything being passed around.
 */
@HiltViewModel
class FollowUpViewModel @Inject constructor(
    private val repository: OccurrenceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<FollowUpUiState>(FollowUpUiState.Loading)
    val state: StateFlow<FollowUpUiState> = _state.asStateFlow()

    private val _friction = MutableStateFlow<FrictionPrompt?>(null)
    val friction: StateFlow<FrictionPrompt?> = _friction.asStateFlow()

    private val _notice = MutableStateFlow<FollowUpNotice?>(null)
    val notice: StateFlow<FollowUpNotice?> = _notice.asStateFlow()

    private var occurrenceId: String? = null
    private var observing: Job? = null

    fun show(id: String) {
        if (occurrenceId == id) return
        // Another occurrence replaces the one on screen: the old one is no longer watched, so it can never draw over the new one.
        observing?.cancel()
        occurrenceId = id
        _friction.value = null
        _notice.value = null
        _state.value = FollowUpUiState.Loading
        observing = viewModelScope.launch {
            repository.observe(id).collectLatest { row ->
                if (row?.state == OccurrenceState.ACKNOWLEDGED) {
                    // Acknowledged but never moved on to guided start (the process died between the two): finish the step, so that the answers
                    // on this screen are ones the state machine will take. The row that follows says where it is.
                    repository.apply(row.id, OccurrenceCommand.StartGuidance, EventSource.USER)
                }
                _state.value = project(row)
            }
        }
    }

    private suspend fun project(row: OccurrenceRow?): FollowUpUiState {
        if (row == null) return FollowUpUiState.NotFound
        val brief = repository.missionBrief(row.missionId) ?: return FollowUpUiState.NotFound
        return when (row.state) {
            OccurrenceState.GUIDED_START, OccurrenceState.BLOCKED ->
                FollowUpUiState.Ask(brief.title, brief.firstAction, started = false, unconfirmed = false)
            OccurrenceState.ACKNOWLEDGED -> FollowUpUiState.Loading // being moved on to guided start, see show()
            OccurrenceState.STARTED_SELF_REPORTED, OccurrenceState.WORKING_SELF_REPORTED ->
                FollowUpUiState.Ask(brief.title, brief.firstAction, started = true, unconfirmed = false)
            OccurrenceState.UNCONFIRMED -> FollowUpUiState.Ask(brief.title, brief.firstAction, started = false, unconfirmed = true)
            OccurrenceState.RECOVERY_OFFERED -> {
                val policy = DialEffects.recoveryPolicy(repository.dialsOf(row).recoveryStructure)
                FollowUpUiState.Recovery(brief.title, brief.firstAction, policy.menu, policy.rescheduleMinutes, policy.offerTomorrow)
            }
            OccurrenceState.COMPLETED_SELF_REPORTED -> FollowUpUiState.Finished(brief.title, FollowUpOutcome.DONE)
            OccurrenceState.SKIPPED -> FollowUpUiState.Finished(brief.title, FollowUpOutcome.SKIPPED)
            OccurrenceState.DELAYED -> FollowUpUiState.Finished(brief.title, FollowUpOutcome.DELAYED)
            OccurrenceState.SCHEDULED, OccurrenceState.ARMED, OccurrenceState.FIRING, OccurrenceState.MISSED, OccurrenceState.SAFETY_SILENCED ->
                FollowUpUiState.Finished(brief.title, FollowUpOutcome.WAITING)
        }
    }

    // --- the answers --------------------------------------------------------------------------------------------

    fun started() = command(OccurrenceCommand.ReportStarted)
    fun working() = command(OccurrenceCommand.ReportWorking)
    fun done() = command(OccurrenceCommand.ReportDone)

    /** "Need a reset": the recovery menu, unless this occurrence has already had all the resets it is allowed. */
    fun needReset() {
        val id = occurrenceId ?: return
        viewModelScope.launch {
            if (repository.apply(id, OccurrenceCommand.RequestRecovery, EventSource.USER) is ApplyResult.RecoveryExhausted) {
                _notice.value = FollowUpNotice.RESETS_USED_UP
            }
        }
    }

    fun delay(minutes: Int) = withFriction { evidence -> OccurrenceCommand.Delay(minutes, evidence) }
    fun skip() = withFriction { evidence -> OccurrenceCommand.Skip(SkipReason.USER, evidence) }

    // --- the recovery menu --------------------------------------------------------------------------------------

    fun restart() = command(OccurrenceCommand.AcceptRecovery(RecoveryChoice.RESET))
    fun rescheduleTo(minutes: Int) = withFriction { evidence -> OccurrenceCommand.RescheduleRecovery(minutes, evidence) }

    /** Not now: the occurrence is left unconfirmed, which is a record of what happened and not a mark against anyone. */
    fun notNow() = command(OccurrenceCommand.NoResponse)

    fun dismissNotice() { _notice.value = null }

    // --- Mission Lock ------------------------------------------------------------------------------------------

    fun dismissFriction() { _friction.value = null }
    /** The dialog goes at the tap. If the mission wants more than was given, the command asks again and it comes back. */
    fun confirmFriction(evidence: FrictionEvidence) {
        val prompt = _friction.value ?: return
        _friction.value = null
        prompt.retry(evidence)
    }

    val reasons: List<SkipReasonCode> = SkipReasonCode.values().toList()

    private fun command(c: OccurrenceCommand) {
        val id = occurrenceId ?: return
        _notice.value = null
        viewModelScope.launch { repository.apply(id, c, EventSource.USER) }
    }

    /** Tries the command bare; if the mission is fixed and wants more, asks, then tries again with what the person gave. */
    private fun withFriction(build: (FrictionEvidence) -> OccurrenceCommand) {
        val id = occurrenceId ?: return
        fun attempt(evidence: FrictionEvidence) {
            viewModelScope.launch {
                when (val result = repository.apply(id, build(evidence), EventSource.USER)) {
                    is ApplyResult.NeedsFriction -> _friction.value = FrictionPrompt(result.required) { given -> attempt(given) }
                    else -> _friction.value = null
                }
            }
        }
        attempt(FrictionEvidence.None)
    }
}
