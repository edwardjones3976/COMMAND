package com.command.reveille

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.SkipReasonCode
import com.command.domain.handshake.TryId
import com.command.R

object ReveilleTags {
    const val HOLD = "reveille_hold"
    const val SILENCE = "reveille_silence"
    const val STARTED = "reveille_started"
    const val SKIP = "reveille_skip"
    const val DELAY = "reveille_delay"
    const val RESUME = "reveille_resume"
    const val ACCESSIBLE = "reveille_accessible_confirm"
    const val ESCAPE = "reveille_escape"
    const val ESCAPE_SILENCE = "reveille_escape_silence"
    const val ESCAPE_SKIP = "reveille_escape_skip"
    const val ESCAPE_KEEP = "reveille_escape_keep"
    const val FRICTION_SILENCE = "reveille_friction_silence"
    const val TYPING_PHRASE = "reveille_typing_phrase"
    const val TYPING_FIELD = "reveille_typing_field"
    const val TYPING_DONE = "reveille_typing_done"
    const val SEQUENCE_PROGRESS = "reveille_sequence_progress"
    const val TWO_STEP_INDICATE = "reveille_two_step_indicate"
    const val OUT_OF_TRIES = "reveille_out_of_tries"
    const val MISTAKEN = "reveille_mistaken"
    const val PROGRESS = "reveille_progress"
    const val ACKNOWLEDGING = "reveille_acknowledging"
    const val HOLD_WORDS = "reveille_hold_words"
    const val QR_WORDS = "reveille_qr_words"
    const val QR_CANNOT_SCAN = "reveille_qr_cannot_scan"
    const val QR_INSTRUCTION = "reveille_qr_instruction"
    const val QR_VIEWFINDER = "reveille_qr_viewfinder"
    fun alternative(position: Int) = "reveille_alternative_$position"
    const val HANDSHAKE_PROMPT = "reveille_handshake_prompt"
    const val UNSPOKEN_LINE = "reveille_unspoken_line"
    const val DIRECTION_COACHING = "reveille_direction_coaching"
    const val DIRECTION_PRAISE = "reveille_direction_praise"
    const val CLOSED_COACHING = "reveille_closed_coaching"
    const val STARTER_BEGIN = "reveille_starter_begin"
    const val STARTER_COUNTDOWN = "reveille_starter_countdown"
    const val STARTER_LINE = "reveille_starter_line"
    const val STARTER_OVER = "reveille_starter_over"
    const val RING_AREA = "reveille_ring_area"
    const val SCREEN_AREA = "reveille_screen_area"
    fun escapeDelay(minutes: Int) = "reveille_escape_delay_$minutes"
    fun sequenceTarget(number: Int) = "reveille_sequence_target_$number"
}

