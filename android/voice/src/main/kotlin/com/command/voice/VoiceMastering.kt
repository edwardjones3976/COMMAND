package com.command.voice

import com.command.domain.voice.LoudnessMeter
import com.command.domain.voice.SpeechLevel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Light, transparent mastering for one synthesized speech line.
 *
 * The whole line is rendered before playback, so every level decision is made with the full
 * buffer in view: one global gain, no pumping, and no clipping. The goal is "clean and evenly
 * leveled", matched to the reference recordings in res/raw (about -21 to -24 LUFS). It is not
 * meant to change the character of the voice; that comes from the voice and its style prompt.
 *
 * Pure Kotlin (no Android imports) so it can be unit-tested on the JVM.
 */
object VoiceMastering {

    /** Speech-active RMS target. Matches the reference recordings. */
    const val TARGET_SPEECH_DBFS = -22f

    /** Hard ceiling for any sample after mastering. */
    const val PEAK_CEILING_DBFS = -1f

    /** Headroom for 16-bit rounding and the edge fade after limiting, so true peaks stay under the ceiling. */
    private const val TRUE_PEAK_MARGIN_DB = 0.1f

    /** The limiter re-measures after each pass; a second pass catches overshoot where gain moved under a peak. */
    private const val LIMITER_PASSES = 3

    /** One side of the 4x true-peak interpolator (32 taps in all, Hann-windowed sinc). */
    private const val TRUE_PEAK_TAPS = 16

    /** Interpolation kernels for the points 1/4, 1/2 and 3/4 of the way to the next sample. */
    private val truePeakKernels: List<FloatArray> = (1..3).map { phase ->
        val frac = phase / 4.0
        FloatArray(2 * TRUE_PEAK_TAPS) { j ->
            val t = (j - TRUE_PEAK_TAPS + 1) - frac
            val window = 0.5 + 0.5 * cos(PI * t / TRUE_PEAK_TAPS)
            (sin(PI * t) / (PI * t) * window).toFloat()
        }
    }

    /** No interpolated point can exceed the nearby sample peak times this, so quieter stretches are skipped. */
    private val truePeakKernelGain: Float = truePeakKernels.maxOf { k -> k.sumOf { abs(it).toDouble() } }.toFloat()

    private const val HIGH_PASS_HZ = 80.0
    private const val MAX_NORMALIZE_GAIN_DB = 30f

    /** Upper bound for the boost slider (150% maps to +5 dB; allow headroom for custom packs). */
    const val MAX_BOOST_DB = 8f
    private const val EDGE_FADE_MS = 5

    /** Fast pre-limiter of the alarm chain: above this level the signal rises a quarter as fast. */
    internal const val PRE_LIMIT_THRESHOLD_DBFS = -12f
    internal const val PRE_LIMIT_RATIO = 4f
    private const val PRE_LIMIT_ATTACK_S = 0.001
    private const val PRE_LIMIT_RELEASE_S = 0.050

    /** Most gain the alarm chain will add looking for its target: a line this far below it is a very quiet engine, not silence. */
    private const val MAX_ALARM_GAIN_DB = 40.0
    private const val ALARM_TOLERANCE_LU = 0.2
    private const val ALARM_SEARCH_STEPS = 8

    /** How one line is levelled. */
    sealed interface Profile {
        /** Something the person asked to hear: speech level [TARGET_SPEECH_DBFS] plus [boostDb]. */
        data class PREVIEW(val boostDb: Float = 0f) : Profile

        /** A ringing alarm: [targetLufs] (BS.1770, gated) plus the pack's [trimDb] (0 to [MAX_BOOST_DB]), under the -1 dBTP ceiling. */
        data class ALARM(val targetLufs: Float, val trimDb: Float = 0f) : Profile
    }

    /** The alarm profile of an alert intensity: -16 LUFS at 1 to 3, -15 at 4, -14 at 5, plus the pack's trim. */
    fun alarmProfile(level: SpeechLevel): Profile.ALARM {
        val target = when {
            level.alertIntensity >= 5 -> -14f
            level.alertIntensity == 4 -> -15f
            else -> -16f
        }
        return Profile.ALARM(target, level.trimDb)
    }

    data class Result(
        val pcm: ShortArray,
        val speechRmsDbfs: Float,
        val peakDbfs: Float
    )

    fun master(input: ShortArray, sampleRate: Int, profile: Profile): Result = when (profile) {
        is Profile.PREVIEW -> master(input, sampleRate, profile.boostDb)
        is Profile.ALARM -> masterAlarm(input, sampleRate, profile)
    }

