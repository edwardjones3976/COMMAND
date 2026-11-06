package com.command.voice

import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechVoice
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
        val pitches = ArrayList<Float>()
        val rates = ArrayList<Float>()
        val languages = ArrayList<Locale>()

        /** Whether the engine takes a voice it is told to use. */
        var refuses: Set<String> = emptySet()

        /** What happens when a line is requested: by default the file is written and the engine says it is done. */
        var onRequest: (text: String, file: File, finished: (Boolean) -> Unit) -> Unit = { _, file, finished ->
            file.writeBytes(wavBytes())
            finished(true)
        }

        override fun voices() = voices
        override fun currentVoice() = current
        override fun setLanguage(locale: Locale) {
            languages += locale
        }

        override fun selectVoice(voice: EngineVoice): Boolean {
            if (voice.name in refuses) return false
            current = voice
            return true
        }

        override fun setPitch(pitch: Float) {
            pitches += pitch
        }

        override fun setSpeechRate(rate: Float) {
            rates += rate
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

    // --- the pack's voice -------------------------------------------------------------------------------------------------------

    private val stone = SpeechVoice("en-US", pitch = 0.86f, rate = 1.28f, female = false)
    private val eleanor = SpeechVoice("en-GB", pitch = 1.01f, rate = 1.04f, female = true)

    private val usMale = EngineVoice("en-us-x-iol-local", Locale.US, quality = 300, needsNetwork = false, installed = true)
    private val usFemale = EngineVoice("en-us-x-iob-local", Locale.US, quality = 300, needsNetwork = false, installed = true)
    private val gbFemale = EngineVoice("en-gb-x-gba-female-local", Locale.UK, quality = 300, needsNetwork = false, installed = true)
    private val gbMale = EngineVoice("en-gb-x-rjs-male-local", Locale.UK, quality = 340, needsNetwork = false, installed = true)
    private val german = EngineVoice("de-de-x-nfh-local", Locale.GERMANY, quality = 300, needsNetwork = false, installed = true)

    private suspend fun spokenWith(voice: SpeechVoice, vararg offered: EngineVoice, configure: ScriptedEngine.() -> Unit = {}): ScriptedEngine {
        val engines = Engines()
        val synth = synthesizer(engines, configure = {
            voices = offered.toList()
            current = offered.first()
            configure()
        })
        assertTrue(synth.synthesize("Time to start.", voice) is Synthesis.Audio)
        return engines.made.single()
    }

    @Test
    fun `two packs give the engine different pitches, rates, languages and voices`() = runTest {
        val a = spokenWith(stone, usFemale, usMale, gbFemale, gbMale)
        val b = spokenWith(eleanor, usFemale, usMale, gbFemale, gbMale)

        assertEquals("the rate is kept to what the engine does well", listOf(0.86f), a.pitches)
        assertEquals(listOf(SYSTEM_TTS_MAX_RATE), a.rates)
        assertEquals(usMale, a.current)
        assertEquals(Locale.US, a.languages.first())
        assertEquals(listOf(1.01f), b.pitches)
        assertEquals(listOf(1.04f), b.rates)
        assertEquals(gbFemale, b.current)
        assertEquals(Locale.UK, b.languages.first())
    }

    @Test
    fun `the words go to the engine only after its voice, pitch and rate are set`() = runTest {
        val order = ArrayList<String>()
        val engines = Engines()
        val synth = synthesizer(engines, configure = {
            voices = listOf(usMale)
            current = null
            onRequest = { _, file, finished ->
                order += "voice=${current?.name} pitch=${pitches.lastOrNull()} rate=${rates.lastOrNull()}"
                file.writeBytes(wavBytes())
                finished(true)
            }
        })
        synth.synthesize("Time to start.", SpeechVoice("en-US", 0.9f, 1.1f, false))
        assertEquals(listOf("voice=en-us-x-iol-local pitch=0.9 rate=1.1"), order)
    }

    @Test
    fun `the score is the voice engine's, so a known kind outweighs quality and the same country counts for a little`() = runTest {
        assertEquals("a woman's voice is wanted, and a man's is better", gbFemale, spokenWith(eleanor, gbMale, gbFemale).current)
        assertEquals(
            "with the kind unknown, quality and then the country decide",
            EngineVoice("en-gb-1", Locale.UK, 340, false, true),
            spokenWith(
                SpeechVoice("en-GB"),
                EngineVoice("en-us-1", Locale.US, 380, false, true),
                EngineVoice("en-gb-1", Locale.UK, 340, false, true),
            ).current,
        )
        assertEquals(
            "a pack that does not say what kind it wants takes the best by quality",
            gbMale,
            spokenWith(SpeechVoice("en-GB"), gbFemale, gbMale).current,
        )
        assertEquals("the wrong kind is still better than silence", usFemale, spokenWith(stone, usFemale).current)
    }

    @Test
    fun `a pack never gets a voice that asks a server or one that is not installed, however good`() = runTest {
        val server = EngineVoice("en-us-x-iol-network", Locale.US, 900, needsNetwork = true, installed = true)
        val notHere = EngineVoice("en-us-x-iom-local", Locale.US, 900, needsNetwork = false, installed = false)
        assertEquals(usFemale, spokenWith(stone, server, notHere, usFemale).current)
    }

    @Test
    fun `a language the phone has no offline voice for falls back to the old choice, and the line is still said`() = runTest {
        val engine = spokenWith(SpeechVoice("fr-CA", pitch = 1.1f, rate = 0.9f, female = true), usMale, gbMale)
        assertEquals("the voice in use works offline, so it stays", usMale, engine.current)
        assertEquals("the phone's language was asked for again", Locale.US, engine.languages.last())
        assertEquals("and the pack's pitch and rate still apply", listOf(1.1f), engine.pitches)
        assertEquals(listOf(0.9f), engine.rates)
    }

    @Test
    fun `a voice the engine will not take falls back too, and the line is still said`() = runTest {
        val engine = spokenWith(stone, usFemale, usMale) { refuses = setOf(usMale.name) }
        assertEquals("the current voice works offline", usFemale, engine.current)
    }

    @Test
    fun `with no voice that works on the phone a pack says nothing either`() = runTest {
        val engines = Engines()
        val synth = synthesizer(engines, configure = {
            voices = listOf(network)
            current = network
        })
        assertEquals(SpeechProblem.NO_OFFLINE_VOICE, (synth.synthesize("Write the report", stone) as Synthesis.Failed).reason)
        assertTrue("the person's words were never handed over", log.isEmpty())
    }

    @Test
    fun `a line with no pack goes back to the engine's normal voice settings after a line that had one`() = runTest {
        val engines = Engines()
        val synth = synthesizer(engines, configure = {
            voices = listOf(usMale, usFemale)
            current = usMale
        })
        synth.synthesize("One", stone)
        synth.synthesize("Two")
        val engine = engines.made.single()
        assertEquals(listOf(0.86f, 1f), engine.pitches)
        assertEquals(listOf(SYSTEM_TTS_MAX_RATE, 1f), engine.rates)
        assertEquals("and to the phone's language", Locale.US, engine.languages.last())
    }

    @Test
    fun `a line with no pack keeps the engine's own voice, as it always did`() = runTest {
        val engine = spokenWith(SpeechVoice.DEFAULT, german, usMale)
        assertEquals(german, engine.current)
        assertEquals(listOf(Locale.US), engine.languages)
    }

    @Test
    fun `a pitch or a rate out of range is brought into range, and a blank language is the phone's`() = runTest {
        val low = spokenWith(SpeechVoice("", pitch = 0f, rate = 0f), usMale)
        assertEquals(listOf(SYSTEM_TTS_MIN_PITCH), low.pitches)
        assertEquals(listOf(SYSTEM_TTS_MIN_RATE), low.rates)
        assertEquals(listOf(Locale.US), low.languages)
        val high = spokenWith(SpeechVoice("en-US", pitch = 9f, rate = 9f), usMale)
        assertEquals(listOf(SYSTEM_TTS_MAX_PITCH), high.pitches)
        assertEquals(listOf(SYSTEM_TTS_MAX_RATE), high.rates)
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