/** Everything the screens can ask for, so they can be tested without a view model. */
class ReveilleActions(
    val onHoldStarted: (TryId, Long) -> Unit = { _, _ -> },
    val onHoldCompleted: (TryId, Long, Long) -> Unit,
    val onHoldReleasedEarly: (TryId, Long, Long) -> Unit,
    val onAssistiveBegun: (TryId) -> Unit = {},
    val onAssistiveConfirmed: (TryId) -> Unit,
    val onTypingChanged: (TryId, String) -> Unit = { _, _ -> },
    val onTypingSubmitted: (TryId, String) -> Unit = { _, _ -> },
    val onSequenceTapped: (TryId, Int) -> Unit = { _, _ -> },
    val onTwoStepIndicated: (TryId) -> Unit = {},
    val onSwitch: (Int) -> Unit = {},
    /** The camera read a code while the try [TryId] was on screen. */
    val onQrScanned: (TryId, String) -> Unit = { _, _ -> },
    /** The camera cannot be used now. */
    val onQrUnavailable: () -> Unit = {},
    val onSilence: () -> Unit,
    val onResume: () -> Unit,
    val onStarted: () -> Unit,
    val onBeginStarter: () -> Unit = {},
    val onDelay: (Int) -> Unit,
    val onSkip: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
fun ReveilleContent(
    state: ReveilleUiState,
    friction: FrictionPrompt?,
    reasons: List<SkipReasonCode>,
    actions: ReveilleActions,
    onFrictionConfirm: (FrictionEvidence) -> Unit,
    onFrictionDismiss: () -> Unit,
) {
    // Back is not an exit while the alarm rings. It opens the way out that tells the truth: silence, delay or skip, each recorded as what
    // it is, never as a start. Safety silence stays on the screen itself, one tap, whatever happens here. What is open belongs to the alarm
    // that is ringing: the screen remembers which alarm the way out was opened for, not that it is open, so what is restored after the
    // activity is recreated can never be taken for another alarm's, and an alarm that was answered, or taken over by another, is simply not
    // the one it was opened for.
    val ringing = state as? ReveilleUiState.Firing
    var openedFor by rememberSaveable { mutableStateOf<String?>(null) }
    val escapeOpen = ringing != null && openedFor == ringing.occurrenceId
    BackHandler(enabled = ringing != null) { openedFor = ringing?.occurrenceId }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            // A ring is a new screen: a press that began on the hold target of the one before it cannot finish on this one.
            is ReveilleUiState.Firing -> key(state.occurrenceId) { Firing(state, actions) }
            else -> Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag(ReveilleTags.SCREEN_AREA).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (state) {
                    ReveilleUiState.Loading -> CircularProgressIndicator()
                    ReveilleUiState.NotFound -> Closed(stringResource(R.string.reveille_not_found), actions.onClose)
                    is ReveilleUiState.Silenced -> Silenced(state, actions)
                    is ReveilleUiState.Direction -> Direction(state, actions)
                    is ReveilleUiState.Starter -> Starter(state, actions)
                    is ReveilleUiState.Closed -> Closed(closedText(state.outcome), actions.onClose, state.title, state.coaching)
                    is ReveilleUiState.Firing -> Unit
                }
            }
        }
    }
    // Once the alarm is answered the sheet has nothing to offer; a fixed commitment's questions (friction) come up over it, and while the
    // alarm rings they carry a silence of their own, so that nothing stands between the person and the quiet.
    if (escapeOpen) EscapeSheet(actions, onDismiss = { openedFor = null })
    if (friction != null) {
        FrictionDialog(friction.required, friction.action, reasons, onFrictionConfirm, onFrictionDismiss, onSilence = if (ringing != null) actions.onSilence else null)
    }
}

