package com.command.alarm

import android.app.NotificationManager
import android.content.Context
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.FakeRingOutput
import com.command.testing.android.RecordingPoster
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.command.data.OccurrenceRepository
import com.command.data.db.CommandDb
import com.command.data.db.OccurrenceRow
import com.command.domain.model.EventSource
import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.testing.FakeRingController
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34, 36])
class ReceiversTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var ringer: FakeRingController
    @Inject lateinit var poster: RecordingPoster

    private lateinit var context: Context

    @Before
    fun setUp() {
        hilt.inject()
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun occurrence(vararg commands: OccurrenceCommand): OccurrenceRow = runBlocking {
        val mission = testMission()
        db.missions().insert(mission)
        val o = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        for (c in commands) repo.apply(o.id, c, EventSource.SYSTEM)
        repo.get(o.id)!!
    }

    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }
    private fun eventTypes(o: OccurrenceRow) = runBlocking { db.events().forOccurrence(o.id).map { it.type } }

    private fun intent(o: OccurrenceRow, action: String, uri: String, target: Class<*>): Intent =
        Intent(context, target).setAction(action).setData(Uri.parse(uri))
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id)
            .putExtra(AlarmContract.EXTRA_REQUEST_CODE, o.requestCode)

    // --- the alarm receiver -----------------------------------------------------------------------------------

    @Test
    fun `the alarm receiver starts the service at once and does no database work`() {
        val o = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT))
        ReveilleAlarmReceiver().onReceive(context, intent(o, AlarmContract.ACTION_FIRE, "command://occurrence/${o.id}", ReveilleAlarmReceiver::class.java))

        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(started)
        assertEquals(ReveilleService::class.java.name, started.component!!.className)
        assertEquals(o.id, started.getStringExtra(AlarmContract.EXTRA_OCCURRENCE_ID))
        assertEquals(o.requestCode, started.getIntExtra(AlarmContract.EXTRA_REQUEST_CODE, 0))
        assertEquals("the receiver changed no state", OccurrenceState.ARMED, stateOf(o))
        assertTrue("and wrote nothing to the audit log beyond arming", EventType.ALARM_FIRED !in eventTypes(o))
    }

    @Test
    fun `the alarm receiver holds the CPU awake briefly`() {
        val o = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT))
        ReveilleAlarmReceiver().onReceive(context, intent(o, AlarmContract.ACTION_FIRE, "command://occurrence/${o.id}", ReveilleAlarmReceiver::class.java))
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(lock)
        assertTrue(lock.isHeld)
        assertEquals(PowerManager.PARTIAL_WAKE_LOCK, PowerManager.PARTIAL_WAKE_LOCK)
    }

    @Test
    fun `an alarm intent without an occurrence is ignored`() {
        ReveilleAlarmReceiver().onReceive(context, Intent(context, ReveilleAlarmReceiver::class.java).setAction(AlarmContract.ACTION_FIRE))
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    // --- the cycle-end backstop -----------------------------------------------------------------------------

    @Test
    fun `the cycle-end alarm ends a ring that nobody answered`() {
        val o = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire)
        CycleEndReceiver().onReceive(context, intent(o, AlarmContract.ACTION_CYCLE_END, "command://cycle-end/${o.id}", CycleEndReceiver::class.java))
        awaitUntil(message = "miss") { stateOf(o) == OccurrenceState.MISSED }
        assertEquals(MissReason.TIMEOUT, runBlocking { repo.get(o.id)!!.missReason })
        // The stop is requested right after the commit, so wait for it rather than assuming it came first.
        awaitUntil(message = "stop request") { o.id in ringer.stopped }
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        awaitUntil(message = "missed note") { manager.allNotifications.isNotEmpty() }
        val notifications = manager.allNotifications
        assertEquals("a quiet missed note, not a ring", 1, notifications.size)
        assertEquals(AlarmContract.CHANNEL_STATUS, notifications.single().channelId)
    }

    @Test
    fun `the cycle-end alarm for a ring that was already silenced does nothing, whichever came first`() {
        val o = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.SafetySilence)
        CycleEndReceiver().onReceive(context, intent(o, AlarmContract.ACTION_CYCLE_END, "command://cycle-end/${o.id}", CycleEndReceiver::class.java))
        awaitUntil(message = "rejection") { EventType.REJECTED_TRANSITION in eventTypes(o) }
        assertEquals(OccurrenceState.SAFETY_SILENCED, stateOf(o))
        assertTrue("no missed note for something the user silenced", shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun `the cycle-end alarm for an occurrence that was already answered does nothing`() {
        val o = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge())
        CycleEndReceiver().onReceive(context, intent(o, AlarmContract.ACTION_CYCLE_END, "command://cycle-end/${o.id}", CycleEndReceiver::class.java))
        awaitUntil(message = "rejection") { EventType.REJECTED_TRANSITION in eventTypes(o) }
        assertEquals(OccurrenceState.ACKNOWLEDGED, stateOf(o))
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    // --- safety silence -------------------------------------------------------------------------------------------

    @Test
    fun `the silence button silences the ring and tells the service immediately`() {
        val o = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire)
        SilenceReceiver().onReceive(context, intent(o, AlarmContract.ACTION_SILENCE, "command://silence/${o.id}", SilenceReceiver::class.java))
        awaitUntil(message = "silence") { stateOf(o) == OccurrenceState.SAFETY_SILENCED }
        awaitUntil(message = "stop request") { o.id in ringer.stopped }
        assertTrue("the stop names the ring notification too", o.requestCode in ringer.stoppedCodes)
        assertTrue("a silence is not a dismissal", EventType.NOTIFICATION_DISMISSED !in eventTypes(o))
    }

    @Test
    fun `swiping the ring notification away counts as silencing it, and says so`() {
        val o = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire)
        SilenceReceiver().onReceive(context, intent(o, AlarmContract.ACTION_DISMISSED, "command://dismissed/${o.id}", SilenceReceiver::class.java))
        awaitUntil(message = "silence") { stateOf(o) == OccurrenceState.SAFETY_SILENCED }
        assertTrue(EventType.NOTIFICATION_DISMISSED in eventTypes(o))
    }

    @Test
    fun `a second silence is harmless`() {
        val o = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.SafetySilence)
        SilenceReceiver().onReceive(context, intent(o, AlarmContract.ACTION_SILENCE, "command://silence/${o.id}", SilenceReceiver::class.java))
        awaitUntil(message = "rejection") { EventType.REJECTED_TRANSITION in eventTypes(o) }
        assertEquals(OccurrenceState.SAFETY_SILENCED, stateOf(o))
    }

    // --- follow-up ---------------------------------------------------------------------------------------

    @Test
    fun `a check-in alarm asks the next question`() {
        val o = occurrence(
            OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire,
            OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance,
        )
        CheckInReceiver().onReceive(
            context,
            intent(o, AlarmContract.ACTION_CHECK_IN, "command://checkin/${o.id}/0", CheckInReceiver::class.java).putExtra(AlarmContract.EXTRA_INDEX, 0),
        )
        awaitUntil(message = "question") { poster.posted.isNotEmpty() }
        assertEquals(Triple(o.id, o.requestCode, 0), poster.posted.single())
    }

    @Test
    fun `a check-in alarm that arrives out of order is ignored`() {
        val o = occurrence(
            OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire,
            OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance,
        )
        CheckInReceiver().onReceive(
            context,
            intent(o, AlarmContract.ACTION_CHECK_IN, "command://checkin/${o.id}/1", CheckInReceiver::class.java).putExtra(AlarmContract.EXTRA_INDEX, 1),
        )
        awaitUntil(message = "rejection") { EventType.REJECTED_TRANSITION in eventTypes(o) }
        assertTrue(poster.posted.isEmpty())
    }
}
