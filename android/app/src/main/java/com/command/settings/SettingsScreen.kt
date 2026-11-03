package com.command.settings

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.domain.voice.RoutePolicy
import com.example.R

object SettingsTags {
    const val ROUTE_SPEAKER = "settings_route_speaker"
    const val ROUTE_HEADSET = "settings_route_headset"
    const val LINK_HISTORY = "settings_link_history"
    const val LINK_RELIABILITY = "settings_link_reliability"
    const val LINK_CONSENTS = "settings_link_consents"
    const val ABOUT = "settings_about"
}

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenConsents: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onChooseRoute = viewModel::chooseRoutePolicy,
        onOpenHistory = onOpenHistory,
        onOpenReliability = onOpenReliability,
        onOpenConsents = onOpenConsents,
        version = com.example.BuildConfig.VERSION_NAME,
    )
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onChooseRoute: (RoutePolicy) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenConsents: () -> Unit,
    version: String,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            SettingsUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is SettingsUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(stringResource(R.string.settings_route_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                }
                item { Text(stringResource(R.string.settings_route_body), style = MaterialTheme.typography.bodyMedium) }
                item {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Choice(
                            selected = state.routePolicy == RoutePolicy.SPEAKER_AND_HEADSET,
                            title = R.string.settings_route_speaker,
                            detail = R.string.settings_route_speaker_detail,
                            onSelect = { onChooseRoute(RoutePolicy.SPEAKER_AND_HEADSET) },
                            tag = SettingsTags.ROUTE_SPEAKER,
                        )
                        Choice(
                            selected = state.routePolicy == RoutePolicy.HEADSET_ONLY,
                            title = R.string.settings_route_headset,
                            detail = R.string.settings_route_headset_detail,
                            onSelect = { onChooseRoute(RoutePolicy.HEADSET_ONLY) },
                            tag = SettingsTags.ROUTE_HEADSET,
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.settings_more_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp).semantics { heading() },
                    )
                }
                item { Link(R.string.settings_link_history, onOpenHistory, SettingsTags.LINK_HISTORY) }
                item { Link(R.string.settings_link_reliability, onOpenReliability, SettingsTags.LINK_RELIABILITY) }
                item { Link(R.string.settings_link_consents, onOpenConsents, SettingsTags.LINK_CONSENTS) }
                item {
                    Column(Modifier.padding(top = 16.dp).testTag(SettingsTags.ABOUT), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.settings_about_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.settings_about_version, version), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.settings_about_privacy), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(selected: Boolean, @StringRes title: Int, @StringRes detail: Int, onSelect: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Link(@StringRes label: Int, onClick: () -> Unit, tag: String) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    }
}