/**
 * What Back offers while the alarm rings. Silence needs nothing from the person. Delay and Skip are answered by the mission's own Mission
 * Lock: a fixed commitment asks for a confirmation or a reason first (the friction dialog opens over this one), a flexible one does not.
 *
 * The silence is one of the dialog's two actions, beside Keep ringing, and not among the choices: the choices scroll (a very large font
 * would push them out of reach), and a silence that scrolled with them could be scrolled out of sight. The actions never scroll, and the
 * dialog lays them out in a flow row of its own, which wraps where they do not fit side by side. (The question over this one builds its
 * own FlowRow for its two, because Material gives a dialog only two action slots.)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EscapeSheet(actions: ReveilleActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ReveilleTags.ESCAPE),
        title = { Text(stringResource(R.string.reveille_escape_title)) },
        text = {
            // It scrolls, so that a very large font cannot push the choices out of reach.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reveille_escape_delay_title), style = MaterialTheme.typography.labelLarge)
                // The three delays wrap onto another line when they do not fit side by side, so that large type never squeezes a label until
                // it breaks inside a word ('mi' and 'n' at twice the size on a 360 dp phone, when they shared the width equally).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (minutes in ESCAPE_DELAYS) {
                        FilledTonalButton(
                            onClick = { actions.onDelay(minutes) },
                            modifier = Modifier.heightIn(min = 56.dp).testTag(ReveilleTags.escapeDelay(minutes)),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) { Text(stringResource(R.string.reveille_escape_delay_minutes, minutes)) }
                    }
                }
                OutlinedButton(onClick = actions.onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ReveilleTags.ESCAPE_SKIP)) {
                    Text(stringResource(R.string.reveille_escape_skip), style = MaterialTheme.typography.labelLarge)
                }
                Text(stringResource(R.string.reveille_escape_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).testTag(ReveilleTags.ESCAPE_KEEP)) {
                Text(stringResource(R.string.reveille_escape_keep))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = actions.onSilence,
                modifier = Modifier.heightIn(min = 56.dp).testTag(ReveilleTags.ESCAPE_SILENCE),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.reveille_escape_silence), style = MaterialTheme.typography.labelLarge) }
        },
    )
}

@Composable
private fun closedText(outcome: ClosedOutcome): String = stringResource(
    when (outcome) {
        ClosedOutcome.STARTED -> R.string.reveille_closed_started
        ClosedOutcome.SKIPPED -> R.string.reveille_closed_skipped
        ClosedOutcome.DELAYED -> R.string.reveille_closed_delayed
        ClosedOutcome.MISSED -> R.string.reveille_closed_missed
        ClosedOutcome.DONE -> R.string.reveille_closed_done
        ClosedOutcome.NOT_RINGING -> R.string.reveille_not_ringing
    },
)

@Composable
private fun Firing(state: ReveilleUiState.Firing, actions: ReveilleActions) {
    // Safety silence is the last thing on the screen and never scrolls: it is always in view and one tap, whatever is being asked. It is the
    // one child of the column that has a size of its own; everything else is given the weight that is left, so that no layout, however much it
    // asks for, can squeeze it (a column that runs out of room squeezes its last child to a sliver, and that was once the silence). A hold
    // keeps its target out of anything that scrolls, so that a finger resting on it can never be taken over by a scroll gesture, and a code to
    // scan keeps its viewfinder out of it in the same way. The title,
    // and typing, the numbers or the two-step, scroll in the space above. The window gives way to the system bars, the notch and the
    // keyboard (safe drawing); in a short window, and with the keyboard up, the margins are tighter.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val tight = maxHeight < TIGHT_WINDOW
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = if (tight) 8.dp else 24.dp).testTag(ReveilleTags.RING_AREA),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val challenge = state.challenge) {
                    is Challenge.Hold -> HoldArea(state, challenge, actions)
                    is Challenge.Qr -> QrArea(state, challenge, actions)
                    else -> Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    ) {
                        TitleBlock(state)
                        when (challenge) {
                            is Challenge.Typing -> key(challenge.tryId) {
                                TypingChallenge(
                                    challenge,
                                    onChanged = { actions.onTypingChanged(challenge.tryId, it) },
                                    onSubmitted = { actions.onTypingSubmitted(challenge.tryId, it) },
                                )
                            }
                            is Challenge.Sequence -> SequenceChallenge(challenge) { actions.onSequenceTapped(challenge.tryId, it) }
                            is Challenge.TwoStep -> TwoStepChallenge(challenge) { actions.onTwoStepIndicated(challenge.tryId) }
                            Challenge.OutOfTries -> OutOfTriesNote()
                            is Challenge.Hold -> Unit
                            is Challenge.Qr -> Unit
                        }
                        Alternatives(state.alternatives, actions.onSwitch)
                    }
                }
            }
            Spacer(Modifier.height(if (tight) 4.dp else 12.dp))
            SilenceButton(actions.onSilence, tight)
        }
    }
}

// What the hold's layout is made of, in dp: the room the words (and the hint) are meant to have, the room the three taps under the target are
// meant to have, the target's full and least size, and when the window counts as short (tighter margins) or as short and wide (the target
// beside the words). The words give way first, then the target shrinks to its least; nothing else is ever squeezed.
private val TIGHT_WINDOW = 420.dp
private val WORDS_MIN = 128.dp
private val CONFIRM_SPACE = 56.dp
private val HOLD_SIZE = 240.dp
private val HOLD_MIN = 96.dp
private val SIDE_BY_SIDE_MAX_HEIGHT = 360.dp
private const val SIDE_BY_SIDE_RATIO = 1.5f

/** The title, and "Step 1 of 2" under it when several steps are all needed. */
@Composable
private fun TitleBlock(state: ReveilleUiState.Firing, caption: String? = null) {
    Text(state.title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    state.progress?.let {
        Text(
            stringResource(R.string.handshake_progress, it.step, it.steps),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag(ReveilleTags.PROGRESS),
        )
    }
    if (caption != null) {
        Text(caption, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.testTag(ReveilleTags.ACKNOWLEDGING))
    }
    // The ring's line, when it was not heard, in type as large as the title's: this is how it reaches the person. The words below the title
    // scroll, so however long the line is, the target and the silence keep their room.
    state.unspokenLine?.let {
        Text(
            it,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag(ReveilleTags.UNSPOKEN_LINE).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.prompt.isNotBlank() && state.challenge != Challenge.OutOfTries) {
        Text(state.prompt, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag(ReveilleTags.HANDSHAKE_PROMPT))
    }
}

/**
 * The hold: the target (or, when its tries are used up, the three taps in its place), kept out of anything that scrolls. It is sized by the
 * room it is actually given, measured here and not guessed from the window: in a short, wide area ([sideBySide]) the target is beside the
 * words, otherwise it is under them, and in any area it is as big as the room allows (never more than its full size), so that a small window
 * shrinks the target and squeezes nothing else.
 */
@Composable
private fun HoldArea(state: ReveilleUiState.Firing, hold: Challenge.Hold, actions: ReveilleActions) {
    val caption = if (hold.acknowledging) stringResource(R.string.handshake_two_hold_first) else null
    val released = hold.earlyRelease && !hold.assistive
    // The three taps are there from the start for a screen reader and a hardware keyboard (the activity says so), and for anyone whose
    // assistive technology clicks instead of pressing (Switch Access, Voice Access) through the hold target's own action: it shows them,
    // it never passes the hold.
    var revealed by rememberSaveable { mutableStateOf(false) }
    val offerConfirm = state.offerAccessibleConfirm || revealed
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sideBySide = !hold.assistive && maxWidth >= maxHeight * SIDE_BY_SIDE_RATIO && maxHeight < SIDE_BY_SIDE_MAX_HEIGHT
        val confirmSpace = if (offerConfirm && !hold.assistive) CONFIRM_SPACE else 0.dp
        val holdSize = (if (sideBySide) minOf(HOLD_SIZE, maxHeight) else (maxHeight - WORDS_MIN - confirmSpace).coerceIn(HOLD_MIN, HOLD_SIZE)).coerceAtMost(maxWidth)
        // A press belongs to a try: when the try changes (a wrong one, a change of mode) the target is a new one, so that a press that began on
        // the old one cannot finish on this one, and the callbacks it holds are always the current try's.
        val target: @Composable () -> Unit = {
            key(hold.tryId) {
                if (hold.assistive) {
                    AssistiveConfirm(hold.tryId, primary = true, onBegun = { actions.onAssistiveBegun(hold.tryId) }) { actions.onAssistiveConfirmed(hold.tryId) }
                } else {
                    HoldButton(
                        hold.seconds,
                        onCompleted = { started, ended -> actions.onHoldCompleted(hold.tryId, started, ended) },
                        onReleasedEarly = { started, ended -> actions.onHoldReleasedEarly(hold.tryId, started, ended) },
                        onStarted = { started -> actions.onHoldStarted(hold.tryId, started) },
                        size = holdSize,
                        onCannotHold = { revealed = true },
                    )
                }
            }
        }
        if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TitleBlock(state, caption)
                    if (released) ReleasedHint(hold)
                    if (offerConfirm) AssistiveConfirm(hold.tryId, primary = false, onBegun = { actions.onAssistiveBegun(hold.tryId) }) { actions.onAssistiveConfirmed(hold.tryId) }
                    Alternatives(state.alternatives, actions.onSwitch)
                }
                target()
            }
        } else {
            // The words, and the hint about a press let go too early, are what gives way: they take the room that is left and scroll in it.
            // The target is above nothing that moves, so a finger on it is not shifted when the hint comes and goes.
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag(ReveilleTags.HOLD_WORDS).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TitleBlock(state, caption)
                        if (released) ReleasedHint(hold)
                        Alternatives(state.alternatives, actions.onSwitch)
                    }
                }
                target()
                if (offerConfirm && !hold.assistive) AssistiveConfirm(hold.tryId, primary = false, onBegun = { actions.onAssistiveBegun(hold.tryId) }) { actions.onAssistiveConfirmed(hold.tryId) }
            }
        }
    }
}

