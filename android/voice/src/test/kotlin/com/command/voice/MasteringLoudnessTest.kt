package com.command.voice

import com.command.domain.dials.DialEffects
import com.command.domain.voice.LoudnessMeter
import com.command.domain.voice.SpeechLevel
import com.command.domain.voice.SpeechUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** What the alarm chain promises: the loudness of the alert intensity, and nothing over the ceiling. Whether it is loud enough to wake someone is for an ear on a speaker. */
class MasteringLoudnessTest {

    private val rate = 24_000

    /** The signal of VoiceAudioTest: a 140 Hz voice with harmonics, in syllable bursts with pauses between. */
    private fun speechLike(amplitude: Double, seconds: Double = 3.0, seed: Int = 7, sampleRate: Int = rate): ShortArray {
        val rnd = Random(seed)
        return ShortArray((sampleRate * seconds).toInt()) { i ->
            val t = i.toDouble() / sampleRate
            val syllable = (t * 4.0) % 1.0
            val env = if (syllable < 0.7) sin(PI * syllable / 0.7) else 0.0
            val voice = sin(2 * PI * 140 * t) * 0.6 + sin(2 * PI * 280 * t) * 0.25 + sin(2 * PI * 1200 * t) * 0.1
            ((voice + (rnd.nextDouble() - 0.5) * 0.05) * env * amplitude).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
    }

    private fun floats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }

    private fun lufs(pcm: ShortArray, sampleRate: Int = rate) = LoudnessMeter.integratedLufs(floats(pcm), sampleRate)

    /** An 8x interpolating peak meter of this test's own (twice the oversampling of the limiter it checks). */
    private fun truePeakDb(pcm: ShortArray): Double {
        val taps = 32
        val x = floats(pcm)
        var peak = 0.0
        for (phase in 0 until 8) {
            val frac = phase / 8.0
            val kernel = DoubleArray(2 * taps) { j ->
                val t = (j - taps + 1) - frac
                if (t == 0.0) 1.0 else sin(PI * t) / (PI * t) * (0.5 + 0.5 * cos(PI * t / taps))
            }
            for (i in x.indices) {
                var acc = 0.0
                for (j in kernel.indices) {
                    val idx = i + j - taps + 1
                    if (idx in x.indices) acc += x[idx] * kernel[j]
                }
                peak = max(peak, abs(acc))
            }
        }
        return 20 * log10(peak.coerceAtLeast(1e-9))
    }

    @Test
    fun `the alarm profile masters to the loudness the dial says, at every intensity`() {
        (-1..7).forEach { intensity ->
            assertEquals(
                "at intensity $intensity",
                DialEffects.loudnessTargetLufs(intensity).toFloat(),
                VoiceMastering.alarmProfile(SpeechLevel(intensity)).targetLufs,
                0f,
            )
        }
    }

    @Test
    fun `the targets of the alert intensity are the plan's`() {
        val targets = (1..5).map { VoiceMastering.alarmProfile(SpeechLevel(it)).targetLufs }
        assertEquals(listOf(-16f, -16f, -16f, -15f, -14f), targets)
        assertEquals(3f, VoiceMastering.alarmProfile(SpeechLevel(5, 3f)).trimDb, 0f)
    }

    @Test
    fun `speech lands within 1 LU of the target at every intensity and input level, under -1 dBTP`() {
        for (intensity in 1..5) {
            for (amplitude in listOf(1_500.0, 12_000.0, 32_767.0)) {
                val profile = VoiceMastering.alarmProfile(SpeechLevel(intensity))
                val out = VoiceMastering.master(speechLike(amplitude), rate, profile).pcm
                assertEquals("intensity $intensity, amplitude $amplitude", profile.targetLufs.toDouble(), lufs(out), 1.0)
                assertTrue("true peak ${truePeakDb(out)} at intensity $intensity, amplitude $amplitude", truePeakDb(out) <= -1.0)
            }
        }
    }

    @Test
    fun `the loudness holds at the rates speech engines write`() {
        for (sampleRate in listOf(16_000, 22_050, 44_100)) {
            val out = VoiceMastering.master(speechLike(12_000.0, sampleRate = sampleRate), sampleRate, VoiceMastering.Profile.ALARM(-14f)).pcm
            assertEquals("at $sampleRate", -14.0, lufs(out, sampleRate), 1.0)
            assertTrue(truePeakDb(out) <= -1.0)
        }
    }

