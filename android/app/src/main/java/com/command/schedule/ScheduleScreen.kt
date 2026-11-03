package com.command.schedule

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.data.db.ScheduleRow
import com.command.followup.isFollowUp
import com.command.domain.dials.Flexibility
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.ui.whenLabel
import com.example.R
import java.time.LocalDate
import java.time.ZoneId

object ScheduleTags {
    const val ADD = "schedule_add"
    const val EMPTY = "schedule_empty"
    const val ATTENTION_BANNER = "schedule_banner_attention"
    const val ATTENTION_ACTION = "schedule_banner_attention_action"
    const val LINK_RELIABILITY = "schedule_link_reliability"
    const val LINK_CONSENTS = "schedule_link_consents"
    const val LINK_HISTORY = "schedule_link_history"
    const val LINK_SETTINGS = "schedule_link_settings"
    fun row(occurrenceId: String) = "schedule_row_$occurrenceId"
}

@Composable
fun ScheduleRoute(
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenOccurrence: (String) -> Unit,
    onOpenReliability: () -> Unit,
    onOpenConsents: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The person can change what alarms depend on in Android's own screens, so look again whenever this one comes back.
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    ScheduleScreen(
        state = state,
        onAdd = onAdd,
        onOpen = onOpen,
        onOpenOccurrence = onOpenOccurrence,
        onOpenReliability = onOpenReliability,
        onOpenConsents = onOpenConsents,
        onOpenHistory = onOpenHistory,
        onOpenSettings = onOpenSettings,
    )
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenReliability: () -> Unit,
    onOpenConsents: () -> Unit,
    onOpenOccurrence: (String) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.testTag(ScheduleTags.ADD),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.schedule_add), style = MaterialTheme.typography.labelLarge) },
            )
        },
    ) { padding ->
        when (state) {
            ScheduleUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ScheduleUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.schedule_title),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOpenReliability, modifier = Modifier.heightIn(min = 48.dp).testTag(ScheduleTags.LINK_RELIABILITY)) {
                            Text(stringResource(R.string.schedule_link_reliability), style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(onClick = onOpenConsents, modifier = Modifier.heightIn(min = 48.dp).testTag(ScheduleTags.LINK_CONSENTS)) {
                            Text(stringResource(R.string.schedule_link_consents), style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(onClick = onOpenHistory, modifier = Modifier.heightIn(min = 48.dp).testTag(ScheduleTags.LINK_HISTORY)) {
                            Text(stringResource(R.string.schedule_link_history), style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp).testTag(ScheduleTags.LINK_SETTINGS)) {
                            Text(stringResource(R.string.schedule_link_settings), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (state.problems > 0) {
                    item { AttentionBanner(problems = state.problems, onAction = onOpenReliability) }
                }
                if (state.upcoming.isEmpty() && state.recent.isEmpty()) {
                    item { Empty() }
                }
                if (state.upcoming.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.schedule_section_upcoming)) }
                    items(state.upcoming, key = { it.occurrenceId }) { RowCard(it, zone, today, onOpen, onOpenOccurrence) }
                }
                if (state.recent.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.schedule_section_recent)) }
                    items(state.recent, key = { it.occurrenceId }) { RowCard(it, zone, today, onOpen, onOpenOccurrence) }
                }
                // Room under the last row for the new-mission button.
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Empty() {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag(ScheduleTags.EMPTY), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.schedule_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.schedule_empty_body), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AttentionBanner(problems: Int, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(ScheduleTags.ATTENTION_BANNER),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pluralStringResource(R.plurals.reliability_summary_problems_title, problems, problems), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.schedule_banner_attention_body), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag(ScheduleTags.ATTENTION_ACTION)) {
                Text(stringResource(R.string.schedule_banner_attention_action), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun RowCard(row: ScheduleRow, zone: ZoneId, today: LocalDate, onOpen: (String) -> Unit, onOpenOccurrence: (String) -> Unit) {
    val whenText = whenLabel(row.scheduledAtUtc, zone, today)
    val state = stateLabel(row)
    val description = stringResource(R.string.schedule_row_description, row.title, whenText, state)
    Card(
        // An occurrence that is waiting for an answer opens its questions; everything else opens the mission.
        onClick = { if (row.state.isFollowUp()) onOpenOccurrence(row.occurrenceId) else onOpen(row.missionId) },
        modifier = Modifier.fillMaxWidth().testTag(ScheduleTags.row(row.occurrenceId)).semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(row.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    stringResource(if (row.flexibility == Flexibility.RIGID) R.string.schedule_badge_fixed else R.string.schedule_badge_flexible),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(whenText, style = MaterialTheme.typography.bodyLarge)
            Text(state, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** What happened to an occurrence, in the plain words the history will use. Silenced and missed are never called started. */
@Composable
private fun stateLabel(row: ScheduleRow): String = stringResource(
    when (row.state) {
        OccurrenceState.SCHEDULED, OccurrenceState.ARMED ->
            if (row.armMode == ArmMode.INEXACT) R.string.schedule_state_set_inexact else R.string.schedule_state_set
        OccurrenceState.FIRING -> R.string.schedule_state_ringing
        OccurrenceState.ACKNOWLEDGED, OccurrenceState.GUIDED_START -> R.string.schedule_state_acknowledged
        OccurrenceState.STARTED_SELF_REPORTED, OccurrenceState.WORKING_SELF_REPORTED -> R.string.schedule_state_started
        OccurrenceState.COMPLETED_SELF_REPORTED -> R.string.schedule_state_done
        OccurrenceState.MISSED ->
            if (row.missReason == MissReason.DOWNTIME) R.string.schedule_state_missed_off else R.string.schedule_state_missed
        OccurrenceState.SAFETY_SILENCED -> R.string.schedule_state_silenced
        OccurrenceState.SKIPPED ->
            if (row.skipReason == SkipReason.CALENDAR_CANCELLED || row.skipReason == SkipReason.CALENDAR_REMOVED) {
                R.string.schedule_state_cancelled
            } else {
                R.string.schedule_state_skipped
            }
        OccurrenceState.DELAYED -> R.string.schedule_state_delayed
        OccurrenceState.BLOCKED -> R.string.schedule_state_blocked
        OccurrenceState.UNCONFIRMED -> R.string.schedule_state_unconfirmed
        OccurrenceState.RECOVERY_OFFERED -> R.string.schedule_state_reset
    },
)
