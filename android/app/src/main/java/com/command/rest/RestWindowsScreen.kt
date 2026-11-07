package com.command.rest

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.R
import com.command.data.RestWindowLine
import com.command.data.RestWindowProblem
import com.command.domain.model.RestKind
import com.command.ui.SafeQuestion
import com.command.ui.TimeQuestion
import com.command.ui.WEEK_DAYS
import com.command.ui.dayNames
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object RestWindowsTags {
    const val LIST = "rest_list"
    const val EMPTY = "rest_empty"
    const val ADD = "rest_add"
    const val FORM = "rest_form"
    const val START = "rest_start"
    const val END = "rest_end"
    const val NEXT_DAY = "rest_next_day"
    const val SAVE = "rest_save"
    const val PROBLEM_DAYS = "rest_problem_days"
    const val PROBLEM_TIMES = "rest_problem_times"
    const val PROBLEM_FAILED = "rest_problem_failed"
    const val DELETE_DIALOG = "rest_delete_dialog"
    const val DELETE_KEEP = "rest_delete_keep"
    const val DELETE_CONFIRM = "rest_delete_confirm"
    const val TIME_OK = "rest_time_ok"
    fun line(firstId: Long) = "rest_line_$firstId"
    fun edit(firstId: Long) = "rest_edit_$firstId"
    fun delete(firstId: Long) = "rest_delete_$firstId"
    fun day(day: DayOfWeek) = "rest_day_${day.name}"
    fun kind(kind: RestKind) = "rest_kind_${kind.name}"
}

class RestWindowsActions(
    val onBack: () -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (RestWindowLine) -> Unit,
    val onDelete: (List<Long>) -> Unit,
    val onDay: (DayOfWeek) -> Unit,
    val onStart: (LocalTime) -> Unit,
    val onEnd: (LocalTime) -> Unit,
    val onKind: (RestKind) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)

@Composable
fun RestWindowsRoute(onBack: () -> Unit, viewModel: RestWindowsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RestWindowsScreen(
        state,
        RestWindowsActions(
            onBack, viewModel::startNew, viewModel::startEdit, viewModel::delete, viewModel::toggleDay, viewModel::setStart, viewModel::setEnd, viewModel::setKind, viewModel::save, viewModel::cancel,
        ),
    )
}

/** Stateless but for which question is open, so it can be shown in tests without a view model. The form takes the place of the list; Back leaves the form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestWindowsScreen(state: RestWindowsUiState, actions: RestWindowsActions) {
    val draft = (state as? RestWindowsUiState.Ready)?.draft
    BackHandler(enabled = draft != null, onBack = actions.onCancel)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (draft == null) R.string.rest_title else R.string.rest_form_title)) },
                navigationIcon = {
                    IconButton(onClick = if (draft == null) actions.onBack else actions.onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.rest_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state !is RestWindowsUiState.Ready -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            draft != null -> WindowForm(draft, actions, Modifier.padding(padding))
            else -> WindowList(state.lines, actions, Modifier.padding(padding))
        }
    }
}

@Composable
private fun WindowList(lines: List<RestWindowLine>, actions: RestWindowsActions, modifier: Modifier) {
    var deleting by remember { mutableStateOf<List<Long>?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(RestWindowsTags.LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.rest_intro), style = MaterialTheme.typography.bodyMedium) }
        if (lines.isEmpty()) {
            item { Text(stringResource(R.string.rest_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(RestWindowsTags.EMPTY)) }
        }
        items(lines, key = { it.ids.first() }) { line -> WindowCard(line, actions) { deleting = line.ids } }
        item {
            Button(onClick = actions.onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RestWindowsTags.ADD)) {
                Text(stringResource(R.string.rest_add))
            }
        }
    }
    deleting?.let { ids ->
        SafeQuestion(
            tag = RestWindowsTags.DELETE_DIALOG,
            title = stringResource(R.string.rest_delete_title),
            message = stringResource(R.string.rest_delete_message),
            keepLabel = R.string.rest_delete_keep,
            keepTag = RestWindowsTags.DELETE_KEEP,
            onKeep = { deleting = null },
            throwAwayLabel = R.string.rest_delete_confirm,
            throwAwayTag = RestWindowsTags.DELETE_CONFIRM,
            onThrowAway = {
                deleting = null
                actions.onDelete(ids)
            },
        )
    }
}

private fun time(at: LocalTime): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)

/** The hours in words, saying so when they end the next day: "10:00 PM to 6:00 AM the next day". */
@Composable
private fun hours(start: LocalTime, end: LocalTime): String =
    stringResource(if (end.isAfter(start)) R.string.rest_hours else R.string.rest_hours_overnight, time(start), time(end))

