package com.command.alarm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.command.data.AppSettings
import com.command.data.ConsentStore
import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.coaching.DirectiveLint
import com.command.domain.model.ConsentKey
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.voice.Line
import com.command.domain.voice.LoudnessMeter
import com.command.domain.voice.RoutePolicy
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.SpeechLevel
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechUsage
import com.command.domain.voice.SpeechVoice
import com.command.voice.LocalTtsSpeaker
import com.command.voice.PcmPlayer
import com.command.voice.SpeechSynthesizer
import com.command.voice.SpokenAudio
import com.command.voice.Synthesis
import kotlin.math.sin
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.FakeAudioRoutes
import com.command.testing.android.FakePreviewSpeaker
import com.command.testing.android.FakeRingOutput
import com.command.testing.android.FakeSpeaker
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/** What the ring says over its tone, where it may say it, and what happens to the alarm volume while it rings. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34, 36])
class ReveilleServiceSpeechTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var output: FakeRingOutput
    @Inject lateinit var speaker: FakeSpeaker
    @Inject lateinit var routes: FakeAudioRoutes
    @Inject lateinit var session: RingSession
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var consents: ConsentStore
    @Inject lateinit var policy: AlarmVolumePolicy
    @Inject lateinit var preview: VoicePreview
    @Inject lateinit var previewSpeaker: FakePreviewSpeaker

    private lateinit var context: Context

    @Before
    fun setUp() {
        hilt.inject()
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun armed(title: String = "Write the report"): OccurrenceRow = runBlocking {
        val mission = testMission(title = title)
        db.missions().insert(mission)
        val occurrence = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(occurrence.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.get(occurrence.id)!!
    }

    private fun fireIntent(o: OccurrenceRow) = Intent(context, ReveilleService::class.java)
        .setAction(AlarmContract.ACTION_FIRE)
        .setData(Uri.parse("command://occurrence/${o.id}"))
        .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id)
        .putExtra(AlarmContract.EXTRA_REQUEST_CODE, o.requestCode)

    private fun startService(o: OccurrenceRow) = Robolectric.buildService(ReveilleService::class.java, fireIntent(o)).create().startCommand(0, 1)

    private fun events(o: OccurrenceRow, type: EventType) = runBlocking { db.events().forOccurrence(o.id).filter { it.type == type } }

    private fun alarmVolume() = context.getSystemService(AudioManager::class.java)

    // --- the voice screen's preview ------------------------------------------------------------------------------------------------

    @Test
    fun `a ring that begins cuts off a preview at once and lets its engine go, and says its own line`() {
        val heard = previewSpeaker.speaker
        heard.result = SpeakResult.Interrupted
        heard.hold = true
        var result: PreviewResult? = null
        CoroutineScope(Dispatchers.Unconfined).launch { result = preview.say(Line.of("Preview"), SpeechLevel(3, 2f), SpeechVoice.DEFAULT) }
        awaitUntil(message = "the preview is being said") { heard.spoken.isNotEmpty() }

        startService(armed())

        assertEquals(1, heard.stops)
        assertEquals(1, heard.releases)
        awaitUntil(message = "the ring's own line") { speaker.spoken.isNotEmpty() }
        awaitUntil(message = "the preview ended") { result != null }
        assertEquals(PreviewResult.RingSounding, result)
        assertEquals("the ring's line is not stopped for the preview's sake", 0, speaker.stops)
    }

    @Test
    fun `no preview is said while a ring sounds, and stopping or letting go of a preview does not touch the ring's line`() {
        startService(armed())
        awaitUntil(message = "the ring's own line") { speaker.spoken.isNotEmpty() }

        val result = runBlocking { preview.say(Line.of("Preview"), SpeechLevel(3, 2f), SpeechVoice.DEFAULT) }
        preview.stop()
        preview.release()

        assertEquals(PreviewResult.RingSounding, result)
        assertTrue(previewSpeaker.speaker.spoken.isEmpty())
        assertEquals(0, speaker.stops)
        assertEquals(0, speaker.releases)
    }

    @Test
    fun `the tone is already sounding when a preview is dealt with, so a slow engine cannot hold a ring up`() {
        var soundingThen = false
        session.onRingStarting = { soundingThen = output.ringing }

        startService(armed())

        assertTrue(soundingThen)
    }

    @Test
    fun `a preview that cannot be stopped does not hold a ring up`() {
        session.onRingStarting = { error("a broken preview") }

        startService(armed())

        assertTrue(output.ringing)
        awaitUntil(message = "the ring's own line") { speaker.spoken.isNotEmpty() }
    }

    // --- the line --------------------------------------------------------------------------------------------------------------

    @Test
    fun `the line is asked for at the loudness the ring was fired with, whatever the profile says by then`() {
        val o = armed()
        runBlocking { repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SYSTEM) }
        val fired = runBlocking { repo.get(o.id)!! }
        val pinned = runBlocking { repo.speechLevelOf(fired) }
        runBlocking {
            for (profile in db.profiles().all()) db.profiles().upsert(profile.copy(alertIntensity = if (pinned.alertIntensity == 1) 2 else 1))
        }
        startService(fired)
        awaitUntil(message = "spoken") { speaker.levels.isNotEmpty() }
        assertEquals(pinned, speaker.levels.single())
    }

    /** What the ring asks of the speaker, with the voice of [profileId] as it is seeded. */
    private fun ringOf(profileId: String): Pair<SpeechVoice, SpeechLevel> {
        speaker.voices.clear()
        speaker.levels.clear()
        val o = runBlocking {
            val mission = testMission(title = "Write the report").copy(profileId = profileId)
            db.missions().insert(mission)
            val occurrence = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
            repo.apply(occurrence.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
            repo.get(occurrence.id)!!
        }
        startService(o)
        awaitUntil(message = "spoken") { speaker.voices.isNotEmpty() }
        return speaker.voices.single() to speaker.levels.single()
    }

    /** The real speaker as the ring would use it, on an engine that hands back the same audio and a player that keeps what it is given. */
    private fun rendered(voice: SpeechVoice, level: SpeechLevel): Pair<SpeechVoice, ShortArray> = runBlocking {
        val rate = 24_000
        val audio = SpokenAudio(ShortArray(rate * 3) { i -> (if ((i / (rate / 4)) % 2 == 0) sin(i * 2 * Math.PI * 440 / rate) * 2_000 else 0.0).toInt().toShort() }, rate)
        var asked: SpeechVoice? = null
        var played: ShortArray? = null
        val synth = object : SpeechSynthesizer {
            override suspend fun synthesize(text: String, voice: SpeechVoice): Synthesis {
                asked = voice
                return Synthesis.Audio(audio)
            }

            override fun shutdown() = Unit
        }
        val player = object : PcmPlayer {
            override suspend fun play(pcm: ShortArray, sampleRate: Int, usage: SpeechUsage, route: RoutePolicy): Long {
                played = pcm
                return 1_000
            }

            override fun stop() = Unit
        }
        LocalTtsSpeaker(synth, player).speak(Line.of("Time to start."), SpeechUsage.ALARM, level = level, voice = voice)
        asked!! to played!!
    }

    @Test
    fun `two packs give different speech parameters on the ring path, from the seeded database to the engine`() {
        val (stoneVoice, stoneLevel) = ringOf("builtin_reveille_max")
        val (eleanorVoice, eleanorLevel) = ringOf("builtin_steady")

        assertEquals(SpeechVoice("en-US", 0.86f, 1.28f, female = false), stoneVoice)
        assertEquals(SpeechVoice("en-US", 1.01f, 1.04f, female = true), eleanorVoice)
        assertEquals(4.5f, stoneLevel.trimDb, 0f)
        assertEquals(2.5f, eleanorLevel.trimDb, 0f)
        assertEquals("and the engine is asked for each", stoneVoice, rendered(stoneVoice, stoneLevel).first)
        assertEquals(eleanorVoice, rendered(eleanorVoice, eleanorLevel).first)
    }

    @Test
    fun `the seeded trim changes what the ring renders, at the same intensity, and only ever upward`() {
        fun lufs(pcm: ShortArray) = LoudnessMeter.integratedLufs(FloatArray(pcm.size) { pcm[it] / 32768f }, 24_000)
        fun gain(profile: String): Double {
            val (voice, level) = ringOf(profile)
            assertTrue("a pack with a trim", level.trimDb > 0f)
            return lufs(rendered(voice, level).second) - lufs(rendered(voice, level.copy(trimDb = 0f)).second)
        }
        // At intensity 2 there is room for all of it. At intensity 5 this dense signal is near what the limiters allow (amendment 28): less, never nothing.
        assertEquals(2.5, gain("builtin_steady"), 0.5)
        assertTrue(gain("builtin_reveille_max") > 0.5)
    }

    @Test
    fun `the line is asked for in the voice the ring was fired with, whatever the profile says by then`() {
        val o = armed()
        runBlocking { repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SYSTEM) }
        val fired = runBlocking { repo.get(o.id)!! }
        val pinned = runBlocking { repo.speechVoiceOf(fired) }
        assertEquals("the seeded pack of the active profile", 1.01f, pinned.pitch, 0f)
        runBlocking {
            for (profile in db.profiles().all()) db.profiles().upsert(profile.copy(voicePackId = "pack_major_stone"))
        }
        startService(fired)
        awaitUntil(message = "spoken") { speaker.voices.isNotEmpty() }
        assertEquals(pinned, speaker.voices.single())
    }

    @Test
    fun `the opening line is spoken over the tone, which is turned down while it is said and back up after`() {
        val o = armed()
        val controller = startService(o)
        awaitUntil(message = "spoken") { speaker.spoken.isNotEmpty() && output.ducks.size >= 2 }

        val (line, usage) = speaker.spoken.single()
        assertEquals(SpeechUsage.ALARM, usage)
        assertEquals("the coaching line says the person's words, marked as theirs", listOf("Write the report.", "Open the document."), line.segments.filter { it.carriesUserText }.map { it.text })
        assertTrue("the coaching engine's words, not the plain ones", DirectiveLint.check(line).isEmpty() && line.text != "Time to start: Write the report. First step: Open the document.")
        assertEquals("down while it speaks, up when it is done", listOf(true, false), output.ducks)
        assertTrue("the tone never stopped for it", output.ringing)
        assertEquals(1, events(o, EventType.AUDIO_ROUTE).size)
        assertTrue(events(o, EventType.AUDIO_ROUTE).single().payloadJson.contains("\"spoke\":\"true\""))
        assertEquals(o.id, controller.get().ringingOccurrenceId)
    }

    @Test
    fun `the ring starts at once and is never held up by the voice`() {
        speaker.hold = true // the engine is slow: the line never finishes on its own
        val o = armed()
        val controller = startService(o)
        assertTrue("the tone is already going before the voice has said a word", output.ringing)
        awaitUntil(message = "ringing") { controller.get().ringingOccurrenceId == o.id }
        awaitUntil(message = "speaking") { speaker.spoken.isNotEmpty() }
        assertTrue(output.ducked)
    }

    @Test
    fun `answering the ring cuts the line off, brings the tone's level back and lets the engine go`() {
        speaker.hold = true
        val o = armed()
        val controller = startService(o)
        awaitUntil(message = "speaking") { speaker.spoken.isNotEmpty() && output.ducked }

        session.end(o.id)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the speaker was told to stop", speaker.stops >= 1)
        assertFalse("the tone's level is not left turned down", output.ducked)
        assertTrue("and the engine is let go, because the ring that needed it is over", speaker.releases >= 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `a phone that cannot speak says why in the log, and the ring carries on`() {
        speaker.result = SpeakResult.Unavailable(SpeechProblem.NO_ENGINE)
        val o = armed()
        startService(o)
        awaitUntil(message = "logged") { events(o, EventType.TTS_UNAVAILABLE).isNotEmpty() }

        assertTrue(events(o, EventType.TTS_UNAVAILABLE).single().payloadJson.contains("NO_ENGINE"))
        assertTrue("it still rings", output.ringing)
        assertEquals("nothing says it spoke", 0, events(o, EventType.AUDIO_ROUTE).size)
        assertFalse("the tone's level is back", output.ducked)
    }

    // --- where it may be said ----------------------------------------------------------------------------------------------------

    @Test
    fun `speech only through a headset is not said on the speaker, and the log says the route refused it`() {
        runBlocking { settings.setRoutePolicy(RoutePolicy.HEADSET_ONLY) }
        routes.headset = false
        val o = armed()
        startService(o)
        awaitUntil(message = "logged") { events(o, EventType.AUDIO_ROUTE).isNotEmpty() }

        assertTrue("nothing was said", speaker.spoken.isEmpty())
        val payload = events(o, EventType.AUDIO_ROUTE).single().payloadJson
        assertTrue(payload.contains("\"spoke\":\"false\"") && payload.contains("HEADSET_ONLY") && payload.contains("speaker"))
        assertTrue("the tone and the vibration ring whatever the route", output.ringing)
    }

    @Test
    fun `speech only through a headset is said once a headset is there`() {
        runBlocking { settings.setRoutePolicy(RoutePolicy.HEADSET_ONLY) }
        routes.headset = true
        val o = armed()
        startService(o)
        awaitUntil(message = "spoken") { speaker.spoken.isNotEmpty() }
        awaitUntil(message = "logged") { events(o, EventType.AUDIO_ROUTE).isNotEmpty() }
        assertTrue(events(o, EventType.AUDIO_ROUTE).single().payloadJson.contains("speaker+wired"))
    }

    @Test
    fun `the default says it on the speaker too`() {
        routes.headset = false
        val o = armed()
        startService(o)
        awaitUntil(message = "spoken") { speaker.spoken.isNotEmpty() }
        assertEquals(listOf(RoutePolicy.SPEAKER_AND_HEADSET), speaker.routes)
    }

    // --- the alarm volume ----------------------------------------------------------------------------------------------------------

    private val loudest get() = alarmVolume().getStreamMaxVolume(AudioManager.STREAM_ALARM)

    private fun lowAlarmVolume() {
        assertTrue("the alarm stream has room to be raised", loudest > 5)
        alarmVolume().setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
    }

    @Test
    fun `without the consent the alarm volume is never touched`() {
        lowAlarmVolume()
        val o = armed()
        startService(o)
        awaitUntil(message = "spoken") { speaker.spoken.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals(null, runBlocking { settings.volumeRestore() })
    }

    @Test
    fun `with the consent the alarm volume is raised for the ring and put back when it ends`() {
        runBlocking { consents.grant(ConsentKey.VOLUME_CONTROL) }
        lowAlarmVolume()
        val o = armed()
        startService(o)
        awaitUntil(message = "raised") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == loudest }
        assertEquals("what it was is written down first", 3, runBlocking { settings.volumeRestore() }?.original)

        session.end(o.id)
        shadowOf(Looper.getMainLooper()).idle()
        awaitUntil(message = "restored") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == 3 }
        awaitUntil(message = "record cleared") { runBlocking { settings.volumeRestore() } == null }
    }

    @Test
    fun `a volume the person set during the ring is theirs and is left as it is`() {
        runBlocking { consents.grant(ConsentKey.VOLUME_CONTROL) }
        lowAlarmVolume()
        val o = armed()
        startService(o)
        awaitUntil(message = "raised") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == loudest }
        alarmVolume().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0) // they turned it down a little

        session.end(o.id)
        shadowOf(Looper.getMainLooper()).idle()
        awaitUntil(message = "record cleared") { runBlocking { settings.volumeRestore() } == null }
        assertEquals(5, alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM))
        assertTrue(events(o, EventType.VOLUME_RESTORE_SKIPPED).isNotEmpty())
    }

    @Test
    fun `a speech engine that blows up cannot take the ring down with it`() {
        speaker.failWith = IllegalStateException("the engine fell over")
        val o = armed()
        val controller = startService(o)
        awaitUntil(message = "logged") { events(o, EventType.TTS_UNAVAILABLE).isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(events(o, EventType.TTS_UNAVAILABLE).single().payloadJson.contains("UNEXPECTED"))
        assertTrue("the tone is still going", output.ringing)
        assertFalse("and is not left turned down", output.ducked)
        assertEquals("and the occurrence is still the one ringing", o.id, controller.get().ringingOccurrenceId)
    }

    @Test
    fun `the speech engine is started with the ring, before the database work has begun, and not when the first line is asked for`() {
        speaker.hold = true
        val o = armed()
        // Looked at by the engine's start itself, not after it: how far the database work has got by the time a test looks is a race with
        // another thread (CI lost it), but what the occurrence was when the engine was started is not.
        var stateWhenStarted: OccurrenceState? = null
        speaker.onPrepare = { stateWhenStarted = runBlocking { repo.get(o.id)?.state } }

        startService(o)

        assertEquals("the engine is started by the start command itself", 1, speaker.prepares)
        assertTrue("the tone is already going too", output.ringing)
        assertEquals("the alarm had not even been fired yet when the engine was started", OccurrenceState.ARMED, stateWhenStarted)
        awaitUntil(message = "the first line is asked for") { speaker.spoken.isNotEmpty() }
        assertEquals("and the engine is not started again for the line", 1, speaker.prepares)
    }

    @Test
    fun `opening the app while a ring is sounding keeps the raised volume, and the ring ending puts it back`() {
        runBlocking { consents.grant(ConsentKey.VOLUME_CONTROL) }
        lowAlarmVolume()
        val o = armed()
        val controller = startService(o)
        awaitUntil(message = "raised and ringing") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == loudest && controller.get().ringingOccurrenceId == o.id }

        runBlocking { policy.restoreStale() } // what opening the app does
        assertEquals("a ring that is sounding keeps what it raised", loudest, alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM))
        assertNotNull(runBlocking { settings.volumeRestore() })

        session.end(o.id)
        shadowOf(Looper.getMainLooper()).idle()
        awaitUntil(message = "restored") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == 3 }
    }

    @Test
    fun `a service that is destroyed with a ring in it does not leave the volume raised`() {
        runBlocking { consents.grant(ConsentKey.VOLUME_CONTROL) }
        lowAlarmVolume()
        val o = armed()
        val controller = startService(o)
        awaitUntil(message = "raised and ringing") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == loudest && controller.get().ringingOccurrenceId == o.id }

        controller.destroy() // the system took the service, and the ring with it
        awaitUntil(message = "restored") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == 3 }
        awaitUntil(message = "record cleared") { runBlocking { settings.volumeRestore() } == null }
    }

    @Test
    fun `the route the person allowed goes to the speaker with the line, so that playback can keep to it`() {
        runBlocking { settings.setRoutePolicy(RoutePolicy.HEADSET_ONLY) }
        routes.headset = true
        val o = armed()
        startService(o)
        awaitUntil(message = "spoken") { speaker.spoken.isNotEmpty() }
        assertEquals(listOf(RoutePolicy.HEADSET_ONLY), speaker.routes)
    }

    @Test
    fun `a headset that went away while the engine was preparing is written down, and the ring carries on`() {
        speaker.result = SpeakResult.Unavailable(SpeechProblem.HEADSET_REQUIRED)
        val o = armed()
        startService(o)
        awaitUntil(message = "logged") { events(o, EventType.TTS_UNAVAILABLE).isNotEmpty() }
        assertTrue(events(o, EventType.TTS_UNAVAILABLE).single().payloadJson.contains("HEADSET_REQUIRED"))
        assertTrue(output.ringing)
    }

    @Test
    fun `a phone with no voice that works offline says nothing, and the log says why`() {
        speaker.result = SpeakResult.Unavailable(SpeechProblem.NO_OFFLINE_VOICE)
        val o = armed()
        startService(o)
        awaitUntil(message = "logged") { events(o, EventType.TTS_UNAVAILABLE).isNotEmpty() }
        assertTrue(events(o, EventType.TTS_UNAVAILABLE).single().payloadJson.contains("NO_OFFLINE_VOICE"))
        assertTrue(output.ringing)
    }

    @Test
    fun `a ring that takes over stops the line of the one it displaced, and says its own`() {
        speaker.hold = true
        val first = armed()
        val controller = startService(first)
        awaitUntil(message = "first line") { speaker.spoken.size == 1 && output.ducked }

        val second = armed()
        controller.withIntent(fireIntent(second)).startCommand(0, 2)
        awaitUntil(message = "taken over") { controller.get().ringingOccurrenceId == second.id && speaker.spoken.size == 2 }

        assertTrue("the displaced ring's line was stopped, in the player and the engine", speaker.stops >= 1)
        assertEquals("down for the first line, up when it was cut off, down again for the second", listOf(true, false, true), output.ducks)
        assertTrue(output.ducked)
    }

    @Test
    fun `the same alarm delivered twice leaves the ring that is going alone, and its line is said once`() {
        speaker.hold = true
        val o = armed("Write the report")
        val controller = startService(o)
        awaitUntil(message = "speaking") { speaker.spoken.size == 1 }

        controller.withIntent(fireIntent(o)).startCommand(0, 2)
        // Deliveries are handled one at a time, so once a later ring has been taken over the duplicate before it has been dealt with.
        val later = armed("Pay the invoice")
        controller.withIntent(fireIntent(later)).startCommand(0, 3)
        awaitUntil(message = "the later ring's line") { speaker.spoken.any { it.first.text.contains("Pay the invoice") } }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("one line for the ring and one for the later one: the duplicate said nothing", 2, speaker.spoken.size)
        assertEquals(later.id, controller.get().ringingOccurrenceId)
        assertTrue("the duplicate was refused by the state machine, not run as a second ring", events(o, EventType.REJECTED_TRANSITION).isNotEmpty())
    }

    @Test
    fun `when the ring that took over ends, the volume the first ring raised goes back`() {
        runBlocking { consents.grant(ConsentKey.VOLUME_CONTROL) }
        lowAlarmVolume()
        val first = armed()
        val controller = startService(first)
        awaitUntil(message = "raised and ringing") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == loudest && controller.get().ringingOccurrenceId == first.id }

        val second = armed()
        controller.withIntent(fireIntent(second)).startCommand(0, 2)
        awaitUntil(message = "taken over") { controller.get().ringingOccurrenceId == second.id }
        awaitUntil(message = "the raised volume now belongs to the second ring") { runBlocking { settings.volumeRestore() }?.occurrenceId == second.id }

        session.end(second.id)
        shadowOf(Looper.getMainLooper()).idle()
        awaitUntil(message = "restored") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == 3 }
        awaitUntil(message = "record cleared") { runBlocking { settings.volumeRestore() } == null }
    }

    /** Lets everything that is queued run for a fixed time, with the main looper pumped as `awaitUntil` does: for what must not happen. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `a clean-up an earlier ring queued, running after the next one has begun, leaves what that one raised alone`() {
        runBlocking { consents.grant(ConsentKey.VOLUME_CONTROL) }
        lowAlarmVolume()
        val first = armed()
        val controller = startService(first)
        awaitUntil(message = "raised and ringing") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == loudest && controller.get().ringingOccurrenceId == first.id }

        val second = armed()
        controller.withIntent(fireIntent(second)).startCommand(0, 2)
        awaitUntil(message = "taken over") { controller.get().ringingOccurrenceId == second.id }
        awaitUntil(message = "the raised volume now belongs to the second ring") { runBlocking { settings.volumeRestore() }?.occurrenceId == second.id }

        // What the first ring's shutDown queued, arriving only now that the second one is sounding.
        policy.restoreAnySoon()
        settle(500) // what is queued has every chance to do its worst
        assertEquals("the second ring still sounds at the level it raised", loudest, alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM))
        assertEquals("and the record is still its own", second.id, runBlocking { settings.volumeRestore() }?.occurrenceId)

        session.end(second.id)
        shadowOf(Looper.getMainLooper()).idle()
        awaitUntil(message = "restored when it ends") { alarmVolume().getStreamVolume(AudioManager.STREAM_ALARM) == 3 }
        awaitUntil(message = "record cleared") { runBlocking { settings.volumeRestore() } == null }
    }
}
