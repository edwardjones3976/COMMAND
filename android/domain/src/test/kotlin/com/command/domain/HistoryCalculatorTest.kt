package com.command.domain

import com.command.domain.history.HistoryCalculator
import com.command.domain.history.HistoryOccurrence
import com.command.domain.history.HistoryReason
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryCalculatorTest {

    private val brisbane = ZoneId.of("Australia/Brisbane") // UTC+10, no daylight saving
    private val newYork = ZoneId.of("America/New_York")

    private fun clockAt(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        FakeClock(nowMs = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli(), zone = zone)

    private fun occ(
        zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int,
        state: OccurrenceState,
        skip: SkipReason? = null,
        miss: MissReason? = null,
    ) = HistoryOccurrence(
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli(), zone, state, skip, miss,
    )

    @Test
    fun `days are the users local days, not UTC days`() {
        // It is 00:10 on 3 Nov in Brisbane. UTC is still 2 Nov.
        val calc = HistoryCalculator(clockAt(brisbane, 2026, 11, 3, 0, 10))
        val lateYesterday = occ(brisbane, 2026, 11, 2, 23, 30, OccurrenceState.COMPLETED_SELF_REPORTED) // 13:30Z, 2 Nov
        val earlyToday = occ(brisbane, 2026, 11, 3, 1, 0, OccurrenceState.STARTED_SELF_REPORTED) // 15:00Z on 2 Nov
        val days = calc.dayCounts(listOf(lateYesterday, earlyToday), days = 2)
        assertEquals(listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3)), days.map { it.date })
        assertEquals(1, days[0][HistoryReason.DONE])
        assertEquals(0, days[0][HistoryReason.STARTED])
        assertEquals("01:00 local on the 3rd belongs to the 3rd even though UTC says the 2nd", 1, days[1][HistoryReason.STARTED])
    }

    @Test
    fun `an evening in a negative offset does not slip into tomorrow`() {
        // 21:30 in New York on 2 Nov is 02:30Z on 3 Nov.
        val calc = HistoryCalculator(clockAt(newYork, 2026, 11, 3, 8))
        val days = calc.dayCounts(listOf(occ(newYork, 2026, 11, 2, 21, 30, OccurrenceState.STARTED_SELF_REPORTED)), days = 2)
        assertEquals(1, days[0][HistoryReason.STARTED])
        assertEquals(0, days[1][HistoryReason.STARTED])
    }

    @Test
    fun `one rule decides what a window holds, so a list and the counts made from the same occurrences agree`() {
        val tokyo = ZoneId.of("Asia/Tokyo") // UTC+9
        val losAngeles = ZoneId.of("America/Los_Angeles")
        // It is 16:30 on 1 March in Los Angeles (00:30Z on the 2nd). A mission pinned to Tokyo rang at 08:00 there, which is already the 2nd.
        val calc = HistoryCalculator(clockAt(losAngeles, 2026, 3, 1, 16, 30))
        val ahead = occ(tokyo, 2026, 3, 2, 8, 0, OccurrenceState.STARTED_SELF_REPORTED)
        val here = occ(losAngeles, 2026, 3, 1, 9, 0, OccurrenceState.STARTED_SELF_REPORTED)

        assertEquals(LocalDate.of(2026, 3, 1), calc.currentDay())
        assertEquals(LocalDate.of(2026, 3, 2), calc.dateOf(ahead))
        assertTrue(!calc.isWithin(ahead, days = 7) && calc.isWithin(here, days = 7))
        val counted = calc.dayCounts(listOf(ahead, here), days = 7).sumOf { day -> day.counts.values.sum() }
        assertEquals("the day counts hold exactly what isWithin lets in", 1, counted)
        assertEquals(1, calc.summary(listOf(ahead, here), days = 7).daysWithStart)

        // Once the phone's own day reaches the 2nd, the same occurrence is inside, in the list and in the counts.
        val later = HistoryCalculator(clockAt(losAngeles, 2026, 3, 2, 0, 10))
        assertTrue(later.isWithin(ahead, days = 7))
        assertEquals(2, later.summary(listOf(ahead, here), days = 7).daysWithStart)
    }

    @Test
    fun `today can be read once and given to every window`() {
        val calc = HistoryCalculator(clockAt(newYork, 2026, 11, 3, 23, 59))
        val o = occ(newYork, 2026, 11, 3, 20, 0, OccurrenceState.STARTED_SELF_REPORTED)
        val given = calc.currentDay() // 3 Nov
        val tomorrow = given.plusDays(1)
        assertEquals(1, calc.summary(listOf(o), days = 2, today = given).daysWithStart)
        assertEquals("two days ending tomorrow still hold the evening of the 3rd", 1, calc.summary(listOf(o), days = 2, today = tomorrow).daysWithStart)
        assertTrue(!calc.isWithin(o, days = 1, today = tomorrow))
        assertEquals(0, calc.summary(listOf(o), days = 1, today = tomorrow).daysWithStart)
    }

    @Test
    fun `every state maps to the reason a person would give`() {
        fun reason(state: OccurrenceState, skip: SkipReason? = null, miss: MissReason? = null) =
            occ(newYork, 2026, 11, 2, 9, 0, state, skip, miss).reason

        assertEquals(HistoryReason.STARTED, reason(OccurrenceState.STARTED_SELF_REPORTED))
        assertEquals(HistoryReason.STARTED, reason(OccurrenceState.WORKING_SELF_REPORTED))
        assertEquals(HistoryReason.DONE, reason(OccurrenceState.COMPLETED_SELF_REPORTED))
        assertEquals(HistoryReason.SKIPPED_BY_USER, reason(OccurrenceState.SKIPPED, SkipReason.USER))
        assertEquals(HistoryReason.SKIPPED_BY_USER, reason(OccurrenceState.SKIPPED))
        assertEquals(HistoryReason.CANCELLED_BY_CALENDAR, reason(OccurrenceState.SKIPPED, SkipReason.CALENDAR_CANCELLED))
        assertEquals(HistoryReason.CANCELLED_BY_CALENDAR, reason(OccurrenceState.SKIPPED, SkipReason.CALENDAR_REMOVED))
        assertEquals(HistoryReason.TIMED_OUT, reason(OccurrenceState.MISSED, miss = MissReason.TIMEOUT))
        assertEquals(HistoryReason.PHONE_WAS_OFF, reason(OccurrenceState.MISSED, miss = MissReason.DOWNTIME))
        assertEquals(HistoryReason.PAUSED, reason(OccurrenceState.MISSED, miss = MissReason.PAUSED))
        assertEquals(HistoryReason.SILENCED, reason(OccurrenceState.SAFETY_SILENCED))
        assertEquals(HistoryReason.UNCONFIRMED, reason(OccurrenceState.UNCONFIRMED))
        assertEquals(HistoryReason.BLOCKED, reason(OccurrenceState.BLOCKED))
        assertEquals(HistoryReason.RESCHEDULED, reason(OccurrenceState.DELAYED))
        assertEquals(HistoryReason.RECOVERY_OFFERED, reason(OccurrenceState.RECOVERY_OFFERED))
        for (open in listOf(OccurrenceState.SCHEDULED, OccurrenceState.ARMED, OccurrenceState.FIRING, OccurrenceState.ACKNOWLEDGED, OccurrenceState.GUIDED_START)) {
            assertEquals(HistoryReason.OPEN, reason(open))
        }
    }

    @Test
    fun `every state has a reason and only starts and completions count as starts`() {
        for (state in OccurrenceState.values()) {
            val r = occ(newYork, 2026, 11, 2, 9, 0, state).reason
            assertEquals(state in setOf(OccurrenceState.STARTED_SELF_REPORTED, OccurrenceState.WORKING_SELF_REPORTED, OccurrenceState.COMPLETED_SELF_REPORTED), r.countsAsStart)
        }
    }

    @Test
    fun `days with a start leave out days the user was not responsible for`() {
        val calc = HistoryCalculator(clockAt(newYork, 2026, 11, 7, 12))
        val occurrences = listOf(
            occ(newYork, 2026, 11, 1, 8, 0, OccurrenceState.COMPLETED_SELF_REPORTED), // start
            occ(newYork, 2026, 11, 2, 8, 0, OccurrenceState.SKIPPED, skip = SkipReason.USER), // user's choice: eligible, no start
            occ(newYork, 2026, 11, 3, 8, 0, OccurrenceState.SKIPPED, skip = SkipReason.CALENDAR_CANCELLED), // not the user's doing
            occ(newYork, 2026, 11, 4, 8, 0, OccurrenceState.MISSED, miss = MissReason.DOWNTIME), // phone was off
            occ(newYork, 2026, 11, 5, 8, 0, OccurrenceState.MISSED, miss = MissReason.PAUSED), // paused
            occ(newYork, 2026, 11, 6, 8, 0, OccurrenceState.STARTED_SELF_REPORTED), // start
            occ(newYork, 2026, 11, 6, 20, 0, OccurrenceState.MISSED, miss = MissReason.TIMEOUT), // same day, timed out
        )
        val s = calc.summary(occurrences, days = 7)
        assertEquals(7, s.days)
        assertEquals("1 Nov and 6 Nov", 2, s.daysWithStart)
        assertEquals("1, 2 and 6 Nov were the user's", 3, s.eligibleDays)
        assertEquals(2.0 / 3.0, s.startRate!!, 1e-9)
    }

    @Test
    fun `an empty window has no rate rather than a made-up zero`() {
        val calc = HistoryCalculator(clockAt(newYork, 2026, 11, 7, 12))
        val s = calc.summary(emptyList(), days = 30)
        assertEquals(0, s.daysWithStart)
        assertEquals(0, s.eligibleDays)
        assertNull(s.startRate)
    }

    @Test
    fun `occurrences outside the window are ignored`() {
        val calc = HistoryCalculator(clockAt(newYork, 2026, 11, 7, 12))
        val old = occ(newYork, 2026, 10, 1, 8, 0, OccurrenceState.COMPLETED_SELF_REPORTED)
        val future = occ(newYork, 2026, 11, 20, 8, 0, OccurrenceState.SCHEDULED)
        val days = calc.dayCounts(listOf(old, future), days = 7)
        assertTrue(days.all { it.counts.isEmpty() })
    }

    @Test
    fun `a delayed occurrence is rescheduled, not a lapse`() {
        val calc = HistoryCalculator(clockAt(newYork, 2026, 11, 7, 12))
        val s = calc.summary(listOf(occ(newYork, 2026, 11, 7, 8, 0, OccurrenceState.DELAYED)), days = 1)
        assertEquals(0, s.eligibleDays)
    }

    @Test
    fun `dayCounts returns oldest first, one entry per day`() {
        val calc = HistoryCalculator(clockAt(newYork, 2026, 11, 7, 12))
        val days = calc.dayCounts(emptyList(), days = 30)
        assertEquals(30, days.size)
        assertEquals(LocalDate.of(2026, 10, 9), days.first().date)
        assertEquals(LocalDate.of(2026, 11, 7), days.last().date)
    }
}
