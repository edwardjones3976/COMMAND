// Copied verbatim from the original AI Studio export (command.zip) for old-vs-new audio comparison only.
package com.example.oldchain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Professional Studio Audio Mastering Strip for Broadcast-Grade Dialogue.
 * Transforms raw speech and hybrid voice stems into punchy, articulate, studio-grade audio:
 * 1. 85 Hz Sub-Rumble High-Pass Filter (12 dB/oct Butterworth)
 * 2. 4.2 kHz Parametric Presence Exciter (+3.8 dB boost, Q=1.6)
 * 3. 6.8 kHz Studio De-Esser (fast sibilance notch filter)
 * 4. Dynamic Broadcast Vocal Compressor / Leveler (3:1 ratio, 15ms attack, 80ms release, auto makeup)
 * 5. Zero-Echo Downward Noise Gate (-48 dB floor, 0% artificial reverb)
 * 6. Studio Soft-Knee Brickwall Limiter (-0.3 dBFS ceiling)
 */
object BroadcastVocalMastering {

    data class MasteringTelemetry(
        val peakDbfs: Float,
        val rmsDbfs: Float,
        val gainReductionDb: Float,
        val isLimiterEngaged: Boolean,
        val masteringProfile: String = "CRISP BROADCAST STUDIO"
    )

    /**
     * Master a 16-bit mono or interleaved PCM buffer at the given sample rate.
     */
    fun masterPcmAudio(
        input: ShortArray,
        sampleRate: Int = 24000,
        presenceBoostDb: Float = 3.8f,
        compressorRatio: Float = 3.0f,
        targetMakeupGain: Float = 1.35f
    ): Pair<ShortArray, MasteringTelemetry> {
        if (input.isEmpty()) {
            return Pair(input, MasteringTelemetry(-96f, -96f, 0f, false))
        }

        val sr = sampleRate.coerceIn(8000, 48000).toDouble()
        val floatSamples = FloatArray(input.size) { input[it] / 32768.0f }

        // 1. 85 Hz 2nd-Order Butterworth High-Pass Filter (Rumble Cut)
        val hpCutoff = 85.0
        val omegaHp = 2.0 * PI * hpCutoff / sr
        val alphaHp = sin(omegaHp) / (2.0 * (1.0 / sqrt(2.0)))
        val cosHp = cos(omegaHp)
        val hpB0 = ((1.0 + cosHp) / 2.0).toFloat()
        val hpB1 = (-(1.0 + cosHp)).toFloat()
        val hpB2 = ((1.0 + cosHp) / 2.0).toFloat()
        val hpA0 = (1.0 + alphaHp).toFloat()
        val hpA1 = (-2.0 * cosHp).toFloat()
        val hpA2 = (1.0 - alphaHp).toFloat()

        var hpX1 = 0f; var hpX2 = 0f; var hpY1 = 0f; var hpY2 = 0f
        for (i in floatSamples.indices) {
            val x = floatSamples[i]
            val y = (hpB0 * x + hpB1 * hpX1 + hpB2 * hpX2 - hpA1 * hpY1 - hpA2 * hpY2) / hpA0
            hpX2 = hpX1; hpX1 = x
            hpY2 = hpY1; hpY1 = y
            floatSamples[i] = y
        }

        // 2. 4.2 kHz Parametric Presence Exciter (Peaking Bell EQ)
        val presenceFreq = 4200.0.coerceAtMost(sr * 0.45)
        val presenceQ = 1.6
        val aPresence = 10.0.pow(presenceBoostDb.toDouble() / 40.0)
        val omegaPresence = 2.0 * PI * presenceFreq / sr
        val alphaPresence = sin(omegaPresence) / (2.0 * presenceQ)
        val cosPresence = cos(omegaPresence)

        val presB0 = (1.0 + alphaPresence * aPresence).toFloat()
        val presB1 = (-2.0 * cosPresence).toFloat()
        val presB2 = (1.0 - alphaPresence * aPresence).toFloat()
        val presA0 = (1.0 + alphaPresence / aPresence).toFloat()
        val presA1 = (-2.0 * cosPresence).toFloat()
        val presA2 = (1.0 - alphaPresence / aPresence).toFloat()

        var presX1 = 0f; var presX2 = 0f; var presY1 = 0f; var presY2 = 0f
        for (i in floatSamples.indices) {
            val x = floatSamples[i]
            val y = (presB0 * x + presB1 * presX1 + presB2 * presX2 - presA1 * presY1 - presA2 * presY2) / presA0
            presX2 = presX1; presX1 = x
            presY2 = presY1; presY1 = y
            floatSamples[i] = y
        }

        // 3. 6.8 kHz De-Esser Notch Filter (Sibilance Control)
        val deEsserFreq = 6800.0.coerceAtMost(sr * 0.45)
        if (sr >= 16000) {
            val deEsserQ = 2.4
            val deEsserCutDb = -4.0
            val aDeEss = 10.0.pow(deEsserCutDb / 40.0)
            val omegaDeEss = 2.0 * PI * deEsserFreq / sr
            val alphaDeEss = sin(omegaDeEss) / (2.0 * deEsserQ)
            val cosDeEss = cos(omegaDeEss)

            val deB0 = (1.0 + alphaDeEss * aDeEss).toFloat()
            val deB1 = (-2.0 * cosDeEss).toFloat()
            val deB2 = (1.0 - alphaDeEss * aDeEss).toFloat()
            val deA0 = (1.0 + alphaDeEss / aDeEss).toFloat()
            val deA1 = (-2.0 * cosDeEss).toFloat()
            val deA2 = (1.0 - alphaDeEss / aDeEss).toFloat()

            var deX1 = 0f; var deX2 = 0f; var deY1 = 0f; var deY2 = 0f
            for (i in floatSamples.indices) {
                val x = floatSamples[i]
                val y = (deB0 * x + deB1 * deX1 + deB2 * deX2 - deA1 * deY1 - deA2 * deY2) / deA0
                deX2 = deX1; deX1 = x
                deY2 = deY1; deY1 = y
                floatSamples[i] = y
            }
        }

        // 4. Dynamic Broadcast Vocal Compressor / Leveler (RMS sidechain with attack/release)
        val compThreshold = 0.22f // ~ -13 dBFS
        val attackCoeff = exp(-1.0 / (sr * 0.015)).toFloat() // 15 ms
        val releaseCoeff = exp(-1.0 / (sr * 0.080)).toFloat() // 80 ms
        var envelope = 0f
        var maxGainReduction = 0f

        val gateThreshold = 0.0039f // ~ -48 dBFS downward gate

        for (i in floatSamples.indices) {
            val absSample = abs(floatSamples[i])

            // Envelope follower
            envelope = if (absSample > envelope) {
                attackCoeff * envelope + (1f - attackCoeff) * absSample
            } else {
                releaseCoeff * envelope + (1f - releaseCoeff) * absSample
            }

            // Downward acoustic gate for dead-silent room floor (zero echo)
            if (envelope < gateThreshold) {
                floatSamples[i] *= (envelope / gateThreshold).coerceIn(0f, 1f)
                continue
            }

            // Compressor gain curve
            var gain = 1.0f
            if (envelope > compThreshold) {
                val excess = envelope - compThreshold
                gain = 1.0f / (1.0f + (excess * (compressorRatio - 1.0f)))
                val reductionDb = -20f * log10(gain.coerceAtLeast(0.0001f))
                if (reductionDb > maxGainReduction) maxGainReduction = reductionDb
            }

            // Apply makeup gain
            floatSamples[i] *= (gain * targetMakeupGain)
        }

        // 5. Studio Soft-Knee Brickwall Limiter (-0.3 dBFS ceiling = ~0.965)
        val limitCeiling = 0.965f
        var peakVal = 0f
        var sumSquares = 0.0
        var limiterEngaged = false
        val output = ShortArray(floatSamples.size)

        for (i in floatSamples.indices) {
            var sample = floatSamples[i]
            val absVal = abs(sample)

            if (absVal > limitCeiling) {
                limiterEngaged = true
                val sign = if (sample >= 0f) 1f else -1f
                val over = absVal - limitCeiling
                // Soft saturation curve: limitCeiling + tanh-like saturation of overage
                val compressedOver = over / (1f + over * 2.5f)
                sample = sign * min(limitCeiling + compressedOver * 0.03f, 0.999f)
            }

            val finalInt = (sample * 32767.0f).toInt().coerceIn(-32768, 32767)
            output[i] = finalInt.toShort()

            val normalizedAbs = abs(sample)
            if (normalizedAbs > peakVal) peakVal = normalizedAbs
            sumSquares += (sample * sample)
        }

        val rms = sqrt(sumSquares / floatSamples.size.coerceAtLeast(1)).toFloat()
        val peakDbfs = if (peakVal > 0.00001f) 20f * log10(peakVal) else -96f
        val rmsDbfs = if (rms > 0.00001f) 20f * log10(rms) else -96f

        val telemetry = MasteringTelemetry(
            peakDbfs = peakDbfs.coerceIn(-96f, 0f),
            rmsDbfs = rmsDbfs.coerceIn(-96f, 0f),
            gainReductionDb = maxGainReduction,
            isLimiterEngaged = limiterEngaged,
            masteringProfile = "CRISP BROADCAST STUDIO • 4.2kHz PRESENCE • ZERO ECHO"
        )

        return Pair(output, telemetry)
    }

