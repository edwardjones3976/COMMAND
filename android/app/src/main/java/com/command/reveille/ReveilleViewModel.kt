package com.command.reveille

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.ApplyExtras
import com.command.data.ApplyResult
import com.command.data.ChallengeRecord
import com.command.data.MissionBrief
import com.command.data.OccurrenceRepository
import com.command.data.REPEATED_ACK_NO_START_AFTER
import com.command.data.db.OccurrenceRow
import com.command.domain.coaching.CoachingPhase
import com.command.domain.dials.ChainOp
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.HandshakeMode
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.SkipReasonCode
import com.command.domain.handshake.ChainEvent
import com.command.domain.handshake.ChainRunner
import com.command.domain.handshake.ChainState
import com.command.domain.handshake.ChainStatus
import com.command.domain.handshake.ConfirmSequence
import com.command.domain.handshake.StepPart
import com.command.domain.handshake.StepProgress
import com.command.domain.handshake.StepStatus
import com.command.domain.handshake.TryId
import com.command.domain.handshake.TypedPhrase
import com.command.domain.model.ChallengeOutcome
import com.command.domain.model.EventSource
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.domain.occurrence.StarterWindow
import com.command.domain.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Why the screen has nothing more to do. */
enum class ClosedOutcome { STARTED, SKIPPED, DELAYED, MISSED, DONE, NOT_RINGING }

sealed interface ReveilleUiState {
    data object Loading : ReveilleUiState
    data object NotFound : ReveilleUiState

    /**
     * The alarm is ringing: the person is asked for [challenge], and may silence it whatever it is. [occurrenceId] says which alarm, so that
     * nothing the screen remembers about one ring is taken for another's. [progress] is set for a chain of several steps that are all needed.
     * [offerAccessibleConfirm] puts the hold's assistive confirm on the screen from the start (a screen reader is on).
     */
    data class Firing(
        val occurrenceId: String,
        val title: String,
        val challenge: Challenge,
        val progress: ChainProgress? = null,
        val offerAccessibleConfirm: Boolean = false,
        val alternatives: List<Alternative> = emptyList(),
        /** The coaching line that asks for the handshake. */
        val prompt: String = "",
        /** The ring's own line when it was not heard (the phone could not speak, or the route said no), for the screen to show in large type. */
        val unspokenLine: String? = null,
    ) : ReveilleUiState

    /** Silenced or missed: nothing was started. The user can pick it up again or let it go. */
    data class Silenced(val title: String, val missed: Boolean = false) : ReveilleUiState

    /**
     * Acknowledged: the one concrete first step, and the one-tap answers. [praise] is the line for a handshake just passed. [offerStarter] puts
     * the optional starter on the screen.
     */
    data class Direction(
        val title: String,
        val firstAction: String,
        val starterMinutes: Int,
        val coaching: String = "",
        val praise: String = "",
        val offerStarter: Boolean = false,
    ) : ReveilleUiState

    /** The starter is running: the first step, what is left of the clock, and the line for this part of it. A start is still the person's to report. */
    data class Starter(val title: String, val firstAction: String, val remainingSeconds: Int, val line: String) : ReveilleUiState

    data class Closed(val title: String, val outcome: ClosedOutcome, val coaching: String = "") : ReveilleUiState
}

/** A skip or delay that needs the user to do a little more first (Mission Lock), and what. */
data class FrictionPrompt(val required: LockPolicy, val retry: (FrictionEvidence) -> Unit)

