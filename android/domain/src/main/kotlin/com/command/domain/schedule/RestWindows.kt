package com.command.domain.schedule

import com.command.domain.model.RestKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

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
        val day = local.dayOfWeek
        val time = local.toLocalTime()
        val yesterday = day.minus(1)
        return windows.any { w ->
            if (w.overnight) {
                (w.weekday == day && !time.isBefore(w.start)) || (w.weekday == yesterday && time.isBefore(w.end))
            } else {
                w.weekday == day && !time.isBefore(w.start) && time.isBefore(w.end)
            }
        }
    }
}
