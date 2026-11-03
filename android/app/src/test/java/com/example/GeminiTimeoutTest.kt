package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CommandRepository
import com.example.service.GeminiAudio
import com.example.service.GeminiSpeechSource
import com.example.service.VoiceDirectorEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Gemini request rules (issue #3), run in virtual time against a fake [GeminiSpeechSource]: an 8 s limit,
 * late replies dropped and never cached, a 120 s per-voice backoff after any failure, and its expiry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiTimeoutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packs = CommandRepository.defaultVoicePacks().associateBy { it.id }
    private val stone = packs.getValue("pack_major_stone")
    private val engines = mutableListOf<VoiceDirectorEngine>()
    private var nowMs = 1_000_000L

    /** Fake Gemini: waits [delayMs] of virtual time, then answers with [reply]; records calls and cancellations. */
    private class FakeGemini(var delayMs: Long = 0, var reply: () -> GeminiAudio? = { audio() }) : GeminiSpeechSource {
        val voices = mutableListOf<String>()
        var cancelled = 0
        override suspend fun synthesize(voiceName: String, prompt: String): GeminiAudio? {
            voices += voiceName
            try {
                delay(delayMs)
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
            return reply()
        }

        companion object {
            fun audio(): GeminiAudio {
                val pcm = ByteBuffer.allocate(480 * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(480) { pcm.putShort((it * 50).toShort()) }
                return GeminiAudio(pcm.array(), "audio/L16;codec=pcm;rate=24000")
            }
        }
    }

    @Before
    fun setUp() {
        File(context.cacheDir, "director_voice").deleteRecursively()
    }

    @After
    fun tearDown() {
        engines.forEach { it.shutdown() }
    }

    private fun engine(gemini: GeminiSpeechSource) =
        VoiceDirectorEngine(context, CoroutineScope(Dispatchers.Unconfined), gemini) { nowMs }.also { engines += it }

    private fun stoneDirector(engine: VoiceDirectorEngine) = engine.resolveDirectorVoice(stone)

    @Test
    fun `a reply just inside 8 seconds is used`() = runTest {
        val gemini = FakeGemini(delayMs = 7_900)
        val e = engine(gemini)
        val rendered = e.renderWithGemini(stoneDirector(e), "prompt")
        assertNotNull(rendered)
        assertEquals("gemini", rendered!!.source)
        assertEquals(24_000, rendered.sampleRate)
        assertEquals(480, rendered.pcm.size)
    }

    @Test
    fun `a reply after 8 seconds is cancelled, dropped and backs the voice off`() = runTest {
        val gemini = FakeGemini(delayMs = 9_000)
        val e = engine(gemini)
        assertNull(e.renderWithGemini(stoneDirector(e), "prompt"))
        assertEquals("the late request must be cancelled, not left running", 1, gemini.cancelled)

        // Inside the 120 s backoff the voice isn't asked again, even if Gemini would now answer at once.
        gemini.delayMs = 0
        nowMs += 119_000
        assertNull(e.renderWithGemini(stoneDirector(e), "prompt"))
        assertEquals(1, gemini.voices.size)

        // After the backoff expires Gemini is tried again.
        nowMs += 2_000
        assertNotNull(e.renderWithGemini(stoneDirector(e), "prompt"))
        assertEquals(2, gemini.voices.size)
    }

    @Test
    fun `a timed-out line falls back without leaving anything in the cache`() = runTest {
        val e = engine(FakeGemini(delayMs = 9_000))
        // System TTS isn't initialised here, so the fallback returns nothing; what matters is the cache stays empty.
        assertNull(e.renderLine(stone, stoneDirector(e), "Move!"))
        val cached = File(context.cacheDir, "director_voice").listFiles { f -> f.name.endsWith(".pcm") }.orEmpty()
        assertTrue("late or failed renders must not be cached: ${cached.map { it.name }}", cached.isEmpty())
    }

    @Test
    fun `empty audio, an error and an undecodable reply each back the voice off`() = runTest {
        val replies = listOf<() -> GeminiAudio?>(
            { null },
            { GeminiAudio(ByteArray(1), "audio/L16;rate=24000") },
            { throw IllegalStateException("401: App Check token is invalid") },
            { GeminiAudio("RIFF".toByteArray() + ByteArray(40), "audio/wav") } // WAV without a valid fmt/data chunk
        )
        replies.forEach { reply ->
            val gemini = FakeGemini(reply = reply)
            val e = engine(gemini)
            assertNull(e.renderWithGemini(stoneDirector(e), "prompt"))
            nowMs += 1_000
            assertNull(e.renderWithGemini(stoneDirector(e), "prompt"))
            assertEquals("backoff should skip the second call", 1, gemini.voices.size)
        }
    }

    @Test
    fun `backoff is per voice, so another director still reaches Gemini`() = runTest {
        val gemini = FakeGemini(reply = { throw IllegalStateException("down") })
        val e = engine(gemini)
        assertNull(e.renderWithGemini(stoneDirector(e), "prompt"))

        gemini.reply = { FakeGemini.audio() }
        val eleanor = e.resolveDirectorVoice(packs.getValue("pack_eleanor_vance"))
        assertNotNull(e.renderWithGemini(eleanor, "prompt"))
        assertEquals(listOf("Algenib", "Kore"), gemini.voices)
    }
}
