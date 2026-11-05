package com.command.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Plain JVM tests for the voice pipeline's audio math. These assert what the listener hears:
 * no clipping, a consistent level regardless of input level, and settings that stay in range.
 */
class VoiceAudioTest {

    private val sampleRate = 24_000
    private val ceiling = (32767 * VoiceMastering.dbToLin(VoiceMastering.PEAK_CEILING_DBFS)).toInt() + 1

    /** Speech-like test signal: a 140 Hz voice with harmonics, in syllable bursts with pauses between. */
    private fun speechLike(amplitude: Double, seconds: Double = 2.0, seed: Int = 7): ShortArray {
        val rnd = Random(seed)
        val n = (sampleRate * seconds).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val syllable = (t * 4.0) % 1.0
            val env = if (syllable < 0.7) sin(PI * syllable / 0.7) else 0.0
            val voice = sin(2 * PI * 140 * t) * 0.6 + sin(2 * PI * 280 * t) * 0.25 + sin(2 * PI * 1200 * t) * 0.1
            val breath = (rnd.nextDouble() - 0.5) * 0.05
            ((voice + breath) * env * amplitude).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
    }

    private fun levelDb(pcm: ShortArray) =
        VoiceMastering.speechRmsDbfs(FloatArray(pcm.size) { pcm[it] / 32768f }, sampleRate)

    @Test
    fun `mastering never clips and stays under the peak ceiling`() {
        // From whisper-quiet to the old chain's hard-clipped output.
        listOf(300.0, 3_000.0, 12_000.0, 32_767.0, 90_000.0).forEach { amp ->
            val out = VoiceMastering.master(speechLike(amp), sampleRate)
            val peak = out.pcm.maxOf { abs(it.toInt()) }
            assertTrue("peak $peak exceeds ceiling for input amplitude $amp", peak <= ceiling)
            assertTrue(out.peakDbfs <= VoiceMastering.PEAK_CEILING_DBFS + 0.05f)
        }
    }

    @Test
    fun `mastering brings any input level to the same speech level`() {
        val levels = listOf(1_000.0, 6_000.0, 20_000.0, 32_767.0).map { amp ->
            levelDb(VoiceMastering.master(speechLike(amp), sampleRate).pcm)
        }
        levels.forEach { level ->
            assertEquals("speech level should land near target", VoiceMastering.TARGET_SPEECH_DBFS, level, 3f)
        }
        assertTrue("levels should agree within 2 dB: $levels", levels.max() - levels.min() <= 2f)
    }

    @Test
    fun `mastering removes rumble below the high-pass and leaves the voice band alone`() {
        // Equal-level 30 Hz rumble and 1 kHz tone. Mastering applies one global gain, so the
        // 30 Hz level relative to 1 kHz isolates the filter's response.
        val mix = ShortArray(sampleRate) { i ->
            (sin(2 * PI * 30.0 * i / sampleRate) * 6_000 + sin(2 * PI * 1_000.0 * i / sampleRate) * 6_000).toInt().toShort()
        }
        val out = VoiceMastering.master(mix, sampleRate).pcm

        fun amplitudeAt(pcm: ShortArray, hz: Double): Double {
            var re = 0.0; var im = 0.0
            pcm.forEachIndexed { i, s ->
                re += s * sin(2 * PI * hz * i / sampleRate)
                im += s * kotlin.math.cos(2 * PI * hz * i / sampleRate)
            }
            return kotlin.math.sqrt(re * re + im * im)
        }
        val before = amplitudeAt(mix, 30.0) / amplitudeAt(mix, 1_000.0)
        val after = amplitudeAt(out, 30.0) / amplitudeAt(out, 1_000.0)
        val attenuationDb = 20 * kotlin.math.log10(before / after)
        // A 2nd-order Butterworth at 80 Hz is about 17 dB down at 30 Hz.
        assertTrue("30 Hz should be at least 14 dB down, was $attenuationDb dB", attenuationDb >= 14.0)
    }

    @Test
    fun `mastering handles empty and silent input`() {
        assertEquals(0, VoiceMastering.master(ShortArray(0), sampleRate).pcm.size)
        val silent = VoiceMastering.master(ShortArray(4_800), sampleRate)
        assertTrue(silent.pcm.all { it.toInt() == 0 })
    }

    @Test
    fun `speech level is finite for a quiet buffer whose loudest frame is under the speech floor`() {
        // Issue #14: loudest frame power 5e-7 (about -63 dBFS) left no frames at the -60 dBFS floor, so the mean was NaN.
        val quiet = FloatArray(sampleRate) { (sin(2 * PI * 200.0 * it / sampleRate) * 0.001).toFloat() }
        assertEquals(-96f, VoiceMastering.speechRmsDbfs(quiet, sampleRate), 0f)

        val out = VoiceMastering.master(ShortArray(sampleRate) { (quiet[it] * 32767).toInt().toShort() }, sampleRate)
        assertTrue(out.speechRmsDbfs.isFinite())
        assertTrue(out.peakDbfs.isFinite())
    }