/**
 * Drives the lock-screen flow for one occurrence. The screen is a projection of the stored occurrence: every tap becomes a
 * command to [OccurrenceRepository], so a relaunch after the process died lands on the right step.
 *
 * *The handshake.* While the alarm rings, the person is asked for what the occurrence's chain asks ([ChainRunner]). What they do arrives as
 * events about the try that is on screen; they are handled one at a time, in the order they came, and each try (and each change of mode)
 * is written down as it ends, so that a screen that is shown again, or a process that restarts, continues where the person was
 * ([ChainRunner.restore]). When the chain is satisfied the alarm is acknowledged and the guidance begins in one transaction. A try that is
 * begun and left unfinished runs out after the step's time, and counts as a try; doing nothing is not a try. Once the ring is over, however
 * it ended, nothing of its handshake goes on. The chain a ring is restored against is the one it was fired with ([OccurrenceRepository.handshakeOf]).
 *
 * *The starter.* [beginStarter] writes its times into the session, and the screen is drawn from those and the clock: nothing about the countdown
 * is written or kept but the times, so a screen drawn again or a process restarted shows what the clock says, the midpoint's line included. It
 * runs only while it is on screen, sets no alarm, and leaves the follow-up questions on their own schedule; they can end it, because the state
 * they move decides what is shown.
 *
 * *Another way.* In a chain of alternatives (one step is enough) or of steps that are all needed in any order, the screen offers the other
 * steps that are still open ([switchTo]); an ordered chain offers none. Turning to one is not a try and writes nothing, and like every other
 * event of the handshake it is about a ring, so it is checked against the ring on screen, in the lock. A step that would give the person
 * nothing that the screen does not give already is not offered ([alternativesOf]: the same screen, such as the numbers that gave way to
 * typing beside a step of typing, or, where every step is needed, the hold of a two-step beside the press that has to be passed anyway).
 */
