package com.command.domain.handshake

import com.command.domain.dials.ChainOp
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.HandshakeMode
import com.command.domain.model.ChallengeOutcome

/** Where one step of a chain stands. */
enum class StepStatus { OPEN, PASSED, STUCK }

/** Whether the chain as a whole can still be satisfied. */
enum class ChainStatus { RUNNING, SATISFIED, EXHAUSTED }

/**
 * Which part of a step is on screen. A two-step is the person saying what they will do first, and it follows an acknowledgement: when
 * nothing in the chain has acknowledged the alarm yet, it begins with a hold of its own ([ACKNOWLEDGE]) and only then asks the question
 * ([MAIN]). Every other step is all [MAIN].
 */
enum class StepPart { ACKNOWLEDGE, MAIN }

/**
 * How one step stands. [mode] is what is on offer now: the mode the chain asked for, or the one that took its place (a mode that cannot be
 * asked for yet, or one the person ran out of tries on). [assistive] is the hold's own way for a person who cannot hold a finger down: three
 * deliberate taps in place of the press. [attempts] counts the tries made on the part and mode that are on offer. [acknowledged] is a
 * two-step's: the acknowledgement it needs has been given, by a hold of its own or by another step that passed, and [acknowledgedAssistively]
 * says that its own hold was the assistive confirm. [way] counts the times what is on screen for the step has changed (the hold's assistive
 * confirm, a fallback mode, the question that follows a two-step's hold): the tries of each way are numbered from one, so it is part of the
 * identity of a try ([TryId]). [lastMiss] is how the last try on the way that is on screen ended, if it ended wrong (FAILED, or TIMEOUT for a try
 * that took too long): what the screen says under the challenge comes from it, and it is cleared when the way changes. It is only read while
 * the step is open: a step that passed, or gave out on its last try, keeps what it had and is not on screen again.
 */
data class StepProgress(
    val position: Int,
    val mode: HandshakeMode,
    val assistive: Boolean = false,
    val attempts: Int = 0,
    val changedMode: Boolean = false,
    val status: StepStatus = StepStatus.OPEN,
    val passedAssistively: Boolean = false,
    val acknowledged: Boolean = false,
    val acknowledgedAssistively: Boolean = false,
    val way: Int = 0,
    val lastMiss: ChallengeOutcome? = null,
) {
    val part: StepPart get() = if (mode == HandshakeMode.TWO_STEP && !acknowledged) StepPart.ACKNOWLEDGE else StepPart.MAIN

    /** The challenge that is really on screen: the hold that a two-step begins with, otherwise the step's own mode. */
    val shown: HandshakeMode get() = if (part == StepPart.ACKNOWLEDGE) HandshakeMode.HOLD else mode
}

/**
 * One try, as the record of it will be written: the audit row for a step of a chain, without the times (the screen knows those). A row
 * names the step, the mode and the number of the try on its way, and not the way ([StepProgress.way]): [ChainRunner.restore] works out
 * the way from the rows that came before.
 */
data class Attempt(val position: Int, val mode: HandshakeMode, val attemptNo: Int, val outcome: ChallengeOutcome)

/**
 * The try an event is about: the step, which way it is on ([StepProgress.way]: the tries of each way are numbered from one, so a repeated
 * event about the hold in front of a two-step must not be taken for its first try at the question), and which try on that way
 * ([ChainState.tryOnScreen] says what is on screen now). An event that is not about the try on screen is a duplicate or a late one (a
 * double tap, a timer that outlived a switch, a screen that was recreated) and changes nothing.
 */
data class TryId(val position: Int, val tryNo: Int, val way: Int = 0)

/** What a person did on the try that is on screen. */
sealed interface ChainEvent {
    /** They did what the step asked. */
    data class Passed(val at: TryId) : ChainEvent

    /** They used the hold's assistive confirm. It acknowledges, and is recorded as the accessible alternative, never as a plain success. */
    data class PassedAssistively(val at: TryId) : ChainEvent

