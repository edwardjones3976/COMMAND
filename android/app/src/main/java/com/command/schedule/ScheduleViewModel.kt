package com.command.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.MissionRepository
import com.command.data.db.ScheduleRow
import com.command.domain.alarm.AlarmScheduler
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
import javax.inject.Inject

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState

    /** [upcoming] runs soonest first; [recent] is what already happened, newest first. */
    data class Ready(
        val upcoming: List<ScheduleRow>,
        val recent: List<ScheduleRow>,
        val exactAlarmsAvailable: Boolean,
    ) : ScheduleUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    missions: MissionRepository,
    private val scheduler: AlarmScheduler,
) : ViewModel() {

    private val exactAvailable = MutableStateFlow(scheduler.canScheduleExact())

    /** The list is read again every minute, so the window of recent history keeps moving while the screen stays open. */
    private val everyMinute = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MS)
        }
    }

    val state: StateFlow<ScheduleUiState> = combine(
        everyMinute.flatMapLatest { missions.observeSchedule() },
        exactAvailable,
    ) { schedule, exact ->
        ScheduleUiState.Ready(schedule.upcoming, schedule.recent, exact)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState.Loading)

    /** Android lets the user change the exact-alarm permission outside the app, so the screen asks again when it returns. */
    fun onResume() {
        exactAvailable.value = scheduler.canScheduleExact()
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}
