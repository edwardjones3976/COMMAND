package com.command.domain.voice

/**
 * One run of words, and whether it contains something the person typed (a mission's title, a first step). Words the person
 * typed are always spoken by the phone's own voice and never sent anywhere; the rest of a line may one day be spoken by a
 * voice that has to be downloaded or rendered elsewhere, and only with a consent of its own.
 */
data class Segment(val text: String, val carriesUserText: Boolean = false)

/** What is said: segments, spoken in order. */
data class Line(val segments: List<Segment>) {
    val text: String get() = segments.joinToString(" ") { it.text.trim() }.trim()
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        fun of(text: String, carriesUserText: Boolean = false) = Line(listOf(Segment(text, carriesUserText)))
    }
}

/** What the speech is for: an alarm that is ringing, or a preview the person asked to hear (which is never loud). */
enum class SpeechUsage { ALARM, PREVIEW }

/** Why nothing was spoken. Whoever asked shows the words on the screen instead, and the audit log records the reason. */
enum class SpeechProblem {
    /** No speech engine on the phone, or it refused to start. */
    NO_ENGINE,

    /** The engine did not become ready in time. An alarm does not wait for it. */
    INIT_TIMEOUT,

    /** The engine would not turn the words into sound. */
    SYNTHESIS_FAILED,

    /** The sound could not be played. */
    PLAYBACK_FAILED,

    /** The person asked for speech only through a headset, and none is connected. */
    HEADSET_REQUIRED,

    /** Something nobody expected went wrong on the way to the speaker. The ring carried on without the line. */
    UNEXPECTED,
}

sealed interface SpeakResult {
    /** All of it was spoken. */
    data class Spoken(val durationMs: Long) : SpeakResult

    /** Nothing was spoken, and [reason] says why. */
    data class Unavailable(val reason: SpeechProblem) : SpeakResult

    /** It was cut off, because the alarm was answered, silenced or ended. */
    data object Interrupted : SpeakResult
}

/** Says a [Line] aloud. Never blocks an alarm: a ring starts with its tone, and speech joins it when it can. */
interface Speaker {
    /**
     * Says [line], and reports every way it could not be said as a [SpeakResult], never as an exception (cancellation aside).
     * [onAudible] is called once, on the caller's context, just before the first sound of the line is made and not
     * at all if there is none, so that whoever is playing something else (the alarm tone) can turn it down for exactly as long as
     * the line is heard, and not while the engine is still getting ready.
     */
    suspend fun speak(line: Line, usage: SpeechUsage, onAudible: () -> Unit = {}): SpeakResult

    /**
     * Starts whatever the speaker needs ahead of the first line, so that the line is not waiting for an engine that was only started when
     * it was asked for (some phones take seconds). Harmless to call again, and only a hint: nothing depends on it.
     */
    fun prepare() = Unit

    /** Cuts off whatever is being said. Harmless when nothing is. */
    fun stop()

    /** Lets go of whatever the speaker holds (a speech engine). It is started again the next time it is asked to speak. */
    fun release()
}

/** Where speech may come out. The tone and the vibration ring either way; this governs only the spoken line. */
enum class RoutePolicy {
    SPEAKER_AND_HEADSET,
    HEADSET_ONLY;

    fun allowsSpeech(headsetConnected: Boolean): Boolean = this == SPEAKER_AND_HEADSET || headsetConnected
}
