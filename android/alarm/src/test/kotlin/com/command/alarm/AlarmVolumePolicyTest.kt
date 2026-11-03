package com.command.alarm

import android.content.Context
import android.media.AudioManager
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
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The alarm volume policy by itself: when it raises the volume, what it writes down first, and when it puts the volume back. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34], shadows = [VolumeRefusalShadow::class])
class AlarmVolumePolicyTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var policy: AlarmVolumePolicy
    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var consents: ConsentStore

    private lateinit var audio: AudioManager

    private val loudest get() = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    private fun volume() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

    @Before
    fun setUp() {
        VolumeRefusalShadow.refuse = false
        hilt.inject()
        audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        assertTrue("the alarm stream has room to be raised", loudest > 5)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
    }

    @After
    fun tearDown() {
        VolumeRefusalShadow.refuse = false
    }

    private fun armed(): OccurrenceRow = runBlocking {
        val mission = testMission()
        db.missions().insert(mission)
        val occurrence = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(occurrence.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.get(occurrence.id)!!
    }

    private fun ringing(): OccurrenceRow = runBlocking {
        val o = armed()
        repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE)
        repo.get(o.id)!!
    }

    private fun allowed() = runBlocking { consents.grant(ConsentKey.VOLUME_CONTROL) }

    private fun events(o: OccurrenceRow, type: EventType) = runBlocking { db.events().forOccurrence(o.id).filter { it.type == type } }

    private fun record() = runBlocking { settings.volumeRestore() }

    // --- raising it -----------------------------------------------------------------------------------------------------------

    @Test
    fun `without the consent nothing is raised and nothing is written down`() {
        val o = armed()
        assertFalse(runBlocking { policy.raiseFor(o) })
        assertEquals(3, volume())
        assertNull(record())
    }

    @Test
    fun `with the consent it is raised to the highest, and what it was is written down`() {
        allowed()
        val o = armed()
        assertTrue(runBlocking { policy.raiseFor(o) })
        assertEquals(loudest, volume())
        val written = record()!!
        assertEquals(o.id, written.occurrenceId)
        assertEquals(3, written.original)
        assertEquals(loudest, written.set)
    }

    @Test
    fun `an alarm volume that is already the highest is left alone and nothing is written down`() {
        allowed()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, loudest, 0)
        assertFalse(runBlocking { policy.raiseFor(armed()) })
        assertNull(record())
    }

    @Test
    fun `a refusal by the platform is logged, and leaves the volume as it was and nothing written down`() {
        allowed()
        VolumeRefusalShadow.refuse = true
        val o = armed()
        assertFalse(runBlocking { policy.raiseFor(o) })
        assertEquals(3, volume())
        assertNull(record())
        assertEquals(1, events(o, EventType.VOLUME_SET_REFUSED).size)
    }

    // --- putting it back --------------------------------------------------------------------------------------------------------

    @Test
    fun `when the ring ends the volume goes back to what it was and the record is cleared`() {
        allowed()
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        runBlocking { policy.restoreFor(o.id) }
        assertEquals(3, volume())
        assertNull(record())
    }

    @Test
    fun `the end of another ring does not undo this one`() {
        allowed()
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        runBlocking { policy.restoreFor("another-occurrence") }
        assertEquals(loudest, volume())
        assertNotNull(record())
    }

    @Test
    fun `a volume the person set during the ring is theirs, and the skipped restore is in the log`() {
        allowed()
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        runBlocking { policy.restoreFor(o.id) }
        assertEquals(5, volume())
        assertNull("the record is cleared either way", record())
        assertEquals(1, events(o, EventType.VOLUME_RESTORE_SKIPPED).size)
    }

    @Test
    fun `the consent being withdrawn mid-ring does not leave the volume raised`() {
        allowed()
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        runBlocking { consents.revoke(ConsentKey.VOLUME_CONTROL) }
        runBlocking { policy.restoreFor(o.id) }
        assertEquals(3, volume())
        assertNull(record())
    }

    @Test
    fun `from the service the restore is done on the policy's own scope and still happens`() {
        allowed()
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        policy.restoreSoonFor(o.id)
        awaitUntil(message = "restored") { volume() == 3 }
        awaitUntil(message = "record cleared") { record() == null }
    }

    @Test
    fun `whichever ring left it raised, the last ring ending puts it back`() {
        allowed()
        runBlocking { policy.raiseFor(armed()) }
        policy.restoreAnySoon()
        awaitUntil(message = "restored") { volume() == 3 }
        awaitUntil(message = "record cleared") { record() == null }
    }

    // --- after a crash --------------------------------------------------------------------------------------------------------------

    @Test
    fun `after a crash a ring that no longer exists is undone`() {
        allowed()
        runBlocking { policy.raiseFor(armed()) } // armed, never rang: nothing is ringing, so nothing needs the raised volume
        runBlocking { policy.restoreStale() }
        assertEquals(3, volume())
        assertNull(record())
    }

    @Test
    fun `a ring that was killed stays firing in the database, and its raised volume is still put back when the app is opened`() {
        allowed()
        runBlocking { policy.raiseFor(ringing()) } // firing, but no service in this process is ringing it: a force-stop took it
        runBlocking { policy.restoreStale() }
        assertEquals(3, volume())
        assertNull(record())
    }

    @Test
    fun `with nothing written down there is nothing to restore, and a volume is never guessed at`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 4, 0)
        runBlocking { policy.restoreStale() }
        runBlocking { policy.restoreFor("any") }
        assertEquals(4, volume())
    }

    @Test
    fun `a restore the platform refuses is logged and kept for later, and done once it is allowed`() {
        allowed()
        val o = armed()
        runBlocking { policy.raiseFor(o) }

        VolumeRefusalShadow.refuse = true
        runBlocking { policy.restoreFor(o.id) }
        assertEquals("still raised: the platform said no", loudest, volume())
        assertNotNull("so the record stays", record())
        assertTrue(events(o, EventType.VOLUME_SET_REFUSED).single().payloadJson.contains("restore"))

        VolumeRefusalShadow.refuse = false
        runBlocking { policy.restoreStale() }
        assertEquals(3, volume())
        assertNull(record())
    }
}