    /** A wrong try: a wrong phrase, a wrong tap in a sequence, a hold let go too early. */
    data class Failed(val at: TryId) : ChainEvent

    /** A try that was begun and not finished within the step's time. */
    data class TimedOut(val at: TryId) : ChainEvent

    /** They picked another of the steps that are still open ([ChainState.alternatives]). */
    data class Switch(val position: Int) : ChainEvent

    /**
     * The way of the open step at [position] cannot run on this phone now: no camera, the consent or the permission withdrawn, no code
     * registered, or the camera failing mid-ring. It is about a step, not a try: it applies to any open step whose way needs something of
     * the phone ([HandshakeMode.needsDevice]), on screen or not, and to nothing else. The step runs its fallback with a fresh allowance and a
     * row says so; it is never a try and never uses an allowance.
     */
    data class Unavailable(val position: Int) : ChainEvent
}

/** The new state, and the tries to write down because of the event, in order. */
data class ChainAdvance(val state: ChainState, val attempts: List<Attempt>)

/**
 * Where a chain of handshake steps stands. [current] is the step on screen, or -1 once the chain is satisfied or out of tries. The spec is the
 * one that was asked for; a step whose mode cannot be asked for yet is already running its replacement in [steps].
 */
data class ChainState(val spec: ChainSpec, val steps: List<StepProgress>, val current: Int, val status: ChainStatus) {
    val step: StepProgress? get() = steps.getOrNull(current)

    /** The try that is on screen, which is what an event has to be about: the screen hands it back with each thing the person does. */
    val tryOnScreen: TryId? get() = step?.let { TryId(it.position, it.attempts + 1, it.way) }

    /** The challenge on screen: the step's mode, or the hold that a two-step begins with. Null once the chain is over. */
    val shown: HandshakeMode? get() = step?.shown

    /** The tries the step on screen has left before it falls back or gives up. */
    val triesLeft: Int get() = triesLeft(current)

    fun triesLeft(position: Int): Int {
        val step = steps.getOrNull(position) ?: return 0
        return (allowance(position) - step.attempts).coerceAtLeast(0)
    }

    /** How many tries the step at [position] allows on a mode: at least one, whatever the profile says (and one for a position there is none at). */
    fun allowance(position: Int): Int = (spec.steps.getOrNull(position)?.maxRetries ?: 1).coerceAtLeast(1)

    /** How long a press has to last, in seconds: the step's own setting, or a short default for a hold that took the place of another mode. */
    fun holdSeconds(position: Int = current): Int =
        (spec.steps.getOrNull(position)?.holdSeconds ?: 0).takeIf { it > 0 }?.coerceAtMost(MAX_HOLD_SECONDS) ?: DEFAULT_HOLD_SECONDS

    /** How long a begun try may take before it is given up as a try, in seconds. */
    fun timeoutSeconds(position: Int = current): Int = (spec.steps.getOrNull(position)?.timeoutSec ?: 0).coerceAtLeast(MIN_TIMEOUT_SECONDS)

    /**
     * The steps the person may turn to instead of the one on screen. In a chain of alternatives (OR) any step that is still open; in a chain
     * where all are needed (AND) any step that is still open, in the order they like; in a chain in order (SEQUENCE) none.
     */
    val alternatives: List<Int>
        get() = if (status != ChainStatus.RUNNING) {
            emptyList()
        } else {
            when (spec.op) {
                ChainOp.SEQUENCE -> emptyList()
                ChainOp.AND, ChainOp.OR -> steps.filter { it.position != current && it.status == StepStatus.OPEN }.map { it.position }
            }
        }

    /** True when a step that passed did so with the hold's assistive confirm (its own, or the first part of a two-step): the acknowledgement is recorded as the accessible alternative. */
    val accessible: Boolean get() = steps.any { it.status == StepStatus.PASSED && (it.passedAssistively || it.acknowledgedAssistively) }

