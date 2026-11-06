package com.command.reliability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.command.domain.reliability.AlarmOutcome
import com.command.domain.reliability.AlarmOutcomeKind
import com.command.domain.reliability.Fix
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.ReliabilityItem
import com.command.domain.reliability.ReliabilityReport
import com.command.domain.reliability.Severity
import com.command.ui.whenLabel
import com.command.R
import java.time.LocalDate
import java.time.ZoneId

object ReliabilityTags {
    const val SUMMARY = "reliability_summary"
    const val ALARM_LIMIT = "reliability_alarm_limit"
    const val OK_LIST = "reliability_ok_list"
    const val RECENT_EMPTY = "reliability_recent_empty"
    const val MAKER = "reliability_maker"
    const val MAKER_ACTION = "reliability_maker_action"
    fun item(precondition: Precondition) = "reliability_item_${precondition.name}"
    fun action(precondition: Precondition) = "reliability_action_${precondition.name}"
    fun recent(occurrenceId: String) = "reliability_recent_$occurrenceId"
}

@Composable
fun ReliabilityRoute(onBack: () -> Unit, viewModel: ReliabilityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canAsk = (state as? ReliabilityUiState.Ready)?.canAskNotifications ?: false
    val onFix = rememberFixHandler(canAsk, viewModel::onNotificationRequestResult)
    val context = LocalContext.current

    // Every setting here can be changed in Android's own screens, so the page reads the phone again whenever it comes back.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    ReliabilityScreen(
        state = state,
        onBack = onBack,
        onFix = onFix,
        onOpenMaker = { viewModel.makerLink()?.let { SystemLinks.open(context, it.intent) } },
    )
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliabilityScreen(
    state: ReliabilityUiState,
    onBack: () -> Unit,
    onFix: (Fix) -> Unit,
    onOpenMaker: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reliability_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reliability_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            ReliabilityUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ReliabilityUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.reliability_intro), style = MaterialTheme.typography.bodyLarge) }
                item { Summary(state.report, state.refusedAlarms) }

                val advice = state.report.items.filter { it.severity == Severity.INFO }
                attention(R.string.reliability_section_problems, state.report.problems, state.canAskNotifications, onFix)
                if (state.refusedAlarms > 0) {
                    if (state.report.problems.isEmpty()) item { SectionTitle(stringResource(R.string.reliability_section_problems)) }
                    item { AlarmLimitCard(state.refusedAlarms) }
                }
                attention(R.string.reliability_section_warnings, state.report.warnings, state.canAskNotifications, onFix)
                attention(R.string.reliability_section_advice, advice, state.canAskNotifications, onFix)

                val working = state.report.items.filter { it.severity == Severity.OK }
                if (working.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.reliability_section_ok)) }
                    item { WorkingCard(working) }
                }

                item { SectionTitle(stringResource(R.string.reliability_section_recent)) }
                if (state.recent.isEmpty()) {
                    item { Text(stringResource(R.string.reliability_recent_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(ReliabilityTags.RECENT_EMPTY)) }
                } else {
                    items(state.recent, key = { it.occurrenceId }) { RecentCard(it, zone, today) }
                }

                if (state.maker != null) {
                    item { SectionTitle(stringResource(R.string.reliability_section_maker)) }
                    item { MakerCard(state.maker, onOpenMaker) }
                }
            }
        }
    }
}

