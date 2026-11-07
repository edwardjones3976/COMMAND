package com.command.profiles

import androidx.lifecycle.ViewModel
import com.command.data.ProfileHandshake
import com.command.data.ProfileRepository
import com.command.data.TemplateOverrideRepository
import com.command.domain.coaching.CoachingContext
import com.command.domain.coaching.CoachingEngine
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.TemplateOverrides
import com.command.domain.dials.Dial
import com.command.domain.dials.Dials
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface DialsUiState {
    data object Loading : DialsUiState
    data object NotFound : DialsUiState

    /** [inUse] is that alarms are fired with this profile; [overrides] are the person's own words, which a ring would say in place of the bank's. */
    data class Ready(val profile: ProfileHandshake, val dials: Dials, val inUse: Boolean, val overrides: TemplateOverrides) : DialsUiState
}

/**
 * The line a ring of this profile would open with, for a mission with these words: the ring's own engine and phase, with the profile's id for
 * the seed, so that the same variant stays on screen and only the dials change the words.
 */
internal fun previewLine(state: DialsUiState.Ready, title: String, firstAction: String): String =
    CoachingEngine.compose(
        state.profile.personality, CoachingPhase.REVEILLE, state.dials, CoachingContext(title, firstAction), attempt = 0, seed = state.profile.id, state.overrides,
    ).text

/**
 * The seven dials of one profile. A dial is written the moment it is set, one column in one statement, and what the screen shows is the row as it
 * is read back: there is no draft to lose, and no write that can undo another writer's change to the rest of the profile.
 */
@HiltViewModel
class DialsViewModel @Inject constructor(private val profiles: ProfileRepository, private val templates: TemplateOverrideRepository) : ViewModel() {

    private val _state = MutableStateFlow<DialsUiState>(DialsUiState.Loading)
    val state: StateFlow<DialsUiState> = _state.asStateFlow()

    private var profileId: String? = null

    fun open(id: String) {
        if (profileId == id) return
        profileId = id
        guardedScope.launch {
            val overrides = readOverrides()
            combine(profiles.observeDials(id), profiles.observeInUse(id)) { profile, used -> profile to used }.collect { (profile, used) ->
                _state.value = if (profile == null) DialsUiState.NotFound else DialsUiState.Ready(profile.handshake, profile.dials, used, overrides)
            }
        }
    }

    fun set(dial: Dial, value: Int) {
        val id = profileId ?: return
        // A tap is written even if the person leaves the screen at once: nothing else on this screen says it was not. This runs at once up to
        // the repository's queue, on the thread of the tap, so that the value tapped last is the one that stays, on this screen or one opened after it.
        guardedScope.launch { withContext(NonCancellable) { profiles.setDial(id, dial, value) } }
    }

    /** A row that cannot be read (from a newer version, say) costs the person's own wording in the preview, as it does on the ring. */
    private suspend fun readOverrides(): TemplateOverrides = try {
        templates.all()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TemplateOverrides()
    }
}
