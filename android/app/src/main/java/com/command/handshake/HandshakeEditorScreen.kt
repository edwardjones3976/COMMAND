package com.command.handshake

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainLimits
import com.command.domain.dials.ChainOp
import com.command.domain.dials.HandshakeMode
import com.command.domain.handshake.ChainState
import com.command.R
import com.command.ui.SafeQuestion

/** Everything the editor can ask for, so the screen can be tested without a view model. */
class HandshakeEditorActions(
    val onBack: () -> Unit,
    val onSetOp: (ChainOp) -> Unit,
    val onAddStep: () -> Unit,
    val onRemoveStep: (key: Int) -> Unit,
    val onMoveStep: (key: Int, up: Boolean) -> Unit,
    val onSetMode: (key: Int, mode: HandshakeMode) -> Unit,
    val onChangeTries: (key: Int, by: Int) -> Unit,
    val onChangeTimeout: (key: Int, bySteps: Int) -> Unit,
    val onChangeHold: (key: Int, by: Int) -> Unit,
    val onSave: () -> Unit,
    val onUseStandard: () -> Unit,
)

@Composable
fun HandshakeEditorRoute(profileId: String, onBack: () -> Unit, viewModel: HandshakeEditorViewModel = hiltViewModel()) {
    LaunchedEffect(profileId) { viewModel.open(profileId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HandshakeEditorScreen(
        state,
        HandshakeEditorActions(
            onBack = onBack,
            onSetOp = viewModel::setOp,
            onAddStep = viewModel::addStep,
            onRemoveStep = viewModel::removeStep,
            onMoveStep = viewModel::moveStep,
            onSetMode = viewModel::setMode,
            onChangeTries = viewModel::changeTries,
            onChangeTimeout = viewModel::changeTimeout,
            onChangeHold = viewModel::changeHold,
            onSave = viewModel::save,
            onUseStandard = viewModel::useStandard,
        ),
    )
}

/**
 * Stateless, so it can be shown in tests without a view model. Two things are asked before they are done, because each throws work away:
 * leaving with changes that were not saved (the arrow and Back both ask), and going back to the standard handshake, which deletes what was saved.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HandshakeEditorScreen(state: HandshakeEditorUiState, actions: HandshakeEditorActions) {
    val unsaved = (state as? HandshakeEditorUiState.Editing)?.changed == true
    var leaving by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    // Whatever made the question moot (the changes were saved while it was open) closes it, so that it cannot come back by itself later.
    LaunchedEffect(unsaved) { if (!unsaved) leaving = false }
    BackHandler(enabled = unsaved) { leaving = true }
    val back = { if (unsaved) leaving = true else actions.onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                // One short word, so that it never wraps and is never cut off: which profile this is stands in the list, as a heading.
                title = { Text(stringResource(R.string.handshake_editor_bar_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.handshake_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            HandshakeEditorUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            HandshakeEditorUiState.NotFound -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.handshake_not_found), style = MaterialTheme.typography.titleMedium)
            }
            is HandshakeEditorUiState.Editing -> Column(Modifier.fillMaxSize().padding(padding)) {
                EditorList(state, actions, onAskStandard = { resetting = true }, modifier = Modifier.weight(1f).fillMaxWidth())
                Outcome(state)
            }
        }
    }
    if (leaving && unsaved) {
        LeaveDialog(onKeep = { leaving = false }, onLeave = { leaving = false; actions.onBack() })
    }
    val editing = state as? HandshakeEditorUiState.Editing
    if (resetting && editing != null) {
        StandardDialog(editing.difficulty, onKeep = { resetting = false }, onConfirm = { resetting = false; actions.onUseStandard() })
    }
}

/** What can be edited, one item after another. [onAskStandard] asks the question before the saved handshake is deleted. */
@Composable
private fun EditorList(state: HandshakeEditorUiState.Editing, actions: HandshakeEditorActions, onAskStandard: () -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    // The note under the list takes room from the list. When it appears, the end of the list (Save, and the way back to the standard) is brought
    // into view above it, so that the control the person has just used does not go under it.
    LaunchedEffect(state.saved, state.restored) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if ((state.saved || state.restored) && last >= 0) listState.scrollToItem(last)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(HandshakeTags.EDITOR),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                state.profileName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.testTag(HandshakeTags.PROFILE_NAME),
            )
        }
        item { Text(stringResource(R.string.handshake_editor_intro), style = MaterialTheme.typography.bodyMedium) }
        item {
            Text(
                stringResource(if (state.inUse) R.string.handshake_profile_in_use else R.string.handshake_profile_unused),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(HandshakeTags.IN_USE),
            )
        }
        if (state.storedUnusable) {
            item {
                Text(
                    stringResource(R.string.handshake_editor_unusable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(HandshakeTags.UNUSABLE),
                )
            }
        }
        if (state.steps.size >= 2) item { HowStepsCombine(state.op, actions.onSetOp) }
        itemsIndexed(state.steps, key = { _, step -> step.key }) { index, step ->
            StepCard(index, state, step, actions)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = actions.onAddStep,
                    enabled = state.canAddStep,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(HandshakeTags.ADD),
                ) { Text(stringResource(R.string.handshake_add_step)) }
                if (!state.canAddStep) {
                    Text(
                        stringResource(R.string.handshake_problem_too_many, ChainLimits.MAX_STEPS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(HandshakeTags.ADD_LIMIT),
                    )
                }
            }
        }
        val general = state.issues.filter { it.position == null }
        if (general.isNotEmpty()) {
            item {
                Column(Modifier.testTag(HandshakeTags.ISSUES), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (issue in general) Text(problemWords(issue.problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Text(stringResource(R.string.handshake_editor_silence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            // A button that is off says why, so that a screen reader does not meet "Save, disabled" with nothing about what to do.
            val whyNot = when {
                state.canSave -> null
                state.issues.isNotEmpty() -> stringResource(R.string.handshake_save_blocked)
                state.saved -> stringResource(R.string.handshake_save_saved)
                else -> stringResource(R.string.handshake_save_unchanged)
            }
            Button(
                onClick = actions.onSave,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics { if (whyNot != null) stateDescription = whyNot }
                    .testTag(HandshakeTags.SAVE),
            ) { Text(stringResource(R.string.handshake_save)) }
        }
        if (!state.usingStandard || state.storedUnusable) {
            item {
                // Apart from Save, and asked about first: it deletes what was saved.
                TextButton(
                    onClick = onAskStandard,
                    modifier = Modifier.padding(top = 16.dp).heightIn(min = 56.dp).testTag(HandshakeTags.STANDARD),
                ) { Text(stringResource(R.string.handshake_use_standard, state.difficulty)) }
            }
        }
    }
}

/**
 * What the last save, or going back to the standard handshake, did. It stands under the list and not in it: Save is at the end of the list,
 * so a note added after it landed below the window, was never drawn and never said, and the only sign of a save was that Save went dim. Here
 * it is in view wherever the list is scrolled to, and it is a live region, so that a screen reader says it when it appears.
 */
@Composable
private fun Outcome(state: HandshakeEditorUiState.Editing) {
    val (words, tag) = when {
        state.saved -> stringResource(if (state.inUse) R.string.handshake_saved else R.string.handshake_saved_unused) to HandshakeTags.SAVED
        state.restored -> stringResource(R.string.handshake_restored, state.difficulty) to HandshakeTags.RESTORED
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            words,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        )
    }
}

/** The question before changes are thrown away; the answer that keeps them is the first (see [Question]). */
@Composable
private fun LeaveDialog(onKeep: () -> Unit, onLeave: () -> Unit) {
    SafeQuestion(
        tag = HandshakeTags.LEAVE_DIALOG,
        title = stringResource(R.string.handshake_leave_title),
        message = stringResource(R.string.handshake_leave_body),
        keepLabel = R.string.handshake_leave_keep,
        keepTag = HandshakeTags.LEAVE_KEEP,
        onKeep = onKeep,
        throwAwayLabel = R.string.handshake_leave_confirm,
        throwAwayTag = HandshakeTags.LEAVE_CONFIRM,
        onThrowAway = onLeave,
    )
}

/** The question before the saved handshake is deleted; the answer that keeps it is the first, as in [LeaveDialog]. */
@Composable
private fun StandardDialog(difficulty: Int, onKeep: () -> Unit, onConfirm: () -> Unit) {
    SafeQuestion(
        tag = HandshakeTags.STANDARD_DIALOG,
        title = stringResource(R.string.handshake_standard_title),
        message = stringResource(R.string.handshake_standard_body, difficulty),
        keepLabel = R.string.handshake_standard_keep,
        keepTag = HandshakeTags.STANDARD_KEEP,
        onKeep = onKeep,
        throwAwayLabel = R.string.handshake_standard_confirm,
        throwAwayTag = HandshakeTags.STANDARD_CONFIRM,
        onThrowAway = onConfirm,
    )
}

@Composable
private fun HowStepsCombine(op: ChainOp, onSetOp: (ChainOp) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.handshake_combine_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup()) {
            OpChoice(op == ChainOp.OR, R.string.handshake_combine_or, R.string.handshake_combine_or_detail, HandshakeTags.OP_OR) { onSetOp(ChainOp.OR) }
            OpChoice(op == ChainOp.AND, R.string.handshake_combine_and, R.string.handshake_combine_and_detail, HandshakeTags.OP_AND) { onSetOp(ChainOp.AND) }
            OpChoice(op == ChainOp.SEQUENCE, R.string.handshake_combine_sequence, R.string.handshake_combine_sequence_detail, HandshakeTags.OP_SEQUENCE) { onSetOp(ChainOp.SEQUENCE) }
        }
    }
}

@Composable
private fun OpChoice(selected: Boolean, title: Int, detail: Int, tag: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCard(index: Int, state: HandshakeEditorUiState.Editing, step: StepDraft, actions: HandshakeEditorActions) {
    val here = state.issues.filter { it.position == index }
    Card(modifier = Modifier.fillMaxWidth().testTag(HandshakeTags.step(index))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.handshake_step_number, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = { actions.onMoveStep(step.key, true) }, enabled = index > 0, modifier = Modifier.size(48.dp).testTag(HandshakeTags.up(index))) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.handshake_move_up, index + 1))
                }
                IconButton(
                    onClick = { actions.onMoveStep(step.key, false) },
                    enabled = index < state.steps.lastIndex,
                    modifier = Modifier.size(48.dp).testTag(HandshakeTags.down(index)),
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.handshake_move_down, index + 1)) }
                // Remove is apart from the arrows: a graze while moving a step must not delete it.
                Spacer(Modifier.width(16.dp))
                IconButton(
                    onClick = { actions.onRemoveStep(step.key) },
                    enabled = state.steps.size > 1,
                    modifier = Modifier.size(48.dp).testTag(HandshakeTags.remove(index)),
                ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.handshake_remove_step, index + 1)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in HandshakeMode.values()) {
                    // Judged as the rules judge it: by the way that will be asked, so a planned way that runs as typing takes typing. A way that is only
                    // planned is dimmed because it is not built, and no step asks for it: it does not say that one does.
                    val takenBy = if (mode.available) state.steps.indexOfFirst { it.key != step.key && it.mode.offered == mode.offered } else -1
                    val usedIn = if (takenBy >= 0) stringResource(R.string.handshake_mode_used_in_step, takenBy + 1) else null
                    FilterChip(
                        selected = step.mode == mode,
                        onClick = { actions.onSetMode(step.key, mode) },
                        enabled = mode.available && takenBy < 0,
                        label = {
                            Text(if (mode.available) modeName(mode) else stringResource(R.string.handshake_mode_planned, modeName(mode)))
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { if (usedIn != null) stateDescription = usedIn }
                            .testTag(HandshakeTags.mode(index, mode)),
                    )
                }
            }
            // A press has no clock of its own, and neither has the hold that a two-step begins with: the time is for the three taps that stand
            // in for a press. A two-step has such a hold only where nothing has acknowledged the alarm before it, which is anywhere in a chain
            // that is done in any order, and first in a chain done in order; later in an ordered chain it is only the question, and the
            // step before it has acknowledged the alarm: nothing to hold, nothing to get wrong, and no clock, so there is nothing to set.
            val pressFirst = step.mode == HandshakeMode.HOLD || (step.mode == HandshakeMode.TWO_STEP && (state.op != ChainOp.SEQUENCE || index == 0))
            if (step.mode == HandshakeMode.TWO_STEP && pressFirst) {
                // What the tries and the time below are for: the hold that this step begins with. Another step can spare it that hold only where every
                // step is needed (a step that passes acknowledges the alarm for any two-step still to come): where one step is enough the chain is
                // over when another passes, and in an ordered chain nothing comes before the first step.
                val seconds = step.holdSeconds.takeIf { it > 0 } ?: ChainState.DEFAULT_HOLD_SECONDS
                val spared = state.op == ChainOp.AND && state.steps.size >= 2
                Text(
                    pluralStringResource(
                        if (spared) R.plurals.handshake_two_step_begins_with_hold_unless else R.plurals.handshake_two_step_begins_with_hold,
                        seconds, seconds,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(HandshakeTags.beginsWithHold(index)),
                )
            }
            if (step.mode == HandshakeMode.TWO_STEP && !pressFirst) {
                Text(
                    stringResource(R.string.handshake_two_step_only_question),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(HandshakeTags.onlyQuestion(index)),
                )
            } else {
                Stepper(
                    text = pluralStringResource(R.plurals.handshake_tries_label, step.tries, step.tries),
                    lessDescription = stringResource(R.string.handshake_tries_less),
                    moreDescription = stringResource(R.string.handshake_tries_more),
                    atLeast = step.tries <= ChainLimits.MIN_TRIES,
                    atMost = step.tries >= ChainLimits.MAX_TRIES,
                    onLess = { actions.onChangeTries(step.key, -1) },
                    onMore = { actions.onChangeTries(step.key, 1) },
                    tag = HandshakeTags.tries(index),
                    lessTag = HandshakeTags.triesLess(index),
                    moreTag = HandshakeTags.triesMore(index),
                )
                Stepper(
                    text = pluralStringResource(if (pressFirst) R.plurals.handshake_time_label_taps else R.plurals.handshake_time_label, step.timeoutSec, step.timeoutSec),
                    lessDescription = stringResource(R.string.handshake_time_less),
                    moreDescription = stringResource(R.string.handshake_time_more),
                    atLeast = step.timeoutSec <= ChainLimits.MIN_TIMEOUT_SECONDS,
                    atMost = step.timeoutSec >= ChainLimits.MAX_TIMEOUT_SECONDS,
                    onLess = { actions.onChangeTimeout(step.key, -1) },
                    onMore = { actions.onChangeTimeout(step.key, 1) },
                    tag = HandshakeTags.time(index),
                    lessTag = HandshakeTags.timeLess(index),
                    moreTag = HandshakeTags.timeMore(index),
                )
            }
            if (step.mode == HandshakeMode.HOLD) {
                val seconds = step.holdSeconds.takeIf { it > 0 } ?: ChainState.DEFAULT_HOLD_SECONDS
                Stepper(
                    text = pluralStringResource(R.plurals.handshake_hold_label, seconds, seconds),
                    lessDescription = stringResource(R.string.handshake_hold_less),
                    moreDescription = stringResource(R.string.handshake_hold_more),
                    atLeast = seconds <= ChainLimits.MIN_HOLD_SECONDS,
                    atMost = seconds >= ChainLimits.MAX_HOLD_SECONDS,
                    onLess = { actions.onChangeHold(step.key, -1) },
                    onMore = { actions.onChangeHold(step.key, 1) },
                    tag = HandshakeTags.hold(index),
                    lessTag = HandshakeTags.holdLess(index),
                    moreTag = HandshakeTags.holdMore(index),
                )
            }
            if (!step.mode.available) {
                Text(stringResource(R.string.handshake_planned_runs_as, modeName(step.mode.offered)), style = MaterialTheme.typography.bodySmall)
            }
            for (issue in here) IssueLine(issue)
        }
    }
}

