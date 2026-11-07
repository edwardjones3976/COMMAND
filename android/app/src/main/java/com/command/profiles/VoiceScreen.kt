package com.command.profiles

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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.R
import com.command.alarm.PreviewResult
import com.command.data.VoicePackChoice
import com.command.domain.dials.Personality
import com.command.domain.voice.BuiltInVoicePacks
import com.command.domain.voice.SpeechProblem
import com.command.voice.VoiceDirectorEngine
import java.util.Locale
import kotlin.math.roundToInt

object VoiceTags {
    const val LIST = "voice_list"
    const val NAME = "voice_profile_name"
    const val HONEST = "voice_honest"
    const val IN_USE = "voice_in_use"
    const val NONE_CHOSEN = "voice_none_chosen"
    const val NOT_HEARD = "voice_not_heard"
    const val WRITE_FAILED = "voice_write_failed"
    const val NOT_FOUND = "voice_not_found"
    const val TRIM = "voice_trim"
    const val TRIM_VALUE = "voice_trim_value"
    fun pack(id: String) = "voice_pack_$id"
    fun choose(id: String) = "voice_choose_$id"
    fun facts(id: String) = "voice_facts_$id"
    fun hear(id: String) = "voice_hear_$id"
}

/** The trim moves in half decibels, which is what the ten packs are set in. */
private const val TRIM_STEP_DB = 0.5f
private val TRIM_STEPS = (BuiltInVoicePacks.MAX_TRIM_DB / TRIM_STEP_DB).roundToInt() - 1

/** What the slider's position means as a trim: the nearest step, whatever the finger did. */
internal fun snappedTrim(value: Float): Float = ((value / TRIM_STEP_DB).roundToInt() * TRIM_STEP_DB).coerceIn(0f, BuiltInVoicePacks.MAX_TRIM_DB)

class VoiceActions(
    val onBack: () -> Unit,
    val onChoose: (String) -> Unit,
    val onSetTrim: (String, Float) -> Unit,
    val onHear: (packId: String, title: String, firstAction: String) -> Unit,
)

@Composable
fun VoiceRoute(profileId: String, onBack: () -> Unit, viewModel: VoiceViewModel = hiltViewModel()) {
    LaunchedEffect(profileId) { viewModel.open(profileId) }
    // It is as loud as an alarm: it does not go on behind a screen that is no longer there.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    VoiceScreen(state, VoiceActions(onBack, viewModel::choose, viewModel::setTrim, viewModel::hear))
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(state: VoiceUiState, actions: VoiceActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.profiles_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            VoiceUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            VoiceUiState.NotFound -> Box(Modifier.fillMaxSize().padding(padding).padding(16.dp).testTag(VoiceTags.NOT_FOUND), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.handshake_not_found), style = MaterialTheme.typography.bodyLarge)
            }
            is VoiceUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).selectableGroup().testTag(VoiceTags.LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Intro(state) }
                state.notHeard?.let { result -> item { Problem(notHeardWords(result), VoiceTags.NOT_HEARD) } }
                if (state.failedWrites > 0) item { Problem(stringResource(R.string.voice_write_failed), VoiceTags.WRITE_FAILED) }
                items(state.packs, key = { it.id }) { pack -> PackCard(pack, state, actions) }
            }
        }
    }
}

@Composable
private fun Intro(state: VoiceUiState.Ready) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(state.profile.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.testTag(VoiceTags.NAME))
        // Said first and plainly: the names are characters, and what can be heard is what this phone can do.
        Text(stringResource(R.string.voice_honest), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(VoiceTags.HONEST))
        Text(stringResource(R.string.voice_choose_explained), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.voice_preview_explained), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (state.inUse) R.string.handshake_profile_in_use else R.string.handshake_profile_unused),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(VoiceTags.IN_USE),
        )
        if (state.packs.none { it.id == state.voicePackId }) {
            Text(stringResource(R.string.voice_none_chosen), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(VoiceTags.NONE_CHOSEN))
        }
    }
}

@Composable
private fun Problem(words: String, tag: String) {
    Text(words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))
}