    /**
     * Search for the one gain that lands on the target once the pre-limiter and the true-peak limiter have had their say: both take level away
     * from a hot line, which is why a single measurement is not enough (the old chain lost 5 dB to its limiter this way). Each step corrects
     * by what the last one measured. Loudness comes first, so a line is driven as hard as its target needs, up to [MAX_ALARM_GAIN_DB]; a target
     * the ceiling makes impossible ends at the line closest to it, at the cost of louder breaths and hiss.
     */
    private fun masterAlarm(input: ShortArray, sampleRate: Int, profile: Profile.ALARM): Result {
        if (input.isEmpty()) return Result(input, -96f, -96f)
        val sr = sampleRate.coerceIn(8000, 96000)
        val x = FloatArray(input.size) { input[it] / 32768f }
        highPass(x, sr, HIGH_PASS_HZ)
        compress(x, sr)

        val target = profile.targetLufs + profile.trimDb.coerceIn(0f, MAX_BOOST_DB).toDouble()
        val start = LoudnessMeter.integratedLufs(x, sr)
        var best = x
        if (start.isFinite()) {
            var gainDb = (target - start).coerceIn(-MAX_ALARM_GAIN_DB, MAX_ALARM_GAIN_DB)
            var bestError = Double.MAX_VALUE
            for (step in 0 until ALARM_SEARCH_STEPS) {
                val gain = dbToLin(gainDb.toFloat())
                val y = FloatArray(x.size) { x[it] * gain }
                preLimit(y, sr)
                limit(y, sr, dbToLin(PEAK_CEILING_DBFS - TRUE_PEAK_MARGIN_DB))
                val error = target - LoudnessMeter.integratedLufs(y, sr)
                if (abs(error) < bestError) {
                    bestError = abs(error)
                    best = y
                }
                if (abs(error) <= ALARM_TOLERANCE_LU) break
                // The limiters give back only part of a change in gain, so a shortfall is pushed harder than it measures.
                val next = (gainDb + error * if (error > 0) 3.0 else 1.0).coerceIn(-MAX_ALARM_GAIN_DB, MAX_ALARM_GAIN_DB)
                if (next == gainDb) break
                gainDb = next
            }
        }
        // Whatever was chosen, a line the meter could not hear included (a short one can hide its loud part from every whole block).
        holdUnderCeiling(best)
        return finish(best, sr)
    }

    /**
     * The last word on the ceiling: the peaks between the samples, read finer than the limiter reads them (sibilants near the top of the band
     * get past its 4x detector), and one small gain for the whole line if any is over.
     */
    private fun holdUnderCeiling(x: FloatArray) {
        val peak = finePeak(x)
        val ceiling = dbToLin(PEAK_CEILING_DBFS - TRUE_PEAK_MARGIN_DB)
        if (peak > ceiling) {
            val gain = ceiling / peak
            for (i in x.indices) x[i] *= gain
        }
    }

    /** The largest absolute value of the waveform, on a grid eight times finer than the samples (windowed sinc, 64 taps). */
    private fun finePeak(x: FloatArray): Float {
        val taps = 32
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        for (phase in 1 until 8) {
            val frac = phase / 8.0
            val kernel = FloatArray(2 * taps) { j ->
                val t = (j - taps + 1) - frac
                (sin(PI * t) / (PI * t) * (0.5 + 0.5 * cos(PI * t / taps))).toFloat()
            }
            for (i in x.indices) {
                var acc = 0f
                for (j in kernel.indices) {
                    val idx = i + j - taps + 1
                    if (idx in x.indices) acc += x[idx] * kernel[j]
                }
                peak = max(peak, abs(acc))
            }
        }
        return peak
    }

    /**
     * The fast pre-limiter: 4:1 above -12 dBFS, 1 ms attack, 50 ms release. It takes the tops off the syllables so that the true-peak limiter
     * behind it, whose job is only the ceiling, has little left to do and the line keeps its level.
     */
    internal fun preLimit(x: FloatArray, sampleRate: Int) {
        val attack = exp(-1.0 / (sampleRate * PRE_LIMIT_ATTACK_S)).toFloat()
        val release = exp(-1.0 / (sampleRate * PRE_LIMIT_RELEASE_S)).toFloat()
        var env = 0f
        for (i in x.indices) {
            val a = abs(x[i])
            val coeff = if (a > env) attack else release
            env = coeff * env + (1f - coeff) * a
            val over = linToDb(env) - PRE_LIMIT_THRESHOLD_DBFS
            if (over > 0f) x[i] *= dbToLin(-over * (1f - 1f / PRE_LIMIT_RATIO))
        }
    }

