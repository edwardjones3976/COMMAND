package com.command.reveille

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.LockPolicy
import com.command.domain.dials.SkipReasonCode
import com.example.R

object ReveilleTags {
    const val HOLD = "reveille_hold"
    const val SILENCE = "reveille_silence"
    const val STARTED = "reveille_started"
    const val SKIP = "reveille_skip"
    const val DELAY = "reveille_delay"
    const val RESUME = "reveille_resume"
    const val ACCESSIBLE = "reveille_accessible_confirm"
}

/** Everything the screens can ask for, so they can be tested without a view model. */
class ReveilleActions(
    val onHoldStarted: () -> Unit = {},
    val onHoldCompleted: (Long, Long) -> Unit,
    val onHoldReleasedEarly: (Long, Long) -> Unit,
    val onAccessibleConfirm: () -> Unit,
    val onSilence: () -> Unit,
    val onResume: () -> Unit,
    val onStarted: () -> Unit,
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
    // Back does nothing while the alarm rings: Safety silence is the one way out of this screen that is recorded as such.
    BackHandler(enabled = state is ReveilleUiState.Firing) { }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            is ReveilleUiState.Firing -> Firing(state, actions)
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (state) {
                    ReveilleUiState.Loading -> CircularProgressIndicator()
                    ReveilleUiState.NotFound -> Closed(stringResource(R.string.reveille_not_found), actions.onClose)
                    is ReveilleUiState.Silenced -> Silenced(state, actions)
                    is ReveilleUiState.Direction -> Direction(state, actions)
                    is ReveilleUiState.Closed -> Closed(closedText(state.outcome), actions.onClose, state.title)
                    is ReveilleUiState.Firing -> Unit
                }
            }
        }
    }
    if (friction != null) FrictionDialog(friction.required, reasons, onFrictionConfirm, onFrictionDismiss)
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
    // Only the title area may scroll (a very large font). The hold target and Safety silence stay put, so a finger resting
    // on the target can never be taken over by a scroll gesture, and silence is always one tap away.
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Text(state.title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        HoldButton(state.holdSeconds, actions.onHoldCompleted, actions.onHoldReleasedEarly, actions.onAccessibleConfirm, onStarted = actions.onHoldStarted)
        Box(modifier = Modifier.height(56.dp), contentAlignment = Alignment.Center) {
            if (state.earlyRelease) {
                Text(stringResource(R.string.reveille_hold_released, state.holdSeconds), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            }
        }
        if (state.offerAccessibleConfirm) {
            TextButton(onClick = actions.onAccessibleConfirm, modifier = Modifier.testTag(ReveilleTags.ACCESSIBLE)) {
                Text(stringResource(R.string.reveille_hold_accessible_button), style = MaterialTheme.typography.labelLarge)
            }
        }
        SilenceButton(actions.onSilence)
    }
}

/** Always on screen while ringing, never behind a challenge or a dialog. */
@Composable
private fun SilenceButton(onSilence: () -> Unit) {
    val description = stringResource(R.string.reveille_silence_description)
    OutlinedButton(
        onClick = onSilence,
        modifier = Modifier.fillMaxWidth().height(64.dp).testTag(ReveilleTags.SILENCE).semantics { contentDescription = description },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.reveille_silence), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Press and hold for [seconds]. The ring fills while a finger stays down; letting go early starts over and is reported as a
 * failed attempt. Each press is its own coroutine, so overlapping or repeated touches can never share state. For TalkBack
 * users the control exposes a custom action that confirms without holding.
 */
@Composable
fun HoldButton(
    seconds: Int,
    onCompleted: (Long, Long) -> Unit,
    onReleasedEarly: (Long, Long) -> Unit,
    onAccessibleConfirm: () -> Unit,
    now: () -> Long = System::currentTimeMillis,
    onStarted: () -> Unit = {},
) {
    var progress by remember { mutableFloatStateOf(0f) }
    val description = stringResource(R.string.reveille_hold_description, seconds)
    val confirmLabel = stringResource(R.string.reveille_hold_confirm_action)

    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .testTag(ReveilleTags.HOLD)
            .semantics {
                role = Role.Button
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction(confirmLabel) { onAccessibleConfirm(); true })
            }
            .pointerInput(seconds) {
                detectTapGestures(
                    onPress = {
                        val started = now()
                        onStarted()
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
    Text(stringResource(R.string.reveille_silenced_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(stringResource(R.string.reveille_silenced_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(32.dp))
    Button(onClick = actions.onResume, modifier = Modifier.fillMaxWidth().height(64.dp).testTag(ReveilleTags.RESUME)) {
        Text(stringResource(R.string.reveille_resume), style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = actions.onSkip, modifier = Modifier.testTag(ReveilleTags.SKIP)) { Text(stringResource(R.string.reveille_skip)) }
}

@Composable
private fun Direction(state: ReveilleUiState.Direction, actions: ReveilleActions) {
    Text(state.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.reveille_direction_label), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Text(state.firstAction, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
    Spacer(Modifier.height(40.dp))
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
private fun Closed(message: String, onClose: () -> Unit, title: String? = null) {
    if (title != null) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
    }
    Text(message, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.reveille_close)) }
}

/** Mission Lock, kept minimal until the full flow lands: exactly as much friction as the mission asks for. */
@Composable
private fun FrictionDialog(required: LockPolicy, reasons: List<SkipReasonCode>, onConfirm: (FrictionEvidence) -> Unit, onDismiss: () -> Unit) {
    val title = when (required) {
        LockPolicy.REASON -> stringResource(R.string.reveille_friction_reason_title)
        LockPolicy.RECOVERY_OFFER -> stringResource(R.string.reveille_friction_recovery_title)
        else -> stringResource(R.string.reveille_friction_confirm_title)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (required) {
                    LockPolicy.REASON -> reasons.forEach { reason ->
                        OutlinedButton(onClick = { onConfirm(FrictionEvidence.Reason(reason)) }, modifier = Modifier.fillMaxWidth()) {
                            Text(reasonLabel(reason))
                        }
                    }
                    LockPolicy.RECOVERY_OFFER -> Text(stringResource(R.string.reveille_friction_recovery_body))
                    else -> Text(stringResource(R.string.reveille_friction_confirm_body))
                }
            }
        },
        confirmButton = {
            if (required != LockPolicy.REASON) {
                TextButton(onClick = { onConfirm(if (required == LockPolicy.RECOVERY_OFFER) FrictionEvidence.RecoveryDeclined else FrictionEvidence.Confirmed) }) {
                    Text(stringResource(R.string.reveille_friction_confirm))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reveille_friction_cancel)) } },
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

