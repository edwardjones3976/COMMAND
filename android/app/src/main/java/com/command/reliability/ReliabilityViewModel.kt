package com.command.reliability

import android.os.Build
import androidx.lifecycle.ViewModel
import com.command.alarm.ReliabilityMonitor
import com.command.data.AppSettings
import com.command.data.ReliabilityRepository
import com.command.domain.reliability.AlarmOutcome
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.reliability.Reliability
import com.command.domain.reliability.ReliabilityReport
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ReliabilityUiState {
    data object Loading : ReliabilityUiState

    /**
     * [canAskNotifications] is true while the runtime request can still be shown; once it has been answered Android may not show
     * it again, so the fix for notifications becomes the settings page. [maker] names the phone maker when its autostart page is
     * on this phone. [refusedAlarms] is how many alarms the platform refused in the last day.
     */
    data class Ready(
        val report: ReliabilityReport,
        val recent: List<AlarmOutcome>,
        val canAskNotifications: Boolean,
        val maker: String?,
        val refusedAlarms: Int = 0,
    ) : ReliabilityUiState
}

@HiltViewModel
class ReliabilityViewModel @Inject constructor(
    private val monitor: ReliabilityMonitor,
    private val settings: AppSettings,
    repository: ReliabilityRepository,
    private val oem: OemAutostart,
) : ViewModel() {

    private val snapshot = MutableStateFlow<PreconditionSnapshot?>(null)
    private val asked = MutableStateFlow(false)
    private val maker = oem.find()

    /** Bumped each time the page returns. "The last day" is worked out when the query starts, so a page kept in memory has to ask again. */
    private val returns = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val refused = returns.flatMapLatest { repository.refusedAlarms() }

    val state: StateFlow<ReliabilityUiState> = combine(snapshot, repository.recentAlarms(), asked, refused) { snap, recent, askedBefore, refusedCount ->
        if (snap == null) {
            ReliabilityUiState.Loading
        } else {
            ReliabilityUiState.Ready(
                report = Reliability.assess(snap),
                recent = recent,
                canAskNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedBefore,
                maker = maker?.maker,
                refusedAlarms = refusedCount,
            )
        }
    }.stateIn(guardedScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReliabilityUiState.Loading)

    /** Reads the platform again. Every setting on this page can be changed outside the app, so it runs whenever the page returns. */
    fun refresh() {
        returns.value++
        guardedScope.launch {
            asked.value = settings.notificationsAsked()
            snapshot.value = monitor.refresh()
        }
    }

    /** The runtime notification request was answered. Android may not show it again, so the page now points to settings. */
    fun onNotificationRequestResult() {
        guardedScope.launch {
            settings.markNotificationsAsked()
            refresh()
        }
    }

    /** The phone maker's autostart page, to open when the person asks for it. */
    fun makerLink(): OemAutostart.Link? = maker

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