    /** True when the person said what they will do first (a two-step that was passed as a two-step). It is never taken for starting. */
    val firstActionIndicated: Boolean get() = steps.any { it.status == StepStatus.PASSED && it.mode == HandshakeMode.TWO_STEP }

    companion object {
        const val DEFAULT_HOLD_SECONDS = 3
        const val MAX_HOLD_SECONDS = 10
        const val MIN_TIMEOUT_SECONDS = 10
    }
}

/**
 * Runs a chain of handshake steps, as pure functions of a state and an event, so that the rules are tested without a screen.
 *
 * *The operators.* OR is satisfied by any one step, AND needs every step, SEQUENCE needs every step in the order listed. One step is on
 * screen at a time. In OR and AND the person may turn to another open step ([ChainState.alternatives]); in SEQUENCE the order is fixed.
 *
 * *Tries.* A step allows [com.command.domain.dials.StepSpec.maxRetries] tries on a mode. A wrong try ([ChainEvent.Failed]) and a try that was
 * begun and left unfinished for the step's time ([ChainEvent.TimedOut]) each use one up; doing nothing sends no event, so a person who is
 * still asleep does not lose their tries. When they are used up the step changes once: a hold offers its assistive confirm, any other mode is
 * replaced by its fallback ([HandshakeMode.fallback]) with a fresh allowance. When that is used up too the step is stuck: in OR the chain
 * turns to any other open step, in AND and SEQUENCE the chain is out of tries (the alarm goes on ringing until its time is up; the person
 * can always silence it, and Back offers the delay and the skip). The assistive confirm is bounded like everything else: the allowance is
 * what makes the chain end, and a person who cannot do it still has the silence, the delay and the skip. It may also be offered, and
 * taken, before the tries on the press are used up ([ChainEvent.PassedAssistively] is accepted for any hold that is on screen): a person
 * with a screen reader or a keyboard cannot press and hold, and making them fail the press first would be no kindness. Either way it is
 * recorded as the accessible alternative, never as a plain success.
 *
 * *Events are about a try.* Each carries the [TryId] of the try it was produced for, and an event that is not about the try on screen (a
 * second tap on a button that has already been answered, a timer that outlived a switch to another step) changes nothing, so that a
 * duplicate can never pass the step after the one it was meant for.
 *
 * *Two-step.* The person says what they will do first, after an acknowledgement. When another step of the chain has passed, that is the
 * acknowledgement; when nothing has, the two-step begins with a hold of its own ([StepPart.ACKNOWLEDGE]), which has its rows (the mode
 * HOLD), its tries and its assistive confirm like any hold, and only then asks the question.
 *
 * *Modes not built yet* (voice) are replaced by their fallback when the chain starts, and a row says so.
 *
 * *Modes that need the phone* (QR: a camera, its consent and permission, a registered code; NFC, a device way like QR since M13b: a tag
 * reader that is on and usable, its consent, a registered tag) are asked for only while the phone can do them
 * ([HandshakeCapability]). One it cannot do is replaced by its fallback ([ChainEvent.Unavailable]) when the chain starts, when it is
 * restored, and whenever the caller finds that the phone can no longer do it ([reconcile]); a row says so each time. A phone that gains the
 * mode during a ring does not take a replaced step back: the record has said what the step is. No mode falls back to one that needs the
 * phone, so a fallback never needs the capability.
 *
 * *Rings begun before M13b.* NFC was not built then, so such a ring wrote a try-0 FALLBACK note for its NFC step and asked for typing. Read
 * back now, that note fits the rule for an unavailable in [restore] (its step is open, NFC is on screen and needs the phone, try 0), so the
 * step stays typing for that ring, on any phone, with no new row.
 *
 * *The record.* Every try produces an [Attempt], and a change of mode produces one more (FALLBACK, with the mode that was left). The rows
 * are the whole truth about the tries of a ring: [restore] replays them to the state they came from, so a screen that is recreated, or a
 * process that died, continues where the person was. A turn to another step ([ChainEvent.Switch]) is not a try and leaves no row, so what
 * the rows lead to is the step of the last try if that left it open, otherwise the first open step: a turn that no try followed is forgotten.
 */
