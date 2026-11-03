package com.command.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.alarm.ReliabilityMonitor
import com.command.data.MissionRepository
import com.command.data.db.ScheduleRow
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
    ) : ScheduleUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    missions: MissionRepository,
    private val monitor: ReliabilityMonitor,
) : ViewModel() {

    private val problems = MutableStateFlow(0)

    /** The list is read again every minute, so the window of recent history keeps moving while the screen stays open. */
    private val everyMinute = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MS)
        }
    }

    val state: StateFlow<ScheduleUiState> = combine(
        everyMinute.flatMapLatest { missions.observeSchedule() },
        problems,
    ) { schedule, problemCount ->
        ScheduleUiState.Ready(schedule.upcoming, schedule.recent, problemCount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState.Loading)

    /**
     * Android lets the person change any of what alarms depend on outside the app, so the screen asks again when it returns. What
     * it finds is also written down, so a permission taken away in settings is on the record the moment the app is looked at.
     */
    fun onResume() {
        viewModelScope.launch { problems.value = Reliability.assess(monitor.refresh()).problems.size }
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}
