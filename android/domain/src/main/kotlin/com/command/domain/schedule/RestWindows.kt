package com.command.domain.schedule

import com.command.domain.model.RestKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A weekly protected period (sleep, leisure, driving). While one is active follow-up questions and recovery prompts
 * are suppressed. A window whose end is not after its start runs overnight into the next day.
 */
data class RestWindow(val weekday: DayOfWeek, val start: LocalTime, val end: LocalTime, val kind: RestKind) {
    val overnight: Boolean get() = !end.isAfter(start)
}

object RestWindows {
    /** Whether [atUtcMs], seen in [zone], falls inside any of the windows. */
    fun contains(windows: List<RestWindow>, atUtcMs: Long, zone: ZoneId): Boolean {
        if (windows.isEmpty()) return false
        val local = Instant.ofEpochMilli(atUtcMs).atZone(zone)
        return containsLocal(windows, local.dayOfWeek, local.toLocalTime())
    }

    /** Whether [time] on [day], on the wall clock, falls inside any of the windows. A window that starts one evening also covers the next morning. */
    fun containsLocal(windows: List<RestWindow>, day: DayOfWeek, time: LocalTime): Boolean {
        val yesterday = day.minus(1)
        return windows.any { w ->
            if (w.overnight) {
                (w.weekday == day && !time.isBefore(w.start)) || (w.weekday == yesterday && time.isBefore(w.end))
            } else {
                w.weekday == day && !time.isBefore(w.start) && time.isBefore(w.end)
            }
        }
    }

    /**
     * When the protected stretch containing [atUtcMs] ends, following windows that begin as one ends, or null if the
     * instant is not protected. Gives up after three weeks of back-to-back windows.
     */
    fun endOf(windows: List<RestWindow>, atUtcMs: Long, zone: ZoneId): Long? {
        if (!contains(windows, atUtcMs, zone)) return null
        var at = atUtcMs
        repeat(MAX_CHAIN) {
            val end = windows.mapNotNull { endContaining(it, at, zone) }.minOrNull() ?: return at
            at = end
            if (!contains(windows, at, zone)) return at
        }
        return at
    }

    private const val MAX_CHAIN = 21

    private fun endContaining(w: RestWindow, atUtcMs: Long, zone: ZoneId): Long? {
        val local = Instant.ofEpochMilli(atUtcMs).atZone(zone)
        val day = local.dayOfWeek
        val time = local.toLocalTime()
        val date = local.toLocalDate()
        val endDate = when {
            !w.overnight -> if (w.weekday == day && !time.isBefore(w.start) && time.isBefore(w.end)) date else return null
            w.weekday == day && !time.isBefore(w.start) -> date.plusDays(1)
            w.weekday == day.minus(1) && time.isBefore(w.end) -> date
            else -> return null
        }
        return ZonedDateTime.ofLocal(LocalDateTime.of(endDate, w.end), zone, null).toInstant().toEpochMilli()
    }
}
