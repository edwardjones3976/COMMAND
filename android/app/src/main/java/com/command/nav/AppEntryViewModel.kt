package com.command.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.command.data.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides where the app opens, once: the first-run screens until the person has been through them, the schedule from then on.
 * It reads the setting a single time on purpose. The first-run screens set it themselves as they finish, and a screen that
 * changed its own starting point would pull the rug from under the navigation it is in the middle of.
 */
@HiltViewModel
class AppEntryViewModel @Inject constructor(settings: AppSettings) : ViewModel() {

    private val _start = MutableStateFlow<String?>(null)

    /** The route to open, or null while the setting is still being read. */
    val start: StateFlow<String?> = _start

    init {
        viewModelScope.launch { _start.value = if (settings.onboardingDone.first()) Routes.SCHEDULE else Routes.WELCOME }
    }
}
