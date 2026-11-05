package com.command.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A voice an engine offers, reduced to what choosing one needs. */
internal data class EngineVoice(
    val name: String,
    val locale: Locale,
    val quality: Int,
    /** The voice asks a server to speak, so the words it is given would leave the phone. */
    val needsNetwork: Boolean,
    /** Its data is on the phone. A voice that is not installed may start a download when it is used. */
    val installed: Boolean,
)

/**
 * What the synthesizer needs from Android's text-to-speech engine. Behind an interface so that the logic around the engine (which voice
 * may be used, one line at a time, stopping a line that was abandoned) is tested without one.
 */
internal interface TtsEngine {
    /** Every voice the engine offers; empty when it does not say. */
    fun voices(): List<EngineVoice>

    /** The voice the engine would speak with now, or null when it does not say. */
    fun currentVoice(): EngineVoice?

    fun setLanguage(locale: Locale)

    fun selectVoice(voice: EngineVoice): Boolean

    /** Starts writing [text] to [file]; [finished] is told true when it is done and false when it failed. Returns whether the request was accepted. */
    fun synthesizeToFile(text: String, file: File, utteranceId: String, finished: (Boolean) -> Unit): Boolean

    /** Abandons whatever the engine is working on or has queued. */
    fun stop()

    fun shutdown()
}

/**
 * The person's own words are only ever given to a voice that works on the phone. The engine's current voice is kept when it does, whatever
 * language it is in: it is the voice the engine chose for the language it was asked for, and the line's own words are English, so a
 * phone whose language has no voice of its own still speaks. When the current voice asks a server (or its data is not installed, which
 * can start a download) an installed voice that works offline is chosen instead: the phone's language first, then English, best quality
 * first. With no such voice, or an engine that does not say which voices it has, nothing is said.
 */
internal object OfflineVoices {

    fun pin(engine: TtsEngine, locale: Locale): Boolean {
        val current = engine.currentVoice()
        if (current != null && current.usable) return true
        val usable = engine.voices().filter { it.usable }
        val candidates = usable.filter { it.locale.language == locale.language }
            .ifEmpty { usable.filter { it.locale.language == Locale.ENGLISH.language } }
        val choice = candidates
            .sortedWith(compareByDescending<EngineVoice> { it.locale.country == locale.country }.thenByDescending { it.quality })
            .firstOrNull() ?: return false
        return engine.selectVoice(choice)
    }

    private val EngineVoice.usable: Boolean get() = installed && !needsNetwork
}

/** The real engine: Android's [TextToSpeech], started with [onReady] told whether it came up. */
internal class AndroidTtsEngine(context: Context, onReady: (Boolean) -> Unit) : TtsEngine {

    private val callbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val tts = TextToSpeech(context) { status -> onReady(status == TextToSpeech.SUCCESS) }

    init {
        // Once, for the life of the engine: each line is told apart by its own utterance id.
        tts.setOnUtteranceProgressListener(listener())
    }

    private fun Voice.toEngineVoice() = EngineVoice(
        name = name,
        locale = locale,
        quality = quality,
        needsNetwork = isNetworkConnectionRequired,
        installed = !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
    )

    override fun voices(): List<EngineVoice> =
        runCatching { tts.voices?.map { it.toEngineVoice() } }.getOrNull().orEmpty().also { all ->
            // What the engine offers, for whoever reads the log of a phone that will not speak: names and flags, never any words.
            Log.d(TAG, "the engine offers ${all.size} voices: " + all.take(MAX_LOGGED).joinToString { "${it.name}(network=${it.needsNetwork}, installed=${it.installed}, q=${it.quality})" })
        }

    override fun currentVoice(): EngineVoice? = runCatching { tts.voice?.toEngineVoice() }.getOrNull()

    override fun setLanguage(locale: Locale) {
        runCatching { tts.language = locale } // an unsupported language leaves the engine's own default
    }

    override fun selectVoice(voice: EngineVoice): Boolean = runCatching {
        val match = tts.voices?.firstOrNull { it.name == voice.name } ?: return false
        tts.setVoice(match) == TextToSpeech.SUCCESS
    }.getOrDefault(false)

    override fun synthesizeToFile(text: String, file: File, utteranceId: String, finished: (Boolean) -> Unit): Boolean {
        callbacks[utteranceId] = finished
        // For whoever reads the log of a phone: which voice is about to speak, and whether it needs a network. The words are never logged.
        runCatching { tts.voice }.getOrNull()?.let { Log.i(TAG, "speaking with voice ${it.name} (network required: ${it.isNetworkConnectionRequired})") }
        val accepted = tts.synthesizeToFile(text, null, file, utteranceId) == TextToSpeech.SUCCESS
        if (!accepted) callbacks.remove(utteranceId)
        return accepted
    }

    override fun stop() {
        callbacks.clear()
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        callbacks.clear()
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private companion object {
        const val TAG = "AndroidTtsEngine"
        const val MAX_LOGGED = 40
    }

    private fun listener() = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { callbacks.remove(it)?.invoke(true) }
        }

        @Deprecated("Superseded by the version with an error code")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { callbacks.remove(it)?.invoke(false) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { callbacks.remove(it)?.invoke(false) }
        }
    }
}
