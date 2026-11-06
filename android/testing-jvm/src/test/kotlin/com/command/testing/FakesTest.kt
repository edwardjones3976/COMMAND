package com.command.testing

import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.occurrence.ArmMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakesTest {
    @Test
    fun `the clock moves wall time and monotonic time together`() {
        val clock = FakeClock(nowMs = 1_000, elapsedMs = 10)
        clock.advance(500)
        assertEquals(1_500, clock.nowMs())
        assertEquals(510, clock.elapsedRealtimeMs())
    }

    @Test
    fun `arming the same identity replaces it, like a platform alarm, and cancel removes it`() {
        val scheduler = FakeAlarmScheduler()
        scheduler.arm(AlarmRequest("occ", 1, AlarmKind.FIRE, atUtcMs = 100))
        scheduler.arm(AlarmRequest("occ", 1, AlarmKind.FIRE, atUtcMs = 200))
        assertEquals(1, scheduler.armed.size)
        assertEquals(200, scheduler.armed.values.single().atUtcMs)
        scheduler.cancel(AlarmRequest("occ", 1, AlarmKind.FIRE, atUtcMs = 0))
        assertTrue(scheduler.armed.isEmpty())
    }

    @Test
    fun `when exact alarms are not allowed the result is inexact`() {
        val scheduler = FakeAlarmScheduler(exactAllowed = false)
        assertEquals(ArmMode.INEXACT, scheduler.arm(AlarmRequest("occ", 1, AlarmKind.FIRE, 0)))
    }

    @Test
    fun `an alarm refused when set again is no longer held, as with the platform`() {
        val scheduler = FakeAlarmScheduler()
        val request = com.command.domain.alarm.AlarmRequest("occ", 1, com.command.domain.alarm.AlarmKind.FIRE, 1_000)
        scheduler.arm(request)
        scheduler.refusing = true

        assertEquals(com.command.domain.occurrence.ArmMode.REFUSED, scheduler.arm(request.copy(atUtcMs = 2_000)))

        assertEquals(false, scheduler.isArmed(request))
    }
}
