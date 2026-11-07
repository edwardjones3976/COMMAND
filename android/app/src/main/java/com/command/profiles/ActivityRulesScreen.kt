package com.command.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.command.data.ActivityRule
import com.command.data.ProfileHandshake
import com.command.data.RuleProblem
import com.command.R
import com.command.ui.SafeQuestion

object RulesTags {
    const val LIST = "rules_list"
    const val EMPTY = "rules_empty"
    const val ADD = "rules_add"
    const val FORM = "rules_form"
    const val CATEGORY = "rules_category"
    const val WORDS = "rules_words"
    const val SAVE = "rules_save"
    const val PROBLEM_NOTHING = "rules_problem_nothing"
    const val PROBLEM_PROFILE = "rules_problem_profile"
    const val PROBLEM_SAME = "rules_problem_same"
    const val DELETE_DIALOG = "rules_delete_dialog"
    const val DELETE_KEEP = "rules_delete_keep"
    const val DELETE_CONFIRM = "rules_delete_confirm"
    fun rule(id: String) = "rules_rule_$id"
    fun gone(id: String) = "rules_gone_$id"
    fun edit(id: String) = "rules_edit_$id"
    fun delete(id: String) = "rules_delete_$id"
    fun profile(id: String) = "rules_profile_$id"
}

class RulesActions(
    val onBack: () -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (ActivityRule) -> Unit,
    val onDelete: (String) -> Unit,
    val onCategory: (String) -> Unit,
    val onWords: (String) -> Unit,
    val onProfile: (String) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)

@Composable
fun ActivityRulesRoute(onBack: () -> Unit, viewModel: ActivityRulesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ActivityRulesScreen(
        state,
        RulesActions(
            onBack, viewModel::startNew, viewModel::startEdit, viewModel::delete, viewModel::setCategory, viewModel::setWords, viewModel::setProfile, viewModel::save, viewModel::cancel,
        ),
    )
}

/** Stateless but for which question is open, so it can be shown in tests without a view model. The form takes the place of the list; Back leaves the form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityRulesScreen(state: ActivityRulesUiState, actions: RulesActions) {
    val draft = (state as? ActivityRulesUiState.Ready)?.draft
    BackHandler(enabled = draft != null, onBack = actions.onCancel)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (draft == null) R.string.rules_title else R.string.rules_form_title)) },
                navigationIcon = {
                    IconButton(onClick = if (draft == null) actions.onBack else actions.onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.profiles_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state !is ActivityRulesUiState.Ready -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            draft != null -> RuleForm(draft, state.profiles, actions, Modifier.padding(padding))
            else -> RuleList(state.rules, actions, Modifier.padding(padding))
        }
    }
}

@Composable
private fun RuleList(rules: List<RuleLine>, actions: RulesActions, modifier: Modifier) {
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(RulesTags.LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.rules_intro), style = MaterialTheme.typography.bodyMedium) }
        if (rules.isEmpty()) {
            item { Text(stringResource(R.string.rules_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(RulesTags.EMPTY)) }
        }
        items(rules, key = { it.rule.id }) { line -> RuleCard(line, actions) { deleting = line.rule.id } }
        item {
            Button(onClick = actions.onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RulesTags.ADD)) {
                Text(stringResource(R.string.rules_add))
            }
        }
    }
    deleting?.let { id ->
        SafeQuestion(
            tag = RulesTags.DELETE_DIALOG,
            title = stringResource(R.string.rules_delete_title),
            message = stringResource(R.string.rules_delete_message),
            keepLabel = R.string.rules_delete_keep,
            keepTag = RulesTags.DELETE_KEEP,
            onKeep = { deleting = null },
            throwAwayLabel = R.string.rules_delete_confirm,
            throwAwayTag = RulesTags.DELETE_CONFIRM,
            onThrowAway = {
                deleting = null
                actions.onDelete(id)
            },
        )
    }
}

/** What a rule asks for, in words. */
@Composable
private fun ruleWords(rule: ActivityRule): String = when {
    rule.category.isNotEmpty() && rule.words.isNotEmpty() -> stringResource(R.string.rules_asks_both, rule.category, rule.words)
    rule.words.isNotEmpty() -> stringResource(R.string.rules_asks_words, rule.words)
    rule.category.isNotEmpty() -> stringResource(R.string.rules_asks_category, rule.category)
    else -> stringResource(R.string.rules_asks_nothing)
}

@Composable
private fun RuleCard(line: RuleLine, actions: RulesActions, onDelete: () -> Unit) {
    val words = ruleWords(line.rule)
    val editNamed = stringResource(R.string.rules_edit_named, words)
    val deleteNamed = stringResource(R.string.rules_delete_named, words)
    Card(Modifier.fillMaxWidth().testTag(RulesTags.rule(line.rule.id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(words, style = MaterialTheme.typography.bodyLarge)
            if (line.profileName != null) {
                Text(stringResource(R.string.rules_rings_with, line.profileName), style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    stringResource(R.string.rules_profile_gone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(RulesTags.gone(line.rule.id)),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actions.onEdit(line.rule) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = editNamed }.testTag(RulesTags.edit(line.rule.id)),
                ) { Text(stringResource(R.string.rules_edit)) }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = deleteNamed }.testTag(RulesTags.delete(line.rule.id)),
                ) { Text(stringResource(R.string.rules_delete)) }
            }
        }
    }
}

@Composable
private fun RuleForm(draft: RuleDraft, profiles: List<ProfileHandshake>, actions: RulesActions, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding().testTag(RulesTags.FORM),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = draft.category,
                onValueChange = actions.onCategory,
                label = { Text(stringResource(R.string.rules_field_category)) },
                supportingText = { Text(stringResource(R.string.rules_field_category_hint)) },
                singleLine = true,
                isError = RuleProblem.NOTHING_ASKED in draft.problems || RuleProblem.SAME_AS_ANOTHER in draft.problems,
                modifier = Modifier.fillMaxWidth().testTag(RulesTags.CATEGORY),
            )
        }
        item {
            OutlinedTextField(
                value = draft.words,
                onValueChange = actions.onWords,
                label = { Text(stringResource(R.string.rules_field_words)) },
                supportingText = { Text(stringResource(R.string.rules_field_words_hint)) },
                singleLine = true,
                isError = RuleProblem.NOTHING_ASKED in draft.problems || RuleProblem.SAME_AS_ANOTHER in draft.problems,
                modifier = Modifier.fillMaxWidth().testTag(RulesTags.WORDS),
            )
        }
        if (RuleProblem.NOTHING_ASKED in draft.problems) item { Problem(R.string.rules_problem_nothing, RulesTags.PROBLEM_NOTHING) }
        if (RuleProblem.SAME_AS_ANOTHER in draft.problems) item { Problem(R.string.rules_problem_same, RulesTags.PROBLEM_SAME) }
        item { Text(stringResource(R.string.rules_field_profile), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
        item {
            Column(Modifier.selectableGroup()) {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(selected = draft.profileId == profile.id, onClick = { actions.onProfile(profile.id) }, role = Role.RadioButton)
                            .testTag(RulesTags.profile(profile.id)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = draft.profileId == profile.id, onClick = null)
                        Text(profile.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        if (RuleProblem.NO_PROFILE in draft.problems) item { Problem(R.string.rules_problem_profile, RulesTags.PROBLEM_PROFILE) }
        item {
            Button(onClick = actions.onSave, enabled = !draft.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RulesTags.SAVE)) {
                Text(stringResource(R.string.rules_save))
            }
        }
    }
}

@Composable
private fun Problem(text: Int, tag: String) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))
}
