package com.command.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.history.reasonLabelRes
import com.command.ui.whenLabel
import com.example.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object DetailTags {
    const val TITLE = "detail_title"
    const val OUTCOME = "detail_outcome"
    const val STORY = "detail_story"
    const val STORY_EMPTY = "detail_story_empty"
    const val NOT_FOUND = "detail_not_found"
    const val TECHNICAL_TOGGLE = "detail_technical_toggle"
    const val TECHNICAL = "detail_technical"
    fun storyLine(index: Int) = "detail_story_line_$index"
}

@Composable
fun OccurrenceDetailRoute(occurrenceId: String, onBack: () -> Unit, viewModel: OccurrenceDetailViewModel = hiltViewModel()) {
    androidx.compose.runtime.LaunchedEffect(occurrenceId) { viewModel.show(occurrenceId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    OccurrenceDetailScreen(state = state, onBack = onBack)
}

/** Stateless, so it can be shown in tests without a view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OccurrenceDetailScreen(state: DetailUiState, onBack: () -> Unit, today: LocalDate = LocalDate.now()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            DetailUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            DetailUiState.NotFound -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp).testTag(DetailTags.NOT_FOUND),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.detail_not_found_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(R.string.detail_not_found_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            is DetailUiState.Ready -> Ready(state, today, Modifier.padding(padding))
        }
    }
}

@Composable
private fun Ready(state: DetailUiState.Ready, today: LocalDate, modifier: Modifier) {
    var showTechnical by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }.testTag(DetailTags.TITLE))
                Text(whenLabel(state.atUtcMs, state.zone, today), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(reasonLabelRes(state.outcome)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(DetailTags.OUTCOME))
            }
        }
        item {
            Text(
                stringResource(R.string.detail_section_story),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
        }
        if (state.story.isEmpty()) {
            item { Text(stringResource(R.string.detail_story_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(DetailTags.STORY_EMPTY)) }
        }
        itemsIndexed(state.story) { index, line ->
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).testTag(DetailTags.storyLine(index)), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(time(line.atUtcMs, state.zone), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 88.dp))
                Text(storyText(line), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        item {
            TextButton(
                onClick = { showTechnical = !showTechnical },
                modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp).testTag(DetailTags.TECHNICAL_TOGGLE),
            ) {
                Text(stringResource(if (showTechnical) R.string.detail_technical_hide else R.string.detail_technical_show))
            }
        }
        if (showTechnical) {
            item { Text(stringResource(R.string.detail_technical_note), style = MaterialTheme.typography.bodySmall) }
            itemsIndexed(state.technical) { index, item ->
                Column(Modifier.fillMaxWidth().testTag("${DetailTags.TECHNICAL}_$index")) {
                    Text(
                        stringResource(R.string.detail_technical_line, time(item.atUtcMs, state.zone), item.title),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (item.details.isNotEmpty()) {
                        Text(item.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun time(atUtcMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(atUtcMs).atZone(zone))

@Composable
private fun storyText(line: StoryItem): String = when (line.kind) {
    StoryKind.QUESTION_LATE -> {
        val seconds = line.arg?.toIntOrNull() ?: 0
        pluralStringResource(R.plurals.detail_story_question_late, seconds, seconds)
    }
    else -> stringResource(storyRes(line.kind))
}

@StringRes
private fun storyRes(kind: StoryKind): Int = when (kind) {
    StoryKind.SET -> R.string.detail_story_set
    StoryKind.SET_AGAIN -> R.string.detail_story_set_again
    StoryKind.SET_ROUGHLY -> R.string.detail_story_set_roughly
    StoryKind.RANG -> R.string.detail_story_rang
    StoryKind.ANSWERED -> R.string.detail_story_answered
    StoryKind.DIRECTED -> R.string.detail_story_directed
    StoryKind.ASKED -> R.string.detail_story_asked
    StoryKind.QUESTION_LATE -> R.string.detail_story_asked
    StoryKind.STARTED -> R.string.detail_story_started
    StoryKind.STILL_WORKING -> R.string.detail_story_still_working
    StoryKind.DONE -> R.string.detail_story_done
    StoryKind.SKIPPED -> R.string.detail_story_skipped
    StoryKind.DELAYED -> R.string.detail_story_delayed
    StoryKind.MISSED -> R.string.detail_story_missed
    StoryKind.SILENCED -> R.string.detail_story_silenced
    StoryKind.NOT_CONFIRMED -> R.string.detail_story_not_confirmed
    StoryKind.RESTART_OFFERED -> R.string.detail_story_restart_offered
    StoryKind.RESTARTED -> R.string.detail_story_restarted
    StoryKind.MOVED_LATER -> R.string.detail_story_moved_later
    StoryKind.BLOCKED -> R.string.detail_story_blocked
    StoryKind.PHONE_WAS_OFF -> R.string.detail_story_phone_off
    StoryKind.PAUSED -> R.string.detail_story_paused
    StoryKind.NOTIFICATION_SWIPED -> R.string.detail_story_swiped
    StoryKind.VOICE_UNAVAILABLE -> R.string.detail_story_voice_unavailable
    StoryKind.TEST -> R.string.detail_story_test
}
