package com.command.domain.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

enum class Frequency { DAILY, WEEKLY }

/** When a recurrence stops. All forms are inclusive. */
sealed interface Until {
    /** The whole of this calendar day in the mission's time zone. */
    data class OnDate(val date: LocalDate) : Until

    /** A wall-clock time in the mission's time zone (RFC 5545 "floating" UNTIL). */
    data class AtLocal(val dateTime: LocalDateTime) : Until

    /** An absolute instant (RFC 5545 UNTIL with a trailing Z). */
    data class AtInstant(val instant: Instant) : Until
}

/**
 * The supported subset of RFC 5545 recurrence rules: FREQ=DAILY|WEEKLY, INTERVAL, BYDAY, UNTIL or COUNT.
 * The mission editor only ever produces this subset; calendar events arrive already expanded by the provider.
 */
data class RRule(
    val frequency: Frequency,
    val interval: Int = 1,
    val byDay: Set<DayOfWeek> = emptySet(),
    val until: Until? = null,
    val count: Int? = null,
) {
    init {
        require(interval >= 1) { "INTERVAL must be at least 1" }
        require(count == null || count >= 1) { "COUNT must be at least 1" }
        require(until == null || count == null) { "UNTIL and COUNT are mutually exclusive" }
    }
}

sealed interface RRuleParse {
    data class Ok(val rule: RRule) : RRuleParse
    data class Error(val message: String) : RRuleParse
}

object RRuleParser {
    private val DAY_CODES = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** Unsupported or malformed rules are rejected, never silently approximated. */
    fun parse(text: String): RRuleParse {
        val body = text.trim().removePrefix("RRULE:").removePrefix("rrule:")
        if (body.isBlank()) return RRuleParse.Error("empty rule")
        val parts = LinkedHashMap<String, String>()
        for (piece in body.split(';')) {
            if (piece.isBlank()) continue
            val eq = piece.indexOf('=')
            if (eq <= 0 || eq == piece.length - 1) return RRuleParse.Error("malformed rule part: $piece")
            val key = piece.substring(0, eq).trim().uppercase()
            if (parts.put(key, piece.substring(eq + 1).trim()) != null) return RRuleParse.Error("duplicate rule part: $key")
        }
        for (key in parts.keys) {
            if (key !in SUPPORTED_KEYS) return RRuleParse.Error("unsupported rule part: $key")
        }
        val frequency = when (parts["FREQ"]?.uppercase()) {
            "DAILY" -> Frequency.DAILY
            "WEEKLY" -> Frequency.WEEKLY
            null -> return RRuleParse.Error("FREQ is required")
            else -> return RRuleParse.Error("unsupported FREQ: ${parts["FREQ"]}")
        }
        val interval = parts["INTERVAL"]?.let { it.toIntOrNull() } ?: if (parts.containsKey("INTERVAL")) return RRuleParse.Error("INTERVAL must be a number") else 1
        if (interval < 1) return RRuleParse.Error("INTERVAL must be at least 1")
        val count = if (parts.containsKey("COUNT")) {
            val c = parts["COUNT"]?.toIntOrNull() ?: return RRuleParse.Error("COUNT must be a number")
            if (c < 1) return RRuleParse.Error("COUNT must be at least 1")
            c
        } else null
        val until = if (parts.containsKey("UNTIL")) (parseUntil(parts.getValue("UNTIL")) ?: return RRuleParse.Error("bad UNTIL: ${parts["UNTIL"]}")) else null
        if (count != null && until != null) return RRuleParse.Error("UNTIL and COUNT are mutually exclusive")
        val wkst = parts["WKST"]?.uppercase()
        if (wkst != null && wkst != "MO") return RRuleParse.Error("only WKST=MO is supported")
        val days = LinkedHashSet<DayOfWeek>()
        if (parts.containsKey("BYDAY")) {
            for (code in parts.getValue("BYDAY").split(',')) {
                val day = DAY_CODES[code.trim().uppercase()] ?: return RRuleParse.Error("unsupported BYDAY value: $code")
                days.add(day)
            }
        }
        return RRuleParse.Ok(RRule(frequency, interval, days, until, count))
    }

    /** A canonical text form; `parse(format(r))` yields `r`. */
    fun format(rule: RRule): String {
        val out = ArrayList<String>()
        out += "FREQ=${rule.frequency.name}"
        if (rule.interval != 1) out += "INTERVAL=${rule.interval}"
        if (rule.byDay.isNotEmpty()) {
            val codes = rule.byDay.sortedBy { it.value }.map { day -> DAY_CODES.entries.first { it.value == day }.key }
            out += "BYDAY=${codes.joinToString(",")}"
        }
        rule.count?.let { out += "COUNT=$it" }
        when (val u = rule.until) {
            is Until.OnDate -> out += "UNTIL=${u.date.format(DATE)}"
            is Until.AtLocal -> out += "UNTIL=${u.dateTime.format(DATE_TIME)}"
            is Until.AtInstant -> out += "UNTIL=${u.instant.atZone(java.time.ZoneOffset.UTC).toLocalDateTime().format(DATE_TIME)}Z"
            null -> Unit
        }
        return out.joinToString(";")
    }

    private val SUPPORTED_KEYS = setOf("FREQ", "INTERVAL", "BYDAY", "UNTIL", "COUNT", "WKST")

    private fun parseUntil(text: String): Until? = try {
        when {
            text.length == 8 -> Until.OnDate(LocalDate.parse(text, DATE))
            text.length == 15 -> Until.AtLocal(LocalDateTime.parse(text, DATE_TIME))
            text.length == 16 && (text.endsWith("Z") || text.endsWith("z")) ->
                Until.AtInstant(LocalDateTime.parse(text.substring(0, 15), DATE_TIME).toInstant(java.time.ZoneOffset.UTC))
            else -> null
        }
    } catch (e: DateTimeParseException) {
        null
    }
}
