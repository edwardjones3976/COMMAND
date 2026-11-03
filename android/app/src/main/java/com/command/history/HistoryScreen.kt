package com.command.history

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.domain.history.HistoryReason
import com.command.domain.history.WindowSummary
import com.example.R
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object HistoryTags {
    const val WEEK = "history_week"
    const val MONTH = "history_month"
    const val EMPTY = "history_empty"
    fun day(date: LocalDate) = "history_day_$date"
    fun entry(occurrenceId: String) = "history_entry_$occurrenceId"
}

@Composable
fun HistoryRoute(onBack: () -> Unit, onOpenOccurrence: (String) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryScreen(state = state, onBack = onBack, onOpenOccurrence = onOpenOccurrence)
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onOpenOccurrence: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.history_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            HistoryUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is HistoryUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Summary(R.string.history_week_title, state.week, Modifier.testTag(HistoryTags.WEEK)) }
                item { Summary(R.string.history_month_title, state.month, Modifier.testTag(HistoryTags.MONTH)) }
                item { Text(stringResource(R.string.history_summary_note), style = MaterialTheme.typography.bodySmall) }
                if (state.days.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag(HistoryTags.EMPTY), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.history_empty_body), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                for (day in state.days) {
                    item(key = "day-${day.date}") {
                        Text(
                            dayLabel(day.date, state.today),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() }.testTag(HistoryTags.day(day.date)),
                        )
                    }
                    items(day.entries, key = { it.occurrenceId }) { entry -> Entry(entry, onOpenOccurrence) }
                }
            }
        }
    }
}

@Composable
private fun Summary(@StringRes title: Int, summary: WindowSummary, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                if (summary.eligibleDays == 0) {
                    stringResource(R.string.history_summary_none)
                } else {
                    pluralStringResource(R.plurals.history_summary, summary.eligibleDays, summary.daysWithStart, summary.eligibleDays)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun Entry(entry: HistoryEntry, onOpen: (String) -> Unit) {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(entry.atUtcMs).atZone(entry.zone))
    val outcome = stringResource(reasonLabelRes(entry.reason))
    val description = stringResource(R.string.history_entry_description, entry.title, time, outcome)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.occurrenceId) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(HistoryTags.entry(entry.occurrenceId)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Text(time, style = MaterialTheme.typography.bodyMedium)
            }
            Text(outcome, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.history_day_today)
    today.minusDays(1) -> stringResource(R.string.history_day_yesterday)
    else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(date)
}

/** What happened, in the person's own words. Never worded as a mark against them unless it was their own choice. */
@StringRes
internal fun reasonLabelRes(reason: HistoryReason): Int = when (reason) {
    HistoryReason.STARTED -> R.string.history_reason_started
    HistoryReason.DONE -> R.string.history_reason_done
    HistoryReason.SKIPPED_BY_USER -> R.string.history_reason_skipped
    HistoryReason.TIMED_OUT -> R.string.history_reason_timed_out
    HistoryReason.SILENCED -> R.string.history_reason_silenced
    HistoryReason.UNCONFIRMED -> R.string.history_reason_unconfirmed
    HistoryReason.BLOCKED -> R.string.history_reason_blocked
    HistoryReason.RECOVERY_OFFERED -> R.string.history_reason_restart
    HistoryReason.CANCELLED_BY_CALENDAR -> R.string.history_reason_calendar
    HistoryReason.PAUSED -> R.string.history_reason_paused
    HistoryReason.PHONE_WAS_OFF -> R.string.history_reason_phone_off
    HistoryReason.RESCHEDULED -> R.string.history_reason_moved
    HistoryReason.OPEN -> R.string.history_reason_open
}
