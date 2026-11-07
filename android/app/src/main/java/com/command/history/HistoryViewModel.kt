package com.command.history

import androidx.lifecycle.ViewModel
import com.command.data.MissionRepository
import com.command.data.db.ScheduleRow
import com.command.domain.history.HistoryCalculator
import com.command.domain.history.HistoryOccurrence
import com.command.domain.history.HistoryReason
import com.command.domain.history.WindowSummary
import com.command.domain.time.Clock
import com.command.ui.guardedScope
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
    data class Ready(val week: WindowSummary, val month: WindowSummary, val days: List<HistoryDay>, val today: LocalDate) : HistoryUiState
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
        .stateIn(guardedScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Loading)

    private fun build(rows: List<ScheduleRow>): HistoryUiState.Ready {
        // One reading of today and one rule for what the window holds: the list and the counts are made from the same occurrences, so they
        // cannot disagree. The query only bounds what is fetched, by instants, which is not the same as by days in each one's own zone.
        val today = calculator.currentDay()
        val kept = rows
            .map { it to HistoryOccurrence(it.scheduledAtUtc, zoneOf(it.zoneId), it.state, it.skipReason, it.missReason) }
            .filter { (_, o) -> calculator.isWithin(o, MONTH_DAYS, today) }
        val occurrences = kept.map { it.second }
        val days = kept
            .groupBy({ (_, o) -> calculator.dateOf(o) }, { (row, o) -> HistoryEntry(row.occurrenceId, row.title, row.scheduledAtUtc, o.zone, o.reason) })
            .map { (date, list) -> HistoryDay(date, list.sortedByDescending { it.atUtcMs }) }
            .sortedByDescending { it.date }
        return HistoryUiState.Ready(calculator.summary(occurrences, WEEK_DAYS, today), calculator.summary(occurrences, MONTH_DAYS, today), days, today)
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
