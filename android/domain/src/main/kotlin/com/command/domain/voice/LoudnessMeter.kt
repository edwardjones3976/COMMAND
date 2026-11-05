package com.command.domain.voice

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Loudness as ITU-R BS.1770 measures it, for one channel: K-weighting, 400 ms blocks overlapping by 75%, an absolute gate at -70 LUFS and a
 * relative gate 10 LU under the mean of what passed it. The filters are worked out for the sample rate from the analog prototype, because
 * the speech engines write at 16 to 48 kHz and the standard only tabulates 48 kHz.
 *
 * A line too short to fill a block is measured as one block.
 */
object LoudnessMeter {
    private const val BLOCK_SECONDS = 0.4
    private const val STEP_SECONDS = 0.1
    private const val ABSOLUTE_GATE_LUFS = -70.0
    private const val RELATIVE_GATE_LU = -10.0

    /** A second-order section, with a0 = 1. */
    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** The first stage of K-weighting, the high shelf that models the head. */
    fun shelf(sampleRate: Int): Biquad {
        val k = tan(PI * 1681.974450955533 / sampleRate)
        val q = 0.7071752369554196
        val vh = 10.0.pow(3.999843853973347 / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            (vh + vb * k / q + k * k) / a0,
            2.0 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0,
            (1.0 - k / q + k * k) / a0,
        )
    }

    /** The second stage, the RLB high-pass. */
    fun highPass(sampleRate: Int): Biquad {
        val k = tan(PI * 38.13547087602444 / sampleRate)
        val q = 0.5003270373238773
        val a0 = 1.0 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    /** Integrated loudness of [samples] (full scale = 1) in LUFS, or negative infinity when no block passes the absolute gate. */
    fun integratedLufs(samples: FloatArray, sampleRate: Int): Double {
        if (samples.isEmpty() || sampleRate <= 0) return Double.NEGATIVE_INFINITY
        val weighted = kWeighted(samples, sampleRate)
        val blocks = blockPowers(weighted, sampleRate)
        val aboveAbsolute = blocks.filter { lufs(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return Double.NEGATIVE_INFINITY
        val relativeGate = lufs(aboveAbsolute.average()) + RELATIVE_GATE_LU
        val passed = aboveAbsolute.filter { lufs(it) > relativeGate }
        return lufs((if (passed.isEmpty()) aboveAbsolute else passed).average())
    }

    private fun lufs(power: Double): Double = if (power > 0.0) -0.691 + 10.0 * log10(power) else Double.NEGATIVE_INFINITY

    private fun kWeighted(samples: FloatArray, sampleRate: Int): DoubleArray {
        val out = DoubleArray(samples.size) { samples[it].toDouble() }
        filter(out, shelf(sampleRate))
        filter(out, highPass(sampleRate))
        return out
    }

    private fun filter(x: DoubleArray, f: Biquad) {
        var z1 = 0.0
        var z2 = 0.0
        for (i in x.indices) {
            val input = x[i]
            val y = f.b0 * input + z1
            z1 = f.b1 * input - f.a1 * y + z2
            z2 = f.b2 * input - f.a2 * y
            x[i] = y
        }
    }

    /** Mean power of each block, from a running sum of squares. */
    private fun blockPowers(x: DoubleArray, sampleRate: Int): List<Double> {
        val block = (sampleRate * BLOCK_SECONDS).toInt().coerceAtLeast(1)
        val step = (sampleRate * STEP_SECONDS).toInt().coerceAtLeast(1)
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i] * x[i]
        if (x.size < block) return listOf(prefix[x.size] / x.size)
        val powers = ArrayList<Double>()
        var start = 0
        while (start + block <= x.size) {
            powers += (prefix[start + block] - prefix[start]) / block
            start += step
        }
        return powers
    }
}
