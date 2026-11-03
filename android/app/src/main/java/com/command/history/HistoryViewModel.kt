package com.command.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.MissionRepository
import com.command.data.db.ScheduleRow
import com.command.domain.history.HistoryCalculator
import com.command.domain.history.HistoryOccurrence
import com.command.domain.history.HistoryReason
import com.command.domain.history.WindowSummary
import com.command.domain.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/** One occurrence as History lists it. */
data class HistoryEntry(val occurrenceId: String, val title: String, val atUtcMs: Long, val zone: ZoneId, val reason: HistoryReason)

/** What came due on one of the person's own days, the latest first. */
data class HistoryDay(val date: LocalDate, val entries: List<HistoryEntry>)

sealed interface HistoryUiState {
    data object Loading : HistoryUiState

    /** [days] holds only the days on which something came due, newest first. The two summaries cover the last seven and thirty days. */
    data class Ready(val week: WindowSummary, val month: WindowSummary, val days: List<HistoryDay>) : HistoryUiState
}

/**
 * What happened with each alarm, derived from the occurrences themselves each time it is shown: nothing is counted up anywhere, there is
 * no streak, and only the person's own choices count against a day (see [HistoryCalculator]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    missions: MissionRepository,
    clock: Clock,
) : ViewModel() {

    private val calculator = HistoryCalculator(clock)

    /** The window is read again every minute, so that it keeps moving with the clock while the screen stays open. */
    private val everyMinute = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MS)
        }
    }

    val state: StateFlow<HistoryUiState> = everyMinute
        .flatMapLatest { missions.observeHistory(MONTH_DAYS) }
        .map { rows -> build(rows) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Loading)

    private fun build(rows: List<ScheduleRow>): HistoryUiState.Ready {
        val occurrences = rows.map { HistoryOccurrence(it.scheduledAtUtc, zoneOf(it.zoneId), it.state, it.skipReason, it.missReason) }
        val entries = rows.zip(occurrences) { row, o -> HistoryEntry(row.occurrenceId, row.title, row.scheduledAtUtc, o.zone, o.reason) }
        val days = entries
            .groupBy { Instant.ofEpochMilli(it.atUtcMs).atZone(it.zone).toLocalDate() }
            .map { (date, list) -> HistoryDay(date, list.sortedByDescending { it.atUtcMs }) }
            .sortedByDescending { it.date }
        return HistoryUiState.Ready(calculator.summary(occurrences, WEEK_DAYS), calculator.summary(occurrences, MONTH_DAYS), days)
    }

    private fun zoneOf(id: String): ZoneId = try {
        ZoneId.of(id)
    } catch (e: DateTimeException) {
        ZoneOffset.UTC
    }

    companion object {
        const val WEEK_DAYS = 7
        const val MONTH_DAYS = 30
        private const val REFRESH_MS = 60_000L
    }
}
