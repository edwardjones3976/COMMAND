package com.command.consents

import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentOwner
import com.command.domain.model.ConsentSource
import com.command.domain.reliability.Fix
import com.command.reliability.SystemLinks
import com.example.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object ConsentTags {
    const val FOOTER = "consent_footer"
    fun item(key: ConsentKey) = "consent_item_${key.name}"
    fun state(key: ConsentKey) = "consent_state_${key.name}"
    fun settings(key: ConsentKey) = "consent_settings_${key.name}"
    fun historyToggle(key: ConsentKey) = "consent_history_toggle_${key.name}"
    fun switch(key: ConsentKey) = "consent_switch_${key.name}"
    fun history(key: ConsentKey) = "consent_history_${key.name}"
}

@Composable
fun ConsentCenterRoute(onBack: () -> Unit, viewModel: ConsentCenterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permissions can be changed in Android's own screens; ask again when the page returns so the history is current.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    ConsentCenterScreen(
        state = state,
        onBack = onBack,
        onOpenSettings = { fix -> SystemLinks.intentFor(context, fix)?.let { SystemLinks.open(context, it) } },
        onToggle = viewModel::setGranted,
    )
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentCenterScreen(
    state: ConsentCenterUiState,
    onBack: () -> Unit,
    onOpenSettings: (Fix) -> Unit,
    onToggle: (ConsentKey, Boolean) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.consent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.consent_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            ConsentCenterUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ConsentCenterUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.consent_intro), style = MaterialTheme.typography.bodyLarge) }
                items(state.items, key = { it.boundary.key.name }) { ConsentCard(it, zone, onOpenSettings, onToggle) }
                item {
                    Text(stringResource(R.string.consent_footer), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(ConsentTags.FOOTER))
                }
            }
        }
    }
}

@Composable
private fun ConsentCard(item: ConsentItem, zone: ZoneId, onOpenSettings: (Fix) -> Unit, onToggle: (ConsentKey, Boolean) -> Unit) {
    val key = item.boundary.key
    var showHistory by rememberSaveable(key.name) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().testTag(ConsentTags.item(key))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(consentTitle(key)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(consentAllows(key)), style = MaterialTheme.typography.bodyLarge)
            consentNot(key)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
            Text(
                stringResource(
                    when (item.granted) {
                        true -> R.string.consent_state_granted
                        false -> R.string.consent_state_off
                        null -> R.string.consent_state_unknown
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = if (item.granted == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.testTag(ConsentTags.state(key)),
            )
            item.latest?.let { latest ->
                val whenText = formatWhen(latest.atUtc, zone)
                Text(
                    stringResource(if (latest.source == ConsentSource.USER) R.string.consent_changed_by_you else R.string.consent_changed_by_android, whenText),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (item.boundary.owner == ConsentOwner.PLATFORM) {
                Text(stringResource(R.string.consent_platform_note), style = MaterialTheme.typography.bodyLarge)
            } else {
                val on = item.granted == true
                // The title is outside the switch's row, so a screen reader would hear only "On" or "Off": the row carries the title.
                val switchLabel = stringResource(consentTitle(key))
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle(key, it) })
                        .semantics { contentDescription = switchLabel }
                        .testTag(ConsentTags.switch(key)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(if (on) R.string.consent_switch_on else R.string.consent_switch_off), style = MaterialTheme.typography.titleLarge)
                    Switch(checked = on, onCheckedChange = null)
                }
                Text(stringResource(R.string.consent_app_note), style = MaterialTheme.typography.bodyLarge)
            }
            item.fix?.let { fix ->
                OutlinedButton(
                    onClick = { onOpenSettings(fix) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(ConsentTags.settings(key)),
                ) { Text(stringResource(R.string.consent_open_settings), style = MaterialTheme.typography.labelLarge) }
            }
            if (item.history.isNotEmpty()) {
                TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.heightIn(min = 48.dp).testTag(ConsentTags.historyToggle(key))) {
                    Text(stringResource(if (showHistory) R.string.consent_history_hide else R.string.consent_history_show), style = MaterialTheme.typography.labelLarge)
                }
                if (showHistory) {
                    Column(modifier = Modifier.testTag(ConsentTags.history(key)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (change in item.history) {
                            Text(historyLine(change, zone), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun historyLine(change: ConsentChange, zone: ZoneId): String = stringResource(
    R.string.consent_history_row,
    stringResource(if (change.granted) R.string.consent_history_granted else R.string.consent_history_revoked),
    formatWhen(change.atUtc, zone),
    stringResource(if (change.source == ConsentSource.USER) R.string.consent_history_by_you else R.string.consent_history_by_android),
)

private fun formatWhen(atUtc: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(atUtc).atZone(zone))

@StringRes
private fun consentTitle(key: ConsentKey): Int = when (key) {
    ConsentKey.NOTIFICATIONS -> R.string.consent_notifications_title
    ConsentKey.EXACT_ALARM -> R.string.consent_exact_title
    ConsentKey.FULL_SCREEN -> R.string.consent_fullscreen_title
    ConsentKey.VOLUME_CONTROL -> R.string.consent_volume_title
    ConsentKey.CALENDAR_READ -> R.string.consent_calendar_title
    else -> error("$key has no words yet: a consent is added to the list together with what it says")
}

@StringRes
private fun consentAllows(key: ConsentKey): Int = when (key) {
    ConsentKey.NOTIFICATIONS -> R.string.consent_notifications_allows
    ConsentKey.EXACT_ALARM -> R.string.consent_exact_allows
    ConsentKey.FULL_SCREEN -> R.string.consent_fullscreen_allows
    ConsentKey.VOLUME_CONTROL -> R.string.consent_volume_allows
    ConsentKey.CALENDAR_READ -> R.string.consent_calendar_allows
    else -> error("$key has no words yet: a consent is added to the list together with what it says")
}

/** What a consent that the app owns does not allow, said as plainly as what it does. The ones Android keeps need no such line. */
@StringRes
private fun consentNot(key: ConsentKey): Int? = when (key) {
    ConsentKey.VOLUME_CONTROL -> R.string.consent_volume_not
    ConsentKey.CALENDAR_READ -> R.string.consent_calendar_not
    else -> null
}
