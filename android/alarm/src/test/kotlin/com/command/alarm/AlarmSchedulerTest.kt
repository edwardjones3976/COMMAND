package com.command.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.occurrence.ArmMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Platform-sensitive: runs on API 24 (no exact-alarm permission), 31 (revocable), 34 and 36. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 31, 34, 36])
class AlarmSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarms: AlarmManager
    private lateinit var scheduler: AndroidAlarmScheduler
    private val at = 1_900_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarms = context.getSystemService(AlarmManager::class.java)
        scheduler = AndroidAlarmScheduler(context)
        if (android.os.Build.VERSION.SDK_INT >= 31) ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun request(kind: AlarmKind = AlarmKind.FIRE, occurrence: String = "occ-1", code: Int = 7, index: Int = 0) =
        AlarmRequest(occurrence, code, kind, at, index)

    private fun scheduled() = shadowOf(alarms).scheduledAlarms

    @Test
    fun `a fire alarm is an exact alarm clock`() {
        assertEquals(ArmMode.EXACT, scheduler.arm(request()))
        val alarm = scheduled().single()
        assertNotNull("the alarm-clock call is the one that wakes a dozing phone", alarm.alarmClockInfo)
        assertEquals(at, alarm.triggerAtMs)
    }

    @Test
    fun `cycle-end and check-in alarms are exact and allowed while idle`() {
        assertEquals(ArmMode.EXACT, scheduler.arm(request(AlarmKind.CYCLE_END)))
        assertEquals(ArmMode.EXACT, scheduler.arm(request(AlarmKind.CHECK_IN, index = 1)))
        for (alarm in scheduled()) {
            assertTrue(alarm.allowWhileIdle)
            assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
            assertNull(alarm.alarmClockInfo)
        }
    }

    @Test
    fun `without the exact-alarm grant everything degrades to inexact and says so`() {
        if (android.os.Build.VERSION.SDK_INT < 31) return // before Android 12 exact alarms need no grant
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(scheduler.canScheduleExact())
        for (kind in AlarmKind.values()) {
            assertEquals(kind.name, ArmMode.INEXACT, scheduler.arm(request(kind)))
        }
        for (alarm in scheduled()) {
            assertNull(alarm.alarmClockInfo)
            assertTrue(alarm.allowWhileIdle)
        }
    }

    @Test
    fun `before Android 12 exact scheduling is always available`() {
        if (android.os.Build.VERSION.SDK_INT >= 31) return
        assertTrue(scheduler.canScheduleExact())
    }

    @Test
    fun `arming the same request again replaces it`() {
        scheduler.arm(request())
        scheduler.arm(request().copy(atUtcMs = at + 60_000))
        assertEquals(1, scheduled().size)
        assertEquals(at + 60_000, scheduled().single().triggerAtMs)
    }

    @Test
    fun `an occurrence's alarms do not collide even though they share a request code`() {
        scheduler.arm(request(AlarmKind.FIRE))
        scheduler.arm(request(AlarmKind.CYCLE_END))
        scheduler.arm(request(AlarmKind.CHECK_IN, index = 0))
        scheduler.arm(request(AlarmKind.CHECK_IN, index = 1))
        assertEquals(4, scheduled().size)
    }

    @Test
    fun `two occurrences never share an alarm`() {
        scheduler.arm(request(occurrence = "a", code = 1))
        scheduler.arm(request(occurrence = "b", code = 2))
        assertEquals(2, scheduled().size)
    }

    @Test
    fun `cancelling removes exactly that alarm`() {
        scheduler.arm(request(AlarmKind.FIRE))
        scheduler.arm(request(AlarmKind.CYCLE_END))
        scheduler.cancel(request(AlarmKind.CYCLE_END))
        assertEquals(1, scheduled().size)
        scheduler.cancel(request(AlarmKind.FIRE))
        assertTrue(scheduled().isEmpty())
    }

    @Test
    fun `cancelling something that was never armed is harmless`() {
        scheduler.cancel(request(occurrence = "nothing"))
        assertTrue(scheduled().isEmpty())
    }

    @Test
    fun `the pending intent names its occurrence, its kind and is immutable`() {
        scheduler.arm(request(AlarmKind.CHECK_IN, index = 2))
        val operation = scheduled().single().operation!!
        val saved = shadowOf(operation).savedIntent
        assertEquals(AlarmContract.ACTION_CHECK_IN, saved.action)
        assertEquals("command://checkin/occ-1/2", saved.dataString)
        assertEquals("occ-1", saved.getStringExtra(AlarmContract.EXTRA_OCCURRENCE_ID))
        assertEquals(7, saved.getIntExtra(AlarmContract.EXTRA_REQUEST_CODE, 0))
        assertEquals(2, saved.getIntExtra(AlarmContract.EXTRA_INDEX, -1))
        assertEquals(CheckInReceiver::class.java.name, saved.component!!.className)
        assertTrue(shadowOf(operation).flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun `fire alarms go to the receiver that starts the service`() {
        scheduler.arm(request(AlarmKind.FIRE))
        val saved = shadowOf(scheduled().single().operation!!).savedIntent
        assertEquals(ReveilleAlarmReceiver::class.java.name, saved.component!!.className)
        assertEquals("command://occurrence/occ-1", saved.dataString)
    }

    @Test
    fun `an armed alarm is found by the probe, a cancelled one is not, and asking never arms one`() {
        assertFalse("nothing is armed yet", scheduler.isArmed(request()))
        assertFalse("and asking did not arm it", scheduler.isArmed(request()))
        assertTrue(scheduled().isEmpty())

        scheduler.arm(request())
        assertTrue(scheduler.isArmed(request()))
        assertEquals(1, scheduled().size)

        scheduler.cancel(request())
        assertFalse(scheduler.isArmed(request()))
    }

    @Test
    fun `the probe tells the three kinds of alarm apart, and one occurrence from another`() {
        scheduler.arm(request(AlarmKind.CHECK_IN, index = 1))
        assertFalse("a check-in is not the ring", scheduler.isArmed(request(AlarmKind.FIRE)))
        assertFalse(scheduler.isArmed(request(AlarmKind.CYCLE_END)))
        assertTrue(scheduler.isArmed(request(AlarmKind.CHECK_IN, index = 1)))
        assertFalse("the second check-in is not the first", scheduler.isArmed(request(AlarmKind.CHECK_IN, index = 2)))

        scheduler.arm(request(AlarmKind.FIRE, occurrence = "occ-1", code = 7))
        assertTrue(scheduler.isArmed(request(AlarmKind.FIRE, occurrence = "occ-1", code = 7)))
        assertFalse("another occurrence", scheduler.isArmed(request(AlarmKind.FIRE, occurrence = "occ-2", code = 8)))
    }

    @Test
    fun `arming the same alarm again replaces it instead of adding a second`() {
        scheduler.arm(request())
        scheduler.arm(request())
        assertEquals(1, scheduled().size)
        assertTrue(scheduler.isArmed(request()))
    }
}
