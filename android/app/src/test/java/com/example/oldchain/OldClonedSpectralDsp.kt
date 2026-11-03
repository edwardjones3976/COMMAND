// Copied from the original AI Studio export (command.zip, VoiceDirectorEngine.processClonedSpectralDsp and
// resolveClonedProfile) for old-vs-new audio comparison only. The DSP body is unchanged.
package com.example.oldchain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** The fields of the original ClonedVoiceAcousticProfile that the DSP reads. */
data class OldProfile(
    val targetPitchFactor: Float,
    val f1FormantHz: Double,
    val f2FormantHz: Double,
    val formantQ1: Double,
    val formantQ2: Double,
    val vocalGritPercent: Int,
    val roomSlapbackMix: Float,
    val chestWarmthGain: Float,
    val airShelfGain: Float
)

object OldClonedSpectralDsp {

    /** resolveClonedProfile's drill-sergeant branch with Stone's grit (85, floored at 80). */
    val stone = OldProfile(
        targetPitchFactor = 0.84f,
        f1FormantHz = 480.0,
        f2FormantHz = 1380.0,
        formantQ1 = 2.6,
        formantQ2 = 3.4,
        vocalGritPercent = 85,
        roomSlapbackMix = 0.05f,
        chestWarmthGain = 1.25f,
        airShelfGain = 0.95f
    )

    fun process(
        input: ShortArray,
        sampleRate: Int,
        profile: OldProfile,
        boostFactor: Float
    ): ShortArray {
        if (input.isEmpty()) return input

        // 1. Resample for target pitch transposition
        val pitchShifted = if (abs(profile.targetPitchFactor - 1.0f) > 0.02f) {
            val targetSize = ((input.size / profile.targetPitchFactor).toInt()).coerceAtLeast(1)
            val resampled = ShortArray(targetSize)
            for (i in 0 until targetSize) {
                val origPos = i * profile.targetPitchFactor
                val idx = origPos.toInt()
                val frac = origPos - idx
                val s1 = input[idx.coerceIn(0, input.size - 1)].toFloat()
                val s2 = input[(idx + 1).coerceIn(0, input.size - 1)].toFloat()
                resampled[i] = (s1 + frac * (s2 - s1)).toInt().toShort()
            }
            resampled
        } else {
            input
        }

        // 2. Dual Biquad Peaking Filters for F1 & F2 Resonances
        val f1 = profile.f1FormantHz.coerceIn(200.0, 1200.0)
        val f2 = profile.f2FormantHz.coerceIn(800.0, 3200.0)

        val omega1 = 2.0 * PI * f1 / sampleRate.toDouble()
        val alpha1 = sin(omega1) / (2.0 * profile.formantQ1)
        val b0_1 = alpha1; val b1_1 = 0.0; val b2_1 = -alpha1
        val a0_1 = 1.0 + alpha1; val a1_1 = -2.0 * cos(omega1); val a2_1 = 1.0 - alpha1

        val omega2 = 2.0 * PI * f2 / sampleRate.toDouble()
        val alpha2 = sin(omega2) / (2.0 * profile.formantQ2)
        val b0_2 = alpha2; val b1_2 = 0.0; val b2_2 = -alpha2
        val a0_2 = 1.0 + alpha2; val a1_2 = -2.0 * cos(omega2); val a2_2 = 1.0 - alpha2

        var x1_1 = 0.0; var x2_1 = 0.0; var y1_1 = 0.0; var y2_1 = 0.0
        var x1_2 = 0.0; var x2_2 = 0.0; var y1_2 = 0.0; var y2_2 = 0.0

        val delaySamples = (sampleRate * 0.028).toInt().coerceAtLeast(1)
        val delayBuffer = FloatArray(delaySamples)
        var delayIdx = 0

        val output = ShortArray(pitchShifted.size)
        val gain = boostFactor.coerceIn(0.6f, 1.8f) * 1.25f

        val gritNorm = (profile.vocalGritPercent / 100f).coerceIn(0f, 1f)

        for (i in pitchShifted.indices) {
            val inSample = pitchShifted[i].toDouble() / 32768.0

            // Stage 1: F1 Formant Peak
            val outF1 = (b0_1 / a0_1) * inSample + (b1_1 / a0_1) * x1_1 + (b2_1 / a0_1) * x2_1 -
                (a1_1 / a0_1) * y1_1 - (a2_1 / a0_1) * y2_1
            x2_1 = x1_1; x1_1 = inSample; y2_1 = y1_1; y1_1 = outF1

            // Stage 2: F2 Formant Peak
            val outF2 = (b0_2 / a0_2) * inSample + (b1_2 / a0_2) * x1_2 + (b2_2 / a0_2) * x2_2 -
                (a1_2 / a0_2) * y1_2 - (a2_2 / a0_2) * y2_2
            x2_2 = x1_2; x1_2 = inSample; y2_2 = y1_2; y2_2 = outF2

            // Chest warmth & air shelf shaping
            val chestHarmonic = inSample * profile.chestWarmthGain
            val airResonance = outF2 * profile.airShelfGain

            // Subharmonic grit injection (glottal vocal fry / overdrive)
            val gritSample = if (gritNorm > 0.1f) {
                val cyclePos = (i * 0.18f) % 1.0f
                val rasp = if (cyclePos < 0.35f) (inSample * inSample * inSample * 1.8) else inSample
                (inSample * (1.0 - gritNorm * 0.5) + rasp * (gritNorm * 0.5))
            } else {
                inSample
            }

            val mixed = (gritSample * 0.60 + chestHarmonic * 0.25 + outF1 * 0.45 + airResonance * 0.35) * gain

            // Soft-knee studio saturation
            val saturated = tanh(mixed).toFloat().coerceIn(-0.98f, 0.98f)

            // Room / slapback delay
            val delayed = delayBuffer[delayIdx]
            delayBuffer[delayIdx] = saturated
            delayIdx = (delayIdx + 1) % delaySamples

            val finalSample = (saturated + delayed * profile.roomSlapbackMix).coerceIn(-0.98f, 0.98f)
            output[i] = (finalSample * 32767.0f).toInt().toShort()
        }

        // Apply Crisp Broadcast Studio Mastering Suite (85Hz HP, 4.2kHz presence, 6.8kHz de-ess, 3.2:1 comp, gate, limiter)
        val (masteredPcm, _) = BroadcastVocalMastering.masterPcmAudio(
            input = output,
            sampleRate = sampleRate,
            presenceBoostDb = 4.2f,
            compressorRatio = 3.2f,
            targetMakeupGain = 1.38f
        )

        return masteredPcm
    }
}
