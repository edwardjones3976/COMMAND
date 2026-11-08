package com.command.handshake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.data.ProfileHandshake
import com.command.data.ProfileRepository
import com.command.R
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface HandshakeProfilesUiState {
    data object Loading : HandshakeProfilesUiState
    /** [inUse] holds the ids of the profiles that alarms are fired with: the active one, and any a mission names. */
    data class Ready(val profiles: List<ProfileHandshake>, val inUse: Set<String>) : HandshakeProfilesUiState
}

/**
 * The profiles and the handshake each asks for, as stored, and which of them alarms are fired with: the list follows the database, so a saved
 * chain shows at once.
 */
@HiltViewModel
class HandshakeProfilesViewModel @Inject constructor(profiles: ProfileRepository) : ViewModel() {
    val state: StateFlow<HandshakeProfilesUiState> = combine<List<ProfileHandshake>, Set<String>, HandshakeProfilesUiState>(
        profiles.observe(),
        profiles.observeInUse(),
    ) { all, inUse -> HandshakeProfilesUiState.Ready(all, inUse) }
        .stateIn(guardedScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HandshakeProfilesUiState.Loading)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

object HandshakeTags {
    const val LIST = "handshake_list"
    fun profile(id: String) = "handshake_profile_$id"
    fun edit(id: String) = "handshake_edit_$id"
    fun inUse(id: String) = "handshake_in_use_$id"

    const val EDITOR = "handshake_editor"
    const val PROFILE_NAME = "handshake_profile_name"
    const val RESTORED = "handshake_restored"
    const val OP_OR = "handshake_op_or"
    const val OP_AND = "handshake_op_and"
    const val OP_SEQUENCE = "handshake_op_sequence"
    const val ADD = "handshake_add"
    const val SAVE = "handshake_save"
    const val STANDARD = "handshake_standard"
    const val SAVED = "handshake_saved"
    const val ISSUES = "handshake_issues"
    const val UNUSABLE = "handshake_unusable"
    const val IN_USE = "handshake_in_use"
    const val ADD_LIMIT = "handshake_add_limit"
    const val LEAVE_DIALOG = "handshake_leave_dialog"
    const val LEAVE_KEEP = "handshake_leave_keep"
    const val LEAVE_CONFIRM = "handshake_leave_confirm"
    const val STANDARD_DIALOG = "handshake_standard_dialog"
    const val STANDARD_KEEP = "handshake_standard_keep"
    const val STANDARD_CONFIRM = "handshake_standard_confirm"
    fun step(index: Int) = "handshake_step_$index"
    fun onlyQuestion(index: Int) = "handshake_step_${index}_only_question"
    fun beginsWithHold(index: Int) = "handshake_step_${index}_begins_with_hold"
    fun mode(index: Int, mode: com.command.domain.dials.HandshakeMode) = "handshake_step_${index}_mode_${mode.name}"
    fun tries(index: Int) = "handshake_step_${index}_tries"
    fun triesLess(index: Int) = "handshake_step_${index}_tries_less"
    fun triesMore(index: Int) = "handshake_step_${index}_tries_more"
    fun time(index: Int) = "handshake_step_${index}_time"
    fun timeLess(index: Int) = "handshake_step_${index}_time_less"
    fun timeMore(index: Int) = "handshake_step_${index}_time_more"
    fun hold(index: Int) = "handshake_step_${index}_hold"
    fun holdLess(index: Int) = "handshake_step_${index}_hold_less"
    fun holdMore(index: Int) = "handshake_step_${index}_hold_more"
    fun up(index: Int) = "handshake_step_${index}_up"
    fun down(index: Int) = "handshake_step_${index}_down"
    fun remove(index: Int) = "handshake_step_${index}_remove"
    fun qrNeeds(index: Int) = "handshake_step_${index}_qr_needs"
    fun codes(index: Int) = "handshake_step_${index}_codes"
    const val CODES = "handshake_codes"
}

@Composable
fun HandshakeProfilesRoute(onBack: () -> Unit, onEdit: (String) -> Unit, onOpenCodes: () -> Unit = {}, viewModel: HandshakeProfilesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HandshakeProfilesScreen(state, onBack, onEdit, onOpenCodes)
}

/** Stateless, so it can be shown in tests without a view model. [onOpenCodes] opens the codes that a step which scans a code checks against. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandshakeProfilesScreen(state: HandshakeProfilesUiState, onBack: () -> Unit, onEdit: (String) -> Unit, onOpenCodes: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.handshake_profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.handshake_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            HandshakeProfilesUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is HandshakeProfilesUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag(HandshakeTags.LIST),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.handshake_profiles_intro), style = MaterialTheme.typography.bodyMedium) }
                item {
                    OutlinedButton(
                        onClick = onOpenCodes,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(HandshakeTags.CODES),
                    ) { Text(stringResource(R.string.handshake_registered_codes)) }
                }
                items(state.profiles, key = { it.id }) { profile -> ProfileCard(profile, profile.id in state.inUse, onEdit) }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: ProfileHandshake, inUse: Boolean, onEdit: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(HandshakeTags.profile(profile.id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(chainWords(profile.chain), style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    profile.storedUnusable -> stringResource(R.string.handshake_profile_unusable)
                    profile.own == null -> stringResource(R.string.handshake_profile_standard, profile.difficulty)
                    else -> stringResource(R.string.handshake_profile_own)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (profile.storedUnusable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(if (inUse) R.string.handshake_profile_in_use else R.string.handshake_profile_unused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(HandshakeTags.inUse(profile.id)),
            )
            // Four profiles, four buttons: each is named for its profile, to a screen reader and to a voice that says "Edit Steady".
            val editNamed = stringResource(R.string.handshake_profile_edit_named, profile.name)
            OutlinedButton(
                onClick = { onEdit(profile.id) },
                modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = editNamed }.testTag(HandshakeTags.edit(profile.id)),
            ) { Text(stringResource(R.string.handshake_profile_edit)) }
        }
    }
}
