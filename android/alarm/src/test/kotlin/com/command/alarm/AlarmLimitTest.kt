package com.command.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.occurrence.ArmMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The platform's limit on how many alarms one app can hold, as far as a shadow can say what it does. Not provoked on a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], shadows = [AlarmLimitShadow::class])
class AlarmLimitTest {

    private lateinit var alarms: AlarmManager
    private lateinit var scheduler: AndroidAlarmScheduler
    private val latch = ErasureLatch()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        alarms = context.getSystemService(AlarmManager::class.java)
        scheduler = AndroidAlarmScheduler(context, latch)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        AlarmLimitShadow.limit = Int.MAX_VALUE
    }

    private fun request(n: Int, kind: AlarmKind = AlarmKind.FIRE, at: Long = 1_900_000_000_000L) = AlarmRequest("occ-$n", n, kind, at)

    private fun held() = shadowOf(alarms).scheduledAlarms

    @Test
    fun `every kind of alarm at the limit is refused, sets nothing, and is not said to be there`() {
        AlarmLimitShadow.limit = 0
        for (kind in AlarmKind.values()) {
            assertEquals(kind.name, ArmMode.REFUSED, scheduler.arm(request(1, kind)))
            assertFalse(kind.name, scheduler.isArmed(request(1, kind)))
        }
        assertTrue(held().isEmpty())
    }

    @Test
    fun `without the exact permission the inexact call is refused the same way`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AlarmLimitShadow.limit = 0
        assertEquals(ArmMode.REFUSED, scheduler.arm(request(1)))
    }

    @Test
    fun `an alarm that is already held is set again at the limit, because it brings its own room`() {
        scheduler.arm(request(1))
        scheduler.arm(request(2))
        AlarmLimitShadow.limit = 2

        assertEquals(ArmMode.EXACT, scheduler.arm(request(1, at = 1_900_000_100_000L)))

        assertEquals("nothing else was disturbed", 2, held().size)
        assertTrue(held().any { it.triggerAtMs == 1_900_000_100_000L })
    }

    @Test
    fun `an alarm that is held and refused again all the same is reported, and nothing is thrown`() {
        scheduler.arm(request(1))
        AlarmLimitShadow.limit = 0

        assertEquals(ArmMode.REFUSED, scheduler.arm(request(1, at = 1_900_000_100_000L)))

        assertFalse("it gave up its place to try, so it is not said to be there", scheduler.isArmed(request(1)))
    }

    @Test
    fun `a new alarm at the limit leaves what is held alone`() {
        scheduler.arm(request(1))
        AlarmLimitShadow.limit = 1

        assertEquals(ArmMode.REFUSED, scheduler.arm(request(2)))

        assertEquals(1, held().size)
        assertTrue(scheduler.isArmed(request(1)))
        assertFalse(scheduler.isArmed(request(2)))
    }

    @Test
    fun `once there is room again it is taken`() {
        AlarmLimitShadow.limit = 0
        assertEquals(ArmMode.REFUSED, scheduler.arm(request(1)))
        AlarmLimitShadow.limit = Int.MAX_VALUE
        assertEquals(ArmMode.EXACT, scheduler.arm(request(1)))
        assertEquals(1, held().size)
    }
}
