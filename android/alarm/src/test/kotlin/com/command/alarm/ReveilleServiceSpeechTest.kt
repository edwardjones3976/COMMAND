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
import com.command.domain.model.ConsentKey
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.voice.RoutePolicy
import com.command.domain.voice.SpeakResult
import com.command.domain.voice.SpeechProblem
import com.command.domain.voice.SpeechUsage
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.FakeAudioRoutes
import com.command.testing.android.FakeRingOutput
import com.command.testing.android.FakeSpeaker
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
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

    private lateinit var context: Context

    @Before
    fun setUp() {
        hilt.inject()
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun armed(): OccurrenceRow = runBlocking {
        val mission = testMission(title = "Write the report")
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

    // --- the line --------------------------------------------------------------------------------------------------------------

    @Test
    fun `the opening line is spoken over the tone, which is turned down while it is said and back up after`() {
        val o = armed()
        val controller = startService(o)
        awaitUntil(message = "spoken") { speaker.spoken.isNotEmpty() && output.ducks.size >= 2 }

        val (line, usage) = speaker.spoken.single()
        assertEquals(SpeechUsage.ALARM, usage)
        assertEquals("Time to start: Write the report. First step: Open the document.", line.text)
        assertEquals("the words the person typed are marked as theirs", listOf(false, true, false, true), line.segments.map { it.carriesUserText })
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
    fun `the speech engine is started with the ring, before the database has even been opened, and not when the first line is asked for`() {
        speaker.hold = true
        val o = armed()
        startService(o)
        assertEquals(1, speaker.prepares)
        assertTrue("the tone is already going too", output.ringing)
        assertTrue("and no line has been asked for yet", speaker.spoken.isEmpty())
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
}