/**
 * A code to scan: the words above (the title, what to do, a wrong code said so, the other ways) take half the room that is left and scroll in
 * it, and the viewfinder takes the other half, out of anything that scrolls, as the hold's target is. The camera is [LocalQrScanner]'s. A code
 * it reads is reported with the try on screen; a camera that cannot be used is reported once, and the view model asks the fallback instead.
 * Under the viewfinder, "Cannot scan? Type a phrase instead" makes the same report, for a camera that opens but never reads anything.
 */
@Composable
private fun QrArea(state: ReveilleUiState.Firing, qr: Challenge.Qr, actions: ReveilleActions) {
    val scanner = LocalQrScanner.current
    val description = stringResource(R.string.handshake_qr_viewfinder)
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag(ReveilleTags.QR_WORDS).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TitleBlock(state)
                QrWords(qr)
                Alternatives(state.alternatives, actions.onSwitch)
            }
        }
        Spacer(Modifier.height(8.dp))
        scanner.Viewfinder(
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).testTag(ReveilleTags.QR_VIEWFINDER).semantics { contentDescription = description },
            onDecoded = { actions.onQrScanned(qr.tryId, it) },
            onError = actions.onQrUnavailable,
        )
        // A camera can open and still never show a code it can read (the camera privacy switch, black frames, a code it cannot focus on), and
        // then it reports nothing: the person can always say so, and is asked the fallback instead. It is not a try, and it is out of anything
        // that scrolls, under the viewfinder; Safety silence is apart from it and is never covered or replaced by it.
        OutlinedButton(
            onClick = actions.onQrUnavailable,
            modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp).testTag(ReveilleTags.QR_CANNOT_SCAN),
        ) {
            Text(stringResource(R.string.handshake_qr_cannot_scan), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/** "Or try another way": the other steps the person may turn to, as buttons, when the chain allows any. */
@Composable
internal fun Alternatives(alternatives: List<Alternative>, onSwitch: (Int) -> Unit) {
    if (alternatives.isEmpty()) return
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(if (alternatives.first().instead) R.string.handshake_alternatives_instead else R.string.handshake_alternatives_order),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
        for (alternative in alternatives) {
            OutlinedButton(
                onClick = { onSwitch(alternative.position) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(ReveilleTags.alternative(alternative.position)),
            ) {
                // A press whose tries are used up leads to the three taps: the button says what will be asked, not the name of the press.
                Text(
                    if (alternative.assistive) stringResource(R.string.handshake_alternative_taps) else com.command.handshake.modeName(alternative.mode),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ReleasedHint(hold: Challenge.Hold) {
    Text(
        pluralStringResource(R.plurals.reveille_hold_released, hold.seconds, hold.seconds),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Always on screen while ringing, never behind a challenge. A dialog that covers it (the way out, a Mission Lock question) carries a silence of its own. */
@Composable
private fun SilenceButton(onSilence: () -> Unit, tight: Boolean = false) {
    val description = stringResource(R.string.reveille_silence_description)
    OutlinedButton(
        onClick = onSilence,
        modifier = Modifier.fillMaxWidth().height(if (tight) 56.dp else 64.dp).testTag(ReveilleTags.SILENCE).semantics { contentDescription = description },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.reveille_silence), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Press and hold for [seconds]. The ring fills while a finger stays down; letting go early starts over and is reported as a
 * failed attempt. Each press is its own coroutine, so overlapping or repeated touches can never share state. A person who cannot
 * hold has the assistive confirm (three deliberate taps) under it, from the start for a screen reader and otherwise once the tries
 * on the press are used up.
 */
@Composable
fun HoldButton(
    seconds: Int,
    onCompleted: (Long, Long) -> Unit,
    onReleasedEarly: (Long, Long) -> Unit,
    now: () -> Long = System::currentTimeMillis,
    onStarted: (Long) -> Unit = {},
    size: Dp = HOLD_SIZE,
    onCannotHold: (() -> Unit)? = null,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val description = pluralStringResource(R.plurals.reveille_hold_description, seconds, seconds)
    val cannotHold = stringResource(R.string.reveille_hold_accessible_button)

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .testTag(ReveilleTags.HOLD)
            .semantics {
                role = Role.Button
                contentDescription = description
                // For assistive technology that clicks (Switch Access, Voice Access, a screen reader's action menu): a click cannot hold, so
                // the action shows the three taps that stand in for the hold. A finger on the target is not affected by it.
                if (onCannotHold != null) {
                    onClick(label = cannotHold) {
                        onCannotHold()
                        true
                    }
                }
            }
            .pointerInput(seconds) {
                detectTapGestures(
                    onPress = {
                        val started = now()
                        onStarted(started)
                        coroutineScope {
                            val timer = launch {
                                // The hold is timed on the frame clock, which the system animation settings do not scale:
                                // with animations turned off (or sped up) a quick tap must still not count as a hold.
                                val holdNanos = seconds * 1_000_000_000L
                                val origin = withFrameNanos { it }
                                var elapsed = 0L
                                while (elapsed < holdNanos) {
                                    elapsed = withFrameNanos { it } - origin
                                    progress = (elapsed.toFloat() / holdNanos).coerceAtMost(1f)
                                }
                                onCompleted(started, now())
                            }
                            tryAwaitRelease()
                            if (timer.isActive) {
                                timer.cancel()
                                progress = 0f
                                onReleasedEarly(started, now())
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), strokeWidth = 14.dp)
        Text(stringResource(R.string.reveille_hold_hint), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Silenced(state: ReveilleUiState.Silenced, actions: ReveilleActions) {
    Text(state.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(if (state.missed) R.string.reveille_missed_title else R.string.reveille_silenced_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(stringResource(if (state.missed) R.string.reveille_missed_body else R.string.reveille_silenced_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(32.dp))
    Button(onClick = actions.onResume, modifier = Modifier.fillMaxWidth().height(64.dp).testTag(ReveilleTags.RESUME)) {
        Text(stringResource(R.string.reveille_resume), style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = actions.onSkip, modifier = Modifier.testTag(ReveilleTags.SKIP)) { Text(stringResource(R.string.reveille_skip)) }
}

@Composable
private fun Direction(state: ReveilleUiState.Direction, actions: ReveilleActions) {
    if (state.praise.isNotBlank()) {
        Text(state.praise, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.testTag(ReveilleTags.DIRECTION_PRAISE))
        Spacer(Modifier.height(16.dp))
    }
    FirstStep(state.title, state.firstAction)
    if (state.coaching.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        Text(state.coaching, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag(ReveilleTags.DIRECTION_COACHING))
    }
    Spacer(Modifier.height(40.dp))
    if (state.offerStarter) {
        // heightIn and not height: at large type the words take two lines, and the button must not clip them.
        FilledTonalButton(onClick = actions.onBeginStarter, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(ReveilleTags.STARTER_BEGIN)) {
            Text(pluralStringResource(R.plurals.reveille_starter_begin, state.starterMinutes, state.starterMinutes), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
    }
    Answers(actions)
}

@Composable
private fun FirstStep(title: String, firstAction: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.reveille_direction_label), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Text(firstAction, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
}

/**
 * The countdown, from what the view model says is left. The figures change every second, so a screen reader hears the minutes when it comes to
 * them and is not told each second; the line, which changes once, is announced.
 */
@Composable
private fun Starter(state: ReveilleUiState.Starter, actions: ReveilleActions) {
    FirstStep(state.title, state.firstAction)
    Spacer(Modifier.height(24.dp))
    val minutesLeft = (state.remainingSeconds + 59) / 60
    val spoken = pluralStringResource(R.plurals.reveille_starter_left, minutesLeft, minutesLeft)
    Text(
        stringResource(R.string.reveille_starter_clock, state.remainingSeconds / 60, state.remainingSeconds % 60),
        style = MaterialTheme.typography.displayLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(ReveilleTags.STARTER_COUNTDOWN).semantics { contentDescription = spoken },
    )
    Spacer(Modifier.height(16.dp))
    if (state.remainingSeconds == 0) {
        Text(stringResource(R.string.reveille_starter_over), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag(ReveilleTags.STARTER_OVER).semantics { liveRegion = LiveRegionMode.Polite })
    } else if (state.line.isNotBlank()) {
        Text(state.line, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag(ReveilleTags.STARTER_LINE).semantics { liveRegion = LiveRegionMode.Polite })
    }
    Spacer(Modifier.height(40.dp))
    Answers(actions)
}

/** What can be answered once the alarm has been dealt with: a start is the person's to report, and a delay or a skip is recorded as what it is. */
@Composable
private fun Answers(actions: ReveilleActions) {
    Button(onClick = actions.onStarted, modifier = Modifier.fillMaxWidth().height(72.dp).testTag(ReveilleTags.STARTED)) {
        Text(stringResource(R.string.reveille_starting), style = MaterialTheme.typography.titleLarge)
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(onClick = { actions.onDelay(DELAY_MINUTES) }, modifier = Modifier.weight(1f).height(56.dp).testTag(ReveilleTags.DELAY)) {
            Text(stringResource(R.string.reveille_delay, DELAY_MINUTES))
        }
        OutlinedButton(onClick = actions.onSkip, modifier = Modifier.weight(1f).height(56.dp).testTag(ReveilleTags.SKIP)) {
            Text(stringResource(R.string.reveille_skip))
        }
    }
}

@Composable
private fun Closed(message: String, onClose: () -> Unit, title: String? = null, coaching: String = "") {
    if (title != null) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
    }
    Text(message, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    if (coaching.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(coaching, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag(ReveilleTags.CLOSED_COACHING))
    }
    Spacer(Modifier.height(32.dp))
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.reveille_close)) }
}

/**
 * Mission Lock, kept minimal until the full flow lands: exactly as much friction as the mission asks for. While the alarm rings
 * ([onSilence] is given) its Safety silence is in the row of actions with Cancel, which never scrolls, and not among the reasons, which do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FrictionDialog(
    required: LockPolicy,
    action: FrictionAction,
    reasons: List<SkipReasonCode>,
    onConfirm: (FrictionEvidence) -> Unit,
    onDismiss: () -> Unit,
    onSilence: (() -> Unit)? = null,
) {
    val delay = action == FrictionAction.DELAY
    val title = when (required) {
        LockPolicy.REASON -> stringResource(if (delay) R.string.reveille_friction_delay_reason_title else R.string.reveille_friction_reason_title)
        LockPolicy.RECOVERY_OFFER -> stringResource(if (delay) R.string.reveille_friction_delay_recovery_title else R.string.reveille_friction_recovery_title)
        else -> stringResource(if (delay) R.string.reveille_friction_delay_confirm_title else R.string.reveille_friction_confirm_title)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // It scrolls, so that a very large font cannot push the buttons out of reach.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (required) {
                    LockPolicy.REASON -> reasons.forEach { reason ->
                        OutlinedButton(onClick = { onConfirm(FrictionEvidence.Reason(reason)) }, modifier = Modifier.fillMaxWidth()) {
                            Text(reasonLabel(reason))
                        }
                    }
                    LockPolicy.RECOVERY_OFFER -> Text(stringResource(if (delay) R.string.reveille_friction_delay_recovery_body else R.string.reveille_friction_recovery_body))
                    else -> Text(stringResource(if (delay) R.string.reveille_friction_delay_confirm_body else R.string.reveille_friction_confirm_body))
                }
            }
        },
        confirmButton = {
            if (required != LockPolicy.REASON) {
                TextButton(onClick = { onConfirm(if (required == LockPolicy.RECOVERY_OFFER) FrictionEvidence.RecoveryDeclined else FrictionEvidence.Confirmed) }) {
                    Text(stringResource(if (delay) R.string.reveille_friction_delay_confirm else R.string.reveille_friction_confirm))
                }
            }
        },
        // While the alarm rings the way to stop it is here too, one tap with nothing asked first: a question about putting it off or
        // skipping it must never stand between the person and the silence, and it stays in view whatever has been scrolled.
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSilence != null) {
                    OutlinedButton(
                        onClick = onSilence,
                        modifier = Modifier.heightIn(min = 56.dp).testTag(ReveilleTags.FRICTION_SILENCE),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.reveille_escape_silence), style = MaterialTheme.typography.labelLarge) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.reveille_friction_cancel)) }
            }
        },
    )
}

@Composable
private fun reasonLabel(reason: SkipReasonCode): String = stringResource(
    when (reason) {
        SkipReasonCode.EMERGENCY -> R.string.reveille_reason_EMERGENCY
        SkipReasonCode.UNWELL -> R.string.reveille_reason_UNWELL
        SkipReasonCode.SCHEDULE_CONFLICT -> R.string.reveille_reason_SCHEDULE_CONFLICT
        SkipReasonCode.NEED_REST -> R.string.reveille_reason_NEED_REST
        SkipReasonCode.NOT_RELEVANT_TODAY -> R.string.reveille_reason_NOT_RELEVANT_TODAY
        SkipReasonCode.OTHER -> R.string.reveille_reason_OTHER
    },
)

private const val DELAY_MINUTES = 10

/** The delays Back offers while it rings. */
private val ESCAPE_DELAYS = listOf(10, 30, 60)

