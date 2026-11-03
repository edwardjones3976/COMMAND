package com.command.domain.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** How a mission says when it starts. */
sealed interface StartRule {
    /** A single occurrence at an absolute instant. */
    data class OneOff(val atUtcMs: Long) : StartRule

    /** A wall-clock recurrence: [dtstart] fixes the first date and the time of day, in whatever zone is in force. */
    data class Recurring(val dtstart: LocalDateTime, val rule: RRule) : StartRule
}

/**
 * Expands a [StartRule] into the instants that fall in a window.
 *
 * Recurring rules are expanded in wall-clock terms and only then converted with the zone's rules, so a daily 08:00
 * stays 08:00 across a daylight-saving change. A time that does not exist (the spring-forward gap) moves later by the
 * length of the gap; a time that happens twice (the fall-back overlap) uses the first, earlier occurrence.
 */
class OccurrenceGenerator {

    /** Instants in [fromUtcMs, toUtcMs), ascending, at most [limit] of them. */
    fun generate(rule: StartRule, zone: ZoneId, fromUtcMs: Long, toUtcMs: Long, limit: Int = DEFAULT_LIMIT): List<Long> {
        if (toUtcMs <= fromUtcMs || limit <= 0) return emptyList()
        return when (rule) {
            is StartRule.OneOff -> if (rule.atUtcMs in fromUtcMs until toUtcMs) listOf(rule.atUtcMs) else emptyList()
            is StartRule.Recurring -> recurring(rule, zone, fromUtcMs, toUtcMs, limit)
        }
    }

    private fun recurring(rule: StartRule.Recurring, zone: ZoneId, from: Long, to: Long, limit: Int): List<Long> {
        val out = ArrayList<Long>()
        val time = rule.dtstart.toLocalTime()
        var produced = 0
        var iterations = 0
        for (date in candidateDates(rule)) {
            if (++iterations > MAX_ITERATIONS) break
            val local = LocalDateTime.of(date, time)
            if (isPast(rule.rule.until, local, zone)) break
            produced++
            val ms = ZonedDateTime.ofLocal(local, zone, null).toInstant().toEpochMilli()
            if (ms >= to) break
            if (ms >= from) {
                out += ms
                if (out.size >= limit) break
            }
            val count = rule.rule.count
            if (count != null && produced >= count) break
        }
        return out
    }

    private fun isPast(until: Until?, local: LocalDateTime, zone: ZoneId): Boolean = when (until) {
        null -> false
        is Until.OnDate -> local.toLocalDate().isAfter(until.date)
        is Until.AtLocal -> local.isAfter(until.dateTime)
        is Until.AtInstant -> ZonedDateTime.ofLocal(local, zone, null).toInstant().isAfter(until.instant)
    }

    /** Every date the rule selects, ascending, starting at the first. Lazily, so COUNT and the window can stop it. */
    private fun candidateDates(rule: StartRule.Recurring): Sequence<LocalDate> {
        val r = rule.rule
        val start = rule.dtstart.toLocalDate()
        return when (r.frequency) {
            Frequency.DAILY -> {
                // Every 7th (or 14th...) day only ever lands on one weekday, so a BYDAY that excludes it selects nothing.
                if (r.byDay.isNotEmpty() && r.interval % 7 == 0 && start.dayOfWeek !in r.byDay) {
                    emptySequence()
                } else {
                    generateSequence(start) { it.plusDays(r.interval.toLong()) }
                        .filter { r.byDay.isEmpty() || it.dayOfWeek in r.byDay }
                }
            }

            Frequency.WEEKLY -> {
                val days = (if (r.byDay.isEmpty()) setOf(start.dayOfWeek) else r.byDay).sortedBy { it.value }
                val firstMonday = start.minusDays((start.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
                generateSequence(0L) { it + r.interval }
                    .flatMap { week -> days.asSequence().map { firstMonday.plusWeeks(week).plusDays((it.value - 1).toLong()) } }
                    .filter { !it.isBefore(start) }
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000
        private const val MAX_ITERATIONS = 200_000
    }
}