    /**
     * Crossfade two 16-bit PCM buffers with a 25ms equal-power sine/cosine curve.
     */
    fun crossfadePcmBuffers(
        first: ShortArray,
        second: ShortArray,
        sampleRate: Int = 24000,
        crossfadeMs: Int = 25
    ): ShortArray {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first

        val fadeSamples = ((sampleRate * crossfadeMs) / 1000).coerceIn(16, min(first.size, second.size) / 2)
        val totalSize = first.size + second.size - fadeSamples
        val result = ShortArray(totalSize)

        // Copy first non-overlapping section
        val firstLeadSize = first.size - fadeSamples
        System.arraycopy(first, 0, result, 0, firstLeadSize)

        // Equal-power crossfade region
        for (i in 0 until fadeSamples) {
            val progress = i.toFloat() / fadeSamples
            val fadeOut = cos(progress * (PI / 2.0)).toFloat()
            val fadeIn = sin(progress * (PI / 2.0)).toFloat()

            val s1 = first[firstLeadSize + i].toFloat()
            val s2 = second[i].toFloat()
            val blended = (s1 * fadeOut + s2 * fadeIn).toInt().coerceIn(-32768, 32767)
            result[firstLeadSize + i] = blended.toShort()
        }

        // Copy remaining second buffer
        val secondTailSize = second.size - fadeSamples
        System.arraycopy(second, fadeSamples, result, first.size, secondTailSize)

        return result
    }
}