private fun LazyListScope.attention(
    @StringRes titleRes: Int,
    items: List<ReliabilityItem>,
    canAskNotifications: Boolean,
    onFix: (Fix) -> Unit,
) {
    if (items.isEmpty()) return
    item { SectionTitle(stringResource(titleRes)) }
    items(items, key = { it.precondition.name }) { ItemCard(it, canAskNotifications, onFix) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Summary(report: ReliabilityReport, refusedAlarms: Int) {
    val problems = report.problems.size + if (refusedAlarms > 0) 1 else 0
    val title = when {
        problems > 0 -> pluralStringResource(R.plurals.reliability_summary_problems_title, problems, problems)
        report.warnings.isNotEmpty() -> stringResource(R.string.reliability_summary_warnings_title)
        else -> stringResource(R.string.reliability_summary_healthy_title)
    }
    val body = stringResource(
        when {
            problems > 0 -> R.string.reliability_summary_problems_body
            report.warnings.isNotEmpty() -> R.string.reliability_summary_warnings_body
            else -> R.string.reliability_summary_healthy_body
        },
    )
    val container = when {
        problems > 0 -> MaterialTheme.colorScheme.errorContainer
        report.warnings.isNotEmpty() -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        problems > 0 -> MaterialTheme.colorScheme.onErrorContainer
        report.warnings.isNotEmpty() -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag(ReliabilityTags.SUMMARY),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
internal fun ItemCard(item: ReliabilityItem, canAskNotifications: Boolean, onFix: (Fix) -> Unit) {
    val action = itemAction(item, canAskNotifications)
    val fix = item.fix
    Card(modifier = Modifier.fillMaxWidth().testTag(ReliabilityTags.item(item.precondition))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(itemTitle(item.precondition), style = MaterialTheme.typography.titleLarge)
            Text(
                itemState(item),
                style = MaterialTheme.typography.labelLarge,
                color = if (item.severity == Severity.PROBLEM) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
            itemBody(item)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (action != null && fix != null) {
                val modifier = Modifier.heightIn(min = 48.dp).testTag(ReliabilityTags.action(item.precondition))
                if (item.severity == Severity.PROBLEM) {
                    Button(onClick = { onFix(fix) }, modifier = modifier) { Text(action, style = MaterialTheme.typography.labelLarge) }
                } else {
                    OutlinedButton(onClick = { onFix(fix) }, modifier = modifier) { Text(action, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

/** The platform's limit on how many alarms one app can hold. Nothing on the phone's settings pages changes it, so there is no button. */
@Composable
private fun AlarmLimitCard(refused: Int) {
    Card(modifier = Modifier.fillMaxWidth().testTag(ReliabilityTags.ALARM_LIMIT)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.reliability_limit_title), style = MaterialTheme.typography.titleLarge)
            Text(pluralStringResource(R.plurals.reliability_limit_state, refused, refused), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.reliability_limit_body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun WorkingCard(items: List<ReliabilityItem>) {
    Card(modifier = Modifier.fillMaxWidth().testTag(ReliabilityTags.OK_LIST)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in items) {
                val title = itemTitle(item.precondition)
                val state = itemState(item)
                val description = stringResource(R.string.reliability_item_description, title, state)
                Row(
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(state, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun RecentCard(outcome: AlarmOutcome, zone: ZoneId, today: LocalDate) {
    val whenText = whenLabel(outcome.scheduledAtUtc, zone, today)
    val result = outcomeText(outcome)
    val title = if (outcome.isTest) stringResource(R.string.reliability_recent_test_title, outcome.title) else outcome.title
    val description = stringResource(R.string.reliability_recent_row_description, title, whenText, result)
    Card(modifier = Modifier.fillMaxWidth().testTag(ReliabilityTags.recent(outcome.occurrenceId)).semantics(mergeDescendants = true) { contentDescription = description }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(whenText, style = MaterialTheme.typography.bodyLarge)
            Text(
                result,
                style = MaterialTheme.typography.bodyLarge,
                color = if (outcome.kind == AlarmOutcomeKind.ON_TIME) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun outcomeText(outcome: AlarmOutcome): String = when (outcome.kind) {
    AlarmOutcomeKind.ON_TIME -> stringResource(R.string.reliability_recent_on_time)
    AlarmOutcomeKind.LATE -> stringResource(R.string.reliability_recent_late, duration(outcome.lateSeconds ?: 0))
    AlarmOutcomeKind.MISSED_WHILE_OFF ->
        outcome.lateSeconds?.let { stringResource(R.string.reliability_recent_missed_found, duration(it)) } ?: stringResource(R.string.reliability_recent_missed)
}

/** A length of time in the largest unit that keeps it short: seconds, minutes or hours. */
@Composable
private fun duration(seconds: Long): String = when {
    seconds < 120 -> stringResource(R.string.reliability_duration_seconds, seconds)
    seconds < 7_200 -> stringResource(R.string.reliability_duration_minutes, seconds / 60)
    else -> stringResource(R.string.reliability_duration_hours, seconds / 3_600)
}

@Composable
private fun MakerCard(maker: String, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(ReliabilityTags.MAKER)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(maker, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.reliability_maker_body), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp).testTag(ReliabilityTags.MAKER_ACTION)) {
                Text(stringResource(R.string.reliability_maker_action), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