    @Test
    fun `the trim goes on top of the target and stays under the ceiling`() {
        val input = speechLike(12_000.0)
        val levels = listOf(0f, 3f, 5f, 8f).map { trim ->
            val out = VoiceMastering.master(input, rate, VoiceMastering.Profile.ALARM(-14f, trim)).pcm
            assertTrue("trim $trim true peak ${truePeakDb(out)}", truePeakDb(out) <= -1.0)
            lufs(out)
        }
        assertEquals(-14.0 + 3.0, levels[1], 1.0)
        // The largest trims can run into the ceiling on a signal this dense (about -11 LUFS here): they stop gaining, and never lose.
        levels.zipWithNext().forEach { (quieter, louder) -> assertTrue("a larger trim must not be quieter: $levels", louder >= quieter - 0.1) }
        assertTrue("a trim is heard: $levels", levels.last() > levels.first() + 2.5)
    }

    /** Speech with sibilants: bursts of noise high in the band, which is where peaks fall between the samples. */
    private fun sibilant(sampleRate: Int, seed: Int = 5): ShortArray {
        val rnd = Random(seed)
        val voice = speechLike(9_000.0, sampleRate = sampleRate)
        var previous = 0.0
        return ShortArray(voice.size) { i ->
            val t = i.toDouble() / sampleRate
            val white = rnd.nextDouble() - 0.5
            val high = white - previous // a first difference: most of its energy is near the top of the band
            previous = white
            val burst = if ((t * 4.0) % 1.0 in 0.7..0.95) 30_000.0 else 0.0
            (voice[i] + high * burst).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
    }

    @Test
    fun `sibilants stay under -1 dBTP with a trim at the rates speech engines write`() {
        for (sampleRate in listOf(16_000, 24_000)) {
            for (trim in listOf(0f, 3f, 8f)) {
                val out = VoiceMastering.master(sibilant(sampleRate), sampleRate, VoiceMastering.Profile.ALARM(-14f, trim)).pcm
                assertTrue("$sampleRate Hz, trim $trim: ${truePeakDb(out)} dBTP", truePeakDb(out) <= -1.0)
            }
        }
    }

    @Test
    fun `speech with sibilants still reaches its loudness, every intensity at least as loud as the one below it`() {
        for (sampleRate in listOf(16_000, 24_000)) {
            for (seed in listOf(5, 6, 7)) {
                val input = sibilant(sampleRate, seed)
                val levels = (1..5).map { lufs(VoiceMastering.master(input, sampleRate, VoiceMastering.alarmProfile(SpeechLevel(it))).pcm, sampleRate) }
                listOf(-16.0, -16.0, -16.0, -15.0, -14.0).zip(levels).forEach { (target, level) ->
                    assertEquals("$sampleRate Hz, seed $seed: $levels", target, level, 1.0)
                }
                assertTrue("$sampleRate Hz, seed $seed: $levels", levels[4] >= levels[0])
            }
        }
    }

    @Test
    fun `a hot tail after a silence too short for the meter to hear is still held under the ceiling`() {
        // 400 ms of silence fills the only whole block, so the line measures as nothing, and the 90 ms after it is at full scale.
        val input = ShortArray(rate * 490 / 1000) { i ->
            if (i < rate * 400 / 1000) 0 else (32_767 * sin(2 * PI * 3_000.0 * i / rate)).toInt().toShort()
        }
        val out = VoiceMastering.master(input, rate, VoiceMastering.Profile.ALARM(-14f)).pcm
        assertTrue("${truePeakDb(out)} dBTP", truePeakDb(out) <= -1.0)
    }

    @Test
    fun `a target the ceiling makes impossible does not drive the pauses up without limit`() {
        // Speech over a noise floor at -60 dBFS, ending in a second of that floor alone.
        val rnd = Random(3)
        val speech = speechLike(12_000.0)
        val input = ShortArray(speech.size + rate) { i ->
            ((if (i < speech.size) speech[i].toInt() else 0) + (rnd.nextDouble() - 0.5) * 113).toInt().toShort()
        }
        val reachable = VoiceMastering.master(input, rate, VoiceMastering.Profile.ALARM(-14f)).pcm
        val impossible = VoiceMastering.master(input, rate, VoiceMastering.Profile.ALARM(-14f, 8f)).pcm
        fun floorDb(pcm: ShortArray): Double {
            val tail = pcm.takeLast(rate / 2)
            return 10 * log10(tail.sumOf { (it / 32768.0) * (it / 32768.0) } / tail.size)
        }
        val raised = floorDb(impossible) - floorDb(reachable)
        // Measured: 15.3 dB. Loudness comes first, so this is what a target out of reach costs; the bound is that it costs no more.
        assertTrue("the floor rose $raised dB over the untrimmed line", raised <= 16.0)
    }

    @Test
    fun `the pre-limiter is in the chain, so the line reaches its loudness with its peaks well under the ceiling`() {
        val out = VoiceMastering.master(speechLike(12_000.0), rate, VoiceMastering.Profile.ALARM(-16f)).pcm
        val crest = truePeakDb(out) - lufs(out)
        assertTrue("true peak is $crest dB over the loudness", crest <= CREST_LIMIT)
    }

    @Test
    fun `an alarm is louder than the preview of the same line, at the lowest intensity too`() {
        val input = speechLike(12_000.0)
        val preview = lufs(VoiceMastering.master(input, rate, VoiceMastering.Profile.PREVIEW()).pcm)
        val quietestAlarm = lufs(VoiceMastering.master(input, rate, VoiceMastering.alarmProfile(SpeechLevel(1))).pcm)
        assertTrue("preview $preview, alarm $quietestAlarm", quietestAlarm > preview + 1.0)
    }

    @Test
    fun `the preview profile is exactly today's boost`() {
        val input = speechLike(12_000.0)
        assertTrue(VoiceMastering.master(input, rate, 3f).pcm.contentEquals(VoiceMastering.master(input, rate, VoiceMastering.Profile.PREVIEW(3f)).pcm))
    }

    @Test
    fun `the pre-limiter leaves what is under -12 dBFS alone and holds 4 to 1 above it`() {
        fun tone(db: Double) = FloatArray(rate) { (10.0.pow20(db) * sin(2 * PI * 1000.0 * it / rate)).toFloat() }
        fun settledPeakDb(x: FloatArray): Double {
            VoiceMastering.preLimit(x, rate)
            return 20 * log10(x.takeLast(rate / 4).maxOf { abs(it) }.toDouble())
        }
        assertEquals(-20.0, settledPeakDb(tone(-20.0)), 0.1)
        // The envelope of a sine rides under its peak, so the settled level is checked against a range around -12 + 8/4.
        val hot = settledPeakDb(tone(-4.0))
        assertTrue("-4 dBFS settled at $hot, 4:1 above -12 would be about -10", hot in -11.5..-8.0)
    }

    @Test
    fun `the pre-limiter closes in about a millisecond and lets go in about 50`() {
        val x = FloatArray(rate) { if (it < rate / 2) 0.8f else 0.1f }
        VoiceMastering.preLimit(x, rate)
        val settled = x[rate / 2 - 1]
        assertTrue("5 ms after the onset it has closed: ${x[rate * 5 / 1000]} vs $settled", abs(x[rate * 5 / 1000] - settled) < 0.05f)
        val afterRelease = x[rate / 2 + rate * 300 / 1000]
        assertEquals("300 ms after the drop it has let go", 0.1f, afterRelease, 0.002f)
        assertTrue("just after the drop it is still holding", x[rate / 2 + 1] < 0.09f)
    }

    @Test
    fun `silence stays silent and empty stays empty`() {
        val alarm = VoiceMastering.Profile.ALARM(-14f, 8f)
        assertTrue(VoiceMastering.master(ShortArray(4_800), rate, alarm).pcm.all { it.toInt() == 0 })
        assertEquals(0, VoiceMastering.master(ShortArray(0), rate, alarm).pcm.size)
    }

    @Test
    fun `an alarm gets its intensity and trim, and a preview only the trim as its boost`() {
        assertEquals(VoiceMastering.Profile.ALARM(-15f, 2f), profileFor(SpeechUsage.ALARM, SpeechLevel(4, 2f)))
        assertEquals(VoiceMastering.Profile.PREVIEW(2f), profileFor(SpeechUsage.PREVIEW, SpeechLevel(4, 2f)))
    }

    /** Measured: 10.7 dB with the pre-limiter, 12.3 without. */
    private val CREST_LIMIT = 11.5

    private fun Double.pow20(db: Double): Double = Math.pow(this, db / 20.0)
}