object ChainRunner {
    /**
     * The chain at its first step, and the rows to write when it begins: one for each mode that is not built and was replaced, and one for
     * each mode that [capability] cannot run and was replaced ([ChainEvent.Unavailable]). Both are try-0 FALLBACK rows of the mode that was
     * asked for, in the order of the steps.
     */
    fun start(spec: ChainSpec, capability: HandshakeCapability): ChainAdvance {
        val notes = ArrayList<Attempt>()
        val steps = spec.steps.mapIndexed { position, step ->
            val offered = step.mode.offered
            if (offered != step.mode) notes += Attempt(position, step.mode, 0, ChallengeOutcome.FALLBACK)
            StepProgress(position = position, mode = offered)
        }
        val reconciled = reconcile(ChainState(spec, steps, current = 0, status = ChainStatus.RUNNING), capability)
        return ChainAdvance(reconciled.state, (notes + reconciled.attempts).sortedBy { it.position })
    }

    /**
     * The chain with every open step whose way [capability] cannot run replaced by its fallback, in the order of the steps, and the rows to
     * write for that. Nothing changes (the same state, no rows) when the phone can do every open step, or when the chain is over. A step
     * already replaced is not taken back when the phone can do its mode again. The caller uses it when the capability may have changed during a
     * ring, and writes the rows it returns.
     */
    fun reconcile(state: ChainState, capability: HandshakeCapability): ChainAdvance {
        var now = state
        val rows = ArrayList<Attempt>()
        for (position in state.steps.indices) {
            val step = now.steps[position]
            if (step.status != StepStatus.OPEN || capability.canRun(step.shown)) continue
            val replaced = advance(now, ChainEvent.Unavailable(position))
            now = replaced.state
            rows += replaced.attempts
        }
        return ChainAdvance(now, rows)
    }

    fun advance(state: ChainState, event: ChainEvent): ChainAdvance {
        val unchanged = ChainAdvance(state, emptyList())
        if (state.status != ChainStatus.RUNNING) return unchanged
        val step = state.steps[state.current]
        return when (event) {
            is ChainEvent.Switch ->
                if (event.position in state.alternatives) ChainAdvance(state.copy(current = event.position), emptyList()) else unchanged
            is ChainEvent.Passed -> if (event.at == state.tryOnScreen) pass(state, step, assistive = false) else unchanged
            is ChainEvent.PassedAssistively ->
                if (event.at == state.tryOnScreen && step.shown == HandshakeMode.HOLD) pass(state, step, assistive = true) else unchanged
            is ChainEvent.Failed -> if (event.at == state.tryOnScreen) miss(state, step, ChallengeOutcome.FAILED) else unchanged
            is ChainEvent.TimedOut -> if (event.at == state.tryOnScreen) miss(state, step, ChallengeOutcome.TIMEOUT) else unchanged
            is ChainEvent.Unavailable -> unavailable(state, event.position) ?: unchanged
        }
    }

    /**
     * The open step at [position] cannot run its way on this phone: it is replaced by the fallback, as a start replaces a mode, with a fresh
     * allowance and a way of its own, and the change of mode it may still make after that is left to it (typing can still fall back to the
     * numbers). The row is the FALLBACK of the mode that was left, numbered with the tries made on it. The step on screen stays the step on
     * screen. Null when there is nothing to replace: a step that is not there, not open, or whose way needs nothing of the phone.
     */
    private fun unavailable(state: ChainState, position: Int): ChainAdvance? {
        val step = state.steps.getOrNull(position) ?: return null
        if (step.status != StepStatus.OPEN || !step.shown.needsDevice) return null
        val next = step.shown.fallback.offered
        if (next == step.shown) return null
        val row = Attempt(position, step.shown, step.attempts, ChallengeOutcome.FALLBACK)
        val replaced = step.copy(mode = next, assistive = false, attempts = 0, way = step.way + 1, lastMiss = null)
        return ChainAdvance(state.copy(steps = state.steps.replacing(replaced)), listOf(row))
    }