@HiltViewModel
class ReveilleViewModel @Inject constructor(
    private val repository: OccurrenceRepository,
    private val clock: Clock,
    private val phrases: PhraseSource,
) : ViewModel() {

    private val _state = MutableStateFlow<ReveilleUiState>(ReveilleUiState.Loading)
    val state: StateFlow<ReveilleUiState> = _state.asStateFlow()

    private val _friction = MutableStateFlow<FrictionPrompt?>(null)
    val friction: StateFlow<FrictionPrompt?> = _friction.asStateFlow()

    private var occurrenceId: String? = null
    private var observing: Job? = null

    /** The ring on screen now, or null. The screens hand back the ring they were drawn for with every event; a test of the view model alone speaks of this one. */
    internal val ringOnScreen: String? get() = occurrenceId

    /**
     * Advances whenever the screen a question was asked on is gone: another occurrence takes over, the screen changes kind, or the alarm is
     * silenced. A command that was tapped on the old screen and is answered later finds the number changed, and asks nothing.
     */
    private var screen = 0

    /** Runs between a command being decided and its answer being used: a seam for the one test that has to place a screen change in between. */
    internal var beforeAnswer: suspend () -> Unit = {}

    /**
     * Whether the hold's assistive confirm (three deliberate taps) is on the screen from the start. A person who cannot hold a finger down
     * has no other way: a screen reader or a hardware keyboard cannot press and hold. Set by the activity when it starts, and again when the
     * person turns a screen reader on or off while the alarm rings.
     */
    var offerAccessibleConfirm = false
        set(value) {
            if (field == value) return
            field = value
            viewModelScope.launch { engine.withLock { publish() } }
        }

    // --- the handshake: everything below is written while holding [engine] -----------------------------------------
    // (Everything runs on the main thread, so the lock does not make anything parallel; it keeps the events in the order they came, and an
    // event that is waiting for its turn when another ring takes over finds [chain] cleared by show(), and does nothing.)

    /** One event at a time, in the order they came. */
    private val engine = Mutex()

    private var chain: ChainState? = null
    private var brief: MissionBrief? = null
    private var phrase = ""
    private var prompt = ""

    /** The ring's own line, the words the service reads out from the same place. */
    private var ringLine: String? = null

    /** The record says this ring's line was not heard. */
    private var lineNotSpoken = false
    private var voice: Job? = null

    /** How many of the sequence's numbers have been tapped rightly on this try. */
    private var tapped = 0

    /** The last try ended wrong (or took too long), for the words under the challenge. */
    private var mistaken = false

    /** The last try was not wrong but too slow: the words say so. */
    private var slow = false

    /** A hold was let go too early, for the hint under it. */
    private var earlyRelease = false

    /** When the try on screen was begun, for the times of its row. */
    private var began: Long? = null
    private var timer: Job? = null

    /** What the countdown waits for between two draws: a seam, so that a test decides when a second has gone by. */
    internal var starterTick: suspend () -> Unit = { kotlinx.coroutines.delay(1000L) }

    /** What waits for a begun try to run out: a seam, so that a test need not wait ninety seconds. */
    internal var attemptTimer: suspend (seconds: Int) -> Unit = { kotlinx.coroutines.delay(it * 1000L) } // not `delay(it * 1000)`: an Int would bind to this class's own delay(minutes)

    fun show(id: String) {
        if (occurrenceId == id) return
        // Another occurrence replaces the one on screen: stop watching the old one, so it can never draw over the new one,
        // and forget anything that belonged to it.
        observing?.cancel()
        voice?.cancel()
        stopStarter()
        screen++
        timer?.cancel()
        timer = null
        occurrenceId = id
        chain = null
        brief = null
        phrase = ""
        tapped = 0
        mistaken = false
        slow = false
        earlyRelease = false
        began = null
        prompt = ""
        ringLine = null
        lineNotSpoken = false
        _friction.value = null
        _state.value = ReveilleUiState.Loading
        // What the phone could not say is on the screen instead, as soon as that is written down, whenever that is in the ring.
        voice = viewModelScope.launch {
            repository.observeLineNotSpoken(id).collect { notSpoken ->
                engine.withLock {
                    lineNotSpoken = notSpoken
                    publish()
                }
            }
        }
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
        // Once the ring is over, however it ended (a silence, a skip, a delay, a miss, the notification), nothing of its handshake may go on:
        // a begun try's clock must not write a time-out for an alarm that has stopped.
        if (row.state != OccurrenceState.FIRING) engine.withLock { endHandshake() }
        val starter = if (row.state == OccurrenceState.GUIDED_START) repository.starterOf(row) else null
        if (starter == null) stopStarter()
        return when (row.state) {
            OccurrenceState.FIRING -> engine.withLock { firingOf(row, brief) }
            OccurrenceState.SAFETY_SILENCED -> ReveilleUiState.Silenced(brief.title)
            OccurrenceState.MISSED -> ReveilleUiState.Silenced(brief.title, missed = true)
            // Only right after the handshake: someone blocked, unanswered or offered a recovery gets the plain screen, no "well done, now move".
            OccurrenceState.ACKNOWLEDGED, OccurrenceState.GUIDED_START -> if (starter != null) starterScreen(row, brief, starter) else guidance(row, brief)
            OccurrenceState.UNCONFIRMED, OccurrenceState.BLOCKED, OccurrenceState.RECOVERY_OFFERED ->
                ReveilleUiState.Direction(brief.title, brief.firstAction, brief.starterMinutes)
            OccurrenceState.STARTED_SELF_REPORTED, OccurrenceState.WORKING_SELF_REPORTED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.STARTED)
            OccurrenceState.COMPLETED_SELF_REPORTED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.DONE)
            // A skip the calendar made is not the person's, and there is nothing to answer.
            OccurrenceState.SKIPPED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.SKIPPED, if (row.skipReason == SkipReason.USER) repository.lineFor(row, CoachingPhase.SKIP_ACK, withMissionWords = false).text else "")
            OccurrenceState.DELAYED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.DELAYED)
            OccurrenceState.SCHEDULED, OccurrenceState.ARMED -> ReveilleUiState.Closed(brief.title, ClosedOutcome.NOT_RINGING)
        }
    }

    /**
     * The first step, with the lines for this moment: praise for a handshake that was passed (and for nothing else), and the direction, unless
     * this mission has been acknowledged again and again without a start, when it is the line that offers something smaller.
     */
    private suspend fun guidance(row: OccurrenceRow, brief: MissionBrief): ReveilleUiState.Direction {
        val repeated = repository.acknowledgedWithoutStart(row.missionId, row.scheduledAtUtc) >= REPEATED_ACK_NO_START_AFTER
        val phase = if (repeated) CoachingPhase.REPEATED_ACK_NO_START else CoachingPhase.DIRECTION
        val praise = if (repository.handshakeJustPassed(row)) repository.lineFor(row, CoachingPhase.HANDSHAKE_SUCCESS, withMissionWords = false).text else ""
        return ReveilleUiState.Direction(
            brief.title,
            brief.firstAction,
            brief.starterMinutes,
            repository.lineFor(row, phase, withMissionWords = false).text,
            praise,
            offerStarter = row.state == OccurrenceState.GUIDED_START && brief.starterMinutes > 0,
        )
    }

    // --- the starter -------------------------------------------------------------------------------------------

    private class StarterOnScreen(val window: StarterWindow, val title: String, val firstAction: String, val startLine: String, val midpointLine: String)

    private var starterOnScreen: StarterOnScreen? = null
    private var starterClock: Job? = null

    private fun stopStarter() {
        starterClock?.cancel()
        starterClock = null
        starterOnScreen = null
    }

    /** The lines keep the length the starter began with, whatever the mission says by now. */
    private suspend fun starterScreen(row: OccurrenceRow, brief: MissionBrief, window: StarterWindow): ReveilleUiState.Starter {
        val on = StarterOnScreen(
            window,
            brief.title,
            brief.firstAction,
            repository.lineFor(row, CoachingPhase.STARTER_START, withMissionWords = false, minutes = window.minutes).text,
            repository.lineFor(row, CoachingPhase.STARTER_MIDPOINT, withMissionWords = false, minutes = window.minutes).text,
        )
        starterOnScreen = on
        if (starterClock?.isActive != true) {
            starterClock = viewModelScope.launch {
                while (!window.isOver(clock.nowMs())) {
                    starterTick()
                    _state.value = drawn(starterOnScreen ?: return@launch)
                }
            }
        }
        return drawn(on)
    }

    private fun drawn(on: StarterOnScreen): ReveilleUiState.Starter {
        val now = clock.nowMs()
        return ReveilleUiState.Starter(on.title, on.firstAction, on.window.remainingSeconds(now), if (on.window.pastMidpoint(now)) on.midpointLine else on.startLine)
    }

    // --- the handshake -----------------------------------------------------------------------------------------

    /** The ring on screen, from the record: the chain is run from its first step, or restored from the tries already written down. */
    private suspend fun firingOf(row: OccurrenceRow, brief: MissionBrief): ReveilleUiState {
        this.brief = brief
        if (chain == null) {
            val wanted = repository.handshakeOf(row)
            phrase = TypedPhrase.pick(phrases.phrases(wanted.personality), row.id)
            prompt = repository.lineFor(row, CoachingPhase.HANDSHAKE_PROMPT, withMissionWords = false).text
            ringLine = repository.lineFor(row, CoachingPhase.REVEILLE).text
            val tries = repository.attemptsOf(row.id)
            val started = ChainRunner.start(wanted.chain)
            if (tries.isEmpty() && started.attempts.isNotEmpty()) {
                // A mode that cannot be asked for yet was replaced by its fallback: the record says so, once.
                val now = clock.nowMs()
                repository.recordChallenges(row.id, started.attempts.map { ChallengeRecord(it.position, it.mode, it.attemptNo, it.outcome, now, now) })
            }
            val restored = ChainRunner.restore(wanted.chain, tries)
            chain = restored
            // The words under the challenge come from how the last try on the way that is on screen ended: a wrong one (or one that took
            // too long) is said again, and a new way starts clean. They come from the state the rows lead to, and not from the last row,
            // so that a row the replay refused cannot change what the screen says.
            wordsFrom(restored)
            // The rows already satisfy the chain but the alarm is still ringing (the profile's chain was changed meanwhile, say): the person
            // has done what the rows say, so the acknowledgement is finished now, and not left waiting for a screen with nothing on it.
            if (restored.status == ChainStatus.SATISFIED) acknowledge(row.id, restored, emptyList())
        }
        return firing(row.id, brief)
    }

    /** Nothing of the handshake goes on once the ring is over: the clock stops, and what is left of the chain is let go. */
    private fun endHandshake() {
        timer?.cancel()
        timer = null
        chain = null
        began = null
    }

    /**
     * Acknowledging is not starting: the next step is the direction, which is where starting is reported. Both changes commit together
     * with the rows of the last try, so a process that dies in between cannot leave a screen whose button the state machine refuses.
     */
    private suspend fun acknowledge(id: String, state: ChainState, rows: List<ChallengeRecord>) {
        repository.inTransaction(EventSource.USER) { tx ->
            val answer = OccurrenceCommand.Acknowledge(
                accessibleAlternative = state.accessible,
                firstActionIndicated = state.firstActionIndicated,
            )
            val result = tx.apply(id, answer, EventSource.USER, ApplyExtras(challenges = rows))
            if (result is ApplyResult.Applied) tx.apply(id, OccurrenceCommand.StartGuidance, EventSource.USER)
        }
    }

    private fun firing(id: String, brief: MissionBrief): ReveilleUiState {
        val state = chain ?: return _state.value
        // Satisfied: the alarm is being acknowledged, and the observer will show the next screen; the screen stays as it is until then.
        if (state.status == ChainStatus.SATISFIED) return _state.value
        return ReveilleUiState.Firing(
            id,
            brief.title,
            challengeOf(id, state, brief.firstAction),
            progressOf(state),
            offerAccessibleConfirm,
            alternativesOf(state),
            prompt,
            ringLine?.takeIf { lineNotSpoken },
        )
    }

    private fun challengeOf(id: String, state: ChainState, firstAction: String): Challenge {
        val step = state.step
        val tryId = state.tryOnScreen
        if (step == null || tryId == null) return Challenge.OutOfTries
        return when (step.shown) {
            HandshakeMode.HOLD ->
                Challenge.Hold(tryId, state.holdSeconds(step.position), earlyRelease, step.assistive, acknowledging = step.part == StepPart.ACKNOWLEDGE)
            HandshakeMode.TYPING -> Challenge.Typing(tryId, phrase, state.triesLeft, mistaken, slow)
            HandshakeMode.CONFIRM_SEQUENCE -> Challenge.Sequence(tryId, ConfirmSequence.layout(id, step.attempts), tapped, state.triesLeft, mistaken, slow)
            HandshakeMode.TWO_STEP -> Challenge.TwoStep(tryId, firstAction)
            // QR, NFC and voice are replaced by the engine before they can reach the screen.
            HandshakeMode.QR, HandshakeMode.NFC, HandshakeMode.VOICE -> Challenge.OutOfTries
        }
    }

    /** The words under the challenge, from how the last try on the way that is on screen ended (null: nothing to say). */
    private fun wordsFrom(state: ChainState) {
        val miss = state.step?.lastMiss
        mistaken = miss != null
        slow = miss == ChallengeOutcome.TIMEOUT
        earlyRelease = miss == ChallengeOutcome.FAILED && state.step?.shown == HandshakeMode.HOLD
    }

    private fun progressOf(state: ChainState): ChainProgress? {
        if (state.spec.op == ChainOp.OR || state.steps.size < 2) return null
        return ChainProgress(minOf(state.steps.count { it.status == StepStatus.PASSED } + 1, state.steps.size), state.steps.size)
    }

    /**
     * The other steps the person may turn to: any open one in a chain of alternatives or of steps that are all needed, none in an ordered one.
     * A step is left out when turning to it would give nothing that the screen does not give already ([Look.makesRedundant]: the same screen,
     * or, where every step is needed, the hold that a two-step begins with beside a press of the same length, which acknowledges the two-step
     * as well when it passes), and of several that would show the same under the same name only the first is offered, so that two buttons
     * never carry one name.
     */
    private fun alternativesOf(state: ChainState): List<Alternative> {
        val onScreen = state.step?.let { lookOf(state, it) }
        return state.alternatives
            .map { state.steps[it] }
            .filter { onScreen == null || !onScreen.makesRedundant(lookOf(state, it), state.spec.op) }
            .distinctBy { it.mode to lookOf(state, it) }
            .map { step ->
                Alternative(
                    step.position,
                    step.mode,
                    instead = state.spec.op == ChainOp.OR,
                    assistive = step.mode == HandshakeMode.HOLD && step.assistive,
                )
            }
    }

    /**
     * What a step shows a person, as far as they can tell. A press is told by how long it lasts, and the three taps that take its place by
     * nothing else (they show no length); every other challenge is the same whichever step it belongs to. [acknowledging] is the hold that a
     * two-step begins with, or the taps in its place: the same press, but one that only acknowledges the alarm and is followed by the question.
     */
    private data class Look(val shown: HandshakeMode, val pressSeconds: Int, val assistive: Boolean, val acknowledging: Boolean = false) {
        /**
         * Turning to [other] would give the person nothing that this screen does not give already: it is the same screen, or, where every step
         * is needed, it is the hold that a two-step begins with (or the taps in its place) and this screen is the same press of a step that
         * is not one. Passing a press acknowledges every two-step as well and the press has to be passed anyway, so that hold would only be
         * an extra press first. Where one step is enough, the two-step is a way of its own (it asks what the person will do first, and
         * records it), so it is offered beside the press as the press is beside it. In either chain the press is worth turning to from the
         * two-step's hold, because it ends the chain (or is the other step that is needed) where that hold only begins.
         */
        fun makesRedundant(other: Look, op: ChainOp) =
            this == other || (op == ChainOp.AND && !acknowledging && other.acknowledging && this == other.copy(acknowledging = false))
    }

    private fun lookOf(state: ChainState, step: StepProgress): Look {
        if (step.shown != HandshakeMode.HOLD) return Look(step.shown, 0, false)
        val acknowledging = step.part == StepPart.ACKNOWLEDGE
        return if (step.assistive) Look(HandshakeMode.HOLD, 0, true, acknowledging)
        else Look(HandshakeMode.HOLD, state.holdSeconds(step.position), false, acknowledging)
    }

    /** Draws the screen again from the chain, if a ring is what is on it. */
    private fun publish() {
        val id = occurrenceId ?: return
        val brief = brief ?: return
        if (_state.value is ReveilleUiState.Firing) _state.value = firing(id, brief)
    }

    /**
     * Runs [block] after the events before it. An event is about a ring: one that comes from the screen of another ring than the one on screen
     * now (the screens hand back the ring they were drawn for) does nothing, even when its try is numbered like one of this ring's, because
     * every ring begins at try 1; the check is made here, inside the lock, when the event's turn comes. A ring that was replaced while this
     * waited has no chain any more ([show] cleared it), and every entry point does nothing without one. With no [ring] it is not an event of
     * a screen at all (a fence, a redraw).
     */
    private fun inRing(ring: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch { engine.withLock { if (ring == null || ring == occurrenceId) block() } }
    }

    /** Runs [block] once every event that was sent before it has been handled: a fence for a test of something that must not happen. */
    internal fun afterPendingEvents(block: () -> Unit) = inRing { block() }

    private fun send(ring: String, event: ChainEvent, startedAtMs: Long? = null, endedAtMs: Long? = null) = inRing(ring) { advance(event, endedAtMs, startedAtMs) }

    /** [startedAtMs] and [endedAtMs] are when the try began and ended, if the screen knows it (a press does); otherwise it is when its first key or tap came, and now. */
    private suspend fun advance(event: ChainEvent, endedAtMs: Long? = null, startedAtMs: Long? = null) {
        val id = occurrenceId ?: return
        val before = chain ?: return
        val stepped = ChainRunner.advance(before, event)
        // An event that is not about the try on screen (a second tap, a timer that outlived a switch) changes nothing, and writes nothing.
        if (stepped.state === before) return
        val now = endedAtMs ?: clock.nowMs()
        val startedAt = startedAtMs ?: began ?: now
        val rows = stepped.attempts.map { ChallengeRecord(it.position, it.mode, it.attemptNo, it.outcome, startedAt, now) }
        chain = stepped.state
        timer?.cancel()
        timer = null
        began = null
        tapped = 0
        // A change of mode (or of step) starts the new one clean: what the words say is about the way that is on screen now.
        wordsFrom(stepped.state)
        if (stepped.state.status == ChainStatus.SATISFIED) {
            acknowledge(id, stepped.state, rows)
            return
        }
        repository.recordChallenges(id, rows)
        publish()
    }

    /**
     * Starts the clock on the try on screen, once: it is begun, and if it is not finished in the step's time it counts as a try.
     *
     * A time-out is an event like any other and is handled in the order it came (the lock is fair). One that is already waiting for its turn
     * when the person turns to another way is handled before the turn, for the try it is about; one that comes after finds another try on
     * screen and changes nothing. Handling the turn stops the clock before the person can see the other way, so no time-out can be queued
     * behind both the turn away and the turn back to a try with the same identity.
     */
    private fun startTimer(state: ChainState, tryId: TryId) {
        if (began == null) began = clock.nowMs()
        if (timer?.isActive == true) return
        val seconds = state.timeoutSeconds(tryId.position)
        val ring = occurrenceId ?: return
        timer = viewModelScope.launch {
            attemptTimer(seconds)
            send(ring, ChainEvent.TimedOut(tryId))
        }
    }

    // --- what the person does ----------------------------------------------------------------------------------

    /**
     * A new press clears the "released early" hint from the last one, and says when it began: a silence tapped while the finger is still down
     * ends a row that starts there, and not at the tap.
     */
    fun holdStarted(ring: String, at: TryId, startedAtMs: Long) = inRing(ring) {
        if (chain?.tryOnScreen != at) return@inRing
        began = startedAtMs
        if (earlyRelease) {
            earlyRelease = false
            publish()
        }
    }

    fun holdCompleted(ring: String, at: TryId, startedAtMs: Long, endedAtMs: Long) = send(ring, ChainEvent.Passed(at), startedAtMs, endedAtMs)

    fun holdReleasedEarly(ring: String, at: TryId, startedAtMs: Long, endedAtMs: Long) = inRing(ring) {
        if (chain?.tryOnScreen != at) return@inRing
        advance(ChainEvent.Failed(at), endedAtMs, startedAtMs)
        // A try that does not move the chain (it cannot happen: a wrong try is always counted) still redraws the hint.
        publish()
    }

    /** The first of the assistive confirm's three taps begins the try: from then on it must be finished in the step's time, like a phrase or a sequence. */
    fun assistiveBegun(ring: String, at: TryId) = inRing(ring) {
        val state = chain ?: return@inRing
        if (state.tryOnScreen == at) startTimer(state, at)
    }

    /** The hold's assistive confirm: three deliberate taps made, for a person who cannot hold a finger down. Recorded as that. */
    fun assistiveConfirmed(ring: String, at: TryId) = send(ring, ChainEvent.PassedAssistively(at))

    /** The first key of a try begins it: from then on it must be finished in the step's time. */
    fun typingChanged(ring: String, at: TryId, text: String) {
        if (text.isEmpty()) return
        inRing(ring) {
            val state = chain ?: return@inRing
            if (state.tryOnScreen == at) startTimer(state, at)
        }
    }

    /** Done on an empty field is not an answer: nothing was tried, so no try is used (doing nothing is never a try). */
    fun typingSubmitted(ring: String, at: TryId, text: String) {
        if (text.isBlank()) return
        send(ring, if (TypedPhrase.matches(text, phrase)) ChainEvent.Passed(at) else ChainEvent.Failed(at))
    }

    fun sequenceTapped(ring: String, at: TryId, number: Int) = inRing(ring) {
        val state = chain ?: return@inRing
        if (state.tryOnScreen != at) return@inRing
        // A number that is done is disabled on the screen; a tap on it that is delivered late (the one before it was still being written
        // down) is a double tap, not a wrong try.
        if (number in 1..tapped) return@inRing
        startTimer(state, at)
        when (ConfirmSequence.press(tapped, number)) {
            ConfirmSequence.Press.RIGHT -> {
                tapped += 1
                publish()
            }
            ConfirmSequence.Press.DONE -> advance(ChainEvent.Passed(at))
            ConfirmSequence.Press.WRONG -> advance(ChainEvent.Failed(at))
        }
    }

    /**
     * The person turns to another open step. The turn is not a try, so nothing is written for it; what is written is the tries made afterwards.
     * A screen that is shown again (or a process that restarts) comes back to the step of the last try that was recorded, if that left it
     * open, and otherwise to the first open step: a turn made after the last try is forgotten, and so is every turn that no try followed.
     */
    fun switchTo(ring: String, position: Int) = inRing(ring) { advance(ChainEvent.Switch(position)) }

    /** "I am doing: ..." The person has said what they will do first. It acknowledges; it does not start. */
    fun twoStepIndicated(ring: String, at: TryId) = send(ring, ChainEvent.Passed(at))

    // --- everything else is one tap ---------------------------------------------------------------------------

    /**
     * One tap, from anywhere, including from over a Mission Lock question: that question has nothing left to wait for. A challenge that was
     * in front of the person is written down as ended by the silence, which is not success.
     */
    fun silence() {
        screen++
        _friction.value = null
        // The clock of a begun try stops at the tap, not when the database has answered: it must not write a time-out after the silence.
        timer?.cancel()
        val id = occurrenceId ?: return
        val step = chain?.step
        val tryId = chain?.tryOnScreen
        viewModelScope.launch {
            val extras = if (step != null && tryId != null) {
                val now = clock.nowMs()
                ApplyExtras(challenges = listOf(ChallengeRecord(step.position, step.shown, tryId.tryNo, ChallengeOutcome.SAFETY_SILENCE, began ?: now, now)))
            } else {
                ApplyExtras.NONE
            }
            repository.apply(id, OccurrenceCommand.SafetySilence, EventSource.USER, extras)
        }
    }

    fun resume() = command(OccurrenceCommand.Resume)
    fun started() = command(OccurrenceCommand.ReportStarted)
    fun beginStarter() = command(OccurrenceCommand.BeginStarter)

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
}
