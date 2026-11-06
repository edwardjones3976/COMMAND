package com.command.handshake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.ProfileHandshake
import com.command.data.ProfileRepository
import com.command.data.SaveChainResult
import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainLimits
import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.ChainValidation
import com.command.domain.dials.DialEffects
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.StepSpec
import com.command.domain.handshake.ChainState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A step while it is being edited. [key] stays with it as steps are moved and removed, so that the screen can tell which is which. */
data class StepDraft(val key: Int, val mode: HandshakeMode, val tries: Int, val timeoutSec: Int, val holdSeconds: Int) {
    fun toSpec() = StepSpec(mode, tries, timeoutSec, holdSeconds)
}

sealed interface HandshakeEditorUiState {
    data object Loading : HandshakeEditorUiState
    data object NotFound : HandshakeEditorUiState

    /**
     * [changed] says that the draft is not what is in force now. [usingStandard] is that the profile has no chain of its own (the standard
     * chain of its difficulty is in force); [storedUnusable] that something is stored that cannot be used, so the standard chain is in force
     * all the same. [saved] is that the last save went through and the draft is what it wrote; [restored] that the profile was just put back
     * on the standard handshake and the draft is that: both stay until the draft changes (an edit that changes nothing leaves them). [inUse]
     * is that alarms are fired with this profile (the active one, or one a mission names): saving a profile no alarm uses changes nothing
     * yet, and the screen says so.
     */
    data class Editing(
        val profileName: String,
        val difficulty: Int,
        val op: ChainOp,
        val steps: List<StepDraft>,
        val issues: List<ChainIssue>,
        val changed: Boolean,
        val usingStandard: Boolean,
        val storedUnusable: Boolean,
        val saved: Boolean,
        val restored: Boolean,
        val inUse: Boolean,
    ) : HandshakeEditorUiState {
        val canSave: Boolean get() = changed && issues.isEmpty()
        val canAddStep: Boolean get() = steps.size < ChainLimits.MAX_STEPS
    }
}

/**
 * Edits the handshake chain of one profile: how its steps combine, which they are, how many tries each allows, how long a try may take and
 * how long a press lasts. The draft is checked as it changes by the same rules the storage and the engine use ([ChainValidation]); nothing
 * is written until Save, and a chain with a problem cannot be saved. What an asynchronous answer (the profile that was read, a save, a
 * return to the standard chain) says is applied only to the profile it was asked for, and a save says "saved" only of the draft it wrote.
 * The row is followed for as long as the editor is open, because the dials screen, and later a sync, write it too: a draft nobody has touched
 * becomes what is in force now, and one that has been touched stays, measured against what is in force now.
 */
@HiltViewModel
class HandshakeEditorViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {

    private val _state = MutableStateFlow<HandshakeEditorUiState>(HandshakeEditorUiState.Loading)
    val state: StateFlow<HandshakeEditorUiState> = _state.asStateFlow()

    private var profileId: String? = null
    private var inForce: ChainSpec? = null
    private var inUse = true
    private var opening: Job? = null

    /** The last save asked for. Saves run one after another, and a return to the standard waits for them: what the person asked for last is what stays. */
    private var saving: Job? = null

    /** The profile being put back on the standard. The answer shows it when it is read back, so the row is not followed until then. */
    private var returning: String? = null
    private var nextKey = 0

    /** A seam for tests: runs before a save is written, so that a slow write can be made to come after something else (a return to the standard). */
    internal var beforeSaveWrite: suspend () -> Unit = {}

    /** A seam for tests: runs after the standard chain has been written back and before the profile is read again, so that something can land between the two. */
    internal var afterUseDefault: suspend () -> Unit = {}

    fun open(id: String) {
        if (profileId == id) return
        profileId = id
        inForce = null
        opening?.cancel()
        _state.value = HandshakeEditorUiState.Loading
        opening = viewModelScope.launch {
            // The row, and whether alarms are fired with this profile, are followed for as long as the editor is open, as the list follows them:
            // the line at the top and the note of a save say what is true now, and not what was true when the editor was opened.
            combine(profiles.observeHandshake(id), profiles.observeInUse(id)) { profile, used -> profile to used }.collect { (profile, used) ->
                // Another profile may have been asked for while this one was read.
                if (profileId != id) return@collect
                inUse = used
                if (returning == id) return@collect
                if (profile == null) _state.value = HandshakeEditorUiState.NotFound else follow(profile, used)
            }
        }
    }