    /**
     * @param boostDb extra loudness above the target (the voice pack's boost slider). Peaks that
     * the boost pushes past the ceiling are caught by a smooth limiter rather than clipped, so a
     * boosted line gets louder and denser without distorting.
     */
    fun master(input: ShortArray, sampleRate: Int, boostDb: Float = 0f): Result {
        if (input.isEmpty()) return Result(input, -96f, -96f)
        val sr = sampleRate.coerceIn(8000, 96000)
        val x = FloatArray(input.size) { input[it] / 32768f }

        highPass(x, sr, HIGH_PASS_HZ)
        compress(x, sr)

        // One global gain to the target (plus boost); the limiter then holds peaks under the ceiling.
        val level = speechRmsDbfs(x, sr)
        val targetDb = TARGET_SPEECH_DBFS + boostDb.coerceIn(0f, MAX_BOOST_DB)
        val wantedDb = if (level > -90f) (targetDb - level).coerceIn(-40f, MAX_NORMALIZE_GAIN_DB) else 0f
        val gain = dbToLin(wantedDb)
        for (i in x.indices) x[i] *= gain
        limit(x, sr, dbToLin(PEAK_CEILING_DBFS - TRUE_PEAK_MARGIN_DB))
        return finish(x, sr)
    }

    /** The edge fade, the rounding to 16 bits and the measurements of what comes out. */
    private fun finish(x: FloatArray, sr: Int): Result {
        val fade = (sr * EDGE_FADE_MS / 1000).coerceAtMost(x.size / 2)
        val out = ShortArray(x.size) { i ->
            val edge = when {
                fade == 0 -> 1f
                i < fade -> i.toFloat() / fade
                i >= x.size - fade -> (x.size - 1 - i).toFloat() / fade
                else -> 1f
            }
            (x[i] * edge * 32767f).roundToInt().coerceIn(-32767, 32767).toShort()
        }

        val outFloat = FloatArray(out.size) { out[it] / 32768f }
        return Result(
            pcm = out,
            speechRmsDbfs = speechRmsDbfs(outFloat, sr),
            peakDbfs = linToDb(outFloat.maxOf { abs(it) })
        )
    }

    /**
     * RMS of the frames that contain speech (20 ms frames within 30 dB of the loudest frame),
     * so pauses between words don't drag the measured level down.
     */
    fun speechRmsDbfs(x: FloatArray, sampleRate: Int): Float {
        val frame = (sampleRate / 50).coerceAtLeast(1)
        val powers = ArrayList<Double>()
        var i = 0
        while (i < x.size) {
            val end = minOf(i + frame, x.size)
            var sum = 0.0
            for (j in i until end) sum += x[j].toDouble() * x[j]
            powers.add(sum / (end - i))
            i = end
        }
        val loudest = powers.maxOrNull() ?: return -96f
        if (loudest <= 1e-10) return -96f
        val floor = max(loudest * 1e-3, 1e-6) // -30 dB relative, and never below -60 dBFS
        val active = powers.filter { it >= floor }
        if (active.isEmpty()) return -96f // every frame is under the -60 dBFS speech floor: silence
        val mean = active.average()
        return (10.0 * log10(mean)).toFloat().coerceAtLeast(-96f)
    }

    /** Per-window loudness (0..1) for driving the UI meter from the actual audio. */
    fun levelEnvelope(pcm: ShortArray, sampleRate: Int, windowMs: Int = 50): FloatArray {
        val window = (sampleRate * windowMs / 1000).coerceAtLeast(1)
        val count = (pcm.size + window - 1) / window
        return FloatArray(count) { w ->
            val start = w * window
            val end = minOf(start + window, pcm.size)
            var sum = 0.0
            for (j in start until end) {
                val s = pcm[j] / 32768.0
                sum += s * s
            }
            val db = 10.0 * log10((sum / (end - start)).coerceAtLeast(1e-10))
            ((db + 50.0) / 44.0).toFloat().coerceIn(0f, 1f) // -50 dBFS -> 0, -6 dBFS -> 1
        }
    }

