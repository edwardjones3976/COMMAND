package com.command.consents

import androidx.lifecycle.ViewModel
import com.command.alarm.ReliabilityMonitor
import com.command.data.ConsentStore
import com.command.domain.model.ConsentBoundaries
import com.command.domain.model.ConsentBoundary
import com.command.domain.model.ConsentKey
import com.command.domain.model.ConsentOwner
import com.command.domain.model.ConsentSource
import com.command.domain.reliability.Fix
import com.command.domain.reliability.Precondition
import com.command.ui.guardedScope
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
    private val consents: ConsentStore,
) : ViewModel() {

    val state: StateFlow<ConsentCenterUiState> = consents.observeAll().map { rows ->
        ConsentCenterUiState.Ready(
            ConsentBoundaries.all.map { boundary ->
                val history = rows.filter { it.key == boundary.key }.map { ConsentChange(it.atUtc, it.granted, it.source) }.reversed()
                ConsentItem(
                    boundary = boundary,
                    // A consent the app owns is off until the person turns it on, so there is no "not checked yet" for it.
                    granted = history.firstOrNull()?.granted ?: if (boundary.owner == ConsentOwner.APP) false else null,
                    latest = history.firstOrNull(),
                    history = history,
                    fix = Precondition.values().firstOrNull { it.consent == boundary.key }?.fix,
                )
            },
        )
    }.stateIn(guardedScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConsentCenterUiState.Loading)

    /** The person turned one of the app's own consents on or off. It is recorded as theirs; turning one off also removes what it allowed. */
    fun setGranted(key: ConsentKey, granted: Boolean) {
        // Android keeps the others: what it says is read and recorded as the system's, and nothing is written here that it does not say.
        if (ConsentBoundaries.all.none { it.key == key && it.owner == ConsentOwner.APP }) return
        guardedScope.launch {
            if (granted) consents.grant(key, ConsentSource.USER) else consents.revoke(key, ConsentSource.USER)
        }
    }

    /** Asks Android again, so a permission changed in its settings is on the record before the person reads it. */
    fun refresh() {
        guardedScope.launch { monitor.refresh() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
