package com.command.voice

import com.command.domain.voice.Line
import com.command.domain.voice.RoutePolicy
import com.command.domain.voice.Segment
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class LocalTtsSpeakerTest {

    private class FakeSynth(private val result: () -> Synthesis) : SpeechSynthesizer {
        val asked = ArrayList<String>()
        var shutdowns = 0
        var prepares = 0
        override fun prepare() {
            prepares++
        }

        override suspend fun synthesize(text: String): Synthesis {
            asked += text
            return result()
        }

        override fun shutdown() {
            shutdowns++
        }
    }

    private class FakePlayer : PcmPlayer {
        val played = ArrayList<Triple<ShortArray, Int, SpeechUsage>>()
        val routes = ArrayList<RoutePolicy>()
        var stops = 0
        var failWith: Exception? = null
        var waitForStop: CompletableDeferred<Unit>? = null
        var durationMs = 1_500L

        override suspend fun play(pcm: ShortArray, sampleRate: Int, usage: SpeechUsage, route: RoutePolicy): Long {
            failWith?.let { throw it }
            routes += route
            played += Triple(pcm, sampleRate, usage)
            waitForStop?.await()
            return durationMs
        }

        override fun stop() {
            stops++
            waitForStop?.complete(Unit)
        }
    }

    /** A second of a quiet tone, the kind of audio an engine hands back. */
    private fun tone(amplitude: Int = 3_000, rate: Int = 24_000) =
        SpokenAudio(ShortArray(rate) { (sin(it * 2 * Math.PI * 220 / rate) * amplitude).toInt().toShort() }, rate)

    private fun speaker(synth: FakeSynth, player: FakePlayer = FakePlayer(), master: ((SpokenAudio) -> ShortArray)? = null) =
        if (master == null) LocalTtsSpeaker(synth, player) else LocalTtsSpeaker(synth, player, master)

    @Test
    fun `the words of the line are what the engine is asked to say, in order, and the audio is played for an alarm`() = runTest {
        val synth = FakeSynth { Synthesis.Audio(tone()) }
        val player = FakePlayer()
        val line = Line(listOf(Segment("Time to start."), Segment("Write the report", carriesUserText = true)))

        val result = speaker(synth, player).speak(line, SpeechUsage.ALARM)

        assertEquals(listOf("Time to start. Write the report"), synth.asked)
        assertEquals(SpeakResult.Spoken(1_500), result)
        assertEquals(SpeechUsage.ALARM, player.played.single().third)
        assertEquals(24_000, player.played.single().second)
    }

    @Test
    fun `what is played has been levelled and held under the ceiling`() = runTest {
        val synth = FakeSynth { Synthesis.Audio(tone(amplitude = 800)) }
        val player = FakePlayer()
        speaker(synth, player).speak(Line.of("Hello"), SpeechUsage.ALARM)

        val played = player.played.single().first
        val peakDbfs = 20 * Math.log10(played.maxOf { abs(it.toInt()) } / 32768.0)
        assertTrue("peak $peakDbfs dBFS is over the ceiling", peakDbfs <= VoiceMastering.PEAK_CEILING_DBFS + 0.05)
        assertTrue("a quiet line is brought up, not left as it was", played.maxOf { abs(it.toInt()) } > 800)
    }

    @Test
    fun `a preview is played as a preview`() = runTest {
        val player = FakePlayer()
        speaker(FakeSynth { Synthesis.Audio(tone()) }, player).speak(Line.of("Hello"), SpeechUsage.PREVIEW)
        assertEquals(SpeechUsage.PREVIEW, player.played.single().third)
    }

    @Test
    fun `an engine that is missing or too slow is reported with its reason and nothing is played`() = runTest {
        for (reason in listOf(SpeechProblem.NO_ENGINE, SpeechProblem.INIT_TIMEOUT, SpeechProblem.SYNTHESIS_FAILED)) {
            val player = FakePlayer()
            val result = speaker(FakeSynth { Synthesis.Failed(reason) }, player).speak(Line.of("Hello"), SpeechUsage.ALARM)
            assertEquals(SpeakResult.Unavailable(reason), result)
            assertTrue(player.played.isEmpty())
        }
    }

    @Test
    fun `audio that cannot be played is reported as unavailable, not as a crash`() = runTest {
        val player = FakePlayer().apply { failWith = IllegalStateException("no sound hardware") }
        val result = speaker(FakeSynth { Synthesis.Audio(tone()) }, player).speak(Line.of("Hello"), SpeechUsage.ALARM)
        assertEquals(SpeakResult.Unavailable(SpeechProblem.PLAYBACK_FAILED), result)
    }

    @Test
    fun `an empty line is nothing to say, so the engine is never asked`() = runTest {
        val synth = FakeSynth { Synthesis.Audio(tone()) }
        assertEquals(SpeakResult.Spoken(0), speaker(synth).speak(Line(listOf(Segment("  "))), SpeechUsage.ALARM))
        assertTrue(synth.asked.isEmpty())
    }

    @Test
    fun `stopping while it plays cuts the sound off and says it was interrupted`() = runTest {
        val player = FakePlayer().apply { waitForStop = CompletableDeferred() }
        val speaker = speaker(FakeSynth { Synthesis.Audio(tone()) }, player)

        val result = async { speaker.speak(Line.of("Hello"), SpeechUsage.ALARM) }
        while (player.played.isEmpty()) kotlinx.coroutines.yield()
        speaker.stop()

        assertEquals(SpeakResult.Interrupted, result.await())
        assertEquals(1, player.stops)
    }

    @Test
    fun `stopping before the audio is even ready means it is never played`() = runTest {
        val player = FakePlayer()
        val ready = CompletableDeferred<Unit>()
        lateinit var speaker: LocalTtsSpeaker
        val synth = object : SpeechSynthesizer {
            override suspend fun synthesize(text: String): Synthesis {
                ready.await()
                return Synthesis.Audio(tone())
            }

            override fun shutdown() = Unit
        }
        speaker = LocalTtsSpeaker(synth, player)

        val result = async { speaker.speak(Line.of("Hello"), SpeechUsage.ALARM) }
        kotlinx.coroutines.yield()
        speaker.stop()
        ready.complete(Unit)

        assertEquals(SpeakResult.Interrupted, result.await())
        assertTrue(player.played.isEmpty())
    }

    @Test
    fun `cancelling the coroutine that is speaking stops the sound and lets the cancellation through`() = runTest {
        val player = FakePlayer().apply { waitForStop = CompletableDeferred() }
        val speaker = LocalTtsSpeaker(FakeSynth { Synthesis.Audio(tone()) }, player, master = { it.pcm })
        val job = async { speaker.speak(Line.of("Hello"), SpeechUsage.ALARM) }
        while (player.played.isEmpty()) kotlinx.coroutines.yield()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals("the sound is stopped, not left playing", 1, player.stops)
    }

    @Test
    fun `a new line after a stop is spoken, because a stop only ends what was playing`() = runTest {
        val speaker = speaker(FakeSynth { Synthesis.Audio(tone()) })
        speaker.stop()
        assertEquals(SpeakResult.Spoken(1_500), speaker.speak(Line.of("Hello"), SpeechUsage.ALARM))
    }

    @Test
    fun `releasing lets go of the engine`() {
        val synth = FakeSynth { Synthesis.Audio(tone()) }
        LocalTtsSpeaker(synth, FakePlayer()).release()
        assertEquals(1, synth.shutdowns)
    }

    @Test
    fun `a phone with no engine at all says nothing and says why`() = runTest {
        assertEquals(SpeakResult.Unavailable(SpeechProblem.NO_ENGINE), SilentSpeaker().speak(Line.of("Hello"), SpeechUsage.ALARM))
        assertFalse(SilentSpeaker().speak(Line.of("x"), SpeechUsage.PREVIEW) is SpeakResult.Spoken)
    }

    @Test
    fun `the caller is told just before the first sound, after the engine and the levelling, and never when nothing will be heard`() = runTest {
        val order = ArrayList<String>()
        val synth = FakeSynth { order += "engine"; Synthesis.Audio(tone()) }
        val player = object : PcmPlayer {
            override suspend fun play(pcm: ShortArray, sampleRate: Int, usage: SpeechUsage, route: RoutePolicy): Long {
                order += "play"
                return 1_000
            }

            override fun stop() = Unit
        }
        val loud = LocalTtsSpeaker(synth, player) { order += "level"; it.pcm }

        assertEquals(SpeakResult.Spoken(1_000), loud.speak(Line.of("Time to start."), SpeechUsage.ALARM) { order += "audible" })
        assertEquals(listOf("engine", "level", "audible", "play"), order)

        var told = 0
        val failing = LocalTtsSpeaker(FakeSynth { Synthesis.Failed(SpeechProblem.NO_ENGINE) }, FakePlayer())
        assertEquals(SpeakResult.Unavailable(SpeechProblem.NO_ENGINE), failing.speak(Line.of("x"), SpeechUsage.ALARM) { told++ })
        assertEquals(SpeakResult.Spoken(0), failing.speak(Line.of("   "), SpeechUsage.ALARM) { told++ })
        assertEquals("nothing was ever audible", 0, told)
    }

    @Test
    fun `an engine or a levelling step that throws is reported as unavailable, not thrown at the ring`() = runTest {
        val broken = FakeSynth { error("the engine fell over") }
        assertEquals(SpeakResult.Unavailable(SpeechProblem.SYNTHESIS_FAILED), speaker(broken).speak(Line.of("x"), SpeechUsage.ALARM))

        val fine = FakeSynth { Synthesis.Audio(tone()) }
        val player = FakePlayer()
        val result = speaker(fine, player, master = { error("the audio could not be levelled") }).speak(Line.of("x"), SpeechUsage.ALARM)
        assertEquals(SpeakResult.Unavailable(SpeechProblem.PLAYBACK_FAILED), result)
        assertTrue("nothing was played", player.played.isEmpty())
    }

    @Test
    fun `preparing starts the engine ahead of the line and says nothing`() = runTest {
        val synth = FakeSynth { Synthesis.Audio(tone()) }
        val player = FakePlayer()
        val speaker = speaker(synth, player)

        speaker.prepare()

        assertEquals(1, synth.prepares)
        assertTrue("nothing was asked for yet", synth.asked.isEmpty())
        assertTrue("and nothing was played", player.played.isEmpty())
        assertEquals(SpeakResult.Spoken(1_500), speaker.speak(Line.of("Time to start."), SpeechUsage.ALARM))
    }

    @Test
    fun `where the line may be heard is passed to the player, and the default is anywhere`() = runTest {
        val player = FakePlayer()
        val speaker = speaker(FakeSynth { Synthesis.Audio(tone()) }, player)
        speaker.speak(Line.of("One"), SpeechUsage.ALARM)
        speaker.speak(Line.of("Two"), SpeechUsage.ALARM, RoutePolicy.HEADSET_ONLY)
        assertEquals(listOf(RoutePolicy.SPEAKER_AND_HEADSET, RoutePolicy.HEADSET_ONLY), player.routes)
    }

    @Test
    fun `a headset that cannot be used is reported as such, and nothing else is said about the line`() = runTest {
        val player = FakePlayer().apply { failWith = RouteUnavailableException() }
        val result = speaker(FakeSynth { Synthesis.Audio(tone()) }, player).speak(Line.of("Time to start."), SpeechUsage.ALARM, RoutePolicy.HEADSET_ONLY)
        assertEquals(SpeakResult.Unavailable(SpeechProblem.HEADSET_REQUIRED), result)
    }

    @Test
    fun `a line asked for while an earlier one is still being prepared replaces it, and the earlier one never becomes audible`() = runTest {
        val first = CompletableDeferred<Synthesis>()
        var calls = 0
        val synth = object : SpeechSynthesizer {
            override suspend fun synthesize(text: String): Synthesis = if (calls++ == 0) first.await() else Synthesis.Audio(tone())
            override fun shutdown() = Unit
        }
        val player = FakePlayer()
        val speaker = LocalTtsSpeaker(synth, player, master = { it.pcm })
        val audibleFor = ArrayList<String>()

        val a = async { speaker.speak(Line.of("First"), SpeechUsage.ALARM) { audibleFor += "first" } }
        kotlinx.coroutines.yield()
        val b = async { speaker.speak(Line.of("Second"), SpeechUsage.ALARM) { audibleFor += "second" } }
        kotlinx.coroutines.yield()
        first.complete(Synthesis.Audio(tone())) // the engine finishes the old line only now

        assertEquals(SpeakResult.Interrupted, a.await())
        assertEquals(SpeakResult.Spoken(1_500), b.await())
        assertEquals("only the later line was ever heard", listOf("second"), audibleFor)
        assertEquals(1, player.played.size)
    }
}
