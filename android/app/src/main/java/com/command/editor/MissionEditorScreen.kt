package com.command.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.data.DraftProblem
import com.command.domain.dials.Flexibility
import com.command.R
import com.command.ui.TimeQuestion
import com.command.ui.WEEK_DAYS
import com.command.ui.dayNames
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object EditorTags {
    const val IMPORTED = "editor_imported"
    const val CONFLICT = "editor_conflict"
    const val REST_WARNING = "editor_rest_warning"
    const val TITLE = "editor_title"
    const val ACTION = "editor_action"
    const val REPEAT_ONCE = "editor_repeat_once"
    const val REPEAT_DAILY = "editor_repeat_daily"
    const val REPEAT_WEEKLY = "editor_repeat_weekly"
    const val DATE = "editor_date"
    const val TIME = "editor_time"
    const val TIME_OK = "editor_time_ok"
    const val FLEXIBLE = "editor_flexible"
    const val FIXED = "editor_fixed"
    const val CATEGORY = "editor_category"
    const val PROFILE_AUTO = "editor_profile_auto"
    const val SAVE = "editor_save"
    const val TEST = "editor_test"
    const val DELETE = "editor_delete"
    const val DELETE_CONFIRM = "editor_delete_confirm"
    fun day(day: DayOfWeek) = "editor_day_${day.name}"
    fun starter(minutes: Int) = "editor_starter_$minutes"
    fun profile(id: String) = "editor_profile_$id"
}

