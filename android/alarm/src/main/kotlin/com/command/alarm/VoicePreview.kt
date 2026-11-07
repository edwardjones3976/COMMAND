package com.command.alarm

import android.util.Log
import com.command.data.AppSettings
import com.command.domain.voice.Line
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.Speaker
import com.command.domain.voice.SpeechLevel
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechUsage
import com.command.domain.voice.SpeechVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The speaker of the voice screen's preview: built as the ring's is, but its own, so that nothing done to it can reach a ring's line. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PreviewSpeaker

/** What became of a preview. */
sealed interface PreviewResult {
    /** All of it was played. */
    data object Heard : PreviewResult

    /** The person stopped it or asked for another. */
    data object Cut : PreviewResult

    /** A ring is sounding or about to, and the alarm comes first: nothing was played, or it was cut off. */
    data object RingSounding : PreviewResult

    /** The phone could not say it, and a ring's line would meet the same [reason]. */
    data class Unavailable(val reason: SpeechProblem) : PreviewResult
}

/**
 * Plays a pack's line the way a ring would: the same kind of speaker, the alarm usage (the alarm stream and the ring's loudness target), the route
 * the person set for the ring's speech, and the level and the voice the caller reads from the same rows a ring is fired from. What it does not do
 * is what is not the line: no tone under it, and the alarm volume is left where the person has it, however a ring may raise it.
 *
 * The ring always wins. A preview is refused while the service is ringing or checking a delivery, and a ring that starts cuts it off at once and
 * lets its engine go ([RingSession.ringStarting]), so that the ring's own engine has the phone's speech service to itself. The cut runs on the main
 * thread, and [say] is called on it (the view model's scope is), with nothing suspending between the check and the speaker counting the line as its own,
 * or between the speaker's last look and its track being made. So a delivery cannot slip between either pair: if it comes later, it finds a line to stop,
 * just after its tone has started. Being a separate instance, stopping or letting go of this speaker cannot cut off or delay the line of a ring.
 *
 * Known limit: a fallback ring, which sounds from a notification with no service, cuts off a preview as it starts but does not count as busy, so a
 * preview asked for while one sounds plays over it. Saying when that ring ends would mean following the notification, for a case this rare.
 */
@Singleton
class VoicePreview @Inject constructor(
    @PreviewSpeaker private val speaker: Speaker,
    private val session: RingSession,
    private val settings: AppSettings,
) {
    init {
        session.onRingStarting = {
            speaker.stop()
            // The engine's shutdown is a call into another process, and this is the thread that answers Safety silence: the sound stops now, and the engine goes on its own time.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { speaker.release() }.onFailure { Log.w(TAG, "the preview's engine could not be let go", it) }
            }
        }
    }

    suspend fun say(line: Line, level: SpeechLevel, voice: SpeechVoice): PreviewResult {
        val route = settings.routePolicy()
        if (session.isBusy) return PreviewResult.RingSounding
        return when (val result = speaker.speak(line, SpeechUsage.ALARM, route, level, voice)) {
            is SpeakResult.Spoken -> PreviewResult.Heard
            SpeakResult.Interrupted -> if (session.isBusy) PreviewResult.RingSounding else PreviewResult.Cut
            is SpeakResult.Unavailable -> if (session.isBusy) PreviewResult.RingSounding else PreviewResult.Unavailable(result.reason)
        }
    }

    /** Ends the sound of the preview; a ring's line is not this speaker's to touch. */
    fun stop() = speaker.stop()

    /** Lets go of the engine, when the screen is gone. The next preview starts it again. */
    fun release() = speaker.release()

    private companion object {
        const val TAG = "VoicePreview"
    }
}
