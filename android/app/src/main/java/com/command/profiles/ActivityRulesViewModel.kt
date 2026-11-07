package com.command.profiles

import androidx.lifecycle.ViewModel
import com.command.data.ActivityRule
import com.command.data.ActivityRuleRepository
import com.command.data.ProfileHandshake
import com.command.data.ProfileRepository
import com.command.data.RuleProblem
import com.command.data.SaveRuleResult
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A rule while it is being written. [id] is null for a new one. While [saving], the fields stay as they were sent, so that what is written is what is shown. */
data class RuleDraft(
    val id: String?,
    val category: String,
    val words: String,
    val profileId: String?,
    val problems: Set<RuleProblem> = emptySet(),
    val saving: Boolean = false,
)

/** A rule to list, with the name of its profile; a null name is a rule whose profile is gone, which the resolver does not apply. */
data class RuleLine(val rule: ActivityRule, val profileName: String?)

sealed interface ActivityRulesUiState {
    data object Loading : ActivityRulesUiState
    data class Ready(val rules: List<RuleLine>, val profiles: List<ProfileHandshake>, val draft: RuleDraft?) : ActivityRulesUiState
}

/** The rules that give a mission a profile by what it is, and the one being written. */
@HiltViewModel
class ActivityRulesViewModel @Inject constructor(private val rules: ActivityRuleRepository, profiles: ProfileRepository) : ViewModel() {

    private val draft = MutableStateFlow<RuleDraft?>(null)

    /** A seam for tests: runs before a rule is written, so that the writes can be counted or held back. */
    internal var beforeWrite: suspend () -> Unit = {}

    val state: StateFlow<ActivityRulesUiState> = combine<List<ActivityRule>, List<ProfileHandshake>, RuleDraft?, ActivityRulesUiState>(
        rules.observe(),
        profiles.observe(),
        draft,
    ) { all, profileRows, written ->
        val names = profileRows.associate { it.id to it.name }
        ActivityRulesUiState.Ready(all.map { RuleLine(it, names[it.profileId]) }, profileRows, written)
    }.stateIn(guardedScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ActivityRulesUiState.Loading)

    fun startNew() {
        draft.value = RuleDraft(id = null, category = "", words = "", profileId = null)
    }

    fun startEdit(rule: ActivityRule) {
        draft.value = RuleDraft(rule.id, rule.category, rule.words, rule.profileId)
    }

    fun setCategory(text: String) = change { it.copy(category = text, problems = it.problems - setOf(RuleProblem.NOTHING_ASKED, RuleProblem.SAME_AS_ANOTHER)) }

    fun setWords(text: String) = change { it.copy(words = text, problems = it.problems - setOf(RuleProblem.NOTHING_ASKED, RuleProblem.SAME_AS_ANOTHER)) }

    fun setProfile(id: String) = change { it.copy(profileId = id, problems = it.problems - RuleProblem.NO_PROFILE) }

    fun cancel() {
        draft.value = null
    }

    fun save() {
        val sent = draft.value?.takeUnless { it.saving }?.copy(problems = emptySet(), saving = true) ?: return
        draft.value = sent
        guardedScope.launch {
            // What was accepted is written even if the person leaves the screen at once.
            val result = withContext(NonCancellable) {
                beforeWrite()
                rules.save(sent.id, sent.category, sent.words, sent.profileId)
            }
            // The person may have left the form, and even started another rule, while this was written: an answer is only for the draft it was asked for.
            draft.update { now ->
                when {
                    now !== sent -> now
                    result is SaveRuleResult.Rejected -> now.copy(problems = result.problems, saving = false)
                    else -> null
                }
            }
        }
    }

    fun delete(id: String) {
        guardedScope.launch {
            withContext(NonCancellable) {
                beforeWrite()
                rules.delete(id)
            }
        }
    }

    private inline fun change(how: (RuleDraft) -> RuleDraft) = draft.update { now -> if (now == null || now.saving) now else how(now) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