/** Everything the editor can ask for, so the screen can be tested without a view model. */
class EditorActions(
    val onBack: () -> Unit,
    val onTitle: (String) -> Unit,
    val onFirstAction: (String) -> Unit,
    val onFlexibility: (Flexibility) -> Unit,
    val onRepeat: (RepeatKind) -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onTime: (LocalTime) -> Unit,
    val onDay: (DayOfWeek) -> Unit,
    val onStarterMinutes: (Int) -> Unit,
    val onCategory: (String) -> Unit,
    val onProfile: (String?) -> Unit,
    val onSave: () -> Unit,
    val onTest: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
fun MissionEditorRoute(onDone: () -> Unit, viewModel: MissionEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val testStarted = stringResource(R.string.editor_test_started)

    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    LaunchedEffect(state.message) {
        if (state.message == EditorMessage.TEST_STARTED) {
            snackbar.showSnackbar(testStarted)
            viewModel.messageShown()
        }
    }

    MissionEditorScreen(
        state = state,
        snackbar = snackbar,
        actions = EditorActions(
            onBack = onDone,
            onTitle = viewModel::onTitle,
            onFirstAction = viewModel::onFirstAction,
            onFlexibility = viewModel::onFlexibility,
            onRepeat = viewModel::onRepeat,
            onDate = viewModel::onDate,
            onTime = viewModel::onTime,
            onDay = viewModel::onDay,
            onStarterMinutes = viewModel::onStarterMinutes,
            onCategory = viewModel::onCategory,
            onProfile = viewModel::onProfile,
            onSave = viewModel::save,
            onTest = viewModel::testRing,
            onDelete = viewModel::delete,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MissionEditorScreen(state: EditorState, actions: EditorActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.editing) R.string.editor_title_edit else R.string.editor_title_new)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.notFound -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.editor_not_found), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.editor_back), style = MaterialTheme.typography.labelLarge) }
            }
            state.imported -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.editor_imported_body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(EditorTags.IMPORTED))
                if (state.conflicts > 0) ConflictWarning(state.conflicts)
                RestWarning(state.restWarningDays)
                TextButton(onClick = actions.onDelete, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(EditorTags.DELETE)) {
                    Text(stringResource(R.string.editor_imported_remove), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.conflicts > 0) ConflictWarning(state.conflicts)
                OutlinedTextField(
                    value = state.title,
                    onValueChange = actions.onTitle,
                    modifier = Modifier.fillMaxWidth().testTag(EditorTags.TITLE),
                    label = { Text(stringResource(R.string.editor_field_title)) },
                    isError = DraftProblem.TITLE_EMPTY in state.problems,
                    supportingText = {
                        Text(stringResource(if (DraftProblem.TITLE_EMPTY in state.problems) R.string.editor_error_title else R.string.editor_field_title_hint))
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.firstAction,
                    onValueChange = actions.onFirstAction,
                    modifier = Modifier.fillMaxWidth().testTag(EditorTags.ACTION),
                    label = { Text(stringResource(R.string.editor_field_action)) },
                    isError = DraftProblem.FIRST_ACTION_EMPTY in state.problems,
                    supportingText = {
                        Text(stringResource(if (DraftProblem.FIRST_ACTION_EMPTY in state.problems) R.string.editor_error_action else R.string.editor_field_action_hint))
                    },
                    maxLines = 3,
                )

                SectionTitle(stringResource(R.string.editor_section_when))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RepeatChip(state.repeat, RepeatKind.ONCE, R.string.editor_repeat_once, EditorTags.REPEAT_ONCE, actions.onRepeat)
                    RepeatChip(state.repeat, RepeatKind.DAILY, R.string.editor_repeat_daily, EditorTags.REPEAT_DAILY, actions.onRepeat)
                    RepeatChip(state.repeat, RepeatKind.WEEKLY, R.string.editor_repeat_weekly, EditorTags.REPEAT_WEEKLY, actions.onRepeat)
                }
                if (state.repeat == RepeatKind.WEEKLY) {
                    Text(stringResource(R.string.editor_days), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((day, label) in WEEK_DAYS) {
                            FilterChip(
                                selected = day in state.days,
                                onClick = { actions.onDay(day) },
                                label = { Text(stringResource(label)) },
                                modifier = Modifier.testTag(EditorTags.day(day)),
                            )
                        }
                    }
                    if (DraftProblem.NO_DAYS_SELECTED in state.problems) ErrorText(stringResource(R.string.editor_error_days))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(EditorTags.DATE)) {
                        Text(
                            stringResource(
                                if (state.repeat == RepeatKind.ONCE) R.string.editor_date else R.string.editor_date_start,
                                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(state.date),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    OutlinedButton(onClick = { pickingTime = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(EditorTags.TIME)) {
                        Text(
                            stringResource(R.string.editor_time, DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(state.time)),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (DraftProblem.IN_THE_PAST in state.problems) ErrorText(stringResource(R.string.editor_error_past))
                RestWarning(state.restWarningDays)

                SectionTitle(stringResource(R.string.editor_section_commitment))
                FirmnessOption(state.flexibility == Flexibility.FLEXIBLE, R.string.editor_flexible, R.string.editor_flexible_body, EditorTags.FLEXIBLE) {
                    actions.onFlexibility(Flexibility.FLEXIBLE)
                }
                FirmnessOption(state.flexibility == Flexibility.RIGID, R.string.editor_fixed, R.string.editor_fixed_body, EditorTags.FIXED) {
                    actions.onFlexibility(Flexibility.RIGID)
                }

                SectionTitle(stringResource(R.string.editor_section_starter))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (minutes in STARTER_MINUTES) {
                        FilterChip(
                            selected = state.starterMinutes == minutes,
                            onClick = { actions.onStarterMinutes(minutes) },
                            label = { Text(stringResource(R.string.editor_starter_minutes, minutes)) },
                            modifier = Modifier.testTag(EditorTags.starter(minutes)),
                        )
                    }
                }

                SectionTitle(stringResource(R.string.editor_section_profile))
                OutlinedTextField(
                    value = state.category,
                    onValueChange = actions.onCategory,
                    modifier = Modifier.fillMaxWidth().testTag(EditorTags.CATEGORY),
                    label = { Text(stringResource(R.string.editor_field_category)) },
                    isError = DraftProblem.CATEGORY_RESERVED in state.problems,
                    supportingText = {
                        Text(stringResource(if (DraftProblem.CATEGORY_RESERVED in state.problems) R.string.editor_error_category else R.string.editor_field_category_hint))
                    },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A name no profile has rings as if it were not there, so that is what is shown.
                    FilterChip(
                        selected = state.profiles.none { it.id == state.profileId },
                        onClick = { actions.onProfile(null) },
                        label = { Text(stringResource(R.string.editor_profile_auto)) },
                        modifier = Modifier.testTag(EditorTags.PROFILE_AUTO),
                    )
                    for (p in state.profiles) {
                        FilterChip(
                            selected = p.id == state.profileId,
                            onClick = { actions.onProfile(p.id) },
                            label = { Text(p.name) },
                            modifier = Modifier.testTag(EditorTags.profile(p.id)),
                        )
                    }
                }
                Text(stringResource(R.string.editor_profile_hint), style = MaterialTheme.typography.bodyLarge)

                Button(onClick = actions.onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(EditorTags.SAVE)) {
                    Text(stringResource(R.string.editor_save), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = actions.onTest, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(EditorTags.TEST)) {
                    Text(stringResource(R.string.editor_test), style = MaterialTheme.typography.labelLarge)
                }
                if (state.editing) {
                    TextButton(onClick = { confirmingDelete = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag(EditorTags.DELETE)) {
                        Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (pickingDate) {
        // The date picker speaks in UTC midnights; the editor holds a plain calendar date.
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { actions.onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickingDate = false
                }) { Text(stringResource(R.string.editor_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.editor_cancel)) } },
        ) { DatePicker(state = picker) }
    }
    if (pickingTime) {
        TimeQuestion(
            state.time,
            EditorTags.TIME_OK,
            onPick = {
                actions.onTime(it)
                pickingTime = false
            },
            onDismiss = { pickingTime = false },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            text = { Text(stringResource(R.string.editor_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    actions.onDelete()
                }, modifier = Modifier.testTag(EditorTags.DELETE_CONFIRM)) {
                    Text(stringResource(R.string.editor_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.editor_cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatChip(current: RepeatKind, kind: RepeatKind, label: Int, tag: String, onSelect: (RepeatKind) -> Unit) {
    FilterChip(
        selected = current == kind,
        onClick = { onSelect(kind) },
        label = { Text(stringResource(label)) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun FirmnessOption(selected: Boolean, title: Int, body: Int, tag: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private val STARTER_MINUTES = listOf(2, 5, 10, 15)

/** Says on which days the alarm falls inside a rest window, and that it rings all the same. Nothing is refused because of it. */
@Composable
private fun RestWarning(days: Set<DayOfWeek>) {
    if (days.isEmpty()) return
    Text(
        stringResource(R.string.editor_rest_warning, dayNames(days)),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(EditorTags.REST_WARNING),
    )
}

@Composable
private fun ConflictWarning(count: Int) {
    Text(
        pluralStringResource(R.plurals.editor_conflict, count, count),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(EditorTags.CONFLICT),
    )
}
