package com.command.domain.reliability

enum class AlarmOutcomeKind {
    /** It rang when it should have. */
    ON_TIME,

    /** It rang, but later than it should have. */
    LATE,

    /** It never rang: the phone was off or Android dropped it, and the missed alarm was recorded when the app next ran. */
    MISSED_WHILE_OFF,
}

/** How one alarm did, for the reliability page's list of recent alarms. */
data class AlarmOutcome(
    val occurrenceId: String,
    val title: String,
    val scheduledAtUtc: Long,
    val kind: AlarmOutcomeKind,
    /** How late it rang, or how late it was found missed; null for an alarm that was on time. */
    val lateSeconds: Long?,
    val isTest: Boolean,
)

object AlarmOutcomes {
    /**
     * A ring that starts within this long of its time counts as on time. A cold start of the ring service has been measured at
     * two to three seconds on an emulator, and an exact alarm is not delivered earlier than its second.
     */
    const val ON_TIME_MS = 15_000L

    /**
     * Reads one alarm's facts. [firedAtUtc] is when the ring began (null if it never did); [missedWhileOff] says it was recorded
     * as missed because the phone was off. Returns null for an alarm that neither rang nor was lost, which has nothing to report.
     */
    fun classify(
        occurrenceId: String,
        title: String,
        scheduledAtUtc: Long,
        firedAtUtc: Long?,
        missedWhileOff: Boolean,
        recordedAtUtc: Long?,
        isTest: Boolean,
    ): AlarmOutcome? {
        if (firedAtUtc != null) {
            val late = (firedAtUtc - scheduledAtUtc).coerceAtLeast(0)
            return if (late <= ON_TIME_MS) {
                AlarmOutcome(occurrenceId, title, scheduledAtUtc, AlarmOutcomeKind.ON_TIME, null, isTest)
            } else {
                AlarmOutcome(occurrenceId, title, scheduledAtUtc, AlarmOutcomeKind.LATE, late / 1000, isTest)
            }
        }
        if (missedWhileOff) {
            val late = recordedAtUtc?.minus(scheduledAtUtc)?.coerceAtLeast(0)?.div(1000)
            return AlarmOutcome(occurrenceId, title, scheduledAtUtc, AlarmOutcomeKind.MISSED_WHILE_OFF, late, isTest)
        }
        return null
    }
}
