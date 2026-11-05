package com.command.domain.reliability

/**
 * What a person can do about a precondition that is not as it should be. The platform decides all of these; the app only
 * sends the person to the right place, and the app module knows which system screen each one opens.
 */
enum class Fix {
    /** The runtime request on Android 13 and later; the notification settings page once the request can no longer be shown. */
    ASK_NOTIFICATIONS,
    OPEN_EXACT_ALARM_SETTINGS,
    OPEN_FULL_SCREEN_SETTINGS,
    OPEN_ALARM_CHANNEL_SETTINGS,
    OPEN_SOUND_SETTINGS,
    OPEN_BATTERY_SETTINGS,
    OPEN_APP_SETTINGS,
}

/** One precondition as the reliability page shows it. [fix] is null when there is nothing to do, or nothing the person can do. */
data class ReliabilityItem(val precondition: Precondition, val value: String, val severity: Severity, val fix: Fix?)

/** Everything the platform last said, with what stops an alarm first. */
class ReliabilityReport(val items: List<ReliabilityItem>) {
    val problems: List<ReliabilityItem> = items.filter { it.severity == Severity.PROBLEM }
    val warnings: List<ReliabilityItem> = items.filter { it.severity == Severity.WARNING }

    /** Nothing stops an alarm and nothing is likely to disturb one. Advice, such as battery optimisation, does not count. */
    val isHealthy: Boolean get() = problems.isEmpty() && warnings.isEmpty()

    val worst: Severity get() = items.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.OK

    fun of(precondition: Precondition): ReliabilityItem? = items.firstOrNull { it.precondition == precondition }
}

object Reliability {
    /** A snapshot read as a report: worst first, then in the order the preconditions are declared. */
    fun assess(snapshot: PreconditionSnapshot): ReliabilityReport {
        val items = Precondition.values().mapNotNull { p ->
            val value = snapshot.values[p] ?: return@mapNotNull null
            val severity = p.severity(value)
            ReliabilityItem(p, value, severity, if (severity == Severity.OK) null else p.fix)
        }
        return ReliabilityReport(items.sortedByDescending { it.severity.ordinal })
    }
}
