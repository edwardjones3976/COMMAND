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
import com.command.domain.time.Clock
import com.command.testing.FakeRingController
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
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
    @Inject lateinit var clock: Clock

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
        val asked = poster.posted.single()
        assertEquals(listOf(o.id, o.requestCode, 0), listOf(asked.occurrenceId, asked.requestCode, asked.index))
        assertEquals("Write the report", asked.title)
        assertFalse("nothing has been reported yet, so the question is whether it was started", asked.started)
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

    // --- how late a question was --------------------------------------------------------------------------------------------------

    private fun guided() = occurrence(OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance)

    @Test
    fun `a check-in alarm that the platform held back says how late it was, in the record and the log`() {
        val o = guided()
        CheckInReceiver().onReceive(
            context,
            intent(o, AlarmContract.ACTION_CHECK_IN, "command://checkin/${o.id}/0", CheckInReceiver::class.java)
                .putExtra(AlarmContract.EXTRA_INDEX, 0)
                .putExtra(AlarmContract.EXTRA_AT, clock.nowMs() - 95_000),
        )
        awaitUntil(message = "question") { poster.posted.isNotEmpty() }
        assertEquals(95_000L, runBlocking { db.checkIns().forOccurrence(o.id).single().lateMs })
        assertTrue(EventType.CHECKIN_LATE in eventTypes(o))
    }

    @Test
    fun `a check-in alarm that came on time, or with no time on it, says nothing about being late`() {
        val o = guided()
        CheckInReceiver().onReceive(
            context,
            intent(o, AlarmContract.ACTION_CHECK_IN, "command://checkin/${o.id}/0", CheckInReceiver::class.java)
                .putExtra(AlarmContract.EXTRA_INDEX, 0)
                .putExtra(AlarmContract.EXTRA_AT, clock.nowMs() - 500),
        )
        awaitUntil(message = "question") { poster.posted.isNotEmpty() }
        assertEquals(500L, runBlocking { db.checkIns().forOccurrence(o.id).single().lateMs })
        assertFalse(EventType.CHECKIN_LATE in eventTypes(o))

        val untimed = guided()
        CheckInReceiver().onReceive(
            context,
            intent(untimed, AlarmContract.ACTION_CHECK_IN, "command://checkin/${untimed.id}/0", CheckInReceiver::class.java).putExtra(AlarmContract.EXTRA_INDEX, 0),
        )
        awaitUntil(message = "second question") { poster.posted.size == 2 }
        assertEquals(0L, runBlocking { db.checkIns().forOccurrence(untimed.id).single().lateMs })
    }

    // --- the answers on the notification ----------------------------------------------------------------------------------------------

    private fun answer(o: OccurrenceRow, name: String?): Intent =
        Intent(context, CheckInAnswerReceiver::class.java).setAction(AlarmContract.ACTION_CHECK_IN_ANSWER)
            .setData(Uri.parse("command://checkin-answer/${o.id}/$name"))
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id)
            .apply { if (name != null) putExtra(AlarmContract.EXTRA_ANSWER, name) }

    @Test
    fun `tapping Started on the question reports the start and takes the question away`() {
        val o = guided()
        CheckInAnswerReceiver().onReceive(context, answer(o, "STARTED"))
        awaitUntil(message = "started") { stateOf(o) == OccurrenceState.STARTED_SELF_REPORTED }
        awaitUntil(message = "question removed") { o.id in poster.cancelled }
    }

    @Test
    fun `Still working and Done report themselves, and Done ends the follow-up`() {
        val working = occurrence(
            OccurrenceCommand.Arm(ArmMode.EXACT), OccurrenceCommand.Fire, OccurrenceCommand.Acknowledge(), OccurrenceCommand.StartGuidance, OccurrenceCommand.ReportStarted,
        )
        CheckInAnswerReceiver().onReceive(context, answer(working, "WORKING"))
        awaitUntil(message = "working") { stateOf(working) == OccurrenceState.WORKING_SELF_REPORTED }

        CheckInAnswerReceiver().onReceive(context, answer(working, "DONE"))
        awaitUntil(message = "done") { stateOf(working) == OccurrenceState.COMPLETED_SELF_REPORTED }
    }

    @Test
    fun `an answer to a question that has outlived its occurrence changes nothing, and the question still goes`() {
        val o = guided()
        runBlocking { repo.apply(o.id, OccurrenceCommand.ReportDone, EventSource.USER) }
        poster.cancelled.clear()
        CheckInAnswerReceiver().onReceive(context, answer(o, "STARTED"))
        awaitUntil(message = "question removed") { o.id in poster.cancelled }
        assertEquals("it stayed done", OccurrenceState.COMPLETED_SELF_REPORTED, stateOf(o))
    }

    @Test
    fun `an answer that is not one of the three, or names no occurrence, is ignored`() {
        val o = guided()
        CheckInAnswerReceiver().onReceive(context, answer(o, "NEED_RESET"))
        CheckInAnswerReceiver().onReceive(context, answer(o, "nonsense"))
        CheckInAnswerReceiver().onReceive(context, answer(o, null))
        CheckInAnswerReceiver().onReceive(context, Intent(context, CheckInAnswerReceiver::class.java).setAction(AlarmContract.ACTION_CHECK_IN_ANSWER).putExtra(AlarmContract.EXTRA_ANSWER, "STARTED"))
        // A valid answer afterwards is the marker that the earlier ones, handled in order, have all been dealt with.
        CheckInAnswerReceiver().onReceive(context, answer(o, "DONE"))
        awaitUntil(message = "done") { stateOf(o) == OccurrenceState.COMPLETED_SELF_REPORTED }
        assertEquals("only the valid answer took the question away", listOf(o.id), poster.cancelled.distinct())
        assertFalse(EventType.REJECTED_TRANSITION in eventTypes(o))
    }
}