    @Test
    fun `boost slider matches its label and makes lines louder without clipping`() {
        assertEquals(0f, VoiceDirectorEngine.boostDbFor(100), 0.001f)
        assertEquals(5f, VoiceDirectorEngine.boostDbFor(150), 0.001f)
        assertEquals(0f, VoiceDirectorEngine.boostDbFor(50), 0.001f)

        val input = speechLike(12_000.0)
        val plain = VoiceMastering.master(input, sampleRate, boostDb = 0f)
        val boosted = VoiceMastering.master(input, sampleRate, boostDb = VoiceDirectorEngine.boostDbFor(150))
        val gained = levelDb(boosted.pcm) - levelDb(plain.pcm)
        assertTrue("150% should be clearly louder (gained $gained dB)", gained >= 3f)
        assertTrue("boosted peak must stay under the ceiling", boosted.pcm.maxOf { abs(it.toInt()) } <= ceiling)
    }

    @Test
    fun `limiter holds hot peaks without hard clipping`() {
        // Max boost on a signal with sharp transients: the limiter must catch every peak smoothly.
        val input = speechLike(30_000.0)
        val out = VoiceMastering.master(input, sampleRate, boostDb = VoiceMastering.MAX_BOOST_DB).pcm
        assertTrue(out.maxOf { abs(it.toInt()) } <= ceiling)
        // Hard clipping shows up as runs of identical samples pinned at the ceiling.
        var longestPinnedRun = 0
        var run = 0
        out.forEach { s -> run = if (abs(s.toInt()) >= ceiling - 2) run + 1 else 0; longestPinnedRun = maxOf(longestPinnedRun, run) }
        assertTrue("found $longestPinnedRun pinned samples in a row (clipping)", longestPinnedRun <= 3)
    }

    @Test
    fun `system tts pitch and rate are clamped to natural-sounding range`() {
        assertEquals(0.85f, VoiceDirectorEngine.clampSystemPitch(0.5f), 0.001f)
        assertEquals(1.15f, VoiceDirectorEngine.clampSystemPitch(1.6f), 0.001f)
        assertEquals(1.0f, VoiceDirectorEngine.clampSystemPitch(1.0f), 0.001f)
        assertEquals(1.20f, VoiceDirectorEngine.clampSystemRate(1.28f), 0.001f)
        assertEquals(0.85f, VoiceDirectorEngine.clampSystemRate(0.5f), 0.001f)
    }

    @Test
    fun `all-caps lines are softened so tts does not spell words out`() {
        assertEquals(
            "Recovery briefing active! You missed yesterday, but we do not quit!",
            VoiceDirectorEngine.softenAllCaps("RECOVERY BRIEFING ACTIVE! YOU MISSED YESTERDAY, BUT WE DO NOT QUIT!")
        )
        assertEquals("Lock in the FIRST action.", VoiceDirectorEngine.softenAllCaps("Lock in the FIRST action."))
        assertEquals("GO!", VoiceDirectorEngine.softenAllCaps("GO!"))
    }

    @Test
    fun `wav parser walks chunks instead of assuming a 44-byte header`() {
        val pcm = ShortArray(100) { (it * 100).toShort() }
        val listChunk = "LIST".toByteArray() + intLe(6) + "abcdef".toByteArray()
        val wav = wav(pcm, 22_050, channels = 1, extraChunk = listChunk)

        val parsed = VoiceDirectorEngine.parseWav(wav)
        assertNotNull(parsed)
        assertEquals(22_050, parsed!!.second)
        assertTrue(parsed.first.contentEquals(pcm))
    }

    @Test
    fun `wav parser downmixes stereo and rejects non-16-bit audio`() {
        val stereo = ShortArray(20) { if (it % 2 == 0) 1000 else 3000 }
        val parsed = VoiceDirectorEngine.parseWav(wav(stereo, 24_000, channels = 2))!!
        assertEquals(10, parsed.first.size)
        assertTrue(parsed.first.all { it.toInt() == 2000 })

        val eightBit = wav(ShortArray(10), 24_000, channels = 1, bits = 8)
        assertEquals(null, VoiceDirectorEngine.parseWav(eightBit))
        assertEquals(null, VoiceDirectorEngine.parseWav(ByteArray(10)))
    }

    private fun intLe(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun wav(pcm: ShortArray, rate: Int, channels: Int, bits: Int = 16, extraChunk: ByteArray = ByteArray(0)): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(12 + 24 + extraChunk.size + 8 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(4 + 24 + extraChunk.size + 8 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        b.put(extraChunk)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}
