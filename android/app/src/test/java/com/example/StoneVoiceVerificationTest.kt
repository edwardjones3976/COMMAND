package com.example

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.example.data.CommandRepository
import com.example.data.VoiceModelType
import com.example.data.VoicePackEntity
import com.example.service.DirectorBriefs
import com.example.service.DirectorVoice
import com.example.service.VoiceDirectorEngine
import com.example.service.VoiceMastering
import com.example.service.VoicePlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * End-to-end checks of GySgt. Marcus Stone's voice: which Gemini voice and brief he gets, what text is
 * spoken, what Gemini is told, and that the disk cache, the system-TTS fallback and A/B comparison all
 * reach mastering. Tests named `finding - ...` pin down current behavior that differs from what the
 * voice fix described, so each finding has a passing reproduction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [CapturingAudioTrack::class])
class StoneVoiceVerificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packs = CommandRepository.defaultVoicePacks().associateBy { it.id }
    private val stone = packs.getValue("pack_major_stone")
    private val sampleRate = 24_000
    private val ceiling = (32767 * VoiceMastering.dbToLin(VoiceMastering.PEAK_CEILING_DBFS)).toInt() + 1
    private val engines = mutableListOf<VoiceDirectorEngine>()

    @Before
    fun setUp() {
        ShadowTextToSpeech.reset()
        ShadowLog.clear()
        CapturingAudioTrack.writes.clear()
        File(context.cacheDir, "director_voice").deleteRecursively()
    }

    @After
    fun tearDown() {
        engines.forEach { it.shutdown() }
    }

    // ---------------------------------------------------------------------------------------
    // Voice, brief and Gemini direction
    // ---------------------------------------------------------------------------------------

    @Test
    fun `stone gets the gravelly Algenib voice and the drill instructor brief`() {
        val engine = newEngine()
        val director = engine.resolveDirectorVoice(stone)
        assertEquals("drill_sergeant", director.id)
        assertEquals("Algenib", director.geminiVoice)
        assertEquals(DirectorBriefs.DRILL_INSTRUCTOR, director.styleBrief)
        assertFalse(director.isFemale)
        assertTrue(director.personaName.contains("Marcus Stone"))

        // The other three reference directors keep their own voices.
        assertEquals("Algieba", engine.resolveDirectorVoice(packs.getValue("pack_coach_ray")).geminiVoice)
        assertEquals("Kore", engine.resolveDirectorVoice(packs.getValue("pack_eleanor_vance")).geminiVoice)
        assertEquals("Zephyr", engine.resolveDirectorVoice(packs.getValue("pack_coach_maya")).geminiVoice)
    }

    @Test
    fun `gemini is told gravel, high intensity and a brisk pace, and not to read the notes`() {
        val engine = newEngine()
        val prompt = engine.buildTtsPrompt(engine.resolveDirectorVoice(stone), stone, "Move!")
        listOf(
            "# AUDIO PROFILE: GySgt. Marcus Stone, USMC drill instructor",
            "Texture: audible gravel and rasp in the voice.",
            "Intensity: high and projecting, forceful but never distorted.",
            "Pace: brisk.",
            "Speak only the transcript. Do not read these notes aloud."
        ).forEach { assertTrue("prompt is missing: $it", prompt.contains(it)) }
        assertTrue(prompt.endsWith("## TRANSCRIPT\nMove!"))
    }

    // ---------------------------------------------------------------------------------------
    // Spoken text
    // ---------------------------------------------------------------------------------------

    @Test
    fun `reveille is framed for aggression 95 and softened so it isn't spelled out`() {
        assertEquals(
            "Eyes front! Eyeballs! Reveille on deck! Drop every distraction right now! " +
                "Lock in your handshake before I count to three! Move! Do it now, move!",
            spokenLine(newEngine(), stone.reveilleScript)
        )
    }

    @Test
    fun `short lines speak the command, not a narration`() {
        val spoken = spokenLine(newEngine(), "Move.")
        assertEquals("Eyes front! Move! Do it now, move!", spoken)
        assertFalse(spoken.contains("squad bay"))
    }

    @Test
    fun `finding - all-caps acronyms lose their capitals`() {
        // Softening lowercases every letter, so the unit name is read as a word ("Usmc") instead of U-S-M-C.
        assertEquals(
            "Usmc mcrd san diego, fall in! Move!",
            VoiceDirectorEngine.softenAllCaps("USMC MCRD SAN DIEGO, FALL IN! MOVE!")
        )
    }

    // ---------------------------------------------------------------------------------------
    // Loudness: boost, limiter, whistle
    // ---------------------------------------------------------------------------------------

    @Test
    fun `stone's 145 percent boost adds 4_5 dB of real gain and the slider caps at 8 dB`() {
        assertEquals(4.5f, VoiceDirectorEngine.boostDbFor(stone.volumeBoostPercent), 0.001f)
        assertEquals(VoiceMastering.MAX_BOOST_DB, VoiceDirectorEngine.boostDbFor(250), 0.001f)

        // A steady quiet tone stays far below the compressor and limiter, so the level change is the boost alone.
        val tone = ShortArray(sampleRate * 2) { (sin(2 * PI * 300.0 * it / sampleRate) * 1_000).toInt().toShort() }
        val plain = VoiceMastering.master(tone, sampleRate, boostDb = 0f).speechRmsDbfs
        val boosted = VoiceMastering.master(tone, sampleRate, boostDb = 4.5f).speechRmsDbfs
        assertEquals(4.5f, boosted - plain, 0.3f)
    }

    @Test
    fun `limiter holds a full-scale square wave and inter-sample peaks under the ceiling at max boost`() {
        val square = ShortArray(sampleRate) { if ((it / 40) % 2 == 0) 32_767 else -32_767 }
        // A quarter-sample-rate sine sampled at 45 degrees: samples sit at 0.707 while the waveform peaks at 1.0.
        val interSample = ShortArray(sampleRate) { (sin(PI / 2 * it + PI / 4) * 32_767).toInt().toShort() }
        listOf(square, interSample).forEach { input ->
            val out = VoiceMastering.master(input, sampleRate, boostDb = VoiceMastering.MAX_BOOST_DB)
            assertTrue(out.pcm.maxOf { abs(it.toInt()) } <= ceiling)
            assertTrue(out.peakDbfs <= VoiceMastering.PEAK_CEILING_DBFS + 0.05f)
        }
    }

    @Test
    fun `stone's whistle plays before the line at about 0_78 of full scale and the line follows mastered`() {
        seedCache(newEngine(), stone.reveilleScript)
        assertTrue(newEngine().speakWithVoicePack(stone, stone.reveilleScript))
        awaitLineLogs(1)
        val whistle = awaitWrite { it.sampleRate == 44_100 }
        val line = awaitWrite { it.sampleRate == sampleRate }

        assertEquals("whistle comes first", whistle, CapturingAudioTrack.writes.first())
        val whistlePeak = whistle.pcm.maxOf { abs(it.toInt()) } / 32767.0
        assertEquals(0.78, whistlePeak, 0.03) // gain 0.45 + 0.95 x 0.35 = 0.7825, the original level
        assertTrue(line.pcm.maxOf { abs(it.toInt()) } <= ceiling)
    }

    // ---------------------------------------------------------------------------------------
    // Audio formats
    // ---------------------------------------------------------------------------------------

    @Test
    fun `raw pcm from the current preview model decodes sample for sample`() {
        val pcm = ShortArray(240) { (it * 97 - 12_000).toShort() }
        val bytes = pcmBytes(pcm)
        assertTrue(VoiceDirectorEngine.bytesToPcm16(bytes, 0, bytes.size).contentEquals(pcm))
    }

    @Test
    fun `finding - a WAV reply from a newer TTS model would be played header and all`() {
        // renderWithGemini passes the inline bytes straight to bytesToPcm16. That is right for
        // gemini-3.1-flash-tts-preview's raw PCM, but gemini-3.8-flash-tts returns a WAV by default.
        val pcm = ShortArray(240) { (sin(2 * PI * it / 24) * 8_000).toInt().toShort() }
        val wavBytes = wav(pcm, 24_000)
        val asGeminiDecodesIt = VoiceDirectorEngine.bytesToPcm16(wavBytes, 0, wavBytes.size)
        assertEquals(pcm.size + 22, asGeminiDecodesIt.size)
        assertEquals(0x4952, asGeminiDecodesIt[0].toInt()) // "RI" of "RIFF", played as a sample
        assertTrue(VoiceDirectorEngine.parseWav(wavBytes)!!.first.contentEquals(pcm))
    }

    @Test
    fun `wav parser handles odd-sized chunks, truncated data and bad sizes from the phone's voice`() {
        val pcm = ShortArray(50) { (it * 300).toShort() }
        val oddList = "LIST".toByteArray() + intLe(5) + "abcde".toByteArray() + byteArrayOf(0) // RIFF pads odd chunks
        assertTrue(VoiceDirectorEngine.parseWav(wav(pcm, 22_050, extraChunk = oddList))!!.first.contentEquals(pcm))

        val truncated = wav(pcm, 22_050).copyOf(44 + 60) // engine stopped mid-write: 30 of 50 samples on disk
        assertEquals(30, VoiceDirectorEngine.parseWav(truncated)!!.first.size)

        val badSize = wav(pcm, 22_050).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1) }
        assertNull(VoiceDirectorEngine.parseWav(badSize))
    }

    // ---------------------------------------------------------------------------------------
    // Backup voice (system TTS)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `backup voice keeps stone's low pitch and caps his speed at 1_20`() {
        assertEquals(0.86f, VoiceDirectorEngine.clampSystemPitch(stone.pitch), 0.001f)
        assertEquals(1.20f, VoiceDirectorEngine.clampSystemRate(stone.speechRate), 0.001f)
    }

    @Test
    fun `finding - stone's backup voice can be female when voice names don't say male or female`() {
        // Google's engine names voices like en-us-x-xxx-local, with no gender in the name. The picker then ranks
        // on quality alone, so Stone and a female director end up with the same voice.
        ShadowTextToSpeech.addVoice(Voice("en-us-x-aaa-local", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()))
        ShadowTextToSpeech.addVoice(Voice("en-us-x-bbb-local", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()))
        val engine = newEngine()
        val eleanor = packs.getValue("pack_eleanor_vance")

        configureSystemVoice(engine, stone)
        val stoneVoice = shadowTts().currentVoice.name
        configureSystemVoice(engine, eleanor)
        val eleanorVoice = shadowTts().currentVoice.name

        assertEquals("en-us-x-aaa-local", stoneVoice)
        assertEquals(stoneVoice, eleanorVoice)
    }

    // ---------------------------------------------------------------------------------------
    // Whole lines through the engine: cache, fallback, A/B
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a saved render is reused from disk by a fresh engine without calling Gemini`() {
        seedCache(newEngine(), stone.reveilleScript)

        val second = newEngine() // nothing in memory: the hit has to come from disk
        assertTrue(second.speakWithVoicePack(stone, stone.reveilleScript))
        val log = awaitLineLogs(1).single()
        assertTrue(log, log.startsWith("line source=cache voice=Algenib"))
        assertNoGeminiAttempt()
        assertMasteredForStone(log)
    }

    @Test
    fun `when Gemini is held off the phone's voice renders the line and it is mastered once`() {
        val engine = newEngine()
        engine.onInit(TextToSpeech.SUCCESS)
        holdOffGemini(engine)

        assertTrue(engine.speakWithVoicePack(stone, "Move."))
        answerSyntheses(1)
        val log = awaitLineLogs(1).single()
        assertTrue(log, log.startsWith("line source=system-tts voice=Algenib"))
        assertEquals("Eyes front! Move! Do it now, move!", shadowTts().lastSynthesizeToFileText)
        assertNoGeminiAttempt()
        assertMasteredForStone(log)
    }

    @Test
    fun `A-B comparison plays voice A then voice B and then resets`() {
        val engine = newEngine()
        engine.onInit(TextToSpeech.SUCCESS)
        holdOffGemini(engine)
        val eleanor = packs.getValue("pack_eleanor_vance")

        engine.runAbComparison(stone, eleanor, "Move.")
        val slots = mutableListOf<String>()
        val answered = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            engine.playbackState.value.activeAbSlot?.let { if (slots.lastOrNull() != it) slots += it }
            answerPendingSynthesis(answered)
            if (lineLogs().size == 2 && engine.playbackState.value == VoicePlaybackState()) break
            Thread.sleep(10)
        }

        assertEquals(listOf("A", "B"), slots)
        val logs = lineLogs()
        assertEquals(2, logs.size)
        assertTrue(logs[0], logs[0].contains("voice=Algenib"))
        assertTrue(logs[1], logs[1].contains("voice=Kore"))
        assertEquals(VoicePlaybackState(), engine.playbackState.value)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun newEngine(): VoiceDirectorEngine =
        VoiceDirectorEngine(context, CoroutineScope(Dispatchers.Unconfined)).also { engines += it }

    /** Writes the disk-cache entry a Gemini render of [text] would have left, keyed exactly as the engine keys it. */
    private fun seedCache(engine: VoiceDirectorEngine, text: String) {
        val director = engine.resolveDirectorVoice(stone)
        val line = spokenLine(engine, text)
        val key = sha256("v1|${director.geminiVoice}|${engine.buildTtsPrompt(director, stone, line)}")
        File(context.cacheDir, "director_voice").apply { mkdirs() }.resolve("$key.pcm")
            .writeBytes(intLe(sampleRate) + pcmBytes(speechLike(9_000.0)))
    }

    private fun awaitWrite(match: (CapturingAudioTrack.Write) -> Boolean): CapturingAudioTrack.Write {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            CapturingAudioTrack.writes.firstOrNull(match)?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no matching audio was played")
    }

    private fun shadowTts() =shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    private fun spokenLine(engine: VoiceDirectorEngine, text: String, pack: VoicePackEntity = stone): String {
        val format = VoiceDirectorEngine::class.java.getDeclaredMethod(
            "formatForVoiceModel", String::class.java, VoiceModelType::class.java, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val display = format.invoke(engine, text, VoiceModelType.valueOf(pack.voiceModelType), pack.aggressionPercent) as String
        return VoiceDirectorEngine.softenAllCaps(display)
    }

    private fun configureSystemVoice(engine: VoiceDirectorEngine, pack: VoicePackEntity) {
        VoiceDirectorEngine::class.java.getDeclaredMethod(
            "configureSystemVoice", TextToSpeech::class.java, VoicePackEntity::class.java, DirectorVoice::class.java
        ).apply { isAccessible = true }
            .invoke(engine, ShadowTextToSpeech.getLastTextToSpeechInstance(), pack, engine.resolveDirectorVoice(pack))
    }

    /** Puts the engine in the state a Gemini failure (such as an App Check 401) leaves it in for 120 s. */
    private fun holdOffGemini(engine: VoiceDirectorEngine) {
        VoiceDirectorEngine::class.java.getDeclaredField("geminiBackoffUntil")
            .apply { isAccessible = true }
            .setLong(engine, Long.MAX_VALUE)
    }

    /** Plays the phone's TTS engine: writes a speech-like WAV where it was asked to and reports the utterance done. */
    private fun answerPendingSynthesis(answered: MutableSet<String>): Boolean {
        val file = shadowTts().lastSynthesizeToFile ?: return false
        val utteranceId = file.nameWithoutExtension
        if (!answered.add(utteranceId)) return false
        file.writeBytes(wav(speechLike(9_000.0, seconds = 1.0), 22_050))
        shadowTts().utteranceProgressListener.onDone(utteranceId)
        return true
    }

    private fun answerSyntheses(count: Int) {
        val answered = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + 20_000
        while (answered.size < count && System.currentTimeMillis() < deadline) {
            if (!answerPendingSynthesis(answered)) Thread.sleep(10)
        }
        assertEquals("system TTS was not asked to render", count, answered.size)
    }

    private fun lineLogs(): List<String> =
        ShadowLog.getLogsForTag("VoiceDirectorEngine").map { it.msg }.filter { it.startsWith("line source=") }

    private fun awaitLineLogs(count: Int): List<String> {
        val deadline = System.currentTimeMillis() + 20_000
        while (lineLogs().size < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return lineLogs().also { assertEquals("lines reaching mastering", count, it.size) }
    }

    private fun assertNoGeminiAttempt() {
        val gemini = ShadowLog.getLogsForTag("VoiceDirectorEngine").map { it.msg }.filter { it.contains("Gemini") }
        assertTrue("unexpected Gemini activity: $gemini", gemini.isEmpty())
    }

    /** Stone's line lands near the speech target plus his +4.5 dB boost, with peaks held at the ceiling. */
    private fun assertMasteredForStone(log: String) {
        val match = Regex("level=(-?[0-9.]+) dBFS peak=(-?[0-9.]+) dBFS").find(log)!!
        val level = match.groupValues[1].toFloat()
        val peak = match.groupValues[2].toFloat()
        assertEquals(VoiceMastering.TARGET_SPEECH_DBFS + 4.5f, level, 2f)
        assertTrue("peak $peak dBFS is over the ceiling", peak <= VoiceMastering.PEAK_CEILING_DBFS + 0.05f)
    }

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

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun intLe(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun pcmBytes(pcm: ShortArray): ByteArray =
        ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()

    private fun wav(pcm: ShortArray, rate: Int, extraChunk: ByteArray = ByteArray(0)): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(12 + 24 + extraChunk.size + 8 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(4 + 24 + extraChunk.size + 8 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put(extraChunk)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}
