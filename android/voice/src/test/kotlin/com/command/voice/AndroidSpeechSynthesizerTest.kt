package com.command.voice

import com.command.domain.voice.SpeechProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * The logic around Android's engine, run against an engine that is a script: when it is ready, which voice it speaks with, one line at a
 * time, and what happens to a line that is given up on. The real engine is checked on a phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpeechSynthesizerTest {

    private val offline = EngineVoice("offline", Locale.US, quality = 300, needsNetwork = false, installed = true)
    private val network = EngineVoice("network", Locale.US, quality = 500, needsNetwork = true, installed = true)

    private lateinit var workDir: File
    private val log = ArrayList<String>()

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("tts-test").toFile()
        log.clear()
    }

    private fun wavBytes(): ByteArray {
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val samples = shortArrayOf(100, -200, 300)
        val data = ByteArrayOutputStream().also { out -> for (s in samples) out.write(le16(s.toInt())) }.toByteArray()
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())
        body.write("fmt ".toByteArray()); body.write(le32(16)); body.write(le16(1)); body.write(le16(1))
        body.write(le32(24_000)); body.write(le32(48_000)); body.write(le16(2)); body.write(le16(16))
        body.write("data".toByteArray()); body.write(le32(data.size)); body.write(data)
        val all = ByteArrayOutputStream()
        all.write("RIFF".toByteArray()); all.write(le32(body.size())); all.write(body.toByteArray())
        return all.toByteArray()
    }

    /** An engine that does what the test tells it to. */
    private inner class ScriptedEngine(val onReady: (Boolean) -> Unit) : TtsEngine {
        var voices: List<EngineVoice> = listOf(offline)
        var current: EngineVoice? = offline
        var shutdowns = 0

        /** What happens when a line is requested: by default the file is written and the engine says it is done. */
        var onRequest: (text: String, file: File, finished: (Boolean) -> Unit) -> Unit = { _, file, finished ->
            file.writeBytes(wavBytes())
            finished(true)
        }

        override fun voices() = voices
        override fun currentVoice() = current
        override fun setLanguage(locale: Locale) = Unit
        override fun selectVoice(voice: EngineVoice): Boolean {
            current = voice
            return true
        }

        override fun synthesizeToFile(text: String, file: File, utteranceId: String, finished: (Boolean) -> Unit): Boolean {
            log += "request:$text"
            onRequest(text, file, finished)
            return true
        }

        override fun stop() {
            log += "stop"
        }

        override fun shutdown() {
            shutdowns++
        }
    }

    private class Engines {
        val made = ArrayList<ScriptedEngine>()
    }

    private fun synthesizer(
        engines: Engines = Engines(),
        ready: ((Boolean) -> Unit) -> Unit = { it(true) },
        configure: ScriptedEngine.() -> Unit = {},
        initTimeoutMs: Long = 1_000,
        synthesisTimeoutMs: Long = 2_000,
    ) = AndroidSpeechSynthesizer(
        newEngine = { onReady ->
            ScriptedEngine(onReady).also {
                it.configure()
                engines.made += it
                ready(onReady)
            }
        },
        workDir = workDir,
        initTimeoutMs = initTimeoutMs,
        synthesisTimeoutMs = synthesisTimeoutMs,
        locale = { Locale.US },
    )

    @Test
    fun `a line is written by the engine and read back, and no file is left behind`() = runTest {
        val result = synthesizer().synthesize("Time to start.")
        val audio = (result as Synthesis.Audio).audio
        assertEquals(24_000, audio.sampleRate)
        assertEquals(listOf<Short>(100, -200, 300), audio.pcm.toList())
        assertTrue(workDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `the engine is started ahead of the line when asked to, and only once`() = runTest {
        val engines = Engines()
        val synth = synthesizer(engines)
        synth.prepare()
        assertEquals(1, engines.made.size)
        synth.synthesize("One")
        synth.synthesize("Two")
        assertEquals("the line found the engine already started", 1, engines.made.size)
    }

    @Test
    fun `an engine that is not ready in time is reported, and the ring does not wait for longer`() = runTest {
        val result = synthesizer(ready = { /* never says it is ready */ }).synthesize("Time to start.")
        assertEquals(SpeechProblem.INIT_TIMEOUT, (result as Synthesis.Failed).reason)
    }

    @Test
    fun `an engine that fails to start is let go, and the next line tries a fresh one`() = runTest {
        val engines = Engines()
        var attempts = 0
        val synth = synthesizer(engines, ready = { onReady -> onReady(++attempts > 1) })
        assertEquals(SpeechProblem.NO_ENGINE, (synth.synthesize("One") as Synthesis.Failed).reason)
        assertEquals(1, engines.made.single().shutdowns)
        assertTrue(synth.synthesize("Two") is Synthesis.Audio)
        assertEquals(2, engines.made.size)
    }

    @Test
    fun `with no voice that works on the phone nothing is asked of the engine at all`() = runTest {
        val synth = synthesizer(configure = {
            voices = listOf(network)
            current = network
        })
        val result = synth.synthesize("Write the report")
        assertEquals(SpeechProblem.NO_OFFLINE_VOICE, (result as Synthesis.Failed).reason)
        assertTrue("the person's words were never handed over", log.isEmpty())
    }

    @Test
    fun `a default voice that asks a server is swapped for an offline one before the words are handed over`() = runTest {
        val engines = Engines()
        val synth = synthesizer(engines, configure = {
            voices = listOf(network, offline)
            current = network
        })
        assertTrue(synth.synthesize("Write the report") is Synthesis.Audio)
        assertEquals(offline, engines.made.single().current)
    }

    @Test
    fun `a line that takes too long is given up on and the engine is told to stop`() = runTest {
        val synth = synthesizer(configure = { onRequest = { _, _, _ -> /* never finishes */ } })
        val result = synth.synthesize("Time to start.")
        assertEquals(SpeechProblem.SYNTHESIS_FAILED, (result as Synthesis.Failed).reason)
        assertEquals(listOf("request:Time to start.", "stop"), log)
        assertTrue(workDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a line that is cancelled is stopped in the engine before the next line is handed to it`() = runTest {
        val synth = synthesizer(configure = {
            onRequest = { text, file, finished ->
                if (text == "A") Unit /* never finishes */ else {
                    file.writeBytes(wavBytes())
                    finished(true)
                }
            }
        })
        val a = async { synth.synthesize("A") }
        yield()
        a.cancelAndJoin()
        assertTrue(synth.synthesize("B") is Synthesis.Audio)
        assertEquals(listOf("request:A", "stop", "request:B"), log)
    }

    @Test
    fun `lines go through the engine one at a time`() = runTest {
        var finishA: ((Boolean) -> Unit)? = null
        var fileA: File? = null
        val synth = synthesizer(configure = {
            onRequest = { text, file, finished ->
                if (text == "A") {
                    finishA = finished
                    fileA = file
                } else {
                    file.writeBytes(wavBytes())
                    finished(true)
                }
            }
        })
        val a = async { synth.synthesize("A") }
        yield()
        val b = async { synth.synthesize("B") }
        yield()
        assertEquals("B waits for A", listOf("request:A"), log)

        fileA!!.writeBytes(wavBytes())
        finishA!!(true)
        assertTrue(a.await() is Synthesis.Audio)
        assertTrue(b.await() is Synthesis.Audio)
        assertEquals(listOf("request:A", "request:B"), log)
    }

    @Test
    fun `letting the engine go ends the wait for a line that was being written`() = runTest {
        val synth = synthesizer(configure = { onRequest = { _, _, _ -> /* never finishes */ } }, synthesisTimeoutMs = 60_000)
        val a = async { synth.synthesize("A") }
        yield()
        synth.shutdown()
        assertEquals(SpeechProblem.SYNTHESIS_FAILED, (a.await() as Synthesis.Failed).reason)
    }
}