@StringRes
private fun kindName(kind: RestKind): Int = when (kind) {
    RestKind.SLEEP -> R.string.rest_kind_sleep
    RestKind.LEISURE -> R.string.rest_kind_leisure
    RestKind.DRIVING -> R.string.rest_kind_driving
}

@Composable
private fun WindowCard(line: RestWindowLine, actions: RestWindowsActions, onDelete: () -> Unit) {
    val days = dayNames(line.days)
    val hours = hours(line.start, line.end)
    val kind = stringResource(kindName(line.kind))
    val words = stringResource(R.string.rest_line, kind, days, hours)
    val editNamed = stringResource(R.string.rest_edit_named, words)
    val deleteNamed = stringResource(R.string.rest_delete_named, words)
    val id = line.ids.first()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.testTag(RestWindowsTags.line(id)).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(kind, style = MaterialTheme.typography.titleMedium)
                Text(days, style = MaterialTheme.typography.bodyLarge)
                Text(hours, style = MaterialTheme.typography.bodyLarge)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actions.onEdit(line) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = editNamed }.testTag(RestWindowsTags.edit(id)),
                ) { Text(stringResource(R.string.rest_edit)) }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = deleteNamed }.testTag(RestWindowsTags.delete(id)),
                ) { Text(stringResource(R.string.rest_delete)) }
            }
        }
    }
}

private enum class Asking { START, END }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowForm(draft: RestWindowDraft, actions: RestWindowsActions, modifier: Modifier) {
    var asking by remember { mutableStateOf<Asking?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(RestWindowsTags.FORM),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Heading(R.string.rest_field_days) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((day, label) in WEEK_DAYS) {
                    FilterChip(
                        selected = day in draft.days,
                        onClick = { actions.onDay(day) },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(RestWindowsTags.day(day)),
                    )
                }
            }
        }
        if (RestWindowProblem.NO_DAYS in draft.problems) item { Problem(R.string.rest_problem_days, RestWindowsTags.PROBLEM_DAYS) }
        item {
            OutlinedButton(onClick = { asking = Asking.START }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RestWindowsTags.START)) {
                Text(stringResource(R.string.rest_field_start, time(draft.start)), style = MaterialTheme.typography.labelLarge)
            }
        }
        item {
            OutlinedButton(onClick = { asking = Asking.END }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RestWindowsTags.END)) {
                Text(stringResource(R.string.rest_field_end, time(draft.end)), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (draft.start != draft.end && !draft.end.isAfter(draft.start)) {
            item { Text(stringResource(R.string.rest_ends_next_day), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(RestWindowsTags.NEXT_DAY)) }
        }
        if (RestWindowProblem.SAME_TIMES in draft.problems) item { Problem(R.string.rest_problem_times, RestWindowsTags.PROBLEM_TIMES) }
        item { Heading(R.string.rest_field_kind) }
        item {
            Column(Modifier.selectableGroup()) {
                for (kind in RestKind.values()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(selected = draft.kind == kind, onClick = { actions.onKind(kind) }, role = Role.RadioButton)
                            .testTag(RestWindowsTags.kind(kind)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = draft.kind == kind, onClick = null)
                        Text(stringResource(kindName(kind)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        if (draft.failed) item { Problem(R.string.rest_problem_failed, RestWindowsTags.PROBLEM_FAILED) }
        item {
            Button(onClick = actions.onSave, enabled = !draft.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RestWindowsTags.SAVE)) {
                Text(stringResource(R.string.rest_save))
            }
        }
    }
    when (asking) {
        Asking.START -> TimeQuestion(draft.start, RestWindowsTags.TIME_OK, onPick = { actions.onStart(it); asking = null }, onDismiss = { asking = null })
        Asking.END -> TimeQuestion(draft.end, RestWindowsTags.TIME_OK, onPick = { actions.onEnd(it); asking = null }, onDismiss = { asking = null })
        null -> Unit
    }
}

@Composable
private fun Heading(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Problem(@StringRes text: Int, tag: String) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))
}
