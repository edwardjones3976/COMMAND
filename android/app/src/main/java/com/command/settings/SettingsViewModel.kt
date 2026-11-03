package com.command.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.AppSettings
import com.command.domain.voice.RoutePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    /** [routePolicy] is where the spoken line of a ring is heard; the tone and the vibration ring on every route. */
    data class Ready(val routePolicy: RoutePolicy) : SettingsUiState
}

/**
 * The few settings the person can change. Each is written the moment it is chosen, so there is nothing to save, and the screen follows
 * what is stored: a choice that did not get written is never shown as made.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = settings.observeRoutePolicy()
        .map<RoutePolicy, SettingsUiState> { SettingsUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState.Loading)

    /** Writes wait for each other, so that two quick choices land in the order they were made. */
    private val writes = Mutex()

    /** The last choice made here, which is what the stored value is about to become. */
    private var chosen: RoutePolicy? = null

    fun chooseRoutePolicy(policy: RoutePolicy) {
        if ((chosen ?: (state.value as? SettingsUiState.Ready)?.routePolicy) == policy) return
        chosen = policy
        viewModelScope.launch { writes.withLock { settings.setRoutePolicy(policy) } }
    }
}