@Composable
private fun notHeardWords(result: PreviewResult): String = stringResource(
    when (result) {
        PreviewResult.RingSounding -> R.string.voice_not_heard_ring
        is PreviewResult.Unavailable -> when (result.reason) {
            SpeechProblem.NO_OFFLINE_VOICE -> R.string.voice_not_heard_no_voice
            SpeechProblem.HEADSET_REQUIRED -> R.string.voice_not_heard_headset
            SpeechProblem.NO_ENGINE, SpeechProblem.INIT_TIMEOUT -> R.string.voice_not_heard_engine
            SpeechProblem.SYNTHESIS_FAILED, SpeechProblem.PLAYBACK_FAILED, SpeechProblem.UNEXPECTED -> R.string.voice_not_heard_failed
        }
        PreviewResult.Heard, PreviewResult.Cut -> R.string.voice_not_heard_failed
    },
)

@Composable
private fun PackCard(pack: VoicePackChoice, state: VoiceUiState.Ready, actions: VoiceActions) {
    val chosen = pack.id == state.voicePackId
    val playing = pack.id == state.playing
    Card(Modifier.fillMaxWidth().testTag(VoiceTags.pack(pack.id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = chosen, onClick = { actions.onChoose(pack.id) }, role = Role.RadioButton)
                    .testTag(VoiceTags.choose(pack.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen, onClick = null)
                Text(pack.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            Text(facts(pack), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(VoiceTags.facts(pack.id)))
            val title = stringResource(R.string.dials_preview_mission)
            val firstAction = stringResource(R.string.dials_preview_first_action)
            // Each button is named for its voice, to a screen reader and to a voice that says "Hear Coach Maya".
            val named = stringResource(if (playing) R.string.voice_stop_named else R.string.voice_hear_named, pack.name)
            OutlinedButton(
                onClick = { actions.onHear(pack.id, title, firstAction) },
                modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = named }.testTag(VoiceTags.hear(pack.id)),
            ) { Text(stringResource(if (playing) R.string.voice_stop else R.string.voice_hear)) }
            if (chosen) Trim(pack, state.failedWrites, actions)
        }
    }
}

/** What differs from one pack to another, in numbers, and whose words it goes with. The numbers are what the engine is really asked for, which is not always what a pack says. */
@Composable
private fun facts(pack: VoicePackChoice): String = stringResource(
    R.string.voice_facts,
    personalityWords(pack.personality),
    pack.voice.languageTag.orEmpty(),
    String.format(Locale.getDefault(), "%.2f", VoiceDirectorEngine.clampSystemPitch(pack.voice.pitch)),
    String.format(Locale.getDefault(), "%.2f", VoiceDirectorEngine.clampSystemRate(pack.voice.rate)),
    trimWords(pack.trimDb),
)

@Composable
private fun personalityWords(personality: Personality): String = stringResource(
    when (personality) {
        Personality.EXECUTIVE_DIRECTOR -> R.string.voice_words_executive
        Personality.DRILL_SERGEANT -> R.string.voice_words_drill
        Personality.ACCOUNTABILITY_PARTNER -> R.string.voice_words_partner
        Personality.SUPPORTIVE_COACH -> R.string.voice_words_coach
        Personality.MINIMALIST -> R.string.voice_words_minimalist
        Personality.CUSTOM -> R.string.voice_words_custom
    },
)

@Composable
private fun trimWords(db: Float): String = stringResource(R.string.voice_trim_value, String.format(Locale.getDefault(), "%.1f", db))

/** The chosen voice's loudness trim. While a finger is on it the slider shows the finger's value; it is written when the finger lets go, and then shows the row. */
@Composable
private fun Trim(pack: VoicePackChoice, failedWrites: Int, actions: VoiceActions) {
    var dragged by remember(pack.id) { mutableStateOf<Float?>(null) }
    // The write has come back, or could not be made: the row says what the trim is. An earlier write coming back says nothing about this one.
    LaunchedEffect(pack.trimDb, dragged) { if (dragged == pack.trimDb) dragged = null }
    LaunchedEffect(failedWrites) { if (failedWrites > 0) dragged = null }
    val shown = dragged ?: pack.trimDb
    val name = stringResource(R.string.voice_trim)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(trimWords(shown), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(VoiceTags.TRIM_VALUE))
        Slider(
            value = shown,
            onValueChange = { dragged = snappedTrim(it) },
            onValueChangeFinished = { dragged?.let { actions.onSetTrim(pack.id, it) } },
            valueRange = 0f..BuiltInVoicePacks.MAX_TRIM_DB,
            steps = TRIM_STEPS,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = name }.testTag(VoiceTags.TRIM),
        )
        Text(stringResource(R.string.voice_trim_explained), style = MaterialTheme.typography.bodySmall)
    }
}