    /**
     * Takes in the profile as it is now. An editor with nothing to show shows it, and so does one with a draft nobody has touched that is no
     * longer what is in force. A draft the person has changed, or one a save is still writing, stays: it is theirs, and whether it is a change
     * still to save is measured against what is in force now. A draft that is what is in force keeps its steps and its notes.
     */
    private fun follow(profile: ProfileHandshake, used: Boolean) {
        val current = _state.value as? HandshakeEditorUiState.Editing
        if (current == null) return show(profile, used)
        val touched = current.changed || saving?.isActive == true
        inForce = profile.chain
        val now = current.copy(
            profileName = profile.name, difficulty = profile.difficulty, usingStandard = profile.own == null, storedUnusable = profile.storedUnusable,
        ).with().copy(inUse = used)
        if (now.changed && !touched) show(profile, used) else _state.value = now
    }

    private fun show(profile: ProfileHandshake, used: Boolean, restored: Boolean = false) {
        inForce = profile.chain
        inUse = used
        _state.value = editing(
            profile.name, profile.difficulty, profile.chain.op, profile.chain.steps.map { draft(it) }, profile.own == null, profile.storedUnusable,
            saved = false, restored = restored,
        )
    }

    private fun draft(step: StepSpec) = StepDraft(nextKey++, step.mode, step.maxRetries, step.timeoutSec, step.holdSeconds)

    private fun editing(
        name: String,
        difficulty: Int,
        op: ChainOp,
        steps: List<StepDraft>,
        usingStandard: Boolean,
        storedUnusable: Boolean,
        saved: Boolean,
        restored: Boolean,
    ): HandshakeEditorUiState.Editing {
        val specs = steps.map { it.toSpec() }
        val issues = ChainValidation.issues(op, specs)
        return HandshakeEditorUiState.Editing(
            name, difficulty, op, steps, issues, differsFromInForce(op, specs, issues), usingStandard, storedUnusable, saved, restored, inUse,
        )
    }

    /**
     * True when the draft is not the chain in force (or cannot be used at all). How the steps combine is compared only with two steps or
     * more: with one it is not shown, and it decides nothing, so it is not a change a person can see or undo.
     */
    private fun differsFromInForce(op: ChainOp, specs: List<StepSpec>, issues: List<ChainIssue>): Boolean {
        if (issues.isNotEmpty()) return true
        val now = inForce ?: return true
        return now.steps != specs || (specs.size >= 2 && now.op != op)
    }

    private inline fun edit(change: (HandshakeEditorUiState.Editing) -> HandshakeEditorUiState.Editing) {
        _state.update { current ->
            if (current is HandshakeEditorUiState.Editing) change(current) else current
        }
    }

    /**
     * The draft with [op] and [steps]. What the last save, or going back to the standard, said stays as long as the draft is what it was: an
     * edit that changes nothing (the way that is chosen already, a step that is not there) leaves the note, and one that changes something
     * takes it away.
     */
    private fun HandshakeEditorUiState.Editing.with(op: ChainOp = this.op, steps: List<StepDraft> = this.steps): HandshakeEditorUiState.Editing {
        val unchanged = op == this.op && steps == this.steps
        return editing(profileName, difficulty, op, steps, usingStandard, storedUnusable, saved = saved && unchanged, restored = restored && unchanged)
    }

    /** True when another step than [key] asks for what [mode] would ask for: a way that is only planned asks for the way that takes its place ([HandshakeMode.offered]), which the rules judge by. */
    private fun HandshakeEditorUiState.Editing.asksFor(key: Int, mode: HandshakeMode) = steps.any { it.key != key && it.mode.offered == mode.offered }

    fun setOp(op: ChainOp) = edit { it.with(op = op) }

    fun addStep() = edit { current ->
        if (!current.canAddStep) return@edit current
        // The first of the built ways that no step asks for yet: the same way twice is not allowed, so a new step starts as one that can stay.
        val mode = BUILT_WAYS.firstOrNull { way -> !current.asksFor(NO_KEY, way) } ?: return@edit current
        current.with(steps = current.steps + draftOf(mode))
    }

    fun removeStep(key: Int) = edit { current ->
        if (current.steps.size <= ChainLimits.MIN_STEPS) return@edit current
        current.with(steps = current.steps.filterNot { it.key == key })
    }

