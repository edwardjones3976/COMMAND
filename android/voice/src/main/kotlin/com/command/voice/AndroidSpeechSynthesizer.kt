package com.command.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.command.domain.voice.SpeechProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Speech from the engine the phone already has (Android's TextToSpeech), written to a file and read back as audio so that it can
 * be levelled and played on the alarm stream. The engine is started ahead of the first line when the caller can ([prepare]) or else
 * the first time it is asked, waited for no longer than [initTimeoutMs] (a cold Google engine took about six seconds to be ready on the
 * API 36 emulator), and let go with [shutdown]. Nothing here uses the network by itself: whether the engine does is its business, and
 * an offline voice is used when the phone has one.
 */
class AndroidSpeechSynthesizer(
    context: Context,
    private val initTimeoutMs: Long = 12_000,
    private val synthesisTimeoutMs: Long = 15_000,
) : SpeechSynthesizer {

    private val context = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    @Synchronized
    private fun start(): Pair<TextToSpeech, CompletableDeferred<Boolean>> {
        val existing = engine
        val gate = ready
        if (existing != null && gate != null) return existing to gate
        val signal = CompletableDeferred<Boolean>()
        val created = TextToSpeech(context) { status -> signal.complete(status == TextToSpeech.SUCCESS) }
        engine = created
        ready = signal
        return created to signal
    }

    override fun prepare() {
        runCatching { start() }
    }

    override suspend fun synthesize(text: String): Synthesis {
        val (tts, signal) = start()
        val usable = withTimeoutOrNull(initTimeoutMs) { signal.await() }
            ?: return Synthesis.Failed(SpeechProblem.INIT_TIMEOUT)
        if (!usable) {
            shutdown() // a failed engine is not kept: the next ask tries again
            return Synthesis.Failed(SpeechProblem.NO_ENGINE)
        }
        runCatching { tts.language = Locale.getDefault() } // an unsupported language leaves the engine's own default
        val utterance = UUID.randomUUID().toString()
        val file = File.createTempFile("line", ".wav", context.cacheDir)
        val finished = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == utterance) finished.complete(true)
            }

            @Deprecated("Superseded by the version with an error code")
            override fun onError(utteranceId: String?) {
                if (utteranceId == utterance) finished.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == utterance) finished.complete(false)
            }
        })
        return try {
            if (tts.synthesizeToFile(text, null, file, utterance) != TextToSpeech.SUCCESS) {
                Synthesis.Failed(SpeechProblem.SYNTHESIS_FAILED)
            } else if (withTimeoutOrNull(synthesisTimeoutMs) { finished.await() } != true) {
                runCatching { tts.stop() }
                Synthesis.Failed(SpeechProblem.SYNTHESIS_FAILED)
            } else {
                val audio = withContext(Dispatchers.IO) { Wav.read(file.readBytes()) }
                if (audio == null) Synthesis.Failed(SpeechProblem.SYNTHESIS_FAILED) else Synthesis.Audio(audio)
            }
        } finally {
            file.delete()
        }
    }

    @Synchronized
    override fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = null
    }
}