    /**
     * Smooth peak limiter. The line is fully rendered, so it can look ahead: gain starts easing down
     * ~2 ms before a peak (backward pass) and recovers over ~80 ms after it (forward pass). Every
     * sample's gain is at or below what that sample needs, so nothing exceeds [ceiling], and the
     * gain never steps, so there is no clipping distortion or clicking. Peaks are measured on the
     * waveform between samples too (4x true peak), so the reconstructed peak stays under the ceiling.
     */
    private fun limit(x: FloatArray, sampleRate: Int, ceiling: Float) {
        val attack = 1f / (sampleRate * 0.002f).coerceAtLeast(1f)
        val release = 1f - exp(-1.0 / (sampleRate * 0.080)).toFloat()
        repeat(LIMITER_PASSES) {
            val peaks = truePeaks(x, ceiling)
            val needed = FloatArray(x.size) { if (peaks[it] > ceiling) ceiling / peaks[it] else 1f }
            if (needed.all { it >= 1f }) return
            for (i in x.size - 2 downTo 0) {
                needed[i] = minOf(needed[i], needed[i + 1] + (1f - needed[i + 1]) * attack)
            }
            var g = needed[0]
            for (i in x.indices) {
                g = minOf(needed[i], g + (1f - g) * release)
                x[i] *= g
            }
        }
    }

    /**
     * Per sample: the larger of |x[i]| and the waveform at i + 1/4, 1/2 and 3/4, reconstructed with
     * a windowed sinc (a 4x-oversampled true-peak meter). Stretches whose nearby samples are too quiet
     * to reach [ceiling] even after interpolation are skipped.
     */
    private fun truePeaks(x: FloatArray, ceiling: Float): FloatArray {
        val peaks = FloatArray(x.size) { abs(x[it]) }
        val block = 64
        val blockMax = FloatArray((x.size + block - 1) / block) { b ->
            var m = 0f
            for (j in b * block until minOf(x.size, (b + 1) * block)) m = max(m, peaks[j])
            m
        }
        for (i in x.indices) {
            val lo = (i - TRUE_PEAK_TAPS + 1).coerceAtLeast(0)
            val hi = (i + TRUE_PEAK_TAPS).coerceAtMost(x.size - 1)
            var nearby = 0f
            for (b in lo / block..hi / block) nearby = max(nearby, blockMax[b])
            if (nearby * truePeakKernelGain <= ceiling) continue
            for (kernel in truePeakKernels) {
                var acc = 0f
                for (j in kernel.indices) {
                    val idx = i + j - TRUE_PEAK_TAPS + 1
                    if (idx in x.indices) acc += x[idx] * kernel[j]
                }
                peaks[i] = max(peaks[i], abs(acc))
            }
        }
        return peaks
    }

    /** 2nd-order Butterworth high-pass (RBJ cookbook) to remove rumble and DC. */
    private fun highPass(x: FloatArray, sampleRate: Int, cutoffHz: Double) {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2.0 * sqrt(0.5))
        val c = cos(w0)
        val a0 = 1.0 + alpha
        val b0 = (1.0 + c) / 2.0 / a0
        val b1 = -(1.0 + c) / a0
        val b2 = (1.0 + c) / 2.0 / a0
        val a1 = -2.0 * c / a0
        val a2 = (1.0 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            x[i] = y.toFloat()
        }
    }

    /**
     * Gentle 2:1 compression on the loudest syllables only (threshold sits 4 dB above the line's
     * own speech level). This evens out shouted words so normalization isn't held back by a few
     * peaks. Gain is computed in dB and smoothed, so there's no distortion or audible pumping.
     */
    private fun compress(x: FloatArray, sampleRate: Int) {
        val level = speechRmsDbfs(x, sampleRate)
        if (level <= -90f) return
        val thresholdDb = level + 4f
        val ratio = 2f
        val attack = exp(-1.0 / (sampleRate * 0.005)).toFloat()   // 5 ms
        val release = exp(-1.0 / (sampleRate * 0.150)).toFloat()  // 150 ms
        var env = 0f
        var gainDb = 0f
        for (i in x.indices) {
            val a = abs(x[i])
            env = if (a > env) attack * env + (1f - attack) * a else release * env + (1f - release) * a
            val envDb = linToDb(env)
            val targetDb = if (envDb > thresholdDb) -(envDb - thresholdDb) * (1f - 1f / ratio) else 0f
            val coeff = if (targetDb < gainDb) attack else release
            gainDb = coeff * gainDb + (1f - coeff) * targetDb
            x[i] *= dbToLin(gainDb)
        }
    }

    fun dbToLin(db: Float): Float = 10f.pow(db / 20f)
    fun linToDb(lin: Float): Float = if (lin > 1e-5f) 20f * log10(lin) else -96f
}
