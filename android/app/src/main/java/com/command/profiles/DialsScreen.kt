package com.command.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.domain.dials.Dial
import com.command.domain.dials.Dials
import com.command.domain.dials.Personality
import com.command.R

object DialsTags {
    const val LIST = "dials_list"
    const val NAME = "dials_profile_name"
    const val IN_USE = "dials_in_use"
    const val PREVIEW = "dials_preview"
    const val PREVIEW_CAP = "dials_preview_cap"
    const val OWN_CHAIN = "dials_own_chain"
    const val OPEN_HANDSHAKE = "dials_open_handshake"
    const val NOT_FOUND = "dials_not_found"
    fun value(dial: Dial, value: Int) = "dials_${dial.name}_$value"
    fun effect(dial: Dial) = "dials_${dial.name}_effect"
}

@Composable
fun DialsRoute(profileId: String, onBack: () -> Unit, onOpenHandshake: (String) -> Unit, viewModel: DialsViewModel = hiltViewModel()) {
    LaunchedEffect(profileId) { viewModel.open(profileId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    DialsScreen(state, onBack, viewModel::set, onOpenHandshake)
}

/** The dials the first group is made of: the two that change the words, with the line that shows it right under them. */
private val WORD_DIALS = listOf(Dial.FIRMNESS, Dial.OBNOXIOUSNESS)
private val OTHER_DIALS = Dial.values().filterNot { it in WORD_DIALS }

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialsScreen(state: DialsUiState, onBack: () -> Unit, onSet: (Dial, Int) -> Unit, onOpenHandshake: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dials_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.profiles_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            DialsUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            DialsUiState.NotFound -> Box(Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag(DialsTags.NOT_FOUND), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.handshake_not_found), style = MaterialTheme.typography.bodyLarge)
            }
            is DialsUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag(DialsTags.LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            state.profile.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() }.testTag(DialsTags.NAME),
                        )
                        Text(stringResource(R.string.dials_intro), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(if (state.inUse) R.string.handshake_profile_in_use else R.string.handshake_profile_unused),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(DialsTags.IN_USE),
                        )
                    }
                }
                items(WORD_DIALS, key = { it.name }) { dial -> DialRow(dial, state.dials, onSet) }
                item { Preview(state) }
                items(OTHER_DIALS, key = { it.name }) { dial ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialRow(dial, state.dials, onSet)
                        if (dial == Dial.HANDSHAKE_DIFFICULTY && state.profile.own != null) OwnChainNote { onOpenHandshake(state.profile.id) }
                    }
                }
            }
        }
    }
}

/** One dial: its name, its five settings and what the one chosen does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialRow(dial: Dial, dials: Dials, onSet: (Dial, Int) -> Unit) {
    val name = dialName(dial)
    val current = dials[dial]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in Dials.RANGE) {
                // Five buttons that say 1 to 5: each says which dial it is, to a screen reader and to a voice that says "Firmness 3".
                val named = stringResource(R.string.dial_value_named, name, value)
                FilterChip(
                    selected = current == value,
                    onClick = { onSet(dial, value) },
                    label = { Text(value.toString()) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = named }.testTag(DialsTags.value(dial, value)),
                )
            }
        }
        Text(dialEffectWords(dial, current), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(DialsTags.effect(dial)))
    }
}

/** The line a ring of this profile would open with, which follows firmness and obnoxiousness as they are set. */
@Composable
private fun Preview(state: DialsUiState.Ready) {
    val title = stringResource(R.string.dials_preview_mission)
    val firstAction = stringResource(R.string.dials_preview_first_action)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dials_preview_title, title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(previewLine(state, title, firstAction), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(DialsTags.PREVIEW))
            // The minimalist's cap takes the noise and then the closer first, so the dials may show less here than they do for the others: the ring does the same.
            if (state.profile.personality == Personality.MINIMALIST) {
                Text(stringResource(R.string.dials_preview_cap), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(DialsTags.PREVIEW_CAP))
            }
        }
    }
}

/** The difficulty dial chooses among the standard handshakes, so it changes nothing while a profile has one of its own. */
@Composable
private fun OwnChainNote(onOpenHandshake: () -> Unit) {
    Column(Modifier.testTag(DialsTags.OWN_CHAIN), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dials_own_chain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onOpenHandshake, modifier = Modifier.heightIn(min = 56.dp).testTag(DialsTags.OPEN_HANDSHAKE)) {
            Text(stringResource(R.string.dials_open_handshake))
        }
    }
}
