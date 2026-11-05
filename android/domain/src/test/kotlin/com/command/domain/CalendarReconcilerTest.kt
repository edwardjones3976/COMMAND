package com.command.domain

import com.command.domain.calendar.CalendarInstance
import com.command.domain.calendar.CalendarLink
import com.command.domain.calendar.CalendarPlan
import com.command.domain.calendar.CalendarReconciler
import com.command.domain.occurrence.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarReconcilerTest {
    private val now = 1_000_000_000L
    private val hour = 3_600_000L
    private val at = now + hour
    private val selected = setOf(7L)

    private fun instance(
        event: Long = 100, begin: Long = at, calendar: Long = 7, original: Long? = null, originalAt: Long? = null,
        cancelled: Boolean = false, deleted: Boolean = false, allDay: Boolean = false,
    ) = CalendarInstance(calendar, event, begin, begin + hour, "Meeting", allDay, "UTC", original, originalAt, cancelled, deleted)

    private fun link(
        occ: String = "o", event: Long = 100, begin: Long = at, calendar: Long = 7, original: Long? = null, originalAt: Long? = null,
        misses: Int = 0, unfired: Boolean = true,
    ) = CalendarLink(occ, calendar, event, begin, original, originalAt, "UTC", misses, unfired)

    private fun plan(links: List<CalendarLink>, instances: List<CalendarInstance>, gone: Set<Long> = emptySet()) =
        CalendarReconciler.reconcile(links, instances, selected, now, gone)

    @Test
    fun `an empty read of nothing plans nothing`() {
        assertEquals(CalendarPlan(), plan(emptyList(), emptyList()))
    }

    @Test
    fun `a new instance is created, and one already linked is not`() {
        assertEquals(listOf(instance()), plan(emptyList(), listOf(instance())).creates)
        val again = plan(listOf(link(misses = 1)), listOf(instance()))
        assertTrue(again.creates.isEmpty())
        assertEquals(listOf(CalendarPlan.LinkUpdate(link(misses = 0), seen = true)), again.linkUpdates)
    }

    @Test
    fun `the same identity twice in one read is one create`() {
        assertEquals(1, plan(emptyList(), listOf(instance(), instance())).creates.size)
    }

    @Test
    fun `an exception to a series is a move of the same instance, not a new one`() {
        val moved = instance(event = 555, begin = at + hour, original = 100, originalAt = at)
        val p = plan(listOf(link()), listOf(moved))
        assertEquals(listOf(CalendarPlan.Move("o", moved)), p.moves)
        assertTrue(p.creates.isEmpty() && p.skips.isEmpty())
    }

    @Test
    fun `an exception already moved is left where it is on the next sync`() {
        val moved = instance(event = 555, begin = at + hour, original = 100, originalAt = at)
        val p = plan(listOf(link(event = 555, begin = at + hour, original = 100, originalAt = at)), listOf(moved))
        assertTrue(p.moves.isEmpty() && p.creates.isEmpty() && p.skips.isEmpty())
    }

    @Test
    fun `an ordinary event edited to another time is a move and rings once`() {
        val edited = instance(begin = at + hour)
        val p = plan(listOf(link()), listOf(edited))
        assertEquals(listOf(CalendarPlan.Move("o", edited)), p.moves)
        assertTrue(p.creates.isEmpty() && p.linkUpdates.isEmpty())
    }

    @Test
    fun `a move to a time already past takes the alarm away`() {
        val p = plan(listOf(link()), listOf(instance(begin = now - 1)))
        assertEquals(listOf(CalendarPlan.Skip("o", SkipReason.CALENDAR_REMOVED)), p.skips)
        assertTrue(p.moves.isEmpty())
    }

    @Test
    fun `a cancelled instance is skipped as cancelled and never created`() {
        assertEquals(listOf(CalendarPlan.Skip("o", SkipReason.CALENDAR_CANCELLED)), plan(listOf(link()), listOf(instance(cancelled = true))).skips)
        assertTrue(plan(emptyList(), listOf(instance(cancelled = true))).creates.isEmpty())
    }

    @Test
    fun `a deleted instance or a parent that is gone is removed at once`() {
        val removed = listOf(CalendarPlan.Skip("o", SkipReason.CALENDAR_REMOVED))
        assertEquals(removed, plan(listOf(link()), listOf(instance(deleted = true))).skips)
        assertEquals(removed, plan(listOf(link()), emptyList(), gone = setOf(100)).skips)
        assertEquals(removed, plan(listOf(link(event = 555, original = 100, originalAt = at)), emptyList(), gone = setOf(100)).skips)
    }

    @Test
    fun `one sync without it is only a miss, the second removes it`() {
        val first = plan(listOf(link()), emptyList())
        assertTrue(first.skips.isEmpty())
        assertEquals(listOf(CalendarPlan.LinkUpdate(link(misses = 1), seen = false)), first.linkUpdates)
        assertEquals(listOf(CalendarPlan.Skip("o", SkipReason.CALENDAR_REMOVED)), plan(listOf(link(misses = 1)), emptyList()).skips)
    }

    @Test
    fun `being seen again clears the misses`() {
        assertEquals(0, plan(listOf(link(misses = 1)), listOf(instance())).linkUpdates.single().link.misses)
    }

    @Test
    fun `what lies outside the window is no miss`() {
        val beyond = now + CalendarReconciler.AFTER_NOW_MS
        val before = now - CalendarReconciler.BEFORE_NOW_MS - 1
        assertEquals(CalendarPlan(), plan(listOf(link(begin = beyond), link("p", begin = before)), emptyList()))
    }

    @Test
    fun `fired or ended occurrences are never touched, and their instance is not imported again`() {
        for (read in listOf(emptyList(), listOf(instance(cancelled = true)), listOf(instance(begin = at + hour, original = 100, originalAt = at)), listOf(instance()))) {
            assertEquals(CalendarPlan(), plan(listOf(link(unfired = false)), read))
        }
        assertEquals(CalendarPlan(), plan(listOf(link(unfired = false)), emptyList(), gone = setOf(100)))
    }

    @Test
    fun `a calendar no longer selected takes its alarms and imports nothing`() {
        val p = plan(listOf(link(calendar = 9)), listOf(instance(calendar = 9, event = 200)))
        assertEquals(listOf(CalendarPlan.Skip("o", SkipReason.CALENDAR_REMOVED)), p.skips)
        assertTrue(p.creates.isEmpty())
    }

    @Test
    fun `all-day events are skipped`() {
        assertTrue(plan(emptyList(), listOf(instance(allDay = true))).creates.isEmpty())
    }

    @Test
    fun `an instance that has already begun is not created`() {
        assertTrue(plan(emptyList(), listOf(instance(begin = now))).creates.isEmpty())
        assertEquals(1, plan(emptyList(), listOf(instance(begin = now + 1))).creates.size)
    }

    @Test
    fun `instances are UTC instants, so the night the clocks change needs no adjustment`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-11-01 01:30 happens twice in New York; the two instants are one hour apart and are two instances.
        val first = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ny).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ny).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val nowBefore = first - hour
        val read = listOf(instance(begin = first), instance(begin = second))
        val created = CalendarReconciler.reconcile(emptyList(), read, selected, nowBefore).creates
        assertEquals(listOf(first, second), created.map { it.beginMs })
        val links = listOf(link("a", begin = first), link("b", begin = second))
        val stable = CalendarReconciler.reconcile(links, read, selected, nowBefore)
        assertTrue(stable.moves.isEmpty() && stable.creates.isEmpty() && stable.skips.isEmpty())
    }

    @Test
    fun `an exception beats the plain row of the same instance`() {
        val moved = instance(event = 555, begin = at + hour, original = 100, originalAt = at)
        assertEquals(listOf(CalendarPlan.Move("o", moved)), plan(listOf(link()), listOf(instance(), moved)).moves)
    }

    @Test
    fun `a series shifted across the edge of the window still moves, with no second alarm`() {
        val shifted = instance(begin = at + hour)
        val p = plan(listOf(link("a"), link("b", begin = at + 24 * hour)), listOf(shifted))
        assertEquals(listOf(CalendarPlan.Move("a", shifted)), p.moves)
        assertTrue(p.creates.isEmpty())
        assertEquals(listOf("b"), p.linkUpdates.map { it.link.occurrenceId })
    }
}
