package com.command.followup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.domain.dials.FrictionEvidence
import com.command.domain.dials.RecoveryOption
import com.command.domain.dials.SkipReasonCode
import com.command.domain.occurrence.OccurrenceCommand
import com.command.reveille.FrictionDialog
import com.command.reveille.FrictionPrompt
import com.example.R

object FollowUpTags {
    const val STARTED = "followup_started"
    const val WORKING = "followup_working"
    const val DONE = "followup_done"
    const val RESET = "followup_reset"
    const val DELAY = "followup_delay"
    const val SKIP = "followup_skip"
    const val RESTART = "followup_restart"
    const val NOT_NOW = "followup_not_now"
    const val CLOSE = "followup_close"
    const val QUESTION = "followup_question"
    const val NOTICE = "followup_notice"
    fun delayFor(minutes: Int) = "followup_delay_$minutes"
    fun rescheduleTo(minutes: Int) = "followup_reschedule_$minutes"
    const val RESCHEDULE_TOMORROW = "followup_reschedule_tomorrow"
}

/** Everything the screen can do, so that it can be shown in a test with nothing behind it. */
data class FollowUpActions(
    val onStarted: () -> Unit = {},
    val onWorking: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onNeedReset: () -> Unit = {},
    val onDelay: (Int) -> Unit = {},
    val onSkip: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onRescheduleTo: (Int) -> Unit = {},
    val onNotNow: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onFrictionConfirm: (FrictionEvidence) -> Unit = {},
    val onFrictionDismiss: () -> Unit = {},
)

@Composable
fun FollowUpRoute(occurrenceId: String, onClose: () -> Unit, viewModel: FollowUpViewModel = hiltViewModel()) {
    LaunchedEffect(occurrenceId) { viewModel.show(occurrenceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val friction by viewModel.friction.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    FollowUpScreen(
        state = state,
        friction = friction,
        notice = notice,
        reasons = viewModel.reasons,
        onClose = onClose,
        actions = FollowUpActions(
            onStarted = viewModel::started,
            onWorking = viewModel::working,
            onDone = viewModel::done,
            onNeedReset = viewModel::needReset,
            onDelay = viewModel::delay,
            onSkip = viewModel::skip,
            onRestart = viewModel::restart,
            onRescheduleTo = viewModel::rescheduleTo,
            onNotNow = viewModel::notNow,
            onDismissNotice = viewModel::dismissNotice,
            onFrictionConfirm = viewModel::confirmFriction,
            onFrictionDismiss = viewModel::dismissFriction,
        ),
    )
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpScreen(
    state: FollowUpUiState,
    friction: FrictionPrompt?,
    notice: FollowUpNotice?,
    reasons: List<SkipReasonCode>,
    actions: FollowUpActions,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.followup_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.followup_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                FollowUpUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                FollowUpUiState.NotFound -> Message(stringResource(R.string.followup_not_found_title), stringResource(R.string.followup_not_found_body), onClose)
                is FollowUpUiState.Ask -> Ask(state, notice, actions)
                is FollowUpUiState.Recovery -> Recovery(state, actions)
                is FollowUpUiState.Finished -> Finished(state, onClose)
            }
        }
    }
    if (friction != null) {
        FrictionDialog(friction.required, reasons, actions.onFrictionConfirm, actions.onFrictionDismiss)
    }
}

