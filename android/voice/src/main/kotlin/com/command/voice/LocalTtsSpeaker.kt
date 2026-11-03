package com.command.voice

import com.command.domain.voice.Line
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.Speaker
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plays audio and says when it has ended. */
interface PcmPlayer {
    /** Plays [pcm] and returns how long it played, in milliseconds. Cancelling the call, or [stop], ends the sound. */
    suspend fun play(pcm: ShortArray, sampleRate: Int, usage: SpeechUsage): Long

    fun stop()
}

/**
 * Says a line with the phone's own voice, and never needs a network: the engine writes the words as audio, the audio is levelled
 * and held under a ceiling ([VoiceMastering]), and it is played on the alarm stream when it is for an alarm. If the engine is
 * missing or too slow the line is reported as unavailable and the caller shows the words instead; an alarm never waits for it.
 */
class LocalTtsSpeaker(
    private val synthesizer: SpeechSynthesizer,
    private val player: PcmPlayer,
    private val master: (SpokenAudio) -> ShortArray = { VoiceMastering.master(it.pcm, it.sampleRate).pcm },
) : Speaker {

    @Volatile private var stopped = false

    override suspend fun speak(line: Line, usage: SpeechUsage, onAudible: () -> Unit): SpeakResult {
        stopped = false
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
        if (stopped) return SpeakResult.Interrupted
        val leveled = try {
            withContext(Dispatchers.Default) { master(audio) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SpeakResult.Unavailable(SpeechProblem.PLAYBACK_FAILED)
        }
        if (stopped) return SpeakResult.Interrupted
        onAudible()
        val playedMs = try {
            player.play(leveled, audio.sampleRate, usage)
        } catch (e: CancellationException) {
            player.stop()
            throw e
        } catch (e: Exception) {
            return SpeakResult.Unavailable(SpeechProblem.PLAYBACK_FAILED)
        }
        return if (stopped) SpeakResult.Interrupted else SpeakResult.Spoken(playedMs)
    }

    override fun prepare() = synthesizer.prepare()

    override fun stop() {
        stopped = true
        player.stop()
    }

    override fun release() = synthesizer.shutdown()
}

/** For a phone with no speech engine at all: says nothing, and says why. */
class SilentSpeaker : Speaker {
    override suspend fun speak(line: Line, usage: SpeechUsage, onAudible: () -> Unit): SpeakResult = SpeakResult.Unavailable(SpeechProblem.NO_ENGINE)

    override fun stop() = Unit

    override fun release() = Unit
}
