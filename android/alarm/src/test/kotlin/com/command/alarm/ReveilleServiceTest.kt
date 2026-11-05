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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
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
    @Inject lateinit var session: RingSession

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

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java))

    /** Moves both clocks forward, letting the ring loop run as the time passes. */
    private fun step(ms: Long) {
        var left = ms
        while (left > 0) {
            val chunk = minOf(5_000L, left)
            clock.advance(chunk)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(chunk))
            left -= chunk
        }
    }

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
        assertNotNull("a quiet note lets the user pick the missed ring up again", notifications().getNotification(o.id, AlarmContract.ID_MISSED))
    }

    @Test
    fun `a stale alarm for an occurrence that is not armed rings nothing and stops`() {
        val o = scheduledOnly()
        val service = startService(fireIntent(o)).get()
        awaitUntil(message = "stop") { shadowOf(service).isStoppedBySelf }
        assertFalse("whatever sounded first is stopped again", output.ringing)
        assertEquals(OccurrenceState.SCHEDULED, stateOf(o))
        assertTrue(EventType.REJECTED_TRANSITION in eventTypes(o))
        assertTrue("and the service says so itself", EventType.ALARM_FIRED_STALE in eventTypes(o))
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
        assertNotNull("and the user is told it was missed", notifications().getNotification(o.id, AlarmContract.ID_MISSED))
    }

    @Test
    fun `ending a ring silences the sound and ends the service`() {
        val o = armed()
        val controller = startService(fireIntent(o))
        awaitUntil(message = "ringing") { controller.get().ringingOccurrenceId == o.id }
        session.end(o.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(output.ringing)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `a silence in the very first moments, before the alarm has been checked, still silences it`() {
        val o = armed()
        val controller = startService(fireIntent(o))
        assertTrue("the sound started ahead of the checks", output.ringing)
        session.end(o.id) // the database has not answered yet
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("silenced at once", output.ringing)
        awaitUntil(message = "service stop") { shadowOf(controller.get()).isStoppedBySelf }
        assertFalse("and the late check does not start it again", output.ringing)
        assertTrue(controller.get().ringingOccurrenceId == null)
    }

    @Test
    fun `ending the ring of a different occurrence leaves this one sounding`() {
        val o = armed()
        val controller = startService(fireIntent(o))
        awaitUntil(message = "ringing") { controller.get().ringingOccurrenceId == o.id }
        session.end("some-other-occurrence")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(output.ringing)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `ending a ring when no service is running starts nothing and throws nothing`() {
        session.end("nothing-is-ringing")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun `a stale alarm that arrives while another ring is sounding leaves that ring alone`() {
        val first = armed()
        val controller = startService(fireIntent(first))
        awaitUntil(message = "first ring") { stateOf(first) == OccurrenceState.FIRING }
        val stale = scheduledOnly()
        controller.get().onStartCommand(fireIntent(stale), 0, 2)
        awaitUntil(message = "stale noted") { EventType.ALARM_FIRED_STALE in eventTypes(stale) }
        assertTrue("the first ring still sounds", output.ringing)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(OccurrenceState.FIRING, stateOf(first))
        assertEquals("the stale alarm did not turn into a ring", OccurrenceState.SCHEDULED, stateOf(stale))
    }

    @Test
    fun `a second valid alarm takes over the sound and leaves the first occurrence as it was`() {
        val first = armed()
        val controller = startService(fireIntent(first))
        awaitUntil(message = "first ring") { stateOf(first) == OccurrenceState.FIRING }
        val second = armed()
        controller.get().onStartCommand(fireIntent(second), 0, 2)
        awaitUntil(message = "second ring") { stateOf(second) == OccurrenceState.FIRING }
        awaitUntil(message = "takeover") { shadowOf(controller.get()).lastForegroundNotificationId == second.requestCode }
        assertTrue(output.ringing)
        assertEquals("the first stays unanswered until its own plan ends", OccurrenceState.FIRING, stateOf(first))

        // Ending the second ring ends the sound; it does not touch the first occurrence.
        session.end(second.id)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(output.ringing)
        assertEquals(OccurrenceState.FIRING, stateOf(first))
    }

    @Test
    fun `the same alarm delivered twice rings once`() {
        val o = armed()
        val controller = startService(fireIntent(o))
        awaitUntil(message = "ring") { stateOf(o) == OccurrenceState.FIRING }
        controller.get().onStartCommand(fireIntent(o), 0, 2)
        awaitUntil(message = "second delivery handled") { EventType.REJECTED_TRANSITION in eventTypes(o) }
        assertEquals(1, eventTypes(o).count { it == EventType.ALARM_FIRED })
        assertTrue(output.ringing)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `setting the phone clock back during a ring does not stretch it`() {
        val o = armed("builtin_command") // two rings of two minutes with a minute between them
        val service = startService(fireIntent(o)).get()
        awaitUntil(message = "ringing") { service.ringingOccurrenceId == o.id }
        step(60_000)
        clock.nowMs -= 60 * 60_000L // an hour back: the wall clock only, the monotonic one carries on
        var waited = 0L
        awaitUntil(message = "miss at the end of the plan, not an hour late") {
            if (waited < 400_000) {
                step(5_000)
                waited += 5_000
            }
            stateOf(o) == OccurrenceState.MISSED
        }
        assertTrue("the ring ended with its five minute plan, not an hour late (waited $waited ms more)", waited <= 245_000)
        awaitUntil(message = "service stop") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun `a service restarted after the phone clock was set back goes on from the monotonic clock, not from the start`() {
        val o = armed() // Steady: one two-minute ring
        runBlocking { repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE) }
        clock.advance(100_000) // 100 of the 120 seconds have passed
        clock.nowMs -= 60 * 60_000L // then the wall clock is set back an hour, and the process dies and restarts
        val service = startService(fireIntent(o)).get()
        awaitUntil(message = "ringing again") { service.ringingOccurrenceId == o.id }
        var waited = 0L
        awaitUntil(message = "miss after the twenty seconds that were left") {
            if (waited < 60_000) {
                step(5_000)
                waited += 5_000
            }
            stateOf(o) == OccurrenceState.MISSED
        }
        assertTrue("the 20 seconds that were left, not the whole ring again (waited $waited ms)", waited <= 30_000)
    }

    @Test
    fun `an end that arrives right after the alarm was fired still silences it, whatever the service was doing`() {
        repeat(3) {
            val o = armed()
            val controller = startService(fireIntent(o))
            awaitUntil(message = "fired") { stateOf(o) == OccurrenceState.FIRING }
            session.end(o.id) // the service may be reading the mission title, or already ringing
            shadowOf(Looper.getMainLooper()).idle()
            awaitUntil(message = "quiet and stopped") { !output.ringing && shadowOf(controller.get()).isStoppedBySelf }
            repeat(10) { // give a late continuation the chance to misbehave
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertFalse("silenced stays silenced", output.ringing)
        }
    }

    @Test
    fun `the service takes over the CPU hold from the alarm receiver and lets the receiver one go`() {
        val o = armed()
        ReveilleAlarmReceiver().onReceive(
            context,
            Intent(context, ReveilleAlarmReceiver::class.java).setAction(AlarmContract.ACTION_FIRE)
                .setData(Uri.parse("command://occurrence/${o.id}"))
                .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, o.id)
                .putExtra(AlarmContract.EXTRA_REQUEST_CODE, o.requestCode),
        )
        val receiverLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(receiverLock.isHeld)
        startService(fireIntent(o))
        val serviceLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue("the service holds the CPU itself", serviceLock.isHeld)
        assertFalse("so the receiver one is let go", receiverLock.isHeld)
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

        val notification: Notification = notifications().getNotification(o.id, AlarmContract.ID_FALLBACK)
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
        assertTrue(notifications().allNotifications.isEmpty())
        val degraded = runBlocking { db.events().forOccurrence(o.id) }.single { it.type == EventType.RING_DEGRADED }
        assertTrue("the trail says nobody could be told", degraded.payloadJson.contains("\"notified\":\"false\""))
    }

    @Test
    fun `answering a degraded ring takes its notification down`() {
        val o = armed()
        runBlocking { resolver.degrade(o.id, o.requestCode, "ForegroundServiceStartNotAllowedException") }
        assertNotNull(notifications().getNotification(o.id, AlarmContract.ID_FALLBACK))
        AndroidRingController(context, session).stop(o.id, o.requestCode)
        assertNull("its sound is the notification, so it must go with the ring", notifications().getNotification(o.id, AlarmContract.ID_FALLBACK))
    }

    @Test
    fun `degrading a ring whose plan has run out records the miss and makes no sound`() {
        val o = armed()
        runBlocking { repo.apply(o.id, OccurrenceCommand.Fire, EventSource.SERVICE) }
        clock.advance(10 * 60_000)
        runBlocking { resolver.degrade(o.id, o.requestCode, "ForegroundServiceStartNotAllowedException") }
        assertEquals(OccurrenceState.MISSED, stateOf(o))
        assertNull("no alarm notification", notifications().getNotification(o.id, AlarmContract.ID_FALLBACK))
        assertNotNull("only the quiet missed note", notifications().getNotification(o.id, AlarmContract.ID_MISSED))
    }

    @Test
    fun `degrading a stale alarm does nothing`() {
        val o = scheduledOnly()
        runBlocking { resolver.degrade(o.id, o.requestCode, "Whatever") }
        assertEquals(OccurrenceState.SCHEDULED, stateOf(o))
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
}
