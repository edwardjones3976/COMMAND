package com.command.calendar

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.data.db.CalendarSourceRow
import com.command.domain.dials.Flexibility
import com.example.R

object CalendarTags {
    const val ALLOW = "calendars_allow"
    const val PERMISSION = "calendars_permission"
    const val EMPTY = "calendars_empty"
    fun source(id: Long) = "calendars_source_$id"
    fun select(id: Long) = "calendars_select_$id"
    fun fixed(id: Long) = "calendars_fixed_$id"
    fun flexible(id: Long) = "calendars_flexible_$id"
    fun profile(id: Long, profileId: String?) = "calendars_profile_${id}_$profileId"
}

@Composable
fun CalendarSourcesRoute(onBack: () -> Unit, viewModel: CalendarSourcesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    CalendarSourcesScreen(
        state = state,
        onBack = onBack,
        onAllow = {
            viewModel.consent()
            ask.launch(Manifest.permission.READ_CALENDAR)
        },
        onSelect = viewModel::setSelected,
        onDefaults = viewModel::setDefaults,
    )
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSourcesScreen(
    state: CalendarSourcesUiState,
    onBack: () -> Unit,
    onAllow: () -> Unit,
    onSelect: (Long, Boolean) -> Unit,
    onDefaults: (CalendarSourceRow, Flexibility, String?) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendars_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.calendars_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            CalendarSourcesUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is CalendarSourcesUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.calendars_intro), style = MaterialTheme.typography.bodyLarge) }
                if (!state.consented || !state.permitted) {
                    item {
                        if (state.consented) Text(stringResource(R.string.calendars_permission_lost), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(CalendarTags.PERMISSION))
                        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(CalendarTags.ALLOW)) {
                            Text(stringResource(R.string.calendars_allow), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else if (state.sources.isEmpty()) {
                    item { Text(stringResource(R.string.calendars_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(CalendarTags.EMPTY)) }
                } else {
                    items(state.sources, key = { it.calendarId }) { source -> SourceCard(source, state.profiles, onSelect, onDefaults) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
    source: CalendarSourceRow,
    profiles: List<ProfileChoice>,
    onSelect: (Long, Boolean) -> Unit,
    onDefaults: (CalendarSourceRow, Flexibility, String?) -> Unit,
) {
    val id = source.calendarId
    Card(Modifier.fillMaxWidth().testTag(CalendarTags.source(id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(source.account, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = source.selected, onCheckedChange = { onSelect(id, it) }, modifier = Modifier.testTag(CalendarTags.select(id)))
            }
            if (source.selected) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = source.defaultFlexibility == Flexibility.FLEXIBLE,
                        onClick = { onDefaults(source, Flexibility.FLEXIBLE, source.defaultProfileId) },
                        label = { Text(stringResource(R.string.editor_flexible)) },
                        modifier = Modifier.testTag(CalendarTags.flexible(id)),
                    )
                    FilterChip(
                        selected = source.defaultFlexibility == Flexibility.RIGID,
                        onClick = { onDefaults(source, Flexibility.RIGID, source.defaultProfileId) },
                        label = { Text(stringResource(R.string.editor_fixed)) },
                        modifier = Modifier.testTag(CalendarTags.fixed(id)),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = source.defaultProfileId == null,
                        onClick = { onDefaults(source, source.defaultFlexibility, null) },
                        label = { Text(stringResource(R.string.calendars_profile_active)) },
                        modifier = Modifier.testTag(CalendarTags.profile(id, null)),
                    )
                    for (p in profiles) {
                        FilterChip(
                            selected = source.defaultProfileId == p.id,
                            onClick = { onDefaults(source, source.defaultFlexibility, p.id) },
                            label = { Text(p.name) },
                            modifier = Modifier.testTag(CalendarTags.profile(id, p.id)),
                        )
                    }
                }
            }
        }
    }
}
