package com.command.domain

import com.command.domain.schedule.Frequency
import com.command.domain.schedule.RRule
import com.command.domain.schedule.RRuleParse
import com.command.domain.schedule.RRuleParser
import com.command.domain.schedule.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class RRuleParserTest {

    private fun ok(text: String): RRule {
        val r = RRuleParser.parse(text)
        assertTrue("expected a valid rule for '$text' but got $r", r is RRuleParse.Ok)
        return (r as RRuleParse.Ok).rule
    }

    private fun error(text: String): String {
        val r = RRuleParser.parse(text)
        assertTrue("expected '$text' to be rejected but got $r", r is RRuleParse.Error)
        return (r as RRuleParse.Error).message
    }

    @Test
    fun `parses a plain daily rule`() {
        assertEquals(RRule(Frequency.DAILY), ok("FREQ=DAILY"))
        assertEquals(RRule(Frequency.DAILY), ok("RRULE:FREQ=DAILY"))
        assertEquals(RRule(Frequency.DAILY), ok("freq=daily"))
    }

    @Test
    fun `parses weekly rules with interval and days`() {
        val r = ok("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR")
        assertEquals(Frequency.WEEKLY, r.frequency)
        assertEquals(2, r.interval)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), r.byDay)
    }

    @Test
    fun `parses count and every form of until`() {
        assertEquals(5, ok("FREQ=DAILY;COUNT=5").count)
        assertEquals(Until.OnDate(LocalDate.of(2026, 12, 31)), ok("FREQ=DAILY;UNTIL=20261231").until)
        assertEquals(Until.AtLocal(LocalDateTime.of(2026, 12, 31, 23, 59, 59)), ok("FREQ=DAILY;UNTIL=20261231T235959").until)
        assertEquals(Until.AtInstant(Instant.parse("2026-12-31T23:59:59Z")), ok("FREQ=DAILY;UNTIL=20261231T235959Z").until)
    }

    @Test
    fun `format round trips`() {
        listOf(
            "FREQ=DAILY",
            "FREQ=DAILY;BYDAY=MO,TU,WE,TH",
            "FREQ=WEEKLY;INTERVAL=3;BYDAY=SA,SU;COUNT=10",
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=20270101",
            "FREQ=DAILY;UNTIL=20261231T235959Z",
        ).forEach { text -> assertEquals(text, RRuleParser.format(ok(text))) }
    }

    @Test
    fun `format puts days in week order whatever order they were given`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", RRuleParser.format(ok("FREQ=WEEKLY;BYDAY=FR,MO,WE")))
    }

    @Test
    fun `unsupported frequencies are rejected, not approximated`() {
        assertTrue(error("FREQ=MONTHLY").contains("MONTHLY"))
        error("FREQ=YEARLY")
        error("FREQ=HOURLY")
        error("INTERVAL=2")
    }

    @Test
    fun `unsupported or malformed parts are rejected`() {
        assertTrue(error("FREQ=WEEKLY;BYMONTH=1").contains("BYMONTH"))
        assertTrue(error("FREQ=WEEKLY;BYDAY=1MO").contains("BYDAY"))
        assertTrue(error("FREQ=WEEKLY;BYDAY=XX").contains("BYDAY"))
        error("FREQ=DAILY;INTERVAL=0")
        error("FREQ=DAILY;INTERVAL=abc")
        error("FREQ=DAILY;COUNT=0")
        error("FREQ=DAILY;UNTIL=tomorrow")
        error("FREQ=DAILY;FREQ=DAILY")
        error("FREQ=DAILY;WKST=SU")
        error("")
        error("FREQ")
    }

    @Test
    fun `until and count are mutually exclusive`() {
        assertTrue(error("FREQ=DAILY;COUNT=3;UNTIL=20261231").contains("exclusive"))
    }
}