@Composable
private fun Ask(state: FollowUpUiState.Ask, notice: FollowUpNotice?, actions: FollowUpActions) {
    var choosingDelay by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(
                when {
                    state.unconfirmed -> R.string.followup_ask_unconfirmed
                    state.started -> R.string.followup_ask_progress
                    else -> R.string.followup_ask_start
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(FollowUpTags.QUESTION),
        )
        if (state.firstAction.isNotBlank()) {
            Text(stringResource(R.string.followup_first_step, state.firstAction), style = MaterialTheme.typography.bodyLarge)
        }
        if (notice == FollowUpNotice.RESETS_USED_UP) {
            Text(
                stringResource(R.string.followup_resets_used_up),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(FollowUpTags.NOTICE),
            )
        }
        Spacer(Modifier.height(8.dp))

        // The one that fits what is known leads. Once a start was reported, Started has nothing left to say and is not offered.
        if (state.started) {
            Primary(R.string.followup_working, FollowUpTags.WORKING, actions.onWorking)
            Secondary(R.string.followup_done, FollowUpTags.DONE, actions.onDone)
        } else {
            Primary(R.string.followup_started, FollowUpTags.STARTED, actions.onStarted)
            Secondary(R.string.followup_working, FollowUpTags.WORKING, actions.onWorking)
            Secondary(R.string.followup_done, FollowUpTags.DONE, actions.onDone)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = actions.onNeedReset, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(FollowUpTags.RESET)) {
                Text(stringResource(R.string.followup_reset), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = { choosingDelay = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(FollowUpTags.DELAY)) {
                Text(stringResource(R.string.followup_delay), textAlign = TextAlign.Center)
            }
        }
        TextButton(onClick = actions.onSkip, modifier = Modifier.heightIn(min = 48.dp).testTag(FollowUpTags.SKIP)) {
            Text(stringResource(R.string.followup_skip))
        }
    }
    if (choosingDelay) {
        AlertDialog(
            onDismissRequest = { choosingDelay = false },
            title = { Text(stringResource(R.string.followup_delay_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((minutes, label) in DELAYS) {
                        OutlinedButton(
                            onClick = { choosingDelay = false; actions.onDelay(minutes) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(FollowUpTags.delayFor(minutes)),
                        ) { Text(stringResource(label)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingDelay = false }) { Text(stringResource(R.string.followup_cancel)) } },
        )
    }
}

@Composable
private fun Recovery(state: FollowUpUiState.Recovery, actions: FollowUpActions) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.followup_recovery_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.followup_recovery_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        for (option in state.options) {
            when (option) {
                RecoveryOption.RESET -> Primary(R.string.followup_recovery_reset, FollowUpTags.RESTART, actions.onRestart)
                // "Make it smaller" needs the first-action suggester of a later milestone: until something can make the step smaller there is
                // nothing to offer, so the option is in the menu the domain allows and not on the screen.
                RecoveryOption.SMALLER -> Unit
                RecoveryOption.RESCHEDULE -> {
                    Text(stringResource(R.string.followup_recovery_later), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        for (minutes in state.rescheduleMinutes) {
                            OutlinedButton(
                                onClick = { actions.onRescheduleTo(minutes) },
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(FollowUpTags.rescheduleTo(minutes)),
                            ) { Text(stringResource(labelForMinutes(minutes)), textAlign = TextAlign.Center) }
                        }
                    }
                    if (state.offerTomorrow) {
                        OutlinedButton(
                            onClick = { actions.onRescheduleTo(OccurrenceCommand.TOMORROW_MINUTES) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(FollowUpTags.RESCHEDULE_TOMORROW),
                        ) { Text(stringResource(R.string.followup_recovery_tomorrow)) }
                    }
                }
            }
        }
        TextButton(onClick = actions.onNotNow, modifier = Modifier.heightIn(min = 48.dp).testTag(FollowUpTags.NOT_NOW)) {
            Text(stringResource(R.string.followup_recovery_not_now))
        }
    }
}

@Composable
private fun Finished(state: FollowUpUiState.Finished, onClose: () -> Unit) {
    val (title, body) = when (state.outcome) {
        FollowUpOutcome.DONE -> R.string.followup_done_title to R.string.followup_done_body
        FollowUpOutcome.SKIPPED -> R.string.followup_skipped_title to R.string.followup_skipped_body
        FollowUpOutcome.DELAYED -> R.string.followup_delayed_title to R.string.followup_delayed_body
        FollowUpOutcome.WAITING -> R.string.followup_waiting_title to R.string.followup_waiting_body
    }
    Message(stringResource(title), stringResource(body), onClose, heading = state.title)
}

@Composable
private fun Message(title: String, body: String, onClose: () -> Unit, heading: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (heading != null) Text(heading, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp).testTag(FollowUpTags.CLOSE)) {
            Text(stringResource(R.string.followup_close))
        }
    }
}

@Composable
private fun Primary(label: Int, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(72.dp).testTag(tag)) {
        Text(stringResource(label), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Secondary(label: Int, tag: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
    }
}

/** What a delay can be, in minutes, and the words for it. */
private val DELAYS = listOf(10 to R.string.followup_delay_10, 30 to R.string.followup_delay_30, 60 to R.string.followup_delay_60)

private fun labelForMinutes(minutes: Int): Int = when (minutes) {
    15 -> R.string.followup_recovery_in_15
    30 -> R.string.followup_recovery_in_30
    60 -> R.string.followup_recovery_in_60
    else -> R.string.followup_recovery_in_other
}