    fun moveStep(key: Int, up: Boolean) = edit { current ->
        val index = current.steps.indexOfFirst { it.key == key }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in current.steps.indices) return@edit current
        val moved = current.steps.toMutableList().apply {
            val step = removeAt(index)
            add(target, step)
        }
        current.with(steps = moved)
    }

    /** A way that another step already asks for, or one that is only planned, is not offered by the screen; asking for it here changes nothing. */
    fun setMode(key: Int, mode: HandshakeMode) = edit { current ->
        if (!mode.available || current.asksFor(key, mode)) return@edit current
        current.with(steps = current.steps.map { step ->
            if (step.key != key || step.mode == mode) step else step.copy(mode = mode, holdSeconds = if (mode == HandshakeMode.HOLD) HOLD_START else 0)
        })
    }

    fun changeTries(key: Int, by: Int) = change(key) { it.copy(tries = (it.tries + by).coerceIn(ChainLimits.MIN_TRIES, ChainLimits.MAX_TRIES)) }

    fun changeTimeout(key: Int, bySteps: Int) =
        change(key) { it.copy(timeoutSec = (it.timeoutSec + bySteps * TIMEOUT_STEP).coerceIn(ChainLimits.MIN_TIMEOUT_SECONDS, ChainLimits.MAX_TIMEOUT_SECONDS)) }

    fun changeHold(key: Int, by: Int) = change(key) { step ->
        if (step.mode != HandshakeMode.HOLD) step else step.copy(holdSeconds = (step.holdSeconds.takeIf { it > 0 } ?: HOLD_START).plus(by).coerceIn(ChainLimits.MIN_HOLD_SECONDS, ChainLimits.MAX_HOLD_SECONDS))
    }

    private inline fun change(key: Int, crossinline how: (StepDraft) -> StepDraft) = edit { current ->
        current.with(steps = current.steps.map { if (it.key == key) how(it) else it })
    }

    private fun draftOf(mode: HandshakeMode) = StepDraft(nextKey++, mode, DEFAULT_TRIES, DEFAULT_TIMEOUT_SECONDS, if (mode == HandshakeMode.HOLD) HOLD_START else 0)

    /**
     * Saves the draft as the profile's own chain, if it can be used. The write takes a moment and the person may go on editing: "saved" is
     * said of the draft only if it is what was written, and a change made meanwhile stays a change still to save.
     */
    fun save() {
        val id = profileId ?: return
        val current = _state.value as? HandshakeEditorUiState.Editing ?: return
        if (!current.canSave) return
        val spec = ChainSpec(current.op, current.steps.map { it.toSpec() })
        val earlier = saving
        saving = viewModelScope.launch {
            earlier?.join()
            beforeSaveWrite()
            val result = profiles.saveChain(id, spec)
            if (profileId != id) return@launch
            when (result) {
                SaveChainResult.Saved -> {
                    inForce = spec
                    edit { cur ->
                        val now = cur.with()
                        now.copy(usingStandard = false, storedUnusable = false, saved = !now.changed)
                    }
                }
                is SaveChainResult.Invalid -> edit { it.copy(issues = result.issues) }
                SaveChainResult.NotFound -> _state.value = HandshakeEditorUiState.NotFound
            }
        }
    }

    /**
     * Goes back to the standard chain of the profile's difficulty, and shows it. The screen asks first: this deletes what was saved, and the
     * changes that were not saved with it. The editor waits for the answer, so that nothing can be edited (the answer would throw it away)
     * and no save can be started in the meantime. A save that is already going finishes first, so that it cannot bring back what the person
     * has just asked to delete. The note says "back on the standard" only if the profile that is read back is on it.
     */
    fun useStandard() {
        val id = profileId ?: return
        if (_state.value !is HandshakeEditorUiState.Editing) return
        _state.value = HandshakeEditorUiState.Loading
        val earlier = saving
        returning = id
        viewModelScope.launch {
            try {
                earlier?.join()
                if (!profiles.useDefault(id)) {
                    if (profileId == id) _state.value = HandshakeEditorUiState.NotFound
                    return@launch
                }
                afterUseDefault()
                val profile = profiles.get(id)
                if (profileId != id) return@launch
                if (profile == null) _state.value = HandshakeEditorUiState.NotFound else show(profile, inUse, restored = profile.own == null)
            } finally {
                if (returning == id) returning = null
            }
        }
    }

    private companion object {
        /** A key no step has, for asking whether any step asks for a way. */
        const val NO_KEY = -1

        /** The ways a new step can start as, in the order they are offered. */
        val BUILT_WAYS = listOf(HandshakeMode.HOLD, HandshakeMode.CONFIRM_SEQUENCE, HandshakeMode.TYPING, HandshakeMode.TWO_STEP)
        const val HOLD_START = ChainState.DEFAULT_HOLD_SECONDS
        const val TIMEOUT_STEP = 10
        val DEFAULT_TRIES = DialEffects.defaultChain(3).steps.first().maxRetries
        val DEFAULT_TIMEOUT_SECONDS = DialEffects.defaultChain(3).steps.first().timeoutSec
    }
}
