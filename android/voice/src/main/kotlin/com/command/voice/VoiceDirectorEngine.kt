package com.command.voice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.command.domain.voice.QuietWindowPolicy
import com.command.domain.voice.SpeechUsage
import com.command.domain.voice.VoiceModelType
import com.command.domain.voice.VoicePackEntity
import kotlinx.coroutines.CancellationException
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
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "VoiceDirectorEngine"

private const val GEMINI_TIMEOUT_MS = 8_000L
private const val GEMINI_BACKOFF_MS = 120_000L
private const val GEMINI_DEFAULT_SAMPLE_RATE = 24_000

/** Bump when voices or style briefs change so cached renders are regenerated. */
private const val VOICE_CACHE_VERSION = 1
private const val VOICE_CACHE_MAX_FILES = 300

/** Pitch/rate outside these bounds is where system TTS starts to sound synthetic. */
internal const val SYSTEM_TTS_MIN_PITCH = 0.85f
internal const val SYSTEM_TTS_MAX_PITCH = 1.15f
internal const val SYSTEM_TTS_MIN_RATE = 0.85f
internal const val SYSTEM_TTS_MAX_RATE = 1.20f

/**
 * How one director should sound. [styleBrief] is the performance direction given to the voice
 * model; for the four reference directors it comes from the narrations in res/raw.
 */
data class DirectorVoice(
    val id: String,
    val personaName: String,
    val isFemale: Boolean,
    val geminiVoice: String,
    val styleBrief: String,
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
    val activeDspBadge: String = "STUDIO VOICE"
)

internal class RenderedLine(val pcm: ShortArray, val sampleRate: Int, val source: String)

/** One Gemini speech reply: the audio bytes and their MIME type (e.g. "audio/L16;rate=24000" or "audio/wav"). */
class GeminiAudio(val bytes: ByteArray, val mimeType: String)

/** Where Gemini speech comes from. The real one is in :cloud, so that nothing in this module can reach the network; tests substitute a fake. */
fun interface GeminiSpeechSource {
    /** Returns the first audio part of the reply, or null when the reply has none. May throw on failure. */
    suspend fun synthesize(voiceName: String, prompt: String): GeminiAudio?
}

class VoiceDirectorEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gemini: GeminiSpeechSource,
    private val quietWindow: QuietWindowPolicy = QuietWindowPolicy.NEVER,
    private val nowMs: () -> Long = System::currentTimeMillis
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    @Volatile private var isTtsReady = false
    private var playbackJob: Job? = null
    private var abComparisonJob: Job? = null
    @Volatile private var activeAudioTrack: AudioTrack? = null

    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    /** Per Gemini voice: after a failure that voice uses system TTS until this time, so one voice's failure doesn't silence others. */
    private val geminiBackoffUntil = ConcurrentHashMap<String, Long>()
    private val lineCounter = AtomicInteger()

    private val voiceCacheDir by lazy { File(context.cacheDir, "director_voice").apply { mkdirs() } }
    private val isDebuggable by lazy {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

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
        if (status != TextToSpeech.SUCCESS) return
        isTtsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pendingUtterances.remove(it)?.complete(true) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pendingUtterances.remove(it)?.complete(false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pendingUtterances.remove(it)?.complete(false) }
            }
        })
    }

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    fun speakWithVoicePack(
        voicePack: VoicePackEntity,
        text: String,
        playAttentionChime: Boolean = false,
        headphoneOnlyMode: Boolean = false,
        quietHoursDucking: Boolean = false,
        usage: SpeechUsage = SpeechUsage.PREVIEW
    ): Boolean {
        stopSpeaking()

        if (headphoneOnlyMode && !isHeadphoneConnected()) {
            _playbackState.value = VoicePlaybackState(
                isSpeaking = false,
                audioRouteNotice = "Playback paused: Headphone-Only Guard is active and no headphones are connected."
            )
            return false
        }

        playbackJob = scope.launch(Dispatchers.IO) {
            speakLine(voicePack, text, playAttentionChime, quietHoursDucking, abSlot = null, usage = usage)
        }
        return true
    }

    fun runAbComparison(
        packA: VoicePackEntity,
        packB: VoicePackEntity,
        phrase: String,
        headphoneOnlyMode: Boolean = false,
        quietHoursDucking: Boolean = false
    ) {
        stopSpeaking()

        if (headphoneOnlyMode && !isHeadphoneConnected()) {
            _playbackState.value = VoicePlaybackState(
                isSpeaking = false,
                audioRouteNotice = "Comparison paused: Headphone-Only Guard is active."
            )
            return
        }

        abComparisonJob = scope.launch(Dispatchers.IO) {
            speakLine(packA, phrase, false, quietHoursDucking, abSlot = "A")
            playCueTone(frequencyHz = 880, durationMs = 90)
            delay(250L)
            speakLine(packB, phrase, false, quietHoursDucking, abSlot = "B")
            // Only reached on normal completion; a cancel comes from stopSpeaking(), which resets state itself.
            _playbackState.value = VoicePlaybackState()
        }
    }

    fun stopSpeaking() {
        lineCounter.incrementAndGet()
        playbackJob?.cancel()
        abComparisonJob?.cancel()
        stopActiveAudioTrack()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        pendingUtterances.values.forEach { it.complete(false) }
        pendingUtterances.clear()
        _playbackState.value = VoicePlaybackState()
    }

    fun shutdown() {
        stopSpeaking()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // One line, end to end: cue -> render (cache / Gemini / system TTS) -> master -> play
    // ---------------------------------------------------------------------------------------

    private suspend fun speakLine(
        voicePack: VoicePackEntity,
        text: String,
        playAttentionChime: Boolean,
        quietHoursDucking: Boolean,
        abSlot: String?,
        usage: SpeechUsage = SpeechUsage.PREVIEW
    ) {
        val lineId = lineCounter.incrementAndGet()
        val modelType = runCatching { VoiceModelType.valueOf(voicePack.voiceModelType) }
            .getOrDefault(VoiceModelType.USMC_MCRD_BARK)
        val director = resolveDirectorVoice(voicePack)
        val displayLine = formatForVoiceModel(text, modelType, voicePack.aggressionPercent)
        val spokenLine = softenAllCaps(displayLine)
        val volume = playbackVolume(quietHoursDucking, usage)

        _playbackState.value = VoicePlaybackState(
            isSpeaking = true,
            activeVoicePackId = voicePack.id,
            activeLine = if (abSlot != null) "VOICE $abSlot (${voicePack.actorName}): \"$text\"" else displayLine,
            progressFraction = 0.02f,
            isAbComparing = abSlot != null,
            activeAbSlot = abSlot,
            activeDspBadge = director.badgeLabel
        )

        try {
            if (playAttentionChime || (modelType == VoiceModelType.USMC_MCRD_BARK && voicePack.aggressionPercent >= 70)) {
                if (modelType == VoiceModelType.USMC_MCRD_BARK || modelType == VoiceModelType.USMC_PARADE_CALLER) {
                    triggerDrillInstructorHaptics()
                    playWhistleCue(voicePack.aggressionPercent)
                } else {
                    triggerTacticalHaptics()
                    playCueTone(frequencyHz = 1200, durationMs = 90)
                }
            }

            val rendered = renderLine(voicePack, director, spokenLine)
            if (rendered == null) {
                // Last resort: let the TTS engine play it directly (unmastered, but at a sane level).
                speakDirectWithSystemTts(voicePack, director, spokenLine, volume)
                return
            }

            val mastered = VoiceMastering.master(rendered.pcm, rendered.sampleRate, boostDbFor(voicePack.volumeBoostPercent))
            Log.i(
                TAG,
                "line source=${rendered.source} voice=${director.geminiVoice} " +
                    "level=${"%.1f".format(mastered.speechRmsDbfs)} dBFS peak=${"%.1f".format(mastered.peakDbfs)} dBFS"
            )
            if (isDebuggable) saveDebugCapture(director, rendered, mastered.pcm)

            playPcm(mastered.pcm, rendered.sampleRate, volume, usage)
        } finally {
            if (abSlot == null && lineCounter.get() == lineId) {
                _playbackState.value = VoicePlaybackState()
            }
        }
    }

    internal suspend fun renderLine(
        voicePack: VoicePackEntity,
        director: DirectorVoice,
        line: String
    ): RenderedLine? {
        val prompt = buildTtsPrompt(director, voicePack, line)
        val cacheKey = sha256("v$VOICE_CACHE_VERSION|${director.geminiVoice}|$prompt")

        readCachedLine(cacheKey)?.let { return it }

        renderWithGemini(director, prompt)?.let { rendered ->
            writeCachedLine(cacheKey, rendered)
            return rendered
        }

        return renderWithSystemTts(voicePack, director, line)
    }

    // ---------------------------------------------------------------------------------------
    // Gemini TTS
    // ---------------------------------------------------------------------------------------

    /**
     * One Gemini attempt, limited to [GEMINI_TIMEOUT_MS]. A timeout cancels the request, so a late reply is
     * never played or cached. A failure, timeout, empty or undecodable reply backs off that voice for
     * [GEMINI_BACKOFF_MS] so each line doesn't wait on a broken service.
     */
    internal suspend fun renderWithGemini(director: DirectorVoice, prompt: String): RenderedLine? {
        val voice = director.geminiVoice
        if (nowMs() < (geminiBackoffUntil[voice] ?: 0L)) return null
        val audio = try {
            withTimeoutOrNull(GEMINI_TIMEOUT_MS) { gemini.synthesize(voice, prompt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return backOffGemini(voice, "Gemini TTS failed", e)
        }
        if (audio == null || audio.bytes.size < 2) {
            return backOffGemini(voice, "Gemini TTS returned no audio (timeout or empty response)")
        }
        val (pcm, sampleRate) = decodeGeminiAudio(audio.bytes, audio.mimeType)
            ?: return backOffGemini(voice, "Gemini TTS returned audio in an unsupported format (${audio.mimeType})")
        return RenderedLine(pcm, sampleRate, "gemini")
    }

    private fun backOffGemini(voice: String, reason: String, error: Exception? = null): RenderedLine? {
        Log.w(TAG, "$reason; $voice uses system TTS for ${GEMINI_BACKOFF_MS / 1000}s", error)
        geminiBackoffUntil[voice] = nowMs() + GEMINI_BACKOFF_MS
        return null
    }

    internal fun buildTtsPrompt(director: DirectorVoice, voicePack: VoicePackEntity, line: String): String {
        val notes = buildList {
            add("Perform the transcript in character as a single, natural take.")
            when {
                voicePack.vocalGritPercent >= 60 -> add("Texture: audible gravel and rasp in the voice.")
                voicePack.vocalGritPercent <= 20 -> add("Texture: clean and smooth.")
            }
            when {
                voicePack.aggressionPercent >= 70 -> add("Intensity: high and projecting, forceful but never distorted.")
                voicePack.aggressionPercent <= 30 -> add("Intensity: calm and contained.")
            }
            when {
                voicePack.speechRate >= 1.15f -> add("Pace: brisk.")
                voicePack.speechRate <= 0.95f -> add("Pace: unhurried.")
            }
            add("Speak only the transcript. Do not read these notes aloud.")
        }
        return buildString {
            appendLine("# AUDIO PROFILE: ${director.personaName}")
            appendLine(director.styleBrief)
            appendLine()
            appendLine("## DIRECTOR'S NOTES")
            notes.forEach { appendLine(it) }
            appendLine()
            appendLine("## TRANSCRIPT")
            append(line)
        }
    }

    // ---------------------------------------------------------------------------------------
    // System TTS fallback
    // ---------------------------------------------------------------------------------------

    private suspend fun renderWithSystemTts(
        voicePack: VoicePackEntity,
        director: DirectorVoice,
        line: String
    ): RenderedLine? {
        val engine = tts ?: return null
        if (!isTtsReady) return null
        configureSystemVoice(engine, voicePack, director)

        val utteranceId = "line_${System.nanoTime()}"
        val wavFile = File(context.cacheDir, "$utteranceId.wav")
        val done = CompletableDeferred<Boolean>()
        pendingUtterances[utteranceId] = done
        try {
            if (engine.synthesizeToFile(line, Bundle(), wavFile, utteranceId) != TextToSpeech.SUCCESS) {
                return null
            }
            val timeoutMs = (3_000L + line.length * 60L).coerceAtMost(20_000L)
            if (withTimeoutOrNull(timeoutMs) { done.await() } != true) return null
            return parseWav(wavFile.readBytes())?.let { (pcm, rate) -> RenderedLine(pcm, rate, "system-tts") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "System TTS file render failed", e)
            return null
        } finally {
            pendingUtterances.remove(utteranceId)
            wavFile.delete()
        }
    }

    private suspend fun speakDirectWithSystemTts(
        voicePack: VoicePackEntity,
        director: DirectorVoice,
        line: String,
        volume: Float
    ) {
        val engine = tts ?: return
        if (!isTtsReady) return
        configureSystemVoice(engine, voicePack, director)
        val utteranceId = "direct_${System.nanoTime()}"
        val done = CompletableDeferred<Boolean>()
        pendingUtterances[utteranceId] = done
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        engine.speak(line, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        withTimeoutOrNull((3_000L + line.length * 90L).coerceAtMost(30_000L)) { done.await() }
        pendingUtterances.remove(utteranceId)
    }

    private fun configureSystemVoice(engine: TextToSpeech, voicePack: VoicePackEntity, director: DirectorVoice) {
        try {
            val locale = Locale.forLanguageTag(voicePack.languageTag)
            engine.language = locale
            engine.voices
                ?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { voice -> voiceScore(voice.name, voice.quality, voice.locale.country == locale.country, director.isFemale) }
                ?.let { engine.voice = it }
            engine.setPitch(clampSystemPitch(voicePack.pitch))
            engine.setSpeechRate(clampSystemRate(voicePack.speechRate))
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------

    private suspend fun playPcm(samples: ShortArray, sampleRate: Int, volume: Float, usage: SpeechUsage) {
        if (samples.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (usage == SpeechUsage.ALARM) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
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
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        activeAudioTrack = track
        try {
            track.write(samples, 0, samples.size)
            track.setVolume(volume)
            track.play()

            val envelope = VoiceMastering.levelEnvelope(samples, sampleRate)
            val windowFrames = sampleRate / 20 // 50 ms, matches levelEnvelope's default window
            val deadline = System.currentTimeMillis() + samples.size * 1000L / sampleRate + 2_000L
            while (System.currentTimeMillis() < deadline) {
                delay(50L)
                val position = track.playbackHeadPosition
                if (position >= samples.size || track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                val level = envelope.getOrElse(position / windowFrames) { 0f }
                _playbackState.value = _playbackState.value.copy(
                    progressFraction = (position.toFloat() / samples.size).coerceIn(0.02f, 1f),
                    spectrumBands = meterBands(level, position)
                )
            }
        } finally {
            if (activeAudioTrack === track) activeAudioTrack = null
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }

    /** Spreads the real playback level across the 16-bar meter the UI draws. */
    private fun meterBands(level: Float, position: Int): List<Float> = List(16) { i ->
        val shape = 0.65f + 0.35f * sin(i * 0.9f + position / 2400f)
        (level * shape).coerceIn(0.05f, 1f)
    }

    private fun stopActiveAudioTrack() {
        val track = activeAudioTrack ?: return
        activeAudioTrack = null
        try {
            track.pause()
            track.flush()
        } catch (_: Exception) {
        }
    }

    /** Bright two-partial cue at its original level, with a 3 ms fade-in so the attack doesn't click. */
    private suspend fun playCueTone(frequencyHz: Int, durationMs: Int) {
        val sampleRate = 44_100
        val n = sampleRate * durationMs / 1000
        val attack = sampleRate * 3 / 1000
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val env = minOf(1.0, i.toDouble() / attack) * (1.0 - i.toDouble() / n)
            val s = (sin(2.0 * PI * frequencyHz * t) * 0.6 + sin(2.0 * PI * frequencyHz * 1.5 * t) * 0.3) * env
            (s * Short.MAX_VALUE).toInt().toShort()
        }
        playSonification(pcm, sampleRate)
    }

    /** Two-tone pea whistle at its original level: louder with aggression, up to 0.8 of full scale. */
    private suspend fun playWhistleCue(aggressionPercent: Int) {
        val sampleRate = 44_100
        val n = sampleRate * 210 / 1000
        val gain = (0.45 + (aggressionPercent / 100.0) * 0.35).coerceIn(0.35, 0.80)
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val env = when {
                i < n * 0.1 -> i / (n * 0.1)
                i > n * 0.8 -> (n - i) / (n * 0.2)
                else -> 1.0
            }
            val trill = 0.65 + 0.35 * cos(2.0 * PI * 38.0 * t)
            val tone = sin(2.0 * PI * 2850.0 * t) * 0.58 + sin(2.0 * PI * 3120.0 * t) * 0.42
            ((tone * trill * env * gain).coerceIn(-0.95, 0.95) * Short.MAX_VALUE).toInt().toShort()
        }
        playSonification(pcm, sampleRate)
    }

    private suspend fun playSonification(pcm: ShortArray, sampleRate: Int) {
        val track = try {
            AudioTrack.Builder()
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
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Exception) {
            return
        }
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(pcm.size * 1000L / sampleRate + 30L)
        } finally {
            track.release()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Voices and text
    // ---------------------------------------------------------------------------------------

    fun resolveDirectorVoice(voicePack: VoicePackEntity): DirectorVoice {
        val packId = voicePack.id.lowercase()
        val stemId = voicePack.stemResourceId.lowercase()
        val actor = voicePack.actorName.lowercase()
        val modelType = runCatching { VoiceModelType.valueOf(voicePack.voiceModelType) }.getOrNull()
        return when {
            stemId.contains("drill") || packId.contains("stone") || actor.contains("stone") -> DirectorVoice(
                id = "drill_sergeant",
                personaName = "GySgt. Marcus Stone, USMC drill instructor",
                isFemale = false,
                geminiVoice = "Algenib",
                styleBrief = DirectorBriefs.DRILL_INSTRUCTOR,
                badgeLabel = "STUDIO VOICE • GYSGT. STONE"
            )
            stemId.contains("fitness") || stemId.contains("jordan") || packId.contains("jordan") ||
                packId == "pack_coach_ray" || actor.contains("jordan") -> DirectorVoice(
                id = "male_fitness",
                personaName = "Coach Jordan Vance, fitness and personal coach",
                isFemale = false,
                geminiVoice = "Algieba",
                styleBrief = DirectorBriefs.FITNESS_COACH,
                badgeLabel = "STUDIO VOICE • COACH JORDAN"
            )
            stemId.contains("executive") || packId.contains("eleanor") || packId.contains("executive") ||
                actor.contains("eleanor") -> DirectorVoice(
                id = "female_executive",
                personaName = "Director Eleanor Vance, Fortune 500 executive",
                isFemale = true,
                geminiVoice = "Kore",
                styleBrief = DirectorBriefs.EXECUTIVE,
                badgeLabel = "STUDIO VOICE • DIRECTOR ELEANOR"
            )
            stemId.contains("motivational") || stemId.contains("maya") || packId.contains("maya") ||
                actor.contains("maya") -> DirectorVoice(
                id = "motivational_coach",
                personaName = "Coach Maya Lin, motivational coach",
                isFemale = true,
                geminiVoice = "Zephyr",
                styleBrief = DirectorBriefs.MOTIVATIONAL_COACH,
                badgeLabel = "STUDIO VOICE • COACH MAYA"
            )
            else -> {
                val isFemale = actor.contains("elena") || actor.contains("nova") || actor.contains("female")
                val (voice, brief) = when (modelType) {
                    VoiceModelType.USMC_MCRD_BARK -> "Alnilam" to DirectorBriefs.DRILL_INSTRUCTOR
                    VoiceModelType.USMC_PARADE_CALLER -> "Alnilam" to DirectorBriefs.PARADE_CALLER
                    VoiceModelType.TACTICAL_COMMS -> (if (isFemale) "Kore" else "Orus") to DirectorBriefs.TACTICAL_COMMS
                    VoiceModelType.KINETIC_COACH -> (if (isFemale) "Zephyr" else "Algieba") to DirectorBriefs.KINETIC_COACH
                    VoiceModelType.CALM_STRATEGIST -> (if (isFemale) "Sulafat" else "Schedar") to DirectorBriefs.CALM_STRATEGIST
                    VoiceModelType.EXEC_NEURAL_HD, null -> (if (isFemale) "Despina" else "Charon") to DirectorBriefs.SENIOR_EXECUTIVE
                }
                DirectorVoice(
                    id = "pack_${voicePack.id}",
                    personaName = voicePack.actorName,
                    isFemale = isFemale,
                    geminiVoice = voice,
                    styleBrief = brief,
                    badgeLabel = "STUDIO VOICE • ${voicePack.actorName.take(16).uppercase()}"
                )
            }
        }
    }

    /** Adds model-specific framing. Casing is left alone (see [softenAllCaps]). */
    private fun formatForVoiceModel(rawText: String, modelType: VoiceModelType, aggressionPercent: Int): String {
        val text = rawText.trim()
        return when (modelType) {
            VoiceModelType.USMC_MCRD_BARK -> {
                val upper = text.uppercase()
                if (aggressionPercent >= 80 && !upper.startsWith("LIGHTS") && !upper.startsWith("GET ON") && !upper.startsWith("ATTENTION")) {
                    "Eyes front! ${text.trimEnd('.', '!', ' ')}! Do it now, move!"
                } else {
                    text
                }
            }
            VoiceModelType.TACTICAL_COMMS -> "Comms active. $text"
            else -> text
        }
    }

    // ---------------------------------------------------------------------------------------
    // Device state
    // ---------------------------------------------------------------------------------------

    /**
     * Full volume; the boost slider is applied in mastering (see [boostDbFor]). A preview the person asked to have ducked is ducked inside
     * their rest windows. An alarm never is: it was set to sound.
     */
    private suspend fun playbackVolume(quietHoursDucking: Boolean, usage: SpeechUsage): Float =
        if (usage != SpeechUsage.ALARM && quietHoursDucking && quietWindow.isQuiet(nowMs())) 0.45f else 1f

    private fun isHeadphoneConnected(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun triggerDrillInstructorHaptics() = vibrate(longArrayOf(0, 90, 45, 90, 45, 140))

    private fun triggerTacticalHaptics() = vibrate(longArrayOf(0, 80, 40, 100))

    private fun vibrate(pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cache and debug capture
    // ---------------------------------------------------------------------------------------

    private fun readCachedLine(key: String): RenderedLine? = try {
        val file = File(voiceCacheDir, "$key.pcm")
        if (!file.exists() || file.length() < 6) {
            null
        } else {
            val bytes = file.readBytes()
            val rate = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            file.setLastModified(System.currentTimeMillis())
            RenderedLine(bytesToPcm16(bytes, 4, bytes.size - 4), rate, "cache")
        }
    } catch (_: Exception) {
        null
    }

    private fun writeCachedLine(key: String, line: RenderedLine) {
        try {
            val buffer = ByteBuffer.allocate(4 + line.pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(line.sampleRate)
            line.pcm.forEach { buffer.putShort(it) }
            val tmp = File(voiceCacheDir, "$key.tmp")
            tmp.writeBytes(buffer.array())
            tmp.renameTo(File(voiceCacheDir, "$key.pcm"))

            val files = voiceCacheDir.listFiles { f -> f.name.endsWith(".pcm") } ?: return
            if (files.size > VOICE_CACHE_MAX_FILES) {
                files.sortedBy { it.lastModified() }.take(files.size - VOICE_CACHE_MAX_FILES).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cache rendered line", e)
        }
    }

    /**
     * Debug builds only: writes the raw render and the mastered output side by side so they can be
     * compared by ear. Pull with: adb pull /sdcard/Android/data/<applicationId>/files/voice_debug
     */
    private fun saveDebugCapture(director: DirectorVoice, rendered: RenderedLine, mastered: ShortArray) {
        try {
            val dir = context.getExternalFilesDir(DEBUG_CAPTURE_FOLDER) ?: return
            val stamp = System.currentTimeMillis()
            writeWav(File(dir, "${stamp}_${director.id}_${rendered.source}_raw.wav"), rendered.pcm, rendered.sampleRate)
            writeWav(File(dir, "${stamp}_${director.id}_${rendered.source}_mastered.wav"), mastered, rendered.sampleRate)
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** The external folder of the debug captures. [com.command.alarm.DataEraser] takes it with the rest, and DeleteLeavesNothingTest looks. */
        const val DEBUG_CAPTURE_FOLDER = "voice_debug"

        /** The pitch and the rate the phone's engine is really asked for: the voice screen shows these, and the speaker sets them. */
        fun clampSystemPitch(pitch: Float) = pitch.coerceIn(SYSTEM_TTS_MIN_PITCH, SYSTEM_TTS_MAX_PITCH)
        fun clampSystemRate(rate: Float) = rate.coerceIn(SYSTEM_TTS_MIN_RATE, SYSTEM_TTS_MAX_RATE)

        /** Boost slider as real gain above the speech target, matching Voice Studio's label: +1 dB per 10% (150% -> +5 dB). */
        internal fun boostDbFor(boostPercent: Int): Float =
            ((boostPercent - 100) / 10f).coerceIn(0f, VoiceMastering.MAX_BOOST_DB)

        /** Acronyms the drill copy uses that must stay spelled out after all-caps text is softened. */
        private val KEEP_UPPERCASE = setOf("USMC", "MCRD", "NCO", "PT", "MOS", "XO")

        /** Converts shouted all-caps text to sentence case so TTS doesn't spell words out as acronyms. */
        internal fun softenAllCaps(text: String): String {
            val letters = text.filter { it.isLetter() }
            if (letters.length < 4 || letters.count { it.isUpperCase() } < letters.length * 0.8) return text
            return Regex("(^|[.!?]\\s+)(\\p{L})")
                .replace(text.lowercase()) { it.groupValues[1] + it.groupValues[2].uppercase() }
                .replace(Regex("\\bi\\b"), "I")
                .replace(Regex("\\b\\p{L}+\\b")) { word ->
                    word.value.uppercase().takeIf { it in KEEP_UPPERCASE } ?: word.value
                }
        }

        // Google's engine names voices by code (en-us-x-iol-local) rather than by gender.
        private val GOOGLE_FEMALE_VOICE_CODES = setOf("iob", "iog", "sfg", "tpc", "tpf")
        private val GOOGLE_MALE_VOICE_CODES = setOf("iol", "iom", "tpd")

        /**
         * How well an installed voice suits a pack; the highest is used. A known gender outweighs quality (at most 500), so Stone never gets a
         * female voice when a male one can be identified. A pack that does not say ([wantFemale] null) goes by quality and country alone.
         */
        internal fun voiceScore(voiceName: String, quality: Int, sameCountry: Boolean, wantFemale: Boolean?): Int {
            var score = quality
            if (sameCountry) score += 50
            if (wantFemale != null) voiceIsFemale(voiceName)?.let { score += if (it == wantFemale) 1_000 else -1_000 }
            return score
        }

        /** True for a female voice, false for male, null when the name doesn't say. */
        internal fun voiceIsFemale(voiceName: String): Boolean? {
            val name = voiceName.lowercase()
            if (Regex("\\b(female|woman)\\b").containsMatchIn(name)) return true
            if (Regex("\\b(male|man)\\b").containsMatchIn(name)) return false
            return when (Regex("^[a-z]{2,3}-[a-z]{2}-x-([a-z]{3})").find(name)?.groupValues?.get(1)) {
                in GOOGLE_FEMALE_VOICE_CODES -> true
                in GOOGLE_MALE_VOICE_CODES -> false
                else -> null
            }
        }

        /**
         * Gemini TTS audio: preview models return raw 16-bit PCM (rate in the MIME type), newer ones a
         * complete WAV. Detecting the RIFF header keeps the WAV header from being played as sound.
         */
        internal fun decodeGeminiAudio(bytes: ByteArray, mimeType: String): Pair<ShortArray, Int>? {
            if (bytes.size >= 12 && String(bytes, 0, 4) == "RIFF") return parseWav(bytes)
            val rate = Regex("rate=(\\d+)").find(mimeType)?.groupValues?.get(1)?.toIntOrNull()
                ?: GEMINI_DEFAULT_SAMPLE_RATE
            return bytesToPcm16(bytes, 0, bytes.size) to rate
        }

        /** Reads a 16-bit PCM WAV by walking its chunks (headers are not always 44 bytes). Downmixes to mono. */
        internal fun parseWav(bytes: ByteArray): Pair<ShortArray, Int>? {
            if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var channels = 1
            var sampleRate = 0
            var bits = 0
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4)
                val size = buf.getInt(offset + 4)
                val body = offset + 8
                if (size < 0) return null
                when (id) {
                    "fmt " -> {
                        channels = buf.getShort(body + 2).toInt().coerceAtLeast(1)
                        sampleRate = buf.getInt(body + 4)
                        bits = buf.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        if (bits != 16 || sampleRate <= 0) return null
                        val length = minOf(size, bytes.size - body)
                        val interleaved = bytesToPcm16(bytes, body, length)
                        if (channels == 1) return interleaved to sampleRate
                        val mono = ShortArray(interleaved.size / channels) { f ->
                            var sum = 0
                            for (c in 0 until channels) sum += interleaved[f * channels + c]
                            (sum / channels).toShort()
                        }
                        return mono to sampleRate
                    }
                }
                offset = body + size + (size and 1)
            }
            return null
        }

        internal fun bytesToPcm16(bytes: ByteArray, offset: Int, length: Int): ShortArray {
            val shorts = ByteBuffer.wrap(bytes, offset, length - (length and 1)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(shorts.remaining()).also { shorts.get(it) }
        }

        private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
            val dataBytes = pcm.size * 2
            val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(dataBytes)
            pcm.forEach { b.putShort(it) }
            file.writeBytes(b.array())
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
