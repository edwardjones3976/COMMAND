package com.command.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioTrack
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.command.domain.voice.QuietWindowPolicy
import com.command.domain.voice.SpeechUsage
import com.command.domain.voice.VoicePackEntity
import com.command.testing.VoicePackFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAudioTrack
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** The speech context reaches the audio: which stream a line plays on, and when it is ducked. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], shadows = [VoiceEngineContextTest.WatchingAudioTrack::class])
class VoiceEngineContextTest {

    @Implements(AudioTrack::class)
    class WatchingAudioTrack : ShadowAudioTrack() {
        @RealObject private lateinit var track: AudioTrack

        @Implementation
        protected fun native_write_short(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, format: Int, isBlocking: Boolean): Int {
            usages += track.audioAttributes.usage
            return sizeInShorts
        }

        @Implementation
        protected fun native_setVolume(left: Float, right: Float) {
            volumes += left
        }

        companion object {
            val usages = CopyOnWriteArrayList<Int>()
            val volumes = CopyOnWriteArrayList<Float>()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pack: VoicePackEntity = VoicePackFixtures.defaultVoicePacks().first { it.aggressionPercent < 70 }

    /** Sixteen-bit PCM at 24 kHz, half a second. */
    private val speech = GeminiSpeechSource { _, _ ->
        val b = ByteBuffer.allocate(24_000).order(ByteOrder.LITTLE_ENDIAN)
        repeat(12_000) { b.putShort(((it % 100) * 200).toShort()) }
        GeminiAudio(b.array(), "audio/L16;rate=24000")
    }

    @Before
    fun clear() {
        WatchingAudioTrack.usages.clear()
        WatchingAudioTrack.volumes.clear()
        context.cacheDir.resolve("director_voice").deleteRecursively()
    }

    @After
    fun end() = scope.cancel()

    private fun play(usage: SpeechUsage, ducking: Boolean, quiet: Boolean) {
        val engine = VoiceDirectorEngine(context, scope, speech, QuietWindowPolicy { quiet }) { 0L }
        engine.speakWithVoicePack(pack, "Hello there.", quietHoursDucking = ducking, usage = usage)
        val deadline = System.currentTimeMillis() + 20_000
        while (WatchingAudioTrack.volumes.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        engine.shutdown()
    }

    @Test
    fun `an alarm line plays on the alarm stream`() {
        play(SpeechUsage.ALARM, ducking = false, quiet = false)
        assertEquals(listOf(AudioAttributes.USAGE_ALARM), WatchingAudioTrack.usages.filter { it != AudioAttributes.USAGE_ASSISTANCE_SONIFICATION })
    }

    @Test
    fun `a preview plays as media`() {
        play(SpeechUsage.PREVIEW, ducking = false, quiet = false)
        assertEquals(listOf(AudioAttributes.USAGE_MEDIA), WatchingAudioTrack.usages.filter { it != AudioAttributes.USAGE_ASSISTANCE_SONIFICATION })
    }

    @Test
    fun `a preview that asked for it is ducked inside a quiet window`() {
        play(SpeechUsage.PREVIEW, ducking = true, quiet = true)
        assertEquals(0.45f, WatchingAudioTrack.volumes.first(), 0.001f)
    }

    @Test
    fun `a preview is not ducked outside a quiet window`() {
        play(SpeechUsage.PREVIEW, ducking = true, quiet = false)
        assertEquals(1f, WatchingAudioTrack.volumes.first(), 0.001f)
    }

    @Test
    fun `an alarm is never ducked`() {
        play(SpeechUsage.ALARM, ducking = true, quiet = true)
        assertEquals(1f, WatchingAudioTrack.volumes.first(), 0.001f)
    }
}
