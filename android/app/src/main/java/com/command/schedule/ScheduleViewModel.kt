package com.command.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.alarm.ReliabilityMonitor
import com.command.data.MissionRepository
import com.command.data.db.ScheduleRow
import com.command.domain.time.Clock
import com.command.domain.reliability.Reliability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState

    /**
     * [upcoming] runs soonest first; [recent] is what already happened, newest first. [problems] is how many things can stop an
     * alarm from ringing right now (none when all is well); the reliability page names each one.
     */
    data class Ready(
        val upcoming: List<ScheduleRow>,
        val recent: List<ScheduleRow>,
        val problems: Int,
        /** When the pause in force ends, or null when COMMAND is not paused. */
        val pausedUntilUtcMs: Long? = null,
        /** The clock's day, which the labels ("Today", "Tomorrow") are counted from; part of the state so that they follow it across midnight. */
        val today: LocalDate = LocalDate.now(),
    ) : ScheduleUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val missions: MissionRepository,
    private val monitor: ReliabilityMonitor,
    private val clock: Clock,
) : ViewModel() {

    private val problems = MutableStateFlow(0)

    /** The list is read again every minute, so the window of recent history keeps moving while the screen stays open. */
    private val everyMinute = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MS)
        }
    }

    // The list is read again every minute, which is also when a pause that has run out stops being shown.
    val state: StateFlow<ScheduleUiState> = combine(
        everyMinute.flatMapLatest { missions.observeSchedule() },
        problems,
        missions.observePause(),
    ) { schedule, problemCount, pause ->
        ScheduleUiState.Ready(
            schedule.upcoming, schedule.recent, problemCount, pause?.untilUtcMs?.takeIf { it > clock.nowMs() },
            Instant.ofEpochMilli(clock.nowMs()).atZone(clock.zone()).toLocalDate(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState.Loading)

    /**
     * Android lets the person change any of what alarms depend on outside the app, so the screen asks again when it returns. What
     * it finds is also written down, so a permission taken away in settings is on the record the moment the app is looked at.
     */
    fun onResume() {
        viewModelScope.launch { problems.value = Reliability.assess(monitor.refresh()).problems.size }
    }

    /** Ends the pause now. */
    fun endPause() {
        viewModelScope.launch { missions.resume() }
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}
