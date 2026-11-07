package com.command.profiles

import androidx.lifecycle.ViewModel
import com.command.alarm.PreviewResult
import com.command.alarm.VoicePreview
import com.command.data.DirectorResult
import com.command.data.ProfileHandshake
import com.command.data.ProfileRepository
import com.command.data.ProfileVoice
import com.command.data.TemplateOverrideRepository
import com.command.data.VoicePackChoice
import com.command.domain.coaching.CoachingContext
import com.command.domain.coaching.CoachingEngine
import com.command.domain.coaching.CoachingPhase
import com.command.domain.coaching.TemplateOverrides
import com.command.domain.dials.Dials
import com.command.domain.voice.SpeechLevel
import com.command.domain.voice.SpeechProblem
import com.command.ui.guardedScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface VoiceUiState {
    data object Loading : VoiceUiState
    data object NotFound : VoiceUiState

    /**
     * [inUse] is that alarms are fired with this profile; [playing] the pack being previewed; [notHeard] why the last preview could not be played;
     * [failedWrites] counts the choices and trims that could not be saved since the last that could.
     */
    data class Ready(
        val profile: ProfileHandshake,
        val dials: Dials,
        val voicePackId: String?,
        val packs: List<VoicePackChoice>,
        val inUse: Boolean,
        val overrides: TemplateOverrides,
        val playing: String? = null,
        val notHeard: PreviewResult? = null,
        val failedWrites: Int = 0,
    ) : VoiceUiState
}

/** The line a ring of this profile would open with if this pack spoke for it: the ring's own engine and phase, in the pack's personality. */
internal fun voicePreviewLine(state: VoiceUiState.Ready, pack: VoicePackChoice, title: String, firstAction: String) =
    CoachingEngine.compose(pack.personality, CoachingPhase.REVEILLE, state.dials, CoachingContext(title, firstAction), attempt = 0, seed = state.profile.id, state.overrides)

/**
 * Who speaks for one profile, how loud each voice is, and hearing them. A choice and a trim are written the moment they are made, and what the screen
 * shows is the rows as they are read back, as on the dials screen.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val templates: TemplateOverrideRepository,
    private val preview: VoicePreview,
) : ViewModel() {

    private class Preview(val playing: String? = null, val notHeard: PreviewResult? = null)

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Loading)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val previewing = MutableStateFlow(Preview())
    private val failedWrites = MutableStateFlow(0)

    private var profileId: String? = null
    private var previewJob: Job? = null

    /** Counts the previews asked for and ended, on the main thread, so that each can tell whether what it has to report is still wanted. */
    private var previewTurn = 0

    fun open(id: String) {
        if (profileId == id) return
        profileId = id
        guardedScope.launch {
            val overrides = readOverrides()
            combine(profiles.observeVoice(id), profiles.observeInUse(id), previewing, failedWrites) { voice, used, heard, failed ->
                stateOf(voice, used, overrides, heard, failed)
            }.collect { _state.value = it }
        }
    }

    private fun stateOf(voice: ProfileVoice?, used: Boolean, overrides: TemplateOverrides, heard: Preview, failed: Int): VoiceUiState {
        if (voice == null) return VoiceUiState.NotFound
        return VoiceUiState.Ready(voice.dials.handshake, voice.dials.dials, voice.voicePackId, voice.packs, used, overrides, heard.playing, heard.notHeard, failed)
    }

    /** Makes the pack the one that speaks for this profile: its personality and the pack, together (amendment 1). */
    fun choose(packId: String) {
        val id = profileId ?: return
        write { profiles.chooseDirector(packId, id) is DirectorResult.Chosen }
    }

    /** Sets the pack's trim, for every profile that speaks with it. */
    fun setTrim(packId: String, trimDb: Float) {
        write { profiles.setVoiceTrim(packId, trimDb) }
    }

    /**
     * A choice is written even if the person leaves the screen at once, and runs at once up to the repository's queue, on the thread of the tap, so that
     * the last one made is the one that stays. One that cannot be saved is said on screen: nothing else would tell, since the row stays as it was.
     */
    private fun write(work: suspend () -> Boolean) {
        guardedScope.launch {
            withContext(NonCancellable) {
                val written = try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                failedWrites.value = if (written) 0 else failedWrites.value + 1
            }
        }
    }

    /** Plays the pack as a ring would, or stops it if that is what is playing. Another that is playing stops first. */
    fun hear(packId: String, title: String, firstAction: String) {
        val current = _state.value as? VoiceUiState.Ready ?: return
        if (previewing.value.playing == packId) {
            stopPreview()
            return
        }
        val pack = current.packs.firstOrNull { it.id == packId } ?: return
        endPlaying()
        previewing.value = Preview(playing = packId)
        // The level is the ring's: the profile's alert intensity and the pack's trim as they are stored now. Nothing suspends before the speaker is asked.
        val line = voicePreviewLine(current, pack, title, firstAction)
        val level = SpeechLevel(current.dials.alertIntensity, pack.trimDb)
        val mine = ++previewTurn
        previewJob = guardedScope.launch {
            val result = try {
                preview.say(line, level, pack.voice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PreviewResult.Unavailable(SpeechProblem.UNEXPECTED)
            }
            // A preview that was replaced or stopped has nothing more to say about what is on screen.
            if (mine == previewTurn) previewing.value = Preview(notHeard = result.takeIf { it is PreviewResult.RingSounding || it is PreviewResult.Unavailable })
        }
    }

    /** Ends the preview, if one is playing. A ring's line is out of its reach. */
    fun stopPreview() {
        if (previewing.value.playing == null) return
        endPlaying()
        previewing.value = Preview()
    }

    private fun endPlaying() {
        previewTurn++
        previewJob?.cancel()
        previewJob = null
        preview.stop()
    }

    override fun onCleared() {
        // The scope has been cancelled by now, which ends the sound; the engine is only held while the screen is there.
        preview.stop()
        preview.release()
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
