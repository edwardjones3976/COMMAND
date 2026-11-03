package com.command.schedule

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.data.db.ScheduleRow
import com.command.domain.dials.Flexibility
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.example.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object ScheduleTags {
    const val ADD = "schedule_add"
    const val EMPTY = "schedule_empty"
    const val NOTIFICATIONS_BANNER = "schedule_banner_notifications"
    const val EXACT_BANNER = "schedule_banner_exact"
    fun row(occurrenceId: String) = "schedule_row_$occurrenceId"
}

@Composable
fun ScheduleRoute(
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var notificationsOff by remember { mutableStateOf(!notificationsEnabled(context)) }

    // Both can be changed in system settings, so look again whenever the screen comes back.
    LifecycleResumeEffect(Unit) {
        notificationsOff = !notificationsEnabled(context)
        viewModel.onResume()
        onPauseOrDispose { }
    }

    ScheduleScreen(
        state = state,
        notificationsOff = notificationsOff,
        onAdd = onAdd,
        onOpen = onOpen,
        onOpenNotificationSettings = { openNotificationSettings(context) },
        onAllowExactAlarms = { requestExactAlarms(context) },
    )
}

/** Stateless, so it can be shown in tests without a view model. */
@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    notificationsOff: Boolean,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onAllowExactAlarms: () -> Unit,
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
                if (notificationsOff) {
                    item {
                        Banner(
                            tag = ScheduleTags.NOTIFICATIONS_BANNER,
                            title = stringResource(R.string.schedule_banner_notifications_title),
                            body = stringResource(R.string.schedule_banner_notifications_body),
                            action = stringResource(R.string.schedule_banner_notifications_action),
                            onAction = onOpenNotificationSettings,
                        )
                    }
                }
                if (!state.exactAlarmsAvailable) {
                    item {
                        Banner(
                            tag = ScheduleTags.EXACT_BANNER,
                            title = stringResource(R.string.schedule_banner_exact_title),
                            body = stringResource(R.string.schedule_banner_exact_body),
                            action = stringResource(R.string.schedule_banner_exact_action),
                            onAction = onAllowExactAlarms,
                        )
                    }
                }
                if (state.upcoming.isEmpty() && state.recent.isEmpty()) {
                    item { Empty() }
                }
                if (state.upcoming.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.schedule_section_upcoming)) }
                    items(state.upcoming, key = { it.occurrenceId }) { RowCard(it, zone, today, onOpen) }
                }
                if (state.recent.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.schedule_section_recent)) }
                    items(state.recent, key = { it.occurrenceId }) { RowCard(it, zone, today, onOpen) }
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
private fun Banner(tag: String, title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun RowCard(row: ScheduleRow, zone: ZoneId, today: LocalDate, onOpen: (String) -> Unit) {
    val whenLabel = whenText(row.scheduledAtUtc, zone, today)
    val state = stateLabel(row)
    val description = stringResource(R.string.schedule_row_description, row.title, whenLabel, state)
    Card(
        onClick = { onOpen(row.missionId) },
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
            Text(whenLabel, style = MaterialTheme.typography.bodyLarge)
            Text(state, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun whenText(atUtcMs: Long, zone: ZoneId, today: LocalDate): String {
    val local = Instant.ofEpochMilli(atUtcMs).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    return when (local.toLocalDate()) {
        today -> stringResource(R.string.schedule_when_today, time)
        today.plusDays(1) -> stringResource(R.string.schedule_when_tomorrow, time)
        else -> stringResource(R.string.schedule_when_other, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local), time)
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

private fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        // Before Android 8 there is no page for an app's notifications; the app's own page has the switch.
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun requestExactAlarms(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