    /**
     * The state that the rows of a ring lead to. Rows that no event produced (a silence, a cancellation) and the notes of a change of mode
     * (which replaying the tries produces again) are skipped; a row that does not fit the chain, repeats a try that has been counted, or was
     * made on another mode than the one that is on screen at that point (a copy of the hold's row cannot answer the question that follows
     * it) is skipped too, because an event that is not about the try on screen changes nothing.
     *
     * A row is taken for the next try of its step when it has the number and the mode of that try. That is exact for the rows this engine
     * wrote, each once (the seeded fuzz in the tests replays them after every event), and it is as far as a row can be checked: a row does
     * not carry the way, so a row that was written twice, the first being a try of a way that has since been left, cannot be told from the
     * first try of the way that follows when that has the same step, mode and number (a press let go early, repeated after its assistive
     * confirm began). Keeping that from happening belongs to whoever writes the rows: the rows of an event in one transaction, one event at
     * a time, and nothing written for an event that is not about the try on screen.
     *
     * *The phone.* The replay begins as a phone that can do everything would ([HandshakeCapability.ALL]), and a FALLBACK row is read as an
     * [ChainEvent.Unavailable] when it is one: its step is open, its mode is the one on screen for that step and needs the phone, and its
     * number is the tries made on it. (The note of a used-up allowance is written after the step has changed way, so its mode is not on screen
     * any more; the note of a mode that is not built names a mode the step never showed.) So a step that was replaced stays replaced on any
     * phone. Then the chain is reconciled with [capability]: an open step this phone cannot do now is replaced, and those rows, which nothing
     * has written yet, are returned for the caller to write. The state never depends on the phone that reads it back once those rows are
     * written.
     */
    fun restore(spec: ChainSpec, history: List<Attempt>, capability: HandshakeCapability): ChainAdvance {
        var state = start(spec, HandshakeCapability.ALL).state
        for (row in history) {
            if (state.status != ChainStatus.RUNNING) break
            if (row.outcome == ChallengeOutcome.FALLBACK) {
                val target = state.steps.getOrNull(row.position)
                if (target != null && target.status == StepStatus.OPEN && row.mode == target.shown && row.mode.needsDevice && row.attemptNo == target.attempts) {
                    state = advance(state, ChainEvent.Unavailable(row.position)).state
                }
                continue
            }
            if (row.outcome == ChallengeOutcome.CANCELLED || row.outcome == ChallengeOutcome.SAFETY_SILENCE) continue
            // The try the row is about: the next try of the way that is on screen for its step. Anything else is not a try of this chain.
            val target = state.steps.getOrNull(row.position) ?: continue
            if (target.status != StepStatus.OPEN || row.attemptNo != target.attempts + 1 || row.mode != target.shown) continue
            if (row.position != state.current) {
                val moved = advance(state, ChainEvent.Switch(row.position)).state
                if (moved.current != row.position) continue
                state = moved
            }
            val at = state.tryOnScreen ?: continue
            val event = when (row.outcome) {
                ChallengeOutcome.SUCCESS -> ChainEvent.Passed(at)
                ChallengeOutcome.ACCESSIBLE_ALT -> ChainEvent.PassedAssistively(at)
                ChallengeOutcome.FAILED -> ChainEvent.Failed(at)
                ChallengeOutcome.TIMEOUT -> ChainEvent.TimedOut(at)
                else -> continue
            }
            state = advance(state, event).state
        }
        return reconcile(state, capability)
    }

