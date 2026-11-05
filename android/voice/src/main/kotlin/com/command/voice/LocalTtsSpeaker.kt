package com.command.voice

import com.command.domain.voice.Line
import com.command.domain.voice.RoutePolicy
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.Speaker
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** The line cannot be played where the person allowed it: it may only come out of a headset, and none can be used or the one in use went away. */
class RouteUnavailableException : Exception("the line may only be played through a headset, and none can be used")

/** Plays audio and says when it has ended. */
interface PcmPlayer {
    /**
     * Plays [pcm] and returns how long it played, in milliseconds. Cancelling the call, or [stop], ends the sound. With
     * [RoutePolicy.HEADSET_ONLY] the sound goes to a headset or nowhere: throws [RouteUnavailableException] when there is none to use, or
     * when the headset stops being where it plays, in which case the sound has already been cut off.
     */
    suspend fun play(pcm: ShortArray, sampleRate: Int, usage: SpeechUsage, route: RoutePolicy): Long

    fun stop()
}

/**
 * Says a line with the phone's own voice: the engine writes the words as audio (offline voices only, see [AndroidSpeechSynthesizer]), the
 * audio is levelled and held under a ceiling ([VoiceMastering]), and it is played on the alarm stream when it is for an alarm. If the
 * engine is missing, too slow or has no offline voice the line is reported as unavailable and the caller shows the words instead; an alarm
 * never waits for it.
 *
 * A line is current only until another is asked for or [stop] is called. An earlier line that is still being prepared when a later one is
 * asked for reports that it was interrupted and never becomes audible, whatever the caller did or did not cancel.
 */
class LocalTtsSpeaker(
    private val synthesizer: SpeechSynthesizer,
    private val player: PcmPlayer,
    private val master: (SpokenAudio) -> ShortArray = { VoiceMastering.master(it.pcm, it.sampleRate).pcm },
) : Speaker {

    /** Counts the lines asked for and the stops, so that each line can tell whether it is still the current one. */
    private val generation = AtomicInteger()

    override suspend fun speak(line: Line, usage: SpeechUsage, route: RoutePolicy, onAudible: () -> Unit): SpeakResult {
        val mine = generation.incrementAndGet()
        fun superseded() = generation.get() != mine
        if (line.isEmpty) return SpeakResult.Spoken(0)
        val made = try {
            synthesizer.synthesize(line.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SpeakResult.Unavailable(SpeechProblem.SYNTHESIS_FAILED)
        }
        val audio = when (made) {
            is Synthesis.Failed -> return SpeakResult.Unavailable(made.reason)
            is Synthesis.Audio -> made.audio
        }
        if (superseded()) return SpeakResult.Interrupted
        val leveled = try {
            withContext(Dispatchers.Default) { master(audio) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SpeakResult.Unavailable(SpeechProblem.PLAYBACK_FAILED)
        }
        if (superseded()) return SpeakResult.Interrupted
        onAudible()
        val playedMs = try {
            player.play(leveled, audio.sampleRate, usage, route)
        } catch (e: CancellationException) {
            player.stop()
            throw e
        } catch (e: RouteUnavailableException) {
            return SpeakResult.Unavailable(SpeechProblem.HEADSET_REQUIRED)
        } catch (e: Exception) {
            return SpeakResult.Unavailable(SpeechProblem.PLAYBACK_FAILED)
        }
        return if (superseded()) SpeakResult.Interrupted else SpeakResult.Spoken(playedMs)
    }

    override fun prepare() = synthesizer.prepare()

    override fun stop() {
        generation.incrementAndGet()
        player.stop()
    }

    override fun release() = synthesizer.shutdown()
}

/** For a phone with no speech engine at all: says nothing, and says why. */
class SilentSpeaker : Speaker {
    override suspend fun speak(line: Line, usage: SpeechUsage, route: RoutePolicy, onAudible: () -> Unit): SpeakResult =
        SpeakResult.Unavailable(SpeechProblem.NO_ENGINE)

    override fun stop() = Unit

    override fun release() = Unit
}
