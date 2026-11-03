package com.command.domain

import com.command.domain.model.ConsentCascade
import com.command.domain.model.ConsentKey
import com.command.domain.model.RestKind
import com.command.domain.schedule.RestWindow
import com.command.domain.schedule.RestWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RestWindowsTest {

    private val newYork = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    // 2026-11-02 is a Monday.
    private val sleep = RestWindow(DayOfWeek.MONDAY, LocalTime.of(22, 0), LocalTime.of(6, 0), RestKind.SLEEP)
    private val lunch = RestWindow(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(13, 0), RestKind.LEISURE)

    @Test
    fun `no windows means never protected`() {
        assertFalse(RestWindows.contains(emptyList(), at(newYork, 2026, 11, 2, 23), newYork))
    }

    @Test
    fun `a same-day window includes its start and excludes its end`() {
        assertTrue(RestWindows.contains(listOf(lunch), at(newYork, 2026, 11, 2, 12, 0), newYork))
        assertTrue(RestWindows.contains(listOf(lunch), at(newYork, 2026, 11, 2, 12, 59), newYork))
        assertFalse(RestWindows.contains(listOf(lunch), at(newYork, 2026, 11, 2, 13, 0), newYork))
        assertFalse(RestWindows.contains(listOf(lunch), at(newYork, 2026, 11, 2, 11, 59), newYork))
        assertFalse("a different weekday", RestWindows.contains(listOf(lunch), at(newYork, 2026, 11, 3, 12, 30), newYork))
    }

    @Test
    fun `an overnight window belongs to the day it starts on and runs into the next morning`() {
        assertTrue(RestWindows.contains(listOf(sleep), at(newYork, 2026, 11, 2, 23, 0), newYork))
        assertTrue("early Tuesday is still Monday night", RestWindows.contains(listOf(sleep), at(newYork, 2026, 11, 3, 5, 59), newYork))
        assertFalse(RestWindows.contains(listOf(sleep), at(newYork, 2026, 11, 3, 6, 0), newYork))
        assertFalse("before the window opens", RestWindows.contains(listOf(sleep), at(newYork, 2026, 11, 2, 21, 59), newYork))
        assertFalse("early Monday is Sunday night, which has no window", RestWindows.contains(listOf(sleep), at(newYork, 2026, 11, 2, 5, 0), newYork))
    }

    @Test
    fun `the instant is read in the zone given, not in UTC`() {
        val evening = RestWindow(DayOfWeek.MONDAY, LocalTime.of(20, 0), LocalTime.of(23, 0), RestKind.LEISURE)
        val instant = at(newYork, 2026, 11, 2, 21, 0) // 21:00 Monday in New York is 02:00 Tuesday UTC
        assertTrue(RestWindows.contains(listOf(evening), instant, newYork))
        assertFalse(RestWindows.contains(listOf(evening), instant, ZoneId.of("UTC")))
    }

    @Test
    fun `withdrawing a consent withdraws only what depended on it`() {
        assertEquals(listOf(ConsentKey.GEMINI_TEXT, ConsentKey.GEMINI_TEXT_TITLES), ConsentCascade.withDependents(ConsentKey.GEMINI_TEXT))
        assertEquals(listOf(ConsentKey.GEMINI_VOICE, ConsentKey.GEMINI_VOICE_TITLES), ConsentCascade.withDependents(ConsentKey.GEMINI_VOICE))
        assertEquals(listOf(ConsentKey.CALENDAR_READ, ConsentKey.CALENDAR_EVENT_TEXT), ConsentCascade.withDependents(ConsentKey.CALENDAR_READ))
        assertEquals(listOf(ConsentKey.MIC_VOICE, ConsentKey.MIC_VOICE_NETWORK), ConsentCascade.withDependents(ConsentKey.MIC_VOICE))
        assertEquals(listOf(ConsentKey.CLOUD_SYNC), ConsentCascade.withDependents(ConsentKey.CLOUD_SYNC))
        // The titles grants are refinements: dropping one never drops the wider grant.
        assertEquals(listOf(ConsentKey.GEMINI_TEXT_TITLES), ConsentCascade.withDependents(ConsentKey.GEMINI_TEXT_TITLES))
    }
}