    private fun pass(state: ChainState, step: StepProgress, assistive: Boolean): ChainAdvance {
        val attemptNo = step.attempts + 1
        val row = Attempt(step.position, step.shown, attemptNo, if (assistive) ChallengeOutcome.ACCESSIBLE_ALT else ChallengeOutcome.SUCCESS)
        if (step.part == StepPart.ACKNOWLEDGE) {
            // The hold that a two-step begins with: the alarm is acknowledged, and what comes first is still to be said.
            val held = step.copy(acknowledged = true, acknowledgedAssistively = assistive, attempts = 0, assistive = false, way = step.way + 1, lastMiss = null)
            return ChainAdvance(state.copy(steps = state.steps.replacing(held)), listOf(row))
        }
        val passed = step.copy(attempts = attemptNo, status = StepStatus.PASSED, passedAssistively = assistive)
        // A step that passed has acknowledged the alarm for any two-step that is still to come: it need not begin with a hold of its own.
        val steps = state.steps.replacing(passed).map {
            if (it.mode == HandshakeMode.TWO_STEP && it.status == StepStatus.OPEN && !it.acknowledged) {
                it.copy(acknowledged = true, attempts = 0, assistive = false, way = it.way + 1, lastMiss = null)
            } else {
                it
            }
        }
        return ChainAdvance(settle(state, steps), listOf(row))
    }

    private fun miss(state: ChainState, step: StepProgress, outcome: ChallengeOutcome): ChainAdvance {
        val used = step.attempts + 1
        val rows = arrayListOf(Attempt(step.position, step.shown, used, outcome))
        if (used < state.allowance(step.position)) {
            return ChainAdvance(state.copy(steps = state.steps.replacing(step.copy(attempts = used, lastMiss = outcome))), rows)
        }
        val replacement = replacementFor(step.copy(attempts = used))
        if (replacement != null) {
            // Out of tries on this mode: it changes once, and the person starts the new one with a fresh allowance.
            rows += Attempt(step.position, step.shown, used, ChallengeOutcome.FALLBACK)
            return ChainAdvance(state.copy(steps = state.steps.replacing(replacement)), rows)
        }
        return ChainAdvance(settle(state, state.steps.replacing(step.copy(attempts = used, status = StepStatus.STUCK))), rows)
    }

    /**
     * A hold, wherever it is (a step's own or the first part of a two-step), offers its assistive confirm; any other mode is replaced by its
     * fallback, once; nothing is left after that.
     */
    private fun replacementFor(step: StepProgress): StepProgress? = when {
        step.shown == HandshakeMode.HOLD && !step.assistive -> step.copy(assistive = true, attempts = 0, way = step.way + 1, lastMiss = null)
        step.part == StepPart.ACKNOWLEDGE -> null
        step.mode != HandshakeMode.HOLD && !step.changedMode -> {
            val next = step.mode.fallback.offered
            if (next == step.mode) null else step.copy(mode = next, assistive = false, attempts = 0, changedMode = true, way = step.way + 1, lastMiss = null)
        }
        else -> null
    }

    /** After a step was passed or became stuck: which step is on screen now, and whether the chain is satisfied or out of tries. */
    private fun settle(state: ChainState, steps: List<StepProgress>): ChainState {
        val open = steps.firstOrNull { it.status == StepStatus.OPEN }
        val (current, status) = when (state.spec.op) {
            ChainOp.OR -> when {
                steps.any { it.status == StepStatus.PASSED } -> -1 to ChainStatus.SATISFIED
                open != null -> open.position to ChainStatus.RUNNING
                else -> -1 to ChainStatus.EXHAUSTED
            }
            ChainOp.AND, ChainOp.SEQUENCE -> when {
                steps.any { it.status == StepStatus.STUCK } -> -1 to ChainStatus.EXHAUSTED
                open != null -> open.position to ChainStatus.RUNNING
                else -> -1 to ChainStatus.SATISFIED
            }
        }
        return state.copy(steps = steps, current = current, status = status)
    }

    private fun List<StepProgress>.replacing(step: StepProgress): List<StepProgress> = map { if (it.position == step.position) step else it }
}
