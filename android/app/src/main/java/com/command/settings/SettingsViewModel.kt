package com.command.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.AppSettings
import com.command.data.MissionRepository
import com.command.data.Pause
import com.command.domain.time.Clock
import com.command.domain.voice.RoutePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    /**
     * [routePolicy] is where the spoken line of a ring is heard; the tone and the vibration ring on every route. [pausedUntilUtcMs] is
     * when the pause in force ends, or null when COMMAND is not paused.
     */
    data class Ready(val routePolicy: RoutePolicy, val pausedUntilUtcMs: Long? = null) : SettingsUiState
}

/**
 * The few settings the person can change, and the global pause. Each is written the moment it is chosen, so there is nothing to save, and
 * the screen follows what is stored: a choice that did not get written is never shown as made.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val missions: MissionRepository,
    private val clock: Clock,
) : ViewModel() {

    /** A pause is over when its end passes, before anything has had the time to close it, so the clock is looked at now and then. */
    private val everyHalfMinute = flow {
        while (true) {
            emit(Unit)
            delay(CLOCK_CHECK_MS)
        }
    }

    val state: StateFlow<SettingsUiState> = combine<RoutePolicy, Pause?, Unit, SettingsUiState>(
        settings.observeRoutePolicy(), missions.observePause(), everyHalfMinute,
    ) { route, pause, _ ->
        SettingsUiState.Ready(route, pause?.untilUtcMs?.takeIf { it > clock.nowMs() })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState.Loading)

    /** Writes wait for each other, so that two quick choices land in the order they were made. */
    private val writes = Mutex()

    /** The last choice made here, which is what the stored value is about to become. */
    private var chosen: RoutePolicy? = null

    fun chooseRoutePolicy(policy: RoutePolicy) {
        if ((chosen ?: (state.value as? SettingsUiState.Ready)?.routePolicy) == policy) return
        chosen = policy
        viewModelScope.launch {
            writes.withLock {
                try {
                    settings.setRoutePolicy(policy)
                } catch (e: Throwable) {
                    // It did not get written, so it is not what is stored, and the same choice must be possible again.
                    if (chosen == policy) chosen = null
                    throw e
                }
            }
        }
    }

    /** Pauses COMMAND for [hours] hours: nothing that comes due before then rings. */
    fun pauseFor(hours: Int) {
        viewModelScope.launch { missions.pause(hours * HOUR_MS) }
    }

    /** Ends the pause now. */
    fun endPause() {
        viewModelScope.launch { missions.resume() }
    }

    private companion object {
        const val CLOCK_CHECK_MS = 30_000L
        const val HOUR_MS = 3_600_000L
    }
}
