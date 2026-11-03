package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.R
import com.example.data.EqPreset
import com.example.data.VoiceModelType
import com.example.data.VoicePackEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

data class ClonedVoiceAcousticProfile(
    val id: String,
    val personaName: String,
    val genderIsFemale: Boolean,
    val targetPitchFactor: Float,
    val f0FundamentalHz: Double,
    val f1FormantHz: Double,
    val f2FormantHz: Double,
    val f3FormantHz: Double,
    val formantQ1: Double = 2.4,
    val formantQ2: Double = 3.2,
    val vocalGritPercent: Int,
    val speechRate: Float,
    val roomSlapbackMix: Float,
    val chestWarmthGain: Float,
    val airShelfGain: Float,
    val badgeLabel: String
)

data class VoicePlaybackState(
    val isSpeaking: Boolean = false,
    val activeVoicePackId: String? = null,
    val activeLine: String = "",
    val progressFraction: Float = 0f,
    val isAbComparing: Boolean = false,
    val activeAbSlot: String? = null, // "A" or "B"
    val spectrumBands: List<Float> = List(16) { 0.05f },
    val audioRouteNotice: String? = null,
    val activeDspBadge: String = "CRISP BROADCAST STUDIO • HYBRID ENGINE"
)

class VoiceDirectorEngine(
    private val context: Context,
    private val scope: CoroutineScope
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var simulationJob: Job? = null
    private var dspPlaybackJob: Job? = null
    private var abComparisonJob: Job? = null
    private var activeAudioTrack: AudioTrack? = null
    private var activeMediaPlayer: MediaPlayer? = null

    private val pendingFileRenders = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Exception) {
            isTtsReady = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null) {
                        pendingFileRenders.remove(utteranceId)?.complete(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null) {
                        pendingFileRenders.remove(utteranceId)?.complete(false)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId != null) {
                        pendingFileRenders.remove(utteranceId)?.complete(false)
                    }
                }
            })
        }
    }

    private fun isHeadphoneConnected(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }
        @Suppress("DEPRECATION")
        return audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
    }

    private fun isQuietHours(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 7
    }

    fun speakWithVoicePack(
        voicePack: VoicePackEntity,
        text: String,
        playAttentionChime: Boolean = false,
        headphoneOnlyMode: Boolean = false,
        quietHoursDucking: Boolean = false
    ): Boolean {
        simulationJob?.cancel()
        dspPlaybackJob?.cancel()
        abComparisonJob?.cancel()
        stopActiveAudioTrack()

        if (headphoneOnlyMode && !isHeadphoneConnected()) {
            _playbackState.value = VoicePlaybackState(
                isSpeaking = false,
                audioRouteNotice = "Playback paused: Headphone-Only Guard is active and no headphones are connected."
            )
            return false
        }

        val modelType = runCatching { VoiceModelType.valueOf(voicePack.voiceModelType) }
            .getOrDefault(VoiceModelType.USMC_MCRD_BARK)

        val formattedLine = formatForVoiceModel(text, modelType, voicePack.aggressionPercent)

        if (playAttentionChime || (modelType == VoiceModelType.USMC_MCRD_BARK && voicePack.aggressionPercent >= 70)) {
            if (modelType == VoiceModelType.USMC_MCRD_BARK || modelType == VoiceModelType.USMC_PARADE_CALLER) {
                playMcrdWhistleBlast(aggressionPercent = voicePack.aggressionPercent)
                triggerDrillInstructorHaptics()
            } else {
                playStudioTacticalChime()
                triggerTacticalHaptics()
            }
        }

        val duckingFactor = if (quietHoursDucking && isQuietHours()) 0.45f else 1.0f
        val boostFactor = (voicePack.volumeBoostPercent.toFloat() / 100f) * duckingFactor

        val profile = resolveClonedProfile(voicePack)
        val rawRes = resolveRawAudioRes(voicePack)

        val isCoreMasterStem = rawRes != null && (
            formattedLine.contains(voicePack.sampleQuote.take(15), ignoreCase = true) ||
            formattedLine.length < 55 ||
            formattedLine.contains("EYEBALLS", ignoreCase = true) ||
            formattedLine.contains("LISTEN UP", ignoreCase = true) ||
            formattedLine.contains("Take a breath", ignoreCase = true) ||
            formattedLine.contains("Hit that handshake", ignoreCase = true) ||
            formattedLine.contains("Commitment window", ignoreCase = true) ||
            formattedLine.contains("Eyes up", ignoreCase = true) ||
            formattedLine.contains("Claim this morning", ignoreCase = true)
        )

        _playbackState.value = VoicePlaybackState(
            isSpeaking = true,
            activeVoicePackId = voicePack.id,
            activeLine = formattedLine,
            progressFraction = 0.05f,
            activeDspBadge = profile.badgeLabel
        )

        val estimatedDurationMs = (formattedLine.length * 48L / profile.speechRate.coerceAtLeast(0.85f)).toLong()
            .coerceIn(2400L, 8500L)

        dspPlaybackJob = scope.launch(Dispatchers.IO) {
            if (isCoreMasterStem && rawRes != null) {
                playRawResourceAudio(
                    rawResId = rawRes,
                    boostFactor = boostFactor,
                    durationMs = estimatedDurationMs
                )
            } else {
                val synthesized = renderAndPlayClonedSpectralVoice(
                    voicePack = voicePack,
                    profile = profile,
                    text = formattedLine,
                    boostFactor = boostFactor
                )
                if (!synthesized) {
                    speakDirectClonedSpectralVoice(
                        voicePack = voicePack,
                        profile = profile,
                        text = formattedLine,
                        boostFactor = boostFactor
                    )
                }
            }
        }
        simulationJob = scope.launch(Dispatchers.Main) {
            val steps = 36
            val stepDelay = estimatedDurationMs / steps
            val eqPreset = runCatching { EqPreset.valueOf(voicePack.eqPreset) }
                .getOrDefault(EqPreset.STUDIO_CRISP)

            for (i in 1..steps) {
                delay(stepDelay)
                val progress = i.toFloat() / steps
                val bands = generateLiveSpectrumBands(
                    eqPreset = eqPreset,
                    modelType = modelType,
                    gritPercent = profile.vocalGritPercent,
                    boostFactor = boostFactor,
                    progress = progress
                )
                _playbackState.value = _playbackState.value.copy(
                    isSpeaking = true,
                    progressFraction = progress,
                    spectrumBands = bands
                )
            }
            _playbackState.value = VoicePlaybackState()
        }
        return true
    }

    /**
     * Formats text into authentic MCRD San Diego Pick-Up Day staccato bark clauses
     * when USMC_MCRD_BARK or USMC_PARADE_CALLER is active.
     */
    private fun formatForVoiceModel(
        rawText: String,
        modelType: VoiceModelType,
        aggressionPercent: Int
    ): String {
        return when (modelType) {
            VoiceModelType.USMC_MCRD_BARK -> {
                val upper = rawText.trim().uppercase()
                if (aggressionPercent >= 80 && !upper.startsWith("LIGHTS") && !upper.startsWith("GET ON") && !upper.startsWith("ATTENTION")) {
                    "EYES FRONT! $upper! DO IT NOW, MOVE!"
                } else {
                    upper
                }
            }
            VoiceModelType.USMC_PARADE_CALLER -> rawText.trim().uppercase()
            VoiceModelType.TACTICAL_COMMS -> "COMMS ACTIVE. ${rawText.trim()}"
            else -> rawText.trim()
        }
    }

    fun resolveClonedProfile(voicePack: VoicePackEntity): ClonedVoiceAcousticProfile {
        val packId = voicePack.id.lowercase()
        val stemId = voicePack.stemResourceId.lowercase()
        val actor = voicePack.actorName.lowercase()
        return when {
            stemId.contains("drill") || packId.contains("stone") || actor.contains("stone") || voicePack.usesDrillSergeantArt -> {
                ClonedVoiceAcousticProfile(
                    id = "cloned_drill_sergeant",
                    personaName = "GySgt. Marcus Stone (USMC Drill Sergeant)",
                    genderIsFemale = false,
                    targetPitchFactor = 0.84f,
                    f0FundamentalHz = 108.0,
                    f1FormantHz = 480.0,
                    f2FormantHz = 1380.0,
                    f3FormantHz = 2450.0,
                    formantQ1 = 2.6,
                    formantQ2 = 3.4,
                    vocalGritPercent = voicePack.vocalGritPercent.coerceAtLeast(80),
                    speechRate = 1.25f,
                    roomSlapbackMix = 0.05f,
                    chestWarmthGain = 1.25f,
                    airShelfGain = 0.95f,
                    badgeLabel = "CRISP BROADCAST STUDIO • HYBRID ENGINE • GYSGT. STONE"
                )
            }
            stemId.contains("fitness") || stemId.contains("jordan") || packId.contains("jordan") || packId == "pack_coach_ray" || actor.contains("jordan") -> {
                ClonedVoiceAcousticProfile(
                    id = "cloned_male_fitness",
                    personaName = "Coach Jordan Vance (Personal & Fitness Coach)",
                    genderIsFemale = false,
                    targetPitchFactor = 0.92f,
                    f0FundamentalHz = 114.0,
                    f1FormantHz = 440.0,
                    f2FormantHz = 1290.0,
                    f3FormantHz = 2200.0,
                    formantQ1 = 2.2,
                    formantQ2 = 2.8,
                    vocalGritPercent = 15,
                    speechRate = 1.05f,
                    roomSlapbackMix = 0.02f,
                    chestWarmthGain = 1.45f,
                    airShelfGain = 1.08f,
                    badgeLabel = "CRISP BROADCAST STUDIO • HYBRID ENGINE • COACH JORDAN"
                )
            }
            stemId.contains("executive") || packId.contains("eleanor") || packId.contains("executive") || actor.contains("eleanor") -> {
                ClonedVoiceAcousticProfile(
                    id = "cloned_female_executive",
                    personaName = "Director Eleanor Vance (Fortune 500 Executive)",
                    genderIsFemale = true,
                    targetPitchFactor = 0.98f,
                    f0FundamentalHz = 180.0,
                    f1FormantHz = 510.0,
                    f2FormantHz = 1620.0,
                    f3FormantHz = 2700.0,
                    formantQ1 = 2.5,
                    formantQ2 = 3.2,
                    vocalGritPercent = 5,
                    speechRate = 1.02f,
                    roomSlapbackMix = 0.02f,
                    chestWarmthGain = 1.15f,
                    airShelfGain = 1.20f,
                    badgeLabel = "CRISP BROADCAST STUDIO • HYBRID ENGINE • DIRECTOR ELEANOR"
                )
            }
            stemId.contains("motivational") || stemId.contains("maya") || packId.contains("maya") || actor.contains("maya") -> {
                ClonedVoiceAcousticProfile(
                    id = "cloned_motivational_coach",
                    personaName = "Coach Maya Lin (Motivational Coach)",
                    genderIsFemale = true,
                    targetPitchFactor = 1.15f,
                    f0FundamentalHz = 218.0,
                    f1FormantHz = 590.0,
                    f2FormantHz = 1880.0,
                    f3FormantHz = 3100.0,
                    formantQ1 = 2.4,
                    formantQ2 = 3.0,
                    vocalGritPercent = 8,
                    speechRate = 1.12f,
                    roomSlapbackMix = 0.02f,
                    chestWarmthGain = 0.90f,
                    airShelfGain = 1.35f,
                    badgeLabel = "CRISP BROADCAST STUDIO • HYBRID ENGINE • COACH MAYA"
                )
            }
            else -> {
                val isFemale = actor.contains("elena") || actor.contains("nova") || actor.contains("female")
                ClonedVoiceAcousticProfile(
                    id = "cloned_standard",
                    personaName = voicePack.name,
                    genderIsFemale = isFemale,
                    targetPitchFactor = voicePack.pitch,
                    f0FundamentalHz = if (isFemale) 210.0 else 125.0,
                    f1FormantHz = if (isFemale) 560.0 else 490.0,
                    f2FormantHz = if (isFemale) 1800.0 else 1450.0,
                    f3FormantHz = if (isFemale) 2900.0 else 2400.0,
                    vocalGritPercent = voicePack.vocalGritPercent,
                    speechRate = voicePack.speechRate,
                    roomSlapbackMix = 0.15f,
                    chestWarmthGain = 1.1f,
                    airShelfGain = 1.1f,
                    badgeLabel = "NEURAL SPECTRAL CLONE • ${voicePack.actorName.take(16).uppercase()}"
                )
            }
        }
    }

    private fun configureBestVoiceForProfile(profile: ClonedVoiceAcousticProfile, languageTag: String) {
        val engine = tts ?: return
        val resolvedLocale = Locale.forLanguageTag(languageTag)
        try {
            engine.language = resolvedLocale
            val availableVoices = engine.voices
            val bestVoice = availableVoices
                ?.filter { it.locale.language == resolvedLocale.language }
                ?.maxByOrNull { voice ->
                    var score = 0
                    val lower = voice.name.lowercase()
                    if (voice.quality >= Voice.QUALITY_HIGH) score += 25
                    if (!voice.isNetworkConnectionRequired) score += 15
                    if (lower.contains("neural") || lower.contains("wavenet") || lower.contains("studio")) score += 30

                    if (profile.genderIsFemale) {
                        // Strictly prefer female voice stems
                        if (lower.contains("female") || lower.contains("fem") || lower.contains("woman") ||
                            lower.contains("tpf") || lower.contains("sfg") || lower.contains("tpc") || lower.contains("iog")
                        ) {
                            score += 55
                        }
                        if (lower.contains("male") && !lower.contains("female")) {
                            score -= 60
                        }
                    } else {
                        // Strictly prefer male voice stems
                        if (lower.contains("male") && !lower.contains("female")) {
                            score += 55
                        }
                        if (lower.contains("iom") || lower.contains("iol") || lower.contains("gbd") || lower.contains("man")) {
                            score += 40
                        }
                        if (lower.contains("female") || lower.contains("woman")) {
                            score -= 60
                        }
                    }
                    score
                }
            if (bestVoice != null) {
                engine.voice = bestVoice
            }
            engine.setPitch(profile.targetPitchFactor)
            engine.setSpeechRate(profile.speechRate)
        } catch (_: Exception) {
        }
    }

    private suspend fun renderAndPlayClonedSpectralVoice(
        voicePack: VoicePackEntity,
        profile: ClonedVoiceAcousticProfile,
        text: String,
        boostFactor: Float
    ): Boolean {
        if (!isTtsReady || tts == null) return false
        return try {
            val engine = tts ?: return false
            configureBestVoiceForProfile(profile, voicePack.languageTag)

            val utteranceId = "clone_${System.currentTimeMillis()}"
            val wavFile = File(context.cacheDir, "$utteranceId.wav")
            val deferred = CompletableDeferred<Boolean>()
            pendingFileRenders[utteranceId] = deferred

            val params = Bundle()
            val result = engine.synthesizeToFile(text, params, wavFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                pendingFileRenders.remove(utteranceId)
                return false
            }

            val completed = withTimeoutOrNull(2800L) { deferred.await() } ?: false
            if (!completed || !wavFile.exists() || wavFile.length() <= 44L) {
                wavFile.delete()
                return false
            }

            val rawBytes = wavFile.readBytes()
            wavFile.delete()
            if (rawBytes.size <= 44) return false

            val headerBuffer = ByteBuffer.wrap(rawBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            val channels = headerBuffer.getShort(22).toInt().coerceIn(1, 2)
            val sampleRate = headerBuffer.getInt(24).coerceIn(8000, 48000)

            val pcmByteCount = rawBytes.size - 44
            val shortCount = pcmByteCount / 2
            val shortBuffer = ByteBuffer.wrap(rawBytes, 44, shortCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val pcmSamples = ShortArray(shortCount)
            shortBuffer.get(pcmSamples)

            val processedSamples = processClonedSpectralDsp(
                input = pcmSamples,
                sampleRate = sampleRate,
                profile = profile,
                boostFactor = boostFactor
            )

            playProcessedPcmOnAudioTrack(processedSamples, sampleRate, channels)
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun processClonedSpectralDsp(
        input: ShortArray,
        sampleRate: Int,
        profile: ClonedVoiceAcousticProfile,
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

    private fun speakDirectClonedSpectralVoice(
        voicePack: VoicePackEntity,
        profile: ClonedVoiceAcousticProfile,
        text: String,
        boostFactor: Float
    ) {
        if (isTtsReady && tts != null) {
            try {
                configureBestVoiceForProfile(profile, voicePack.languageTag)
                val clauses = text.split(Regex("(?<=[.!?])\\s+|(?<=[,;])\\s+"))
                    .filter { it.isNotBlank() }

                val pauseMs = when (profile.id) {
                    "cloned_drill_sergeant" -> 50L
                    "cloned_male_fitness" -> 110L
                    "cloned_female_executive" -> 90L
                    "cloned_motivational_coach" -> 85L
                    else -> 100L
                }

                for (idx in clauses.indices) {
                    val clause = clauses[idx].trim()
                    val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    tts?.speak(clause, mode, null, "cmd_${System.currentTimeMillis()}_$idx")
                    if (idx < clauses.size - 1) {
                        tts?.playSilentUtterance(pauseMs, TextToSpeech.QUEUE_ADD, "pause_$idx")
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Layer profile-specific acoustic resonance pulses
        when (profile.id) {
            "cloned_drill_sergeant" -> {
                playMcrdCadenceChestPulses(
                    gritPercent = profile.vocalGritPercent,
                    aggressionPercent = voicePack.aggressionPercent,
                    boostFactor = boostFactor
                )
            }
            "cloned_male_fitness" -> {
                playBaritoneWarmthPulses(boostFactor = boostFactor)
            }
            "cloned_motivational_coach" -> {
                playVocalAirResonancePulses(boostFactor = boostFactor, isHighSoprano = true)
            }
            "cloned_female_executive" -> {
                playVocalAirResonancePulses(boostFactor = boostFactor, isHighSoprano = false)
            }
        }
    }

    private fun playBaritoneWarmthPulses(boostFactor: Float) {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 24000
                val durationMs = 850
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val raw = ShortArray(numSamples)

                val burstWindows = listOf(0.06..0.45, 0.52..0.92)
                for (i in 0 until numSamples) {
                    val normPos = i.toDouble() / numSamples
                    val activeWindow = burstWindows.firstOrNull { normPos in it }
                    if (activeWindow != null) {
                        val localProgress = (normPos - activeWindow.start) / (activeWindow.endInclusive - activeWindow.start)
                        val syllableEnv = sin(PI * localProgress).coerceIn(0.0, 1.0)
                        val t = i.toDouble() / sampleRate
                        // 114Hz Baritone Fundamental + 440Hz warm chest overtone
                        val baritone = sin(2.0 * PI * 114.0 * t) * 0.60 +
                            sin(2.0 * PI * 228.0 * t) * 0.28 +
                            sin(2.0 * PI * 440.0 * t) * 0.12
                        raw[i] = (baritone * syllableEnv * 12000.0 * boostFactor.coerceIn(0.6f, 1.4f)).toInt().toShort()
                    }
                }
                playProcessedPcmOnAudioTrack(raw, sampleRate, 1)
            } catch (_: Exception) {}
        }
    }

    private fun playVocalAirResonancePulses(boostFactor: Float, isHighSoprano: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 24000
                val durationMs = 800
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val raw = ShortArray(numSamples)

                val baseF0 = if (isHighSoprano) 218.0 else 180.0
                val overtone = if (isHighSoprano) 590.0 else 510.0

                val burstWindows = listOf(0.05..0.42, 0.48..0.88)
                for (i in 0 until numSamples) {
                    val normPos = i.toDouble() / numSamples
                    val activeWindow = burstWindows.firstOrNull { normPos in it }
                    if (activeWindow != null) {
                        val localProgress = (normPos - activeWindow.start) / (activeWindow.endInclusive - activeWindow.start)
                        val syllableEnv = sin(PI * localProgress).coerceIn(0.0, 1.0)
                        val t = i.toDouble() / sampleRate
                        val vocalEnergy = sin(2.0 * PI * baseF0 * t) * 0.55 +
                            sin(2.0 * PI * overtone * t) * 0.35 +
                            sin(2.0 * PI * (baseF0 * 3.0) * t) * 0.10
                        raw[i] = (vocalEnergy * syllableEnv * 11000.0 * boostFactor.coerceIn(0.6f, 1.4f)).toInt().toShort()
                    }
                }
                playProcessedPcmOnAudioTrack(raw, sampleRate, 1)
            } catch (_: Exception) {}
        }
    }

    private fun configureBestVoiceForModel(voicePack: VoicePackEntity, modelType: VoiceModelType) {
        val engine = tts ?: return
        val resolvedLocale = Locale.forLanguageTag(voicePack.languageTag)
        try {
            engine.language = resolvedLocale
            val availableVoices = engine.voices
            val bestVoice = availableVoices
                ?.filter { it.locale.language == resolvedLocale.language }
                ?.maxByOrNull { voice ->
                    var score = 0
                    val lower = voice.name.lowercase()
                    if (voice.quality >= Voice.QUALITY_HIGH) score += 25
                    if (!voice.isNetworkConnectionRequired) score += 15
                    if (lower.contains("neural") || lower.contains("wavenet") || lower.contains("studio")) score += 30
                    // For MCRD Drill Sergeant, strongly prefer deep male chest voices (e.g., iom, iol, gba, gbd, male)
                    if (modelType == VoiceModelType.USMC_MCRD_BARK || modelType == VoiceModelType.USMC_PARADE_CALLER) {
                        if (lower.contains("iom") || lower.contains("iol") || lower.contains("gbd") || lower.contains("male")) {
                            score += 45
                        }
                    }
                    score
                }
            if (bestVoice != null) {
                engine.voice = bestVoice
            }

            val targetPitch = when (modelType) {
                VoiceModelType.USMC_MCRD_BARK -> (voicePack.pitch * 0.88f).coerceIn(0.72f, 0.92f)
                VoiceModelType.USMC_PARADE_CALLER -> (voicePack.pitch * 0.85f).coerceIn(0.70f, 0.90f)
                VoiceModelType.TACTICAL_COMMS -> voicePack.pitch.coerceIn(0.90f, 1.02f)
                else -> voicePack.pitch.coerceIn(0.88f, 1.05f)
            }
            val targetRate = when (modelType) {
                VoiceModelType.USMC_MCRD_BARK -> (voicePack.speechRate * (1.0f + (voicePack.aggressionPercent - 50) * 0.0035f))
                    .coerceIn(1.08f, 1.38f)
                VoiceModelType.USMC_PARADE_CALLER -> voicePack.speechRate.coerceIn(0.98f, 1.16f)
                else -> voicePack.speechRate.coerceIn(0.92f, 1.18f)
            }

            engine.setPitch(targetPitch)
            engine.setSpeechRate(targetRate)
        } catch (_: Exception) {
        }
    }

    /**
     * Synthesizes speech with human voice actor configuration and applies
     * resonant vocal-tract formant shifting and pitch transposition DSP.
     */
    private suspend fun renderAndPlayHumanVoiceStemWav(
        voicePack: VoicePackEntity,
        text: String,
        boostFactor: Float
    ): Boolean {
        if (!isTtsReady || tts == null) return false
        return try {
            val engine = tts ?: return false
            val resolvedLocale = Locale.forLanguageTag(voicePack.languageTag)
            engine.language = resolvedLocale
            engine.setPitch(voicePack.pitch)
            engine.setSpeechRate(voicePack.speechRate)

            val utteranceId = "stem_${System.currentTimeMillis()}"
            val wavFile = File(context.cacheDir, "$utteranceId.wav")
            val deferred = CompletableDeferred<Boolean>()
            pendingFileRenders[utteranceId] = deferred

            val params = Bundle()
            val result = engine.synthesizeToFile(text, params, wavFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                pendingFileRenders.remove(utteranceId)
                return false
            }

            val completed = withTimeoutOrNull(2200L) { deferred.await() } ?: false
            if (!completed || !wavFile.exists() || wavFile.length() <= 44L) {
                wavFile.delete()
                return false
            }

            val rawBytes = wavFile.readBytes()
            wavFile.delete()
            if (rawBytes.size <= 44) return false

            val headerBuffer = ByteBuffer.wrap(rawBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            val channels = headerBuffer.getShort(22).toInt().coerceIn(1, 2)
            val sampleRate = headerBuffer.getInt(24).coerceIn(8000, 48000)

            val pcmByteCount = rawBytes.size - 44
            val shortCount = pcmByteCount / 2
            val shortBuffer = ByteBuffer.wrap(rawBytes, 44, shortCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val pcmSamples = ShortArray(shortCount)
            shortBuffer.get(pcmSamples)

            val processedSamples = processHumanVoiceStemDsp(
                input = pcmSamples,
                sampleRate = sampleRate,
                pitch = voicePack.pitch,
                formantShiftPercent = voicePack.formantShiftPercent,
                boostFactor = boostFactor
            )

            playProcessedPcmOnAudioTrack(processedSamples, sampleRate, channels)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resonant 16-bit PCM Vocal-Tract Formant Shifting & Pitch DSP:
     * - Resamples for pitch transposition (0.75x to 1.25x)
     * - Dual resonant biquad peaking filters (F1 ~520Hz, F2 ~1550Hz) scaled by formantShiftPercent
     *   (-50% expands perceived vocal tract for deep chest resonance; +50% contracts it for bright studio presence)
     * - Studio soft-knee tanh saturation + brickwall limiting at 0.98 ceiling.
     */
    internal fun processHumanVoiceStemDsp(
        input: ShortArray,
        sampleRate: Int,
        pitch: Float,
        formantShiftPercent: Int,
        boostFactor: Float
    ): ShortArray {
        if (input.isEmpty()) return input

        // 1. Resample for pitch factor (0.70x to 1.35x)
        val pitchFactor = pitch.coerceIn(0.70f, 1.35f)
        val pitchShifted = if (abs(pitchFactor - 1.0f) > 0.02f) {
            val targetSize = ((input.size / pitchFactor).toInt()).coerceAtLeast(1)
            val resampled = ShortArray(targetSize)
            for (i in 0 until targetSize) {
                val origPos = i * pitchFactor
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

        // 2. Formant Shift via Dual Biquad Peaking Filters (F1 ~520Hz, F2 ~1550Hz)
        val shiftMultiplier = (1.0 + (formantShiftPercent.coerceIn(-50, 50).toDouble() / 100.0) * 0.42)
            .coerceIn(0.60, 1.50)
        val f1 = (520.0 * shiftMultiplier).coerceIn(200.0, 1200.0)
        val f2 = (1550.0 * shiftMultiplier).coerceIn(800.0, 3200.0)

        val omega1 = 2.0 * PI * f1 / sampleRate.toDouble()
        val alpha1 = sin(omega1) / (2.0 * 2.2) // Q = 2.2
        val b0_1 = alpha1
        val b1_1 = 0.0
        val b2_1 = -alpha1
        val a0_1 = 1.0 + alpha1
        val a1_1 = -2.0 * cos(omega1)
        val a2_1 = 1.0 - alpha1

        val omega2 = 2.0 * PI * f2 / sampleRate.toDouble()
        val alpha2 = sin(omega2) / (2.0 * 3.0) // Q = 3.0
        val b0_2 = alpha2
        val b1_2 = 0.0
        val b2_2 = -alpha2
        val a0_2 = 1.0 + alpha2
        val a1_2 = -2.0 * cos(omega2)
        val a2_2 = 1.0 - alpha2

        var x1_1 = 0.0; var x2_1 = 0.0; var y1_1 = 0.0; var y2_1 = 0.0
        var x1_2 = 0.0; var x2_2 = 0.0; var y1_2 = 0.0; var y2_2 = 0.0

        val output = ShortArray(pitchShifted.size)
        val gain = boostFactor.coerceIn(0.6f, 1.6f) * 1.25f

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

            // Dry / Wet blend of formant resonances
            val formantResonanceMix = (inSample * 0.72 + outF1 * 0.65 + outF2 * 0.45) * gain

            // Soft-knee studio saturation
            val saturated = tanh(formantResonanceMix).coerceIn(-0.98, 0.98)
            output[i] = (saturated * 32767.0).toInt().toShort()
        }

        return output
    }

    /**
     * Synthesizes offline studio vocal phoneme harmonics through the human stem formant DSP pipeline.
     */
    private suspend fun playHumanStemFormantAudio(
        voicePack: VoicePackEntity,
        boostFactor: Float
    ) {
        try {
            val sampleRate = 24000
            val durationMs = 1200
            val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
            val raw = ShortArray(numSamples)

            val baseF0 = if (voicePack.actorName.contains("Elena") || voicePack.actorName.contains("Nova")) {
                220.0 * voicePack.pitch.toDouble()
            } else {
                130.0 * voicePack.pitch.toDouble()
            }

            // 3 smooth studio phoneme envelopes ("Mis-sion-Set")
            val phonemeWindows = listOf(
                0.05..0.32,
                0.36..0.62,
                0.66..0.94
            )

            for (i in 0 until numSamples) {
                val normPos = i.toDouble() / numSamples
                val window = phonemeWindows.firstOrNull { normPos in it }
                if (window != null) {
                    val localProg = (normPos - window.start) / (window.endInclusive - window.start)
                    val env = sin(PI * localProg).coerceIn(0.0, 1.0)
                    val t = i.toDouble() / sampleRate
                    // Harmonic voice stem: F0 fundamental + even/odd overtones
                    val voiceSignal = sin(2.0 * PI * baseF0 * t) * 0.55 +
                        sin(2.0 * PI * (baseF0 * 2.0) * t) * 0.30 +
                        sin(2.0 * PI * (baseF0 * 3.0) * t) * 0.15
                    raw[i] = (voiceSignal * env * 16000.0).toInt().toShort()
                }
            }

            val processed = processHumanVoiceStemDsp(
                input = raw,
                sampleRate = sampleRate,
                pitch = voicePack.pitch,
                formantShiftPercent = voicePack.formantShiftPercent,
                boostFactor = boostFactor
            )

            playProcessedPcmOnAudioTrack(processed, sampleRate, 1)
        } catch (_: Exception) {
        }
    }

    /**
     * Synthesizes speech via TextToSpeech.synthesizeToFile() into a temporary WAV file,
     * parses the 16-bit PCM samples, runs them through our MCRD Vocal Overdrive + Gravel DSP,
     * and plays the processed buffer on a dedicated high-gain AudioTrack.
     */
    private suspend fun renderAndPlayOverdriveWav(
        voicePack: VoicePackEntity,
        modelType: VoiceModelType,
        text: String,
        boostFactor: Float
    ): Boolean {
        if (!isTtsReady || tts == null) return false
        return try {
            configureBestVoiceForModel(voicePack, modelType)
            val utteranceId = "dsp_${System.currentTimeMillis()}"
            val wavFile = File(context.cacheDir, "$utteranceId.wav")
            val deferred = CompletableDeferred<Boolean>()
            pendingFileRenders[utteranceId] = deferred

            val params = Bundle()
            val result = tts?.synthesizeToFile(text, params, wavFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                pendingFileRenders.remove(utteranceId)
                return false
            }

            val completed = withTimeoutOrNull(2200L) { deferred.await() } ?: false
            if (!completed || !wavFile.exists() || wavFile.length() <= 44L) {
                wavFile.delete()
                return false
            }

            val rawBytes = wavFile.readBytes()
            wavFile.delete()
            if (rawBytes.size <= 44) return false

            // Extract WAV sample rate from header bytes 24..27 (little-endian int)
            val headerBuffer = ByteBuffer.wrap(rawBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            val channels = headerBuffer.getShort(22).toInt().coerceIn(1, 2)
            val sampleRate = headerBuffer.getInt(24).coerceIn(8000, 48000)

            val pcmByteCount = rawBytes.size - 44
            val shortCount = pcmByteCount / 2
            val shortBuffer = ByteBuffer.wrap(rawBytes, 44, shortCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val pcmSamples = ShortArray(shortCount)
            shortBuffer.get(pcmSamples)

            val processedSamples = processPcmWithMcrdVocalDsp(
                input = pcmSamples,
                sampleRate = sampleRate,
                modelType = modelType,
                gritPercent = voicePack.vocalGritPercent,
                aggressionPercent = voicePack.aggressionPercent,
                boostFactor = boostFactor
            )

            playProcessedPcmOnAudioTrack(processedSamples, sampleRate, channels)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Custom 16-bit PCM DSP chain for authentic USMC MCRD Pick-Up Day vocal projection:
     * 1. High-pass filter (110Hz) to tighten chest transients
     * 2. Laryngeal "frog-voice" gravel generator (128Hz sub-harmonic ring modulation + asymmetric rasp)
     * 3. 2.2kHz vocal projection presence boost
     * 4. Soft-clipping tanh overdrive saturation scaled by Command Aggression
     * 5. 28ms squad-bay slapback reflection
     * 6. Brickwall peak limiter at 0.98 ceiling
     */
    internal fun processPcmWithMcrdVocalDsp(
        input: ShortArray,
        sampleRate: Int,
        modelType: VoiceModelType,
        gritPercent: Int,
        aggressionPercent: Int,
        boostFactor: Float
    ): ShortArray {
        if (input.isEmpty()) return input

        val gritAmount = (gritPercent.coerceIn(0, 100) / 100f)
        val aggressionAmount = (aggressionPercent.coerceIn(0, 100) / 100f)

        val preGain = when (modelType) {
            VoiceModelType.USMC_MCRD_BARK -> 1.85f + (aggressionAmount * 1.65f)
            VoiceModelType.USMC_PARADE_CALLER -> 1.65f + (aggressionAmount * 1.35f)
            VoiceModelType.TACTICAL_COMMS -> 1.55f + (aggressionAmount * 0.9f)
            VoiceModelType.KINETIC_COACH -> 1.35f + (aggressionAmount * 0.6f)
            VoiceModelType.EXEC_NEURAL_HD -> 1.25f + (aggressionAmount * 0.4f)
            VoiceModelType.CALM_STRATEGIST -> 1.15f
        } * boostFactor.coerceIn(0.45f, 1.55f)

        // 28ms Squad-Bay reflection delay line
        val delaySamples = ((sampleRate * 0.028f).toInt()).coerceAtLeast(1)
        val delayBuffer = FloatArray(delaySamples)
        var delayIdx = 0
        val slapbackMix = when (modelType) {
            VoiceModelType.USMC_MCRD_BARK -> 0.24f
            VoiceModelType.USMC_PARADE_CALLER -> 0.34f
            else -> 0.08f
        }

        val output = ShortArray(input.size)
        var prevIn = 0f
        var prevHp = 0f
        var envelope = 0f

        for (i in input.indices) {
            val normalizedIn = input[i] / 32768f

            // 1. High-pass filter (~110Hz) to remove muddy low-end and sharpen bark attack
            val hpAlpha = 0.965f
            val hpSample = hpAlpha * (prevHp + normalizedIn - prevIn)
            prevIn = normalizedIn
            prevHp = hpSample

            // Track vocal amplitude envelope so gravel only engages when speaking
            val absSample = abs(hpSample)
            envelope = if (absSample > envelope) {
                envelope * 0.85f + absSample * 0.15f
            } else {
                envelope * 0.992f + absSample * 0.008f
            }

            // 2. Laryngeal Gravel / "Frog-Voice" Vocal Cord Rasp (128Hz + 256Hz harmonic modulation)
            val timeSec = i.toDouble() / sampleRate.toDouble()
            val laryngealCarrier = (sin(2.0 * PI * 128.0 * timeSec) * 0.65 +
                sin(2.0 * PI * 256.0 * timeSec) * 0.35).toFloat()

            val raspFactor = when (modelType) {
                VoiceModelType.USMC_MCRD_BARK,
                VoiceModelType.USMC_PARADE_CALLER -> gritAmount * 0.52f
                VoiceModelType.TACTICAL_COMMS -> gritAmount * 0.28f
                else -> gritAmount * 0.14f
            }

            val gravellySample = if (envelope > 0.025f) {
                hpSample * (1.0f + raspFactor * laryngealCarrier) +
                    (hpSample * abs(hpSample) * raspFactor * 0.65f)
            } else {
                hpSample
            }

            // 3. Radio Band-Limit or Presence Bite Boost
            val midBoosted = if (modelType == VoiceModelType.TACTICAL_COMMS) {
                // Crisp push-to-talk mid presence
                gravellySample * 1.35f
            } else {
                gravellySample * (1.0f + aggressionAmount * 0.45f)
            }

            // 4. Asymmetric Soft-Clipping Overdrive Saturation (tanh)
            val driven = midBoosted * preGain
            val saturated = if (driven >= 0f) {
                tanh((driven * (1.0f + gritAmount * 0.85f)).toDouble()).toFloat()
            } else {
                // Slight asymmetry creates authentic chest-voice 2nd harmonic punch
                tanh((driven * (1.0f + gritAmount * 0.55f)).toDouble()).toFloat() * 0.94f
            }

            // 5. 28ms Squad-Bay Slapback Reflection
            val delayed = delayBuffer[delayIdx]
            delayBuffer[delayIdx] = saturated
            delayIdx = (delayIdx + 1) % delaySamples

            val withAcoustics = saturated + (delayed * slapbackMix)

            // 6. Brickwall Peak Limiter at 0.98f ceiling
            val limited = withAcoustics.coerceIn(-0.98f, 0.98f)
            output[i] = (limited * 32767f).toInt().toShort()
        }

        return output
    }

    private suspend fun playProcessedPcmOnAudioTrack(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int
    ) {
        val channelConfig = if (channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val bufferSizeBytes = (samples.size * 2).coerceAtLeast(
            AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        activeAudioTrack = track
        track.write(samples, 0, samples.size)
        track.play()

        val durationMs = ((samples.size.toDouble() / (sampleRate * channels)) * 1000.0).toLong()
        delay(durationMs.coerceIn(400L, 9000L) + 80L)
        stopActiveAudioTrack()
    }

    private fun stopActiveAudioTrack() {
        try {
            activeMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
        } catch (_: Exception) {
        } finally {
            activeMediaPlayer = null
        }

        try {
            activeAudioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (_: Exception) {
        } finally {
            activeAudioTrack = null
        }
    }

    internal fun resolveRawAudioRes(voicePack: VoicePackEntity): Int? {
        val packId = voicePack.id.lowercase()
        val stemId = voicePack.stemResourceId.lowercase()
        return when {
            stemId.contains("drill") || packId.contains("stone") || (packId.contains("drill") && !packId.contains("brody")) -> {
                R.raw.voice_drill_sergeant
            }
            stemId.contains("fitness") || stemId.contains("jordan") || packId.contains("jordan") || packId == "pack_coach_ray" || packId == "pack_marcus_vance" -> {
                R.raw.voice_male_fitness
            }
            stemId.contains("executive") || packId.contains("eleanor") || packId.contains("executive") || (packId.contains("vance") && !packId.contains("jordan")) -> {
                R.raw.voice_female_executive
            }
            stemId.contains("motivational") || stemId.contains("maya") || packId.contains("maya") -> {
                R.raw.voice_motivational_coach
            }
            else -> null
        }
    }

    private suspend fun playRawResourceAudio(
        rawResId: Int,
        boostFactor: Float,
        durationMs: Long
    ) {
        try {
            val mp = MediaPlayer.create(context, rawResId)
            activeMediaPlayer = mp
            if (mp != null) {
                val vol = (boostFactor * 0.95f).coerceIn(0.1f, 1.0f)
                mp.setVolume(vol, vol)
                val actualDuration = mp.duration.toLong().let { if (it > 0) it else durationMs }
                mp.start()

                val steps = 60
                val stepDelay = (actualDuration / steps).coerceAtLeast(30L)
                for (i in 1..steps) {
                    delay(stepDelay)
                    if (activeMediaPlayer != mp || !mp.isPlaying) break
                    val progress = i.toFloat() / steps
                    val bands = generateLiveSpectrumBands(
                        eqPreset = EqPreset.STUDIO_CRISP,
                        modelType = VoiceModelType.USMC_MCRD_BARK,
                        gritPercent = 75,
                        boostFactor = boostFactor,
                        progress = progress
                    )
                    _playbackState.value = _playbackState.value.copy(
                        isSpeaking = true,
                        progressFraction = progress,
                        spectrumBands = bands
                    )
                }
                try {
                    if (mp.isPlaying) mp.stop()
                    mp.release()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            activeMediaPlayer = null
            _playbackState.value = VoicePlaybackState()
        }
    }

    /**
     * Fallback path when a device's TTS engine does not support file synthesis:
     * Fires rapid staccato TTS clauses AND layers an acoustic laryngeal chest-resonator
     * burst so the Drill Sergeant still projects physical MCRD Pick-Up Day energy.
     */
    private fun speakDirectWithGravelLayer(
        voicePack: VoicePackEntity,
        modelType: VoiceModelType,
        text: String,
        boostFactor: Float
    ) {
        if (isTtsReady && tts != null) {
            try {
                configureBestVoiceForModel(voicePack, modelType)
                val clauses = text.split(Regex("(?<=[.!?])\\s+|(?<=[,;])\\s+"))
                    .filter { it.isNotBlank() }

                val pauseMs = when (modelType) {
                    VoiceModelType.USMC_MCRD_BARK -> 55L // Rapid staccato MCRD bark
                    VoiceModelType.USMC_PARADE_CALLER -> 120L
                    else -> 130L
                }

                for (idx in clauses.indices) {
                    val clause = clauses[idx].trim()
                    val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    tts?.speak(clause, mode, null, "cmd_${System.currentTimeMillis()}_$idx")
                    if (idx < clauses.size - 1) {
                        tts?.playSilentUtterance(pauseMs, TextToSpeech.QUEUE_ADD, "pause_$idx")
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Layer low-end chest-voice formant cadence pulses for MCRD Drill Sergeant models
        if (modelType == VoiceModelType.USMC_MCRD_BARK || modelType == VoiceModelType.USMC_PARADE_CALLER) {
            playMcrdCadenceChestPulses(
                gritPercent = voicePack.vocalGritPercent,
                aggressionPercent = voicePack.aggressionPercent,
                boostFactor = boostFactor
            )
        }
    }

    /**
     * Authentic USMC MCRD Pick-Up Day Dual-Tone Pea Whistle Blast (2850Hz + 3120Hz with 38Hz pea trill)
     * that immediately commands attention before the Drill Instructor barks.
     */
    private fun playMcrdWhistleBlast(aggressionPercent: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val durationMs = 210
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val buffer = ShortArray(numSamples)
                val gain = (0.45 + (aggressionPercent / 100.0) * 0.35).coerceIn(0.35, 0.80)

                for (i in 0 until numSamples) {
                    val time = i.toDouble() / sampleRate
                    val env = if (i < numSamples * 0.1) {
                        i / (numSamples * 0.1)
                    } else if (i > numSamples * 0.8) {
                        (numSamples - i) / (numSamples * 0.2)
                    } else {
                        1.0
                    }
                    // Pea-whistle modulation at 38Hz
                    val peaTrill = 0.65 + 0.35 * cos(2.0 * PI * 38.0 * time)
                    val tone = (sin(2.0 * PI * 2850.0 * time) * 0.58 +
                        sin(2.0 * PI * 3120.0 * time) * 0.42)
                    val sample = tone * peaTrill * env * gain
                    buffer[i] = (sample.coerceIn(-0.95, 0.95) * Short.MAX_VALUE).toInt().toShort()
                }

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.play()
                delay(durationMs.toLong() + 30L)
                audioTrack.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Synthesizes rhythmic gravelly chest-formant cadence pulses (128Hz fundamental + 950Hz/2200Hz vocal tract formants)
     * so even on emulators without TTS voices installed, previewing the Drill Sergeant produces audible,
     * unmistakable gravelly vocal energy.
     */
    private fun playMcrdCadenceChestPulses(
        gritPercent: Int,
        aggressionPercent: Int,
        boostFactor: Float
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 24000
                val durationMs = 1100
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val raw = ShortArray(numSamples)

                // 4 rapid staccato syllable bursts ("GET! ON! LINE! NOW!")
                val burstWindows = listOf(
                    0.04..0.22,
                    0.27..0.45,
                    0.50..0.68,
                    0.73..0.96
                )

                for (i in 0 until numSamples) {
                    val normPos = i.toDouble() / numSamples
                    val activeWindow = burstWindows.firstOrNull { normPos in it }
                    if (activeWindow != null) {
                        val localProgress = (normPos - activeWindow.start) / (activeWindow.endInclusive - activeWindow.start)
                        val syllableEnv = sin(PI * localProgress).coerceIn(0.0, 1.0)
                        val t = i.toDouble() / sampleRate
                        // Vocal cord glottal pulse (125Hz chest fundamental + 920Hz pharyngeal + 2250Hz projection formant)
                        val glottal = sin(2.0 * PI * 125.0 * t) * 0.50 +
                            sin(2.0 * PI * 250.0 * t) * 0.25 +
                            sin(2.0 * PI * 920.0 * t) * 0.15 +
                            sin(2.0 * PI * 2250.0 * t) * 0.10
                        raw[i] = (glottal * syllableEnv * 14000.0).toInt().toShort()
                    }
                }

                val processed = processPcmWithMcrdVocalDsp(
                    input = raw,
                    sampleRate = sampleRate,
                    modelType = VoiceModelType.USMC_MCRD_BARK,
                    gritPercent = gritPercent,
                    aggressionPercent = aggressionPercent,
                    boostFactor = boostFactor * 0.55f
                )

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(processed.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(processed, 0, processed.size)
                track.play()
                delay(durationMs.toLong() + 40L)
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    fun runAbComparison(
        packA: VoicePackEntity,
        packB: VoicePackEntity,
        phrase: String,
        headphoneOnlyMode: Boolean = false,
        quietHoursDucking: Boolean = false
    ) {
        stopSpeaking()
        abComparisonJob?.cancel()

        if (headphoneOnlyMode && !isHeadphoneConnected()) {
            _playbackState.value = VoicePlaybackState(
                isSpeaking = false,
                audioRouteNotice = "Comparison paused: Headphone-Only Guard is active."
            )
            return
        }

        abComparisonJob = scope.launch(Dispatchers.Main) {
            _playbackState.value = VoicePlaybackState(
                isSpeaking = true,
                isAbComparing = true,
                activeAbSlot = "A",
                activeVoicePackId = packA.id,
                activeLine = "VOICE A (${packA.actorName}): \"$phrase\""
            )
            speakWithVoicePack(
                voicePack = packA,
                text = phrase,
                playAttentionChime = false,
                headphoneOnlyMode = false,
                quietHoursDucking = quietHoursDucking
            )
            val durationA = (phrase.length * 50L / packA.speechRate).toLong().coerceIn(2200L, 5200L)
            delay(durationA + 400L)

            playStudioTacticalChime(frequency = 880)
            delay(250L)

            _playbackState.value = VoicePlaybackState(
                isSpeaking = true,
                isAbComparing = true,
                activeAbSlot = "B",
                activeVoicePackId = packB.id,
                activeLine = "VOICE B (${packB.actorName}): \"$phrase\""
            )
            speakWithVoicePack(
                voicePack = packB,
                text = phrase,
                playAttentionChime = false,
                headphoneOnlyMode = false,
                quietHoursDucking = quietHoursDucking
            )
            val durationB = (phrase.length * 50L / packB.speechRate).toLong().coerceIn(2200L, 5200L)
            delay(durationB + 300L)

            _playbackState.value = VoicePlaybackState()
        }
    }

    private fun generateLiveSpectrumBands(
        eqPreset: EqPreset,
        modelType: VoiceModelType,
        gritPercent: Int,
        boostFactor: Float,
        progress: Float
    ): List<Float> {
        val gritBonus = (gritPercent / 100f) * 0.28f
        val baseFreqs = List(16) { index ->
            val phase = (index * 0.45f) + (progress * 15f)
            val wave = (sin(phase.toDouble()).toFloat() * 0.38f + 0.55f).coerceIn(0.12f, 1f)
            val eqShaped = when (eqPreset) {
                EqPreset.STUDIO_CRISP -> if (index >= 10) (wave * 1.35f) else (wave * 0.9f)
                EqPreset.TACTICAL_HUD -> if (index in 5..11) (wave * 1.45f) else (wave * 0.75f)
                EqPreset.DEEP_COMMAND -> if (index <= 6) (wave * 1.45f) else (wave * 0.85f)
                EqPreset.BROADCAST -> wave * 1.15f
            }
            val modelBoost = if (modelType == VoiceModelType.USMC_MCRD_BARK || modelType == VoiceModelType.USMC_PARADE_CALLER) {
                1.0f + gritBonus
            } else {
                1.0f
            }
            eqShaped * modelBoost * (boostFactor.coerceIn(0.8f, 1.5f) * 0.88f)
        }
        return baseFreqs.map { it.coerceIn(0.08f, 1.0f) }
    }

    private fun playStudioTacticalChime(frequency: Int = 1200) {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val durationMs = 90
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val buffer = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val time = i.toDouble() / sampleRate
                    val decay = 1.0 - (i.toDouble() / numSamples)
                    val sample = (sin(2.0 * PI * frequency * time) * 0.6 +
                        sin(2.0 * PI * (frequency * 1.5) * time) * 0.3) * decay
                    buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
                }

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.play()
                delay(durationMs.toLong() + 20)
                audioTrack.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun triggerDrillInstructorHaptics() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Rapid Pick-Up Day footstep cadence vibration pattern
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 90, 45, 90, 45, 140), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(220)
            }
        } catch (_: Exception) {
        }
    }

    private fun triggerTacticalHaptics() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 80, 40, 100), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(150)
            }
        } catch (_: Exception) {
        }
    }

    fun stopSpeaking() {
        simulationJob?.cancel()
        dspPlaybackJob?.cancel()
        abComparisonJob?.cancel()
        stopActiveAudioTrack()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        _playbackState.value = VoicePlaybackState()
    }

    fun shutdown() {
        stopSpeaking()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
}
