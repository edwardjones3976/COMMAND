package com.command.voice

import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechVoice

/** Audio for some words: 16-bit mono samples and the rate they are to be played at. */
class SpokenAudio(val pcm: ShortArray, val sampleRate: Int)

sealed interface Synthesis {
    class Audio(val audio: SpokenAudio) : Synthesis

    /** There is no audio, and [reason] says why. */
    class Failed(val reason: SpeechProblem) : Synthesis
}

/**
 * Turns words into audio. The real one asks Android's text-to-speech engine; tests hand back whatever audio they like. It
 * never blocks an alarm for long: an engine that is not ready in time is reported, not waited for.
 */
interface SpeechSynthesizer {
    /** Writes [text] as audio, in [voice] as far as the phone can. */
    suspend fun synthesize(text: String, voice: SpeechVoice = SpeechVoice.DEFAULT): Synthesis

    /** Starts the engine ahead of the first [synthesize], which then finds it started or on its way. Harmless to call again. */
    fun prepare() = Unit

    /** Lets go of the engine. The next [synthesize] starts it again. */
    fun shutdown()
}
