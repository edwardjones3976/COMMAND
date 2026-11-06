package com.command.voice

import android.content.Context
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Speech from the engine the phone already has (Android's TextToSpeech), written to a file and read back as audio so that it can be levelled
 * and played on the alarm stream. The engine is started ahead of the first line when the caller can ([prepare]) or else the first time it
 * is asked, waited for no longer than [initTimeoutMs] (a cold Google engine took about six seconds to be ready on the API 36 emulator), and
 * let go with [shutdown].
 *
 * The person's words go only to a voice that works on the phone: the best installed offline voice for the pack, otherwise the engine's own
 * voice when it does, otherwise an installed offline voice of the person's language ([OfflineVoices]), and when there is none nothing is said. Nothing here opens a connection. The engine handles one
 * line at a time, so a line that is abandoned (the ring ended, or a later ring took over) is stopped in the engine before the next is handed
 * over, and cannot hold it up.
 */
class AndroidSpeechSynthesizer internal constructor(
    private val newEngine: (onReady: (Boolean) -> Unit) -> TtsEngine,
    private val workDir: File,
    private val initTimeoutMs: Long,
    private val synthesisTimeoutMs: Long,
    private val locale: () -> Locale,
) : SpeechSynthesizer {

    constructor(context: Context, initTimeoutMs: Long = 12_000, synthesisTimeoutMs: Long = 15_000) : this(
        newEngine = { onReady -> AndroidTtsEngine(context.applicationContext, onReady) },
        workDir = context.applicationContext.cacheDir,
        initTimeoutMs = initTimeoutMs,
        synthesisTimeoutMs = synthesisTimeoutMs,
        locale = { Locale.getDefault() },
    )

    private class Started(val engine: TtsEngine, val ready: CompletableDeferred<Boolean>)

    private var started: Started? = null

    /** One line at a time goes through the engine. */
    private val gate = Mutex()

    /** The line the engine is working on, so that letting the engine go can end the wait for it. */
    @Volatile private var inFlight: CompletableDeferred<Boolean>? = null

    @Synchronized
    private fun start(): Started {
        started?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val engine = newEngine { ok -> ready.complete(ok) }
        return Started(engine, ready).also { started = it }
    }

    override fun prepare() {
        runCatching { start() }
    }

    override suspend fun synthesize(text: String, voice: SpeechVoice): Synthesis {
        val engine = start()
        val usable = withTimeoutOrNull(initTimeoutMs) { engine.ready.await() }
            ?: return Synthesis.Failed(SpeechProblem.INIT_TIMEOUT)
        if (!usable) {
            shutdown() // a failed engine is not kept: the next ask tries again
            return Synthesis.Failed(SpeechProblem.NO_ENGINE)
        }
        return gate.withLock { synthesizeOne(engine.engine, text, voice) }
    }

    private suspend fun synthesizeOne(engine: TtsEngine, text: String, voice: SpeechVoice): Synthesis {
        if (!OfflineVoices.choose(engine, voice, locale())) return Synthesis.Failed(SpeechProblem.NO_OFFLINE_VOICE)
        // Every line sets these, because the engine keeps them: a line with no pack must not sound like the one before it.
        engine.setPitch(VoiceDirectorEngine.clampSystemPitch(voice.pitch))
        engine.setSpeechRate(VoiceDirectorEngine.clampSystemRate(voice.rate))
        val utterance = UUID.randomUUID().toString()
        val file = File.createTempFile("line", ".wav", workDir)
        val finished = CompletableDeferred<Boolean>()
        inFlight = finished
        try {
            if (!engine.synthesizeToFile(text, file, utterance) { ok -> finished.complete(ok) }) {
                return Synthesis.Failed(SpeechProblem.SYNTHESIS_FAILED)
            }
            if (withTimeoutOrNull(synthesisTimeoutMs) { finished.await() } != true) return Synthesis.Failed(SpeechProblem.SYNTHESIS_FAILED)
            val audio = withContext(Dispatchers.IO) { Wav.read(file.readBytes()) }
            return if (audio == null) Synthesis.Failed(SpeechProblem.SYNTHESIS_FAILED) else Synthesis.Audio(audio)
        } finally {
            inFlight = null
            // Cancelled, timed out or failed: the engine is told to stop before anything else is handed to it. (A line that finished leaves
            // nothing to stop.)
            if (!finished.isCompleted) runCatching { engine.stop() }
            file.delete()
        }
    }

    override fun shutdown() {
        inFlight?.complete(false) // the line that was being written is not waited for any more
        val current = synchronized(this) { started.also { started = null } } ?: return
        current.engine.shutdown()
    }
}
