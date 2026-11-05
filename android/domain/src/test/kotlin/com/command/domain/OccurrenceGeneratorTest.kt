package com.command.domain

import com.command.domain.schedule.Frequency
import com.command.domain.schedule.OccurrenceGenerator
import com.command.domain.schedule.RRule
import com.command.domain.schedule.StartRule
import com.command.domain.schedule.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class OccurrenceGeneratorTest {

    private val generator = OccurrenceGenerator()
    private val newYork = ZoneId.of("America/New_York")
    private val chicago = ZoneId.of("America/Chicago")

    private fun ms(text: String): Long = Instant.parse(text).toEpochMilli()

    private fun local(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun daily(h: Int, min: Int = 0, from: LocalDate = LocalDate.of(2026, 1, 1), rule: RRule = RRule(Frequency.DAILY)) =
        StartRule.Recurring(LocalDateTime.of(from, java.time.LocalTime.of(h, min)), rule)

    private fun times(rule: StartRule, zone: ZoneId, from: String, to: String, limit: Int = 1000): List<String> =
        generator.generate(rule, zone, ms(from), ms(to), limit).map { Instant.ofEpochMilli(it).toString() }

    @Test
    fun `a one-off is returned only inside the window`() {
        val at = ms("2026-11-02T12:00:00Z")
        val rule = StartRule.OneOff(at)
        assertEquals(listOf(at), generator.generate(rule, newYork, at - 1, at + 1))
        assertEquals(listOf(at), generator.generate(rule, newYork, at, at + 1))
        assertTrue(generator.generate(rule, newYork, at + 1, at + 100).isEmpty())
        assertTrue("the window end is exclusive", generator.generate(rule, newYork, at - 100, at).isEmpty())
    }

    @Test
    fun `a daily 8am stays 8am local across spring forward in New York`() {
        val r = times(daily(8), newYork, "2026-03-07T00:00:00Z", "2026-03-10T00:00:00Z")
        assertEquals(
            listOf(
                "2026-03-07T13:00:00Z", // 08:00 EST
                "2026-03-08T12:00:00Z", // 08:00 EDT after clocks moved at 02:00 on the 8th
                "2026-03-09T12:00:00Z",
            ),
            r,
        )
    }

    @Test
    fun `a daily 8am stays 8am local across fall back in New York`() {
        val r = times(daily(8), newYork, "2026-10-31T00:00:00Z", "2026-11-03T00:00:00Z")
        assertEquals(
            listOf("2026-10-31T12:00:00Z", "2026-11-01T13:00:00Z", "2026-11-02T13:00:00Z"), // EDT, then EST from 1 Nov
            r,
        )
    }

    @Test
    fun `a time that does not exist moves later by the length of the gap`() {
        // 02:30 does not exist in New York on 2026-03-08; it becomes 03:30 EDT.
        val r = times(daily(2, 30), newYork, "2026-03-08T00:00:00Z", "2026-03-09T00:00:00Z")
        assertEquals(listOf("2026-03-08T07:30:00Z"), r)
    }

    @Test
    fun `a time that happens twice uses the first one`() {
        // 01:30 happens twice in New York on 2026-11-01; the first is EDT (05:30Z), the repeat is EST (06:30Z).
        val r = times(daily(1, 30), newYork, "2026-11-01T00:00:00Z", "2026-11-03T00:00:00Z")
        assertEquals(listOf("2026-11-01T05:30:00Z", "2026-11-02T06:30:00Z"), r)
    }

    @Test
    fun `the same wall time lands on different instants when the zone changes`() {
        val rule = daily(8)
        val ny = generator.generate(rule, newYork, ms("2026-06-01T00:00:00Z"), ms("2026-06-02T00:00:00Z"))
        val chi = generator.generate(rule, chicago, ms("2026-06-01T00:00:00Z"), ms("2026-06-02T00:00:00Z"))
        assertEquals(listOf(local(newYork, 2026, 6, 1, 8)), ny)
        assertEquals(listOf(local(chicago, 2026, 6, 1, 8)), chi)
        assertEquals(3_600_000L, chi.single() - ny.single())
    }

    @Test
    fun `weekly with days and interval skips the off weeks`() {
        // Monday 2026-11-02 is week 0. Every second week: Mon/Wed/Fri of weeks 0, 2, 4.
        val start = StartRule.Recurring(
            LocalDateTime.of(2026, 11, 2, 7, 0),
            RRule(Frequency.WEEKLY, interval = 2, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
        )
        val dates = generator.generate(start, ZoneId.of("UTC"), ms("2026-11-01T00:00:00Z"), ms("2026-12-01T00:00:00Z"))
            .map { Instant.ofEpochMilli(it).toString().substring(0, 10) }
        assertEquals(
            listOf("2026-11-02", "2026-11-04", "2026-11-06", "2026-11-16", "2026-11-18", "2026-11-20", "2026-11-30"),
            dates,
        )
    }

    @Test
    fun `weekly without BYDAY repeats on the start weekday and never before the start date`() {
        val start = StartRule.Recurring(LocalDateTime.of(2026, 11, 4, 9, 0), RRule(Frequency.WEEKLY)) // a Wednesday
        val dates = generator.generate(start, ZoneId.of("UTC"), ms("2026-10-01T00:00:00Z"), ms("2026-11-30T00:00:00Z"))
            .map { Instant.ofEpochMilli(it).toString().substring(0, 10) }
        assertEquals(listOf("2026-11-04", "2026-11-11", "2026-11-18", "2026-11-25"), dates)
    }

    @Test
    fun `daily with BYDAY keeps only those weekdays`() {
        val weekdays = RRule(Frequency.DAILY, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY))
        val dates = generator.generate(daily(6, rule = weekdays, from = LocalDate.of(2026, 11, 2)), ZoneId.of("UTC"), ms("2026-11-02T00:00:00Z"), ms("2026-11-10T00:00:00Z"))
            .map { Instant.ofEpochMilli(it).toString().substring(0, 10) }
        assertEquals(listOf("2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05", "2026-11-09"), dates)
    }

    @Test
    fun `a rule that can never match terminates with nothing`() {
        // Every 7th day from a Monday only ever lands on Mondays, so BYDAY=TU selects nothing.
        val never = RRule(Frequency.DAILY, interval = 7, byDay = setOf(DayOfWeek.TUESDAY))
        assertTrue(generator.generate(daily(6, rule = never, from = LocalDate.of(2026, 11, 2)), ZoneId.of("UTC"), 0, ms("2030-01-01T00:00:00Z")).isEmpty())
    }

    @Test
    fun `count is measured from the first date, not from the window`() {
        val rule = daily(8, rule = RRule(Frequency.DAILY, count = 3), from = LocalDate.of(2026, 11, 1))
        val all = times(rule, ZoneId.of("UTC"), "2026-10-01T00:00:00Z", "2026-12-01T00:00:00Z")
        assertEquals(listOf("2026-11-01T08:00:00Z", "2026-11-02T08:00:00Z", "2026-11-03T08:00:00Z"), all)
        // A window after the run has ended finds nothing, even though it holds days after the start.
        assertTrue(times(rule, ZoneId.of("UTC"), "2026-11-04T00:00:00Z", "2026-12-01T00:00:00Z").isEmpty())
        // A window inside the run sees only what really remains.
        assertEquals(listOf("2026-11-03T08:00:00Z"), times(rule, ZoneId.of("UTC"), "2026-11-03T00:00:00Z", "2026-12-01T00:00:00Z"))
    }

    @Test
    fun `until is inclusive in each of its forms`() {
        val from = LocalDate.of(2026, 11, 1)
        val byDate = daily(8, rule = RRule(Frequency.DAILY, until = Until.OnDate(LocalDate.of(2026, 11, 3))), from = from)
        assertEquals(3, generator.generate(byDate, ZoneId.of("UTC"), 0, ms("2027-01-01T00:00:00Z")).size)

        val byLocal = daily(8, rule = RRule(Frequency.DAILY, until = Until.AtLocal(LocalDateTime.of(2026, 11, 3, 8, 0))), from = from)
        assertEquals(3, generator.generate(byLocal, ZoneId.of("UTC"), 0, ms("2027-01-01T00:00:00Z")).size)
        val justBefore = daily(8, rule = RRule(Frequency.DAILY, until = Until.AtLocal(LocalDateTime.of(2026, 11, 3, 7, 59))), from = from)
        assertEquals(2, generator.generate(justBefore, ZoneId.of("UTC"), 0, ms("2027-01-01T00:00:00Z")).size)

        val byInstant = daily(8, rule = RRule(Frequency.DAILY, until = Until.AtInstant(Instant.parse("2026-11-03T08:00:00Z"))), from = from)
        assertEquals(3, generator.generate(byInstant, ZoneId.of("UTC"), 0, ms("2027-01-01T00:00:00Z")).size)
    }

    @Test
    fun `the window is half open and the limit is honoured`() {
        val rule = daily(8, from = LocalDate.of(2026, 11, 1))
        val utc = ZoneId.of("UTC")
        assertEquals(1, generator.generate(rule, utc, ms("2026-11-02T08:00:00Z"), ms("2026-11-03T08:00:00Z")).size)
        assertEquals(3, generator.generate(rule, utc, ms("2026-11-01T00:00:00Z"), ms("2027-11-01T00:00:00Z"), limit = 3).size)
    }

    @Test
    fun `a start years in the past still expands quickly`() {
        val rule = daily(8, from = LocalDate.of(2020, 1, 1))
        val out = generator.generate(rule, newYork, ms("2026-11-01T00:00:00Z"), ms("2026-11-08T00:00:00Z"))
        assertEquals(7, out.size)
    }

    @Test
    fun `results are ascending`() {
        val rule = StartRule.Recurring(
            LocalDateTime.of(2026, 1, 5, 22, 15),
            RRule(Frequency.WEEKLY, byDay = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
        )
        val out = generator.generate(rule, newYork, ms("2026-01-01T00:00:00Z"), ms("2026-12-31T00:00:00Z"))
        assertEquals(out.sorted(), out)
        assertTrue(out.size > 100)
    }
}
