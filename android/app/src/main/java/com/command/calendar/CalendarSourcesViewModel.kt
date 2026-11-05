package com.command.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.alarm.CalendarSyncScheduler
import com.command.data.CalendarSync
import com.command.data.ConsentStore
import com.command.data.ProfileRepository
import com.command.data.db.CalendarSourceRow
import com.command.domain.dials.Flexibility
import com.command.domain.model.ConsentKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A profile a calendar can take as its default. */
data class ProfileChoice(val id: String, val name: String)

sealed interface CalendarSourcesUiState {
    data object Loading : CalendarSourcesUiState
    data class Ready(
        val consented: Boolean,
        val permitted: Boolean,
        val sources: List<CalendarSourceRow>,
        val profiles: List<ProfileChoice>,
    ) : CalendarSourcesUiState
}

/** Which calendars are imported and what their events take by default. Nothing is imported until the person says so, twice over. */
@HiltViewModel
class CalendarSourcesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: CalendarSync,
    private val consents: ConsentStore,
    profiles: ProfileRepository,
) : ViewModel() {
    private val permitted = MutableStateFlow(sync.hasPermission())

    val state: StateFlow<CalendarSourcesUiState> = combine(
        consents.observe(ConsentKey.CALENDAR_READ), permitted, sync.observeSources(), profiles.observe(),
    ) { consented, permission, sources, known ->
        CalendarSourcesUiState.Ready(consented, permission, sources, known.map { ProfileChoice(it.id, it.name) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarSourcesUiState.Loading)

    /** The person agreed on this screen. Android's own question follows, from the screen. */
    fun consent() {
        viewModelScope.launch { consents.grant(ConsentKey.CALENDAR_READ) }
    }

    /** Android answered, or the screen came back: the permission can change in Android's settings at any time. */
    fun refresh() {
        permitted.value = sync.hasPermission()
        if (permitted.value) viewModelScope.launch { sync.refreshSources() }
    }

    fun setSelected(calendarId: Long, selected: Boolean) {
        viewModelScope.launch {
            sync.setSelected(calendarId, selected)
            CalendarSyncScheduler.ensure(context)
            CalendarSyncScheduler.syncNow(context)
        }
    }

    fun setDefaults(source: CalendarSourceRow, flexibility: Flexibility, profileId: String?) {
        viewModelScope.launch {
            sync.setDefaults(source.calendarId, flexibility, profileId)
            CalendarSyncScheduler.syncNow(context)
        }
    }
}
