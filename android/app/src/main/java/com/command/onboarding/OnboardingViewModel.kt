package com.command.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import com.command.alarm.ReliabilityMonitor
import com.command.data.AppSettings
import com.command.data.MissionRepository
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.reliability.Reliability
import com.command.domain.reliability.ReliabilityItem
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PermissionsUiState {
    data object Loading : PermissionsUiState

    /** [items] are the permissions that exist on this version of Android, notifications first. */
    data class Ready(val items: List<ReliabilityItem>, val canAskNotifications: Boolean) : PermissionsUiState {
        val allAllowed: Boolean get() = items.all { it.fix == null }
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val monitor: ReliabilityMonitor,
    private val settings: AppSettings,
    missions: MissionRepository,
) : ViewModel() {

    private val snapshot = MutableStateFlow<PreconditionSnapshot?>(null)
    private val asked = MutableStateFlow(false)

    val permissions: StateFlow<PermissionsUiState> = combine(snapshot, asked) { snap, askedBefore ->
        if (snap == null) {
            PermissionsUiState.Loading
        } else {
            val report = Reliability.assess(snap)
            val items = SHOWN.filter { it != Precondition.FULL_SCREEN_INTENT || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE }
                .mapNotNull { report.of(it) }
            PermissionsUiState.Ready(items, canAskNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedBefore)
        }
    }.stateIn(guardedScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PermissionsUiState.Loading)

    private val _hasMissions = MutableStateFlow<Boolean?>(null)

    /** Whether there already are missions (for instance brought across from the previous version); null until known. */
    val hasMissions: StateFlow<Boolean?> = _hasMissions

    init {
        guardedScope.launch { _hasMissions.value = missions.hasMissions() }
    }

    /** Reads the phone's permissions again; they can change in Android's own screens while this one is behind them. */
    fun refresh() {
        guardedScope.launch {
            asked.value = settings.notificationsAsked()
            snapshot.value = monitor.refresh()
        }
    }

    /** The runtime notification request was answered, granted or not. */
    fun onNotificationRequestResult() {
        guardedScope.launch {
            settings.markNotificationsAsked()
            refresh()
        }
    }

    /** The person has seen what there is to see: remember it, so these screens are not shown again, then carry on. */
    fun finish(then: () -> Unit) {
        guardedScope.launch {
            settings.markOnboardingDone()
            then()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val SHOWN = listOf(Precondition.NOTIFICATIONS, Precondition.EXACT_ALARMS, Precondition.FULL_SCREEN_INTENT)
    }
}
