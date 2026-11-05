package com.command.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanTest {

    private val start = 1_000_000L
    private val total = 120_000L

    /** A two-minute ring that began when the monotonic clock read 5 000, so its deadline is 125 000. */
    private fun plan(endElapsed: Long? = 125_000) = SessionPlan(start, start + total, listOf(0L until total), endElapsed)

    @Test
    fun `within the same boot the monotonic deadline decides, however the wall clock was set`() {
        val wallSetBackAnHour = start + 100_000 - 3_600_000
        assertEquals(100_000, plan().elapsedMs(wallSetBackAnHour, nowElapsed = 105_000))
        assertFalse(plan().isOver(wallSetBackAnHour, nowElapsed = 105_000))

        val wallSetForwardAnHour = start + 10_000 + 3_600_000
        assertEquals(10_000, plan().elapsedMs(wallSetForwardAnHour, nowElapsed = 15_000))
        assertFalse(plan().isOver(wallSetForwardAnHour, nowElapsed = 15_000))
    }

    @Test
    fun `the plan is over exactly at its monotonic deadline`() {
        assertFalse(plan().isOver(0, nowElapsed = 124_999))
        assertTrue(plan().isOver(0, nowElapsed = 125_000))
        assertTrue(plan().isOver(0, nowElapsed = 400_000))
    }

    @Test
    fun `after a reboot the monotonic reference means nothing and the wall clock is used`() {
        val fromAnEarlierBoot = plan(endElapsed = 9_000_000)
        assertEquals(30_000, fromAnEarlierBoot.elapsedMs(start + 30_000, nowElapsed = 40_000))
        assertFalse(fromAnEarlierBoot.isOver(start + 30_000, nowElapsed = 40_000))
        assertTrue(fromAnEarlierBoot.isOver(start + total + 1, nowElapsed = 40_000))
    }

    @Test
    fun `with no monotonic deadline stored the wall clock is used`() {
        val old = plan(endElapsed = null)
        assertEquals(45_000, old.elapsedMs(start + 45_000, nowElapsed = 0))
        assertTrue(old.isOver(start + total, nowElapsed = 0))
    }

    @Test
    fun `time never runs back past the start`() {
        assertEquals(0, plan(endElapsed = null).elapsedMs(start - 10_000, nowElapsed = 0))
        assertEquals(0, plan().elapsedMs(start, nowElapsed = 5_000))
    }

    @Test
    fun `the total is the length of the plan`() {
        assertEquals(total, plan().totalMs)
    }
}
