package com.command.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.command.testing.android.FakeAlarmModule
import com.command.testing.android.FakeRingOutput
import com.command.testing.android.RecordingPoster
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import android.content.Intent
import android.net.Uri
import android.os.Looper
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
import com.command.testing.FakeAlarmScheduler
import com.command.testing.FakeClock
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
import java.time.Duration
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34, 36])
class ReveilleServiceTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var repo: OccurrenceRepository
    @Inject lateinit var db: CommandDb
    @Inject lateinit var clock: FakeClock
    @Inject lateinit var output: FakeRingOutput
    @Inject lateinit var scheduler: FakeAlarmScheduler
    @Inject lateinit var resolver: RingModeResolver

    private lateinit var context: Context

    @Before
    fun setUp() {
        hilt.inject()
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun armed(profileId: String? = "builtin_steady"): OccurrenceRow = runBlocking {
        val mission = testMission(profileId = profileId)
        db.missions().insert(mission)
        val occurrence = repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
        repo.apply(occurrence.id, OccurrenceCommand.Arm(ArmMode.EXACT), EventSource.SYSTEM)
        repo.get(occurrence.id)!!
    }

    private fun scheduledOnly(): OccurrenceRow = runBlocking {
        val mission = testMission()
        db.missions().insert(mission)
        repo.createOccurrence(mission.id, FakeAlarmModule.T0 + 60_000, "UTC").occurrence
    }

    private fun stateOf(o: OccurrenceRow) = runBlocking { repo.get(o.id)!!.state }

    private fun fireIntent(o: OccurrenceRow) = Intent(context, ReveilleService::class.java)
        .setAction(AlarmContract.ACTION_FIRE)
        .setData(Uri.parse("command://occurrence/${o.id}"))
        .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id)
        .putExtra(AlarmContract.EXTRA_REQUEST_CODE, o.requestCode)

    private fun startService(intent: Intent) =
        Robolectric.buildService(ReveilleService::class.java, intent).create().startCommand(0, 1)

    private fun eventTypes(o: OccurrenceRow) = runBlocking { db.events().forOccurrence(o.id).map { it.type } }

    @Test
    fun `it becomes a foreground service before it touches the database`() {
        val o = armed()
        val controller = startService(fireIntent(o))
        // startCommand returned synchronously, and the foreground call is made inside it before any coroutine is launched.
        val service = controller.get()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(o.requestCode, shadowOf(service).lastForegroundNotificationId)
        assertEquals("the ongoing notification already offers both actions", 2, shadowOf(service).lastForegroundNotification.actions.size)
    }

    @Test
    fun `a valid alarm fires the occurrence and rings`() {
        val o = armed()
        startService(fireIntent(o))
        assertTrue("the sound starts at once, before the database is consulted", output.ringing)
        awaitUntil(message = "fired") { stateOf(o) == OccurrenceState.FIRING }
        assertTrue(EventType.ALARM_FIRED in eventTypes(o))
        // Platform effects run just after the commit, so give them a moment.
        awaitUntil(message = "backstop") { scheduler.armed.keys.any { it.startsWith("command://cycle-end/") } }
        assertEquals("the cycle-end backstop is armed", 1, scheduler.armed.keys.count { it.startsWith("command://cycle-end/") })
    }

    @Test
    fun `the ring stops at the end of the plan and records a miss`() {
        val o = armed() // Steady: one two-minute ring
        val service = startService(fireIntent(o)).get()
        awaitUntil(message = "fired") { stateOf(o) == OccurrenceState.FIRING }
        // Let two minutes of virtual time pass in small steps, so the ring cycle's delay is reached whenever it is scheduled.
        var elapsed = 0L
        awaitUntil(message = "miss") {
            if (elapsed < 3 * 60_000) {
                clock.advance(5_000)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000))
                elapsed += 5_000
            }
            stateOf(o) == OccurrenceState.MISSED
        }
        awaitUntil(message = "service stop") { shadowOf(service).isStoppedBySelf }
        assertFalse(output.ringing)
        assertEquals(MissReason.TIMEOUT, runBlocking { repo.get(o.id)!!.missReason })
    }

    @Test
    fun `a stale alarm for an occurrence that is not armed rings nothing and stops`() {
        val o = scheduledOnly()
        val service = startService(fireIntent(o)).get()
        awaitUntil(message = "stop") { shadowOf(service).isStoppedBySelf }
        assertFalse("whatever sounded first is stopped again", output.ringing)
        assertEquals(OccurrenceState.SCHEDULED, stateOf(o))
        assertTrue(EventType.REJECTED_TRANSITION in eventTypes(o))
    }

    @Test
    fun `a service restarted mid-ring resumes the same ring without firing twice`() {
        val o = armed()
        runBlocking { repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE) }
        clock.advance(30_000)
        val service = startService(fireIntent(o)).get()
        awaitUntil(message = "ringing again") { output.ringing }
        assertEquals(1, eventTypes(o).count { it == EventType.ALARM_FIRED })
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a service restarted after the plan ended records the miss and is not left ringing`() {
        val o = armed()
        runBlocking { repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE) }
        clock.advance(10 * 60_000)
        val service = startService(fireIntent(o)).get()
        awaitUntil(message = "miss") { stateOf(o) == OccurrenceState.MISSED }
        awaitUntil(message = "stop") { shadowOf(service).isStoppedBySelf }
        assertFalse("it is not left ringing", output.ringing)
    }

    @Test
    fun `a stop request silences the sound and ends the service`() {
        val o = armed()
        val controller = startService(fireIntent(o))
        awaitUntil(message = "ringing") { output.ringing }
        val stop = Intent(context, ReveilleService::class.java).setAction(AlarmContract.ACTION_STOP).putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id)
        controller.get().onStartCommand(stop, 0, 2)
        assertFalse(output.ringing)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `an intent with no occurrence still answers the foreground contract, then stops`() {
        val service = startService(Intent(context, ReveilleService::class.java).setAction(AlarmContract.ACTION_FIRE)).get()
        assertTrue("it went foreground, then dropped it", shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the degraded ring fires the occurrence, logs why, and rings from a full-screen notification that times itself out`() {
        val o = armed()
        runBlocking { resolver.degrade(o.id, o.requestCode, "ForegroundServiceStartNotAllowedException") }
        assertEquals(OccurrenceState.FIRING, stateOf(o))
        assertTrue(EventType.RING_DEGRADED in eventTypes(o))
        assertEquals("the same backstop bounds this path", 1, scheduler.armed.keys.count { it.startsWith("command://cycle-end/") })

        val notification: Notification = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(o.requestCode)
        assertNotNull(notification)
        assertTrue(notification.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(AlarmContract.CHANNEL_FALLBACK, notification.channelId)
        assertNotNull(notification.fullScreenIntent)
        assertEquals("two minutes of ring", 2 * 60_000L, notification.timeoutAfter)
    }

    @Test
    fun `without notification permission the ring still fires and nothing is posted or thrown`() {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val o = armed()
        runBlocking { resolver.degrade(o.id, o.requestCode, "ForegroundServiceStartNotAllowedException") }
        assertEquals("the occurrence is still fired and bounded", OccurrenceState.FIRING, stateOf(o))
        assertTrue(EventType.RING_DEGRADED in eventTypes(o))
        assertEquals(1, scheduler.armed.keys.count { it.startsWith("command://cycle-end/") })
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun `degrading a stale alarm does nothing`() {
        val o = scheduledOnly()
        runBlocking { resolver.degrade(o.id, o.requestCode, "Whatever") }
        assertEquals(OccurrenceState.SCHEDULED, stateOf(o))
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
}