@Composable
private fun IssueLine(issue: ChainIssue) {
    Text(problemWords(issue.problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** A value with a button each way. At a limit the button that would pass it is off and says why, so that it is not a button that silently does nothing. */
@Composable
private fun Stepper(
    text: String,
    lessDescription: String,
    moreDescription: String,
    atLeast: Boolean,
    atMost: Boolean,
    onLess: () -> Unit,
    onMore: () -> Unit,
    tag: String,
    lessTag: String,
    moreTag: String,
) {
    val least = stringResource(R.string.handshake_at_least)
    val most = stringResource(R.string.handshake_at_most)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag))
        OutlinedButton(
            onClick = onLess,
            enabled = !atLeast,
            modifier = Modifier.size(48.dp).testTag(lessTag).semantics {
                contentDescription = lessDescription
                if (atLeast) stateDescription = least
            },
            contentPadding = PaddingValues(0.dp),
        ) { Text(stringResource(R.string.handshake_minus)) }
        OutlinedButton(
            onClick = onMore,
            enabled = !atMost,
            modifier = Modifier.padding(start = 8.dp).size(48.dp).testTag(moreTag).semantics {
                contentDescription = moreDescription
                if (atMost) stateDescription = most
            },
            contentPadding = PaddingValues(0.dp),
        ) { Text(stringResource(R.string.handshake_plus)) }
    }
}
