package com.command.domain

import com.command.domain.schedule.RestWindow
import com.command.domain.model.RestKind
import com.command.domain.voice.LoudnessMeter
import com.command.domain.voice.QuietWindowPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The reference for these is the table of BS.1770 itself (the 48 kHz coefficients, annex 1), evaluated in the frequency domain, not the meter's
 * own filters.
 */
class LoudnessMeterTest {

    private class Sine(val hz: Double, val amplitude: Double, val phase: Double)

    /** Pink noise as a sum of sines: power per octave is equal, phases are random, so its exact spectrum is known. */
    private fun pinkPartials(top: Double, seed: Int = 11): List<Sine> {
        val rnd = Random(seed)
        val perOctave = 48
        val partials = ArrayList<Sine>()
        var hz = 20.0
        while (hz < top) {
            partials += Sine(hz, 0.02, rnd.nextDouble() * 2 * PI)
            hz *= 2.0.pow(1.0 / perOctave)
        }
        return partials
    }

    private fun render(partials: List<Sine>, rate: Int, seconds: Double): FloatArray {
        val out = FloatArray((rate * seconds).toInt())
        for (p in partials) {
            val w = 2 * PI * p.hz / rate
            for (i in out.indices) out[i] += (p.amplitude * sin(w * i + p.phase)).toFloat()
        }
        return out
    }

    private fun response(b: DoubleArray, a: DoubleArray, hz: Double, rate: Int): Double {
        val w = 2 * PI * hz / rate
        fun mag2(c: DoubleArray): Double {
            val re = c[0] + c[1] * cos(w) + c[2] * cos(2 * w)
            val im = c[1] * sin(w) + c[2] * sin(2 * w)
            return re * re + im * im
        }
        return mag2(b) / mag2(a)
    }

    /** What BS.1770's own table says a stationary sum of these sines measures. */
    private fun expectedLufs(partials: List<Sine>): Double {
        val power = partials.sumOf { p ->
            p.amplitude * p.amplitude / 2 *
                response(TABLE_SHELF_B, TABLE_SHELF_A, p.hz, 48_000) *
                response(TABLE_HP_B, TABLE_HP_A, p.hz, 48_000)
        }
        return -0.691 + 10 * log10(power)
    }

    @Test
    fun `pink noise measures within half an LU of the standard`() {
        val partials = pinkPartials(20_000.0)
        val measured = LoudnessMeter.integratedLufs(render(partials, 48_000, 4.0), 48_000)
        assertEquals(expectedLufs(partials), measured, 0.5)
    }

    @Test
    fun `pink noise measures the same at the rates speech engines write`() {
        val partials = pinkPartials(6_000.0)
        val reference = expectedLufs(partials)
        for (rate in listOf(16_000, 22_050, 24_000, 44_100, 48_000)) {
            assertEquals("at $rate Hz", reference, LoudnessMeter.integratedLufs(render(partials, rate, 4.0), rate), 0.5)
        }
    }

    @Test
    fun `the filters are the standard's at 48 kHz`() {
        val s = LoudnessMeter.shelf(48_000)
        val h = LoudnessMeter.highPass(48_000)
        val got = listOf(s.b0, s.b1, s.b2, s.a1, s.a2, h.b0, h.b1, h.b2, h.a1, h.a2)
        val table = listOf(TABLE_SHELF_B[0], TABLE_SHELF_B[1], TABLE_SHELF_B[2], TABLE_SHELF_A[1], TABLE_SHELF_A[2], 1.0, -2.0, 1.0, TABLE_HP_A[1], TABLE_HP_A[2])
        got.zip(table).forEach { (g, t) -> assertEquals(t, g, 1e-6) }
    }

    @Test
    fun `a 1 kHz sine reads where a mono meter should`() {
        val amplitude = 0.1
        val x = FloatArray(48_000 * 2) { (amplitude * sin(2 * PI * 1000.0 * it / 48_000)).toFloat() }
        // RMS is 3.01 dB under the peak, and K-weighting at 1 kHz cancels the -0.691.
        assertEquals(20 * log10(amplitude) - 3.01, LoudnessMeter.integratedLufs(x, 48_000), 0.1)
    }

    @Test
    fun `the relative gate leaves out what is 10 LU under the rest`() {
        val rate = 48_000
        val loud = FloatArray(rate * 2) { (0.1 * sin(2 * PI * 1000.0 * it / rate)).toFloat() }
        val quiet = FloatArray(rate * 8) { (0.005 * sin(2 * PI * 1000.0 * it / rate)).toFloat() }
        assertEquals(LoudnessMeter.integratedLufs(loud, rate), LoudnessMeter.integratedLufs(loud + quiet, rate), 0.5)
    }

    @Test
    fun `the absolute gate calls near silence nothing`() {
        val rate = 48_000
        assertEquals(Double.NEGATIVE_INFINITY, LoudnessMeter.integratedLufs(FloatArray(rate), rate), 0.0)
        val faint = FloatArray(rate) { (1e-4 * sin(2 * PI * 1000.0 * it / rate)).toFloat() }
        assertEquals(Double.NEGATIVE_INFINITY, LoudnessMeter.integratedLufs(faint, rate), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, LoudnessMeter.integratedLufs(FloatArray(0), rate), 0.0)
    }

    @Test
    fun `a word shorter than a block is still measured`() {
        val rate = 24_000
        val x = FloatArray(rate / 5) { (0.1 * sin(2 * PI * 1000.0 * it / rate)).toFloat() }
        assertEquals(20 * log10(0.1) - 3.01, LoudnessMeter.integratedLufs(x, rate), 0.5)
    }

    @Test
    fun `the quiet window follows the person's rest windows and not the old fixed hours`() = runBlocking {
        val windows = listOf(RestWindow(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(14, 0), RestKind.values().first()))
        val policy = QuietWindowPolicy.of({ windows }, { ZoneOffset.UTC })
        fun monday(h: Int) = ZonedDateTime.of(2026, 11, 2, h, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertTrue(policy.isQuiet(monday(13)))
        assertFalse("23:30 was quiet under the fixed hours; it is not one of this person's windows", policy.isQuiet(monday(23)))
        assertFalse(QuietWindowPolicy.NEVER.isQuiet(monday(13)))
    }

    private companion object {
        val TABLE_SHELF_B = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
        val TABLE_SHELF_A = doubleArrayOf(1.0, -1.69065929318241, 0.73248077421585)
        val TABLE_HP_B = doubleArrayOf(1.0, -2.0, 1.0)
        val TABLE_HP_A = doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621)
    }
}
