package com.command.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.AppSettings
import com.command.domain.voice.RoutePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SettingsUiState {
    data object Loading : SettingsUiState

    /** [routePolicy] is where the spoken line of a ring is heard; the tone and the vibration ring on every route. */
    data class Ready(val routePolicy: RoutePolicy) : SettingsUiState
}

/** The few settings the person can change. Each is written the moment it is chosen, so there is nothing to save. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
) : ViewModel() {

    private val _state = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = SettingsUiState.Ready(settings.routePolicy()) }
    }

    fun chooseRoutePolicy(policy: RoutePolicy) {
        val current = (_state.value as? SettingsUiState.Ready)?.routePolicy
        if (current == policy) return
        // What the person sees is what they chose; if the write fails the next opening of the screen shows what is really stored.
        _state.value = SettingsUiState.Ready(policy)
        viewModelScope.launch { settings.setRoutePolicy(policy) }
    }
}
