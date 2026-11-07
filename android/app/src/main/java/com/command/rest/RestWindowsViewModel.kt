package com.command.rest

import android.util.Log
import androidx.lifecycle.ViewModel
import com.command.data.RestWindowLine
import com.command.data.RestWindowProblem
import com.command.data.RestWindowRepository
import com.command.data.SaveRestWindowResult
import com.command.domain.model.RestKind
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
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

/** A window while it is being written. [ids] is empty for a new one. While [saving], the fields stay as they were sent, so that what is written is what is shown. */
data class RestWindowDraft(
    val ids: List<Long>,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val kind: RestKind,
    val problems: Set<RestWindowProblem> = emptySet(),
    val saving: Boolean = false,
    /** The last save did not reach the database. */
    val failed: Boolean = false,
)

sealed interface RestWindowsUiState {
    data object Loading : RestWindowsUiState
    data class Ready(val lines: List<RestWindowLine>, val draft: RestWindowDraft?) : RestWindowsUiState
}

/** The person's rest windows, and the one being written. */
@HiltViewModel
class RestWindowsViewModel @Inject constructor(private val windows: RestWindowRepository) : ViewModel() {

    private val draft = MutableStateFlow<RestWindowDraft?>(null)

    /** A seam for tests: runs before a window is written, so that the writes can be counted, held back or made to fail. */
    internal var beforeWrite: suspend () -> Unit = {}

    val state: StateFlow<RestWindowsUiState> = combine<List<RestWindowLine>, RestWindowDraft?, RestWindowsUiState>(windows.observe(), draft) { lines, written ->
        RestWindowsUiState.Ready(lines, written)
    }.stateIn(guardedScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RestWindowsUiState.Loading)

    fun startNew() {
        draft.value = RestWindowDraft(emptyList(), emptySet(), DEFAULT_START, DEFAULT_END, RestKind.SLEEP)
    }

    fun startEdit(line: RestWindowLine) {
        draft.value = RestWindowDraft(line.ids, line.days, line.start, line.end, line.kind)
    }

    fun toggleDay(day: DayOfWeek) = change { it.copy(days = if (day in it.days) it.days - day else it.days + day, problems = it.problems - RestWindowProblem.NO_DAYS) }

    fun setStart(time: LocalTime) = change { it.copy(start = time, problems = it.problems - RestWindowProblem.SAME_TIMES) }

    fun setEnd(time: LocalTime) = change { it.copy(end = time, problems = it.problems - RestWindowProblem.SAME_TIMES) }

    fun setKind(kind: RestKind) = change { it.copy(kind = kind) }

    fun cancel() {
        draft.value = null
    }

    fun save() {
        val sent = draft.value?.takeUnless { it.saving }?.copy(problems = emptySet(), saving = true, failed = false) ?: return
        draft.value = sent
        guardedScope.launch {
            // What was accepted is written even if the person leaves the screen at once. A failure is said on the form, which would otherwise stay on saving for good.
            val result = withContext(NonCancellable) {
                try {
                    beforeWrite()
                    windows.save(sent.ids, sent.days, sent.start, sent.end, sent.kind)
                } catch (e: Exception) {
                    Log.w(TAG, "a rest window was not saved: ${e.javaClass.simpleName}", e)
                    null
                }
            }
            // The person may have left the form, and even started another window, while this was written: an answer is only for the draft it was asked for.
            draft.update { now ->
                when {
                    now !== sent -> now
                    result == null -> now.copy(saving = false, failed = true)
                    result is SaveRestWindowResult.Rejected -> now.copy(problems = result.problems, saving = false)
                    else -> null
                }
            }
        }
    }

    fun delete(ids: List<Long>) {
        guardedScope.launch {
            withContext(NonCancellable) {
                beforeWrite()
                windows.delete(ids)
            }
        }
    }

    private inline fun change(how: (RestWindowDraft) -> RestWindowDraft) = draft.update { now -> if (now == null || now.saving) now else how(now).copy(failed = false) }

    private companion object {
        const val TAG = "RestWindows"
        const val STOP_TIMEOUT_MS = 5_000L
        val DEFAULT_START: LocalTime = LocalTime.of(22, 0)
        val DEFAULT_END: LocalTime = LocalTime.of(7, 0)
    }
}
