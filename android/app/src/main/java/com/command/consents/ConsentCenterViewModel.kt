package com.command.consents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.alarm.ReliabilityMonitor
import com.command.data.ConsentStore
import com.command.domain.model.ConsentBoundaries
import com.command.domain.model.ConsentBoundary
import com.command.domain.model.ConsentSource
import com.command.domain.reliability.Fix
import com.command.domain.reliability.Precondition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One line of a consent's history: it was granted or turned off, when, and by whom. */
data class ConsentChange(val atUtc: Long, val granted: Boolean, val source: ConsentSource)

/**
 * One consent as the center shows it. [granted] is null until something has been recorded; [latest] is the last change;
 * [history] is every change, newest first; [fix] is where the person goes to change it, when Android keeps the setting.
 */
data class ConsentItem(
    val boundary: ConsentBoundary,
    val granted: Boolean?,
    val latest: ConsentChange?,
    val history: List<ConsentChange>,
    val fix: Fix?,
)

sealed interface ConsentCenterUiState {
    data object Loading : ConsentCenterUiState
    data class Ready(val items: List<ConsentItem>) : ConsentCenterUiState
}

@HiltViewModel
class ConsentCenterViewModel @Inject constructor(
    private val monitor: ReliabilityMonitor,
    consents: ConsentStore,
) : ViewModel() {

    val state: StateFlow<ConsentCenterUiState> = consents.observeAll().map { rows ->
        ConsentCenterUiState.Ready(
            ConsentBoundaries.all.map { boundary ->
                val history = rows.filter { it.key == boundary.key }.map { ConsentChange(it.atUtc, it.granted, it.source) }.reversed()
                ConsentItem(
                    boundary = boundary,
                    granted = history.firstOrNull()?.granted,
                    latest = history.firstOrNull(),
                    history = history,
                    fix = Precondition.values().firstOrNull { it.consent == boundary.key }?.fix,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConsentCenterUiState.Loading)

    /** Asks Android again, so a permission changed in its settings is on the record before the person reads it. */
    fun refresh() {
        viewModelScope.launch { monitor.refresh() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
