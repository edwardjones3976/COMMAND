package com.command.profiles

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.command.data.ProfileHandshake
import com.command.data.ProfileRepository
import com.example.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProfilesUiState {
    data object Loading : ProfilesUiState

    /** [activeId] is the profile the setting names; none is shown as active when it names one that is gone. [inUse] holds the ids of the profiles alarms are fired with. */
    data class Ready(val profiles: List<ProfileHandshake>, val activeId: String?, val inUse: Set<String>) : ProfilesUiState
}

/** The profiles, which is active and which alarms use, all as stored now. Choosing one writes it at once, as Settings does. */
@HiltViewModel
class ProfilesViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    val state: StateFlow<ProfilesUiState> = combine<List<ProfileHandshake>, String?, Set<String>, ProfilesUiState>(
        profiles.observe(),
        profiles.observeActiveId(),
        profiles.observeInUse(),
    ) { all, active, inUse -> ProfilesUiState.Ready(all, active, inUse) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProfilesUiState.Loading)

    /** A profile that has gone meanwhile is not made active, and the list then no longer shows it. */
    fun activate(id: String) {
        viewModelScope.launch { profiles.activate(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

object ProfilesTags {
    const val LIST = "profiles_list"
    const val RULES = "profiles_rules"
    const val NONE_ACTIVE = "profiles_none_active"
    fun profile(id: String) = "profiles_profile_$id"
    fun active(id: String) = "profiles_active_$id"
    fun inUse(id: String) = "profiles_in_use_$id"
    fun dials(id: String) = "profiles_dials_$id"
    fun handshake(id: String) = "profiles_handshake_$id"
}

class ProfilesActions(
    val onBack: () -> Unit,
    val onActivate: (String) -> Unit,
    val onOpenDials: (String) -> Unit,
    val onOpenHandshake: (String) -> Unit,
    val onOpenRules: () -> Unit,
)

@Composable
fun ProfilesRoute(
    onBack: () -> Unit,
    onOpenDials: (String) -> Unit,
    onOpenHandshake: (String) -> Unit,
    onOpenRules: () -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfilesScreen(state, ProfilesActions(onBack, viewModel::activate, onOpenDials, onOpenHandshake, onOpenRules))
}

/** Stateless, so it can be shown in tests without a view model. This is the screen that sets the active profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(state: ProfilesUiState, actions: ProfilesActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.profiles_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            ProfilesUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ProfilesUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).selectableGroup().testTag(ProfilesTags.LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.profiles_intro), style = MaterialTheme.typography.bodyMedium) }
                if (state.profiles.none { it.id == state.activeId }) {
                    item {
                        Text(
                            stringResource(R.string.profiles_none_active),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(ProfilesTags.NONE_ACTIVE),
                        )
                    }
                }
                items(state.profiles, key = { it.id }) { profile -> ProfileCard(profile, profile.id == state.activeId, profile.id in state.inUse, actions) }
                item {
                    OutlinedButton(onClick = actions.onOpenRules, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ProfilesTags.RULES)) {
                        Text(stringResource(R.string.profiles_rules_link))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(profile: ProfileHandshake, active: Boolean, inUse: Boolean, actions: ProfilesActions) {
    Card(modifier = Modifier.fillMaxWidth().testTag(ProfilesTags.profile(profile.id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = active, onClick = { actions.onActivate(profile.id) }, role = Role.RadioButton)
                    .testTag(ProfilesTags.active(profile.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = active, onClick = null)
                Text(
                    stringResource(if (active) R.string.profiles_active else R.string.profiles_make_active),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                stringResource(if (inUse) R.string.handshake_profile_in_use else R.string.handshake_profile_unused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ProfilesTags.inUse(profile.id)),
            )
            // Each button is named for its profile, to a screen reader and to a voice that says "Dials of Steady".
            val dialsNamed = stringResource(R.string.profiles_dials_named, profile.name)
            val handshakeNamed = stringResource(R.string.profiles_handshake_named, profile.name)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actions.onOpenDials(profile.id) },
                    modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = dialsNamed }.testTag(ProfilesTags.dials(profile.id)),
                ) { Text(stringResource(R.string.profiles_dials)) }
                OutlinedButton(
                    onClick = { actions.onOpenHandshake(profile.id) },
                    modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = handshakeNamed }.testTag(ProfilesTags.handshake(profile.id)),
                ) { Text(stringResource(R.string.profiles_handshake)) }
            }
        }
    }
}
