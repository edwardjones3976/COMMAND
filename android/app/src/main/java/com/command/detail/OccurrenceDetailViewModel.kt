package com.command.detail

import androidx.lifecycle.ViewModel
import com.command.data.OccurrenceDetail
import com.command.data.OccurrenceRepository
import com.command.domain.history.HistoryOccurrence
import com.command.domain.history.HistoryReason
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data object NotFound : DetailUiState

    /** [story] is what happened in the person's words; [technical] is everything as recorded, shown only when asked for. */
    data class Ready(
        val title: String,
        val atUtcMs: Long,
        val zone: ZoneId,
        val outcome: HistoryReason,
        val story: List<StoryItem>,
        val technical: List<TechnicalItem>,
    ) : DetailUiState
}

/**
 * One occurrence and what is recorded about it. It reads the record again whenever the occurrence changes, so a question answered from a
 * notification while the page is open shows up on it.
 */
@HiltViewModel
class OccurrenceDetailViewModel @Inject constructor(
    private val repository: OccurrenceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var shown: String? = null
    private var observing: Job? = null

    fun show(id: String) {
        if (shown == id) return
        observing?.cancel()
        shown = id
        _state.value = DetailUiState.Loading
        observing = guardedScope.launch {
            repository.observeDetail(id).collectLatest { _state.value = project(it) }
        }
    }

    private fun project(detail: OccurrenceDetail?): DetailUiState {
        if (detail == null) return DetailUiState.NotFound
        val o = detail.occurrence
        val zone = try {
            ZoneId.of(o.zoneId)
        } catch (e: DateTimeException) {
            ZoneOffset.UTC
        }
        val outcome = HistoryOccurrence(o.scheduledAtUtc, zone, o.state, o.skipReason, o.missReason).reason
        return DetailUiState.Ready(detail.title, o.scheduledAtUtc, zone, outcome, DetailStory.story(detail), DetailStory.technical(detail))
    }
}
