package com.command.domain.history

import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.occurrence.SkipReason
import com.command.domain.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Why an occurrence ended the way it did, in words the user would use. Only the user's own choices count against
 * a day; a calendar cancellation, a pause or a switched-off phone is never presented as a lapse.
 */
enum class HistoryReason(val countsAsStart: Boolean, val userAttributable: Boolean) {
    STARTED(true, true),
    DONE(true, true),
    SKIPPED_BY_USER(false, true),
    TIMED_OUT(false, true),
    SILENCED(false, true),
    UNCONFIRMED(false, true),
    BLOCKED(false, true),
    RECOVERY_OFFERED(false, true),
    CANCELLED_BY_CALENDAR(false, false),
    PAUSED(false, false),
    PHONE_WAS_OFF(false, false),
    RESCHEDULED(false, false),
    OPEN(false, false),
}

/** One finished-or-pending occurrence, reduced to what history needs. [zone] is the zone it was scheduled in. */
data class HistoryOccurrence(
    val scheduledAtUtcMs: Long,
    val zone: ZoneId,
    val state: OccurrenceState,
    val skipReason: SkipReason? = null,
    val missReason: MissReason? = null,
) {
    val reason: HistoryReason
        get() = when (state) {
            OccurrenceState.STARTED_SELF_REPORTED, OccurrenceState.WORKING_SELF_REPORTED -> HistoryReason.STARTED
            OccurrenceState.COMPLETED_SELF_REPORTED -> HistoryReason.DONE
            OccurrenceState.SKIPPED ->
                if (skipReason == null || skipReason == SkipReason.USER) HistoryReason.SKIPPED_BY_USER else HistoryReason.CANCELLED_BY_CALENDAR
            OccurrenceState.MISSED -> when (missReason) {
                MissReason.DOWNTIME -> HistoryReason.PHONE_WAS_OFF
                MissReason.PAUSED -> HistoryReason.PAUSED
                MissReason.TIMEOUT, null -> HistoryReason.TIMED_OUT
            }
            OccurrenceState.SAFETY_SILENCED -> HistoryReason.SILENCED
            OccurrenceState.UNCONFIRMED -> HistoryReason.UNCONFIRMED
            OccurrenceState.BLOCKED -> HistoryReason.BLOCKED
            OccurrenceState.DELAYED -> HistoryReason.RESCHEDULED
            OccurrenceState.RECOVERY_OFFERED -> HistoryReason.RECOVERY_OFFERED
            OccurrenceState.SCHEDULED, OccurrenceState.ARMED, OccurrenceState.FIRING,
            OccurrenceState.ACKNOWLEDGED, OccurrenceState.GUIDED_START -> HistoryReason.OPEN
        }
}

data class DayCounts(val date: LocalDate, val counts: Map<HistoryReason, Int>) {
    operator fun get(reason: HistoryReason): Int = counts[reason] ?: 0
    val hasStart: Boolean get() = counts.any { (reason, n) -> reason.countsAsStart && n > 0 }
    val hasUserAttributable: Boolean get() = counts.any { (reason, n) -> reason.userAttributable && n > 0 }
}

/**
 * [daysWithStart] over [eligibleDays]: how many of the days that held something the user was responsible for saw
 * a self-reported start. Days that held only calendar cancellations, pauses or phone-off time are left out.
 */
data class WindowSummary(val days: Int, val daysWithStart: Int, val eligibleDays: Int) {
    val startRate: Double? get() = if (eligibleDays == 0) null else daysWithStart.toDouble() / eligibleDays
}

/**
 * History is derived on demand from the occurrence rows, never stored as a counter. There are no run counters,
 * credits, badges or buy-backs, and days are the user's local days, not UTC days.
 */
class HistoryCalculator(private val clock: Clock) {

    /** Per-day counts for the last [days] local days ending today (in the clock's zone), oldest first. */
    fun dayCounts(occurrences: List<HistoryOccurrence>, days: Int): List<DayCounts> {
        require(days >= 1)
        val today = LocalDate.now(java.time.Clock.fixed(Instant.ofEpochMilli(clock.nowMs()), clock.zone()))
        val first = today.minusDays((days - 1).toLong())
        val byDate = HashMap<LocalDate, MutableMap<HistoryReason, Int>>()
        for (o in occurrences) {
            val date = Instant.ofEpochMilli(o.scheduledAtUtcMs).atZone(o.zone).toLocalDate()
            if (date.isBefore(first) || date.isAfter(today)) continue
            val counts = byDate.getOrPut(date) { HashMap() }
            counts[o.reason] = (counts[o.reason] ?: 0) + 1
        }
        return (0 until days).map { i ->
            val date = first.plusDays(i.toLong())
            DayCounts(date, byDate[date]?.toMap() ?: emptyMap())
        }
    }

    fun summary(occurrences: List<HistoryOccurrence>, days: Int): WindowSummary {
        val perDay = dayCounts(occurrences, days)
        return WindowSummary(
            days = days,
            daysWithStart = perDay.count { it.hasStart },
            eligibleDays = perDay.count { it.hasUserAttributable },
        )
    }
}
