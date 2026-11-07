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
        VolumeRefusalShadow.floor = 0
        VolumeRefusalShadow.ignore = false
        VolumeRefusalShadow.hardeningThrows = false
        VolumeRefusalShadow.ignoreNext = 0
        hilt.inject()
        audio = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
        assertTrue("the alarm stream has room to be raised", loudest > 5)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
    }

    @After
    fun tearDown() {
        VolumeRefusalShadow.refuse = false
        VolumeRefusalShadow.floor = 0
        VolumeRefusalShadow.ignore = false
        VolumeRefusalShadow.hardeningThrows = false
        VolumeRefusalShadow.ignoreNext = 0
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

    @Test
    fun `a restore the platform drops without a word is logged and kept for later, and done once it is allowed`() {
        // Measured on an API 37 emulator with audio hardening enforced: the reconcile after a ring that died ran with no foreground service, the
        // platform left the alarm volume where the ring had put it, and the record was cleared as though a floor had been reached.
        allowed()
        val o = armed()
        runBlocking { policy.raiseFor(o) }

        VolumeRefusalShadow.ignore = true
        runBlocking { policy.restoreStale() }
        assertEquals("still raised: the platform dropped the change", loudest, volume())
        assertNotNull("so the record stays, for the next reconcile", record())
        val note = events(o, EventType.VOLUME_SET_REFUSED).single().payloadJson
        assertTrue(note.contains("restore") && note.contains("\"wanted\":\"3\""))

        VolumeRefusalShadow.ignore = false
        runBlocking { policy.restoreStale() }
        assertEquals(3, volume())
        assertNull(record())
    }

    @Test
    fun `a restore that hardening makes throw is a refusal like any other, and never breaks the reconcile that asked for it`() {
        allowed()
        val o = armed()
        runBlocking { policy.raiseFor(o) }

        VolumeRefusalShadow.hardeningThrows = true
        runBlocking { policy.restoreStale() } // must not throw: the reconcile re-arms alarms after this
        assertEquals(loudest, volume())
        assertNotNull("kept for the next reconcile", record())
        assertTrue(events(o, EventType.VOLUME_SET_REFUSED).single().payloadJson.contains("restore"))

        VolumeRefusalShadow.hardeningThrows = false
        runBlocking { policy.restoreStale() }
        assertEquals(3, volume())
        assertNull(record())
    }

    @Test
    fun `a raise that hardening makes throw is logged as refused and leaves nothing written down`() {
        allowed()
        val o = armed()
        VolumeRefusalShadow.hardeningThrows = true
        assertFalse(runBlocking { policy.raiseFor(o) })
        assertEquals(3, volume())
        assertNull(record())
        assertEquals(1, events(o, EventType.VOLUME_SET_REFUSED).size)
    }

    @Test
    fun `a raise the platform drops once is tried again while the ring sounds, and then holds`() {
        // Measured on CI's API 37 emulator with hardening enforced: the ring's raise was dropped without an error in one run of three.
        allowed()
        val o = armed()
        VolumeRefusalShadow.ignoreNext = 1
        assertTrue(runBlocking { policy.raiseFor(o) })
        assertEquals(loudest, volume())
        assertEquals(3, record()!!.original)
        assertTrue(events(o, EventType.VOLUME_SET_REFUSED).isEmpty())
    }

    @Test
    fun `a raise the platform keeps dropping is logged as refused and leaves nothing written down`() {
        allowed()
        val o = armed()
        VolumeRefusalShadow.ignore = true
        assertFalse(runBlocking { policy.raiseFor(o) })
        assertEquals(3, volume())
        assertNull("nothing was raised, so there is nothing to put back", record())
        assertTrue(events(o, EventType.VOLUME_SET_REFUSED).single().payloadJson.contains("raise"))
    }

    @Test
    fun `a later ring adopts the volume an earlier one raised, so the earlier one's delayed clean-up cannot lower it`() {
        allowed()
        val first = armed()
        val second = armed()
        runBlocking { policy.raiseFor(first) }

        assertFalse("already as loud as it goes", runBlocking { policy.raiseFor(second) })
        assertEquals("but the raised volume is now the second ring's", second.id, record()!!.occurrenceId)

        runBlocking { policy.restoreFor(first.id) } // the first ring's clean-up, arriving after the second ring has begun
        assertEquals(loudest, volume())
        assertNotNull(record())

        runBlocking { policy.restoreFor(second.id) }
        assertEquals(3, volume())
        assertNull(record())
    }

    @Test
    fun `a volume the person turned down during one ring is what the next ring puts back`() {
        allowed()
        val first = armed()
        val second = armed()
        runBlocking { policy.raiseFor(first) }
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0) // they turned it down a little

        assertTrue(runBlocking { policy.raiseFor(second) })
        assertEquals("their level, not the one before the first ring", 5, record()!!.original)

        runBlocking { policy.restoreFor(first.id) } // the first ring's clean-up, arriving late, changes nothing
        assertEquals(loudest, volume())

        runBlocking { policy.restoreFor(second.id) }
        assertEquals(5, volume())
        assertNull(record())
    }

    @Test
    fun `restoring now says whether the volume is the person's again, and keeps what it could not finish for a retry that needs no database`() {
        allowed()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        assertFalse(policy.hasUnfinishedRestore)

        VolumeRefusalShadow.floor = 3 // the platform keeps it at 3 or more
        assertFalse("it went only as low as the platform allows", runBlocking { policy.restoreNow() })
        assertNull("the record is cleared, there is nothing more the database can do", record())
        assertTrue("but what was wanted is kept", policy.hasUnfinishedRestore)
        assertFalse("and trying again, with the platform still saying 3, is no better", runBlocking { policy.retryUnfinishedRestore() })
        assertEquals(3, volume())
        assertTrue(policy.hasUnfinishedRestore)

        VolumeRefusalShadow.floor = 0
        assertTrue("the platform allows it now", runBlocking { policy.retryUnfinishedRestore() })
        assertEquals(1, volume())
        assertFalse(policy.hasUnfinishedRestore)
        assertTrue("and then there is nothing left to do", runBlocking { policy.retryUnfinishedRestore() })
    }

    @Test
    fun `during an erase a restore that only reached the platform's floor is remembered whoever did it, so that the erase can say so`() {
        allowed()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        policy.eraseBegan()

        VolumeRefusalShadow.floor = 3
        runBlocking { policy.restoreFor(o.id) } // the ring's own clean-up, which runs as the erase ends the ring and gets there first
        assertNull("the record is gone", record())
        assertTrue("but the shortfall is remembered", policy.hasUnfinishedRestore)

        assertFalse("so the erase's own restore still says the volume is not what the person had", runBlocking { policy.restoreNow() })
        assertTrue(policy.hasUnfinishedRestore)
        assertEquals(3, volume())

        VolumeRefusalShadow.floor = 0
        assertTrue("and it is put back once the platform allows it", runBlocking { policy.retryUnfinishedRestore() })
        assertEquals(1, volume())
        assertFalse(policy.hasUnfinishedRestore)
    }

    @Test
    fun `a shortfall the ring's clean-up left is forgotten once the person has set the volume themselves`() {
        allowed()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        policy.eraseBegan()
        VolumeRefusalShadow.floor = 3
        runBlocking { policy.restoreFor(o.id) }
        assertTrue(policy.hasUnfinishedRestore)

        VolumeRefusalShadow.floor = 0
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 6, 0) // theirs

        assertTrue("what they chose is theirs", runBlocking { policy.restoreNow() })
        assertEquals(6, volume())
        assertFalse(policy.hasUnfinishedRestore)
    }

    @Test
    fun `outside an erase a ring that ended on a floor leaves nothing to report, so a later erase is not blamed for it`() {
        allowed()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val o = armed()
        runBlocking { policy.raiseFor(o) }

        VolumeRefusalShadow.floor = 3
        runBlocking { policy.restoreFor(o.id) }

        assertFalse(policy.hasUnfinishedRestore)
        assertTrue("an erase with nothing recorded has nothing to report", runBlocking { policy.restoreNow() })
    }

    @Test
    fun `an erase that was abandoned stops remembering what falls short`() {
        allowed()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        policy.eraseBegan()
        policy.eraseAbandoned()

        VolumeRefusalShadow.floor = 3
        runBlocking { policy.restoreFor(o.id) }

        assertFalse(policy.hasUnfinishedRestore)
    }

    @Test
    fun `restoring now is a success when nothing was raised, and when the person moved the volume since`() {
        allowed()
        assertTrue("nothing was raised", runBlocking { policy.restoreNow() })
        assertFalse(policy.hasUnfinishedRestore)

        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 4, 0) // the person turns it down themselves, but not to what it was
        assertTrue("what they chose is theirs", runBlocking { policy.restoreNow() })
        assertEquals(4, volume())
        assertFalse(policy.hasUnfinishedRestore)
    }

    @Test
    fun `a retry finds the volume moved by the person and leaves it`() {
        allowed()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val o = armed()
        runBlocking { policy.raiseFor(o) }
        VolumeRefusalShadow.refuse = true
        assertFalse(runBlocking { policy.restoreNow() })
        assertTrue(policy.hasUnfinishedRestore)

        VolumeRefusalShadow.refuse = false
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        assertTrue("moved since, so it is theirs", runBlocking { policy.retryUnfinishedRestore() })
        assertEquals(5, volume())
        assertFalse(policy.hasUnfinishedRestore)
    }

    @Test
    fun `a restore that the platform cannot take as low as the person had it is written down with what was wanted and what came out`() {
        allowed()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0) // a level that only a privileged caller could have set
        val o = armed()
        runBlocking { policy.raiseFor(o) }

        VolumeRefusalShadow.floor = 3
        runBlocking { policy.restoreFor(o.id) }

        assertEquals(3, volume())
        assertNull("nothing more can be done, so the record is cleared", record())
        val note = events(o, EventType.VOLUME_SET_REFUSED).single().payloadJson
        assertTrue(note.contains("\"wanted\":\"1\"") && note.contains("\"got\":\"3\"") && note.contains("restore"))
    }
}
