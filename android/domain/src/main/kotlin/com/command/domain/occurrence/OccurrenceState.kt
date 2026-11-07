package com.command.domain.occurrence

/**
 * The spec state model (section 5), verbatim:
 * SCHEDULED, ARMED, FIRING, then ACKNOWLEDGED / SAFETY_SILENCED / MISSED, then GUIDED_START, then
 * STARTED_SELF_REPORTED / BLOCKED / DELAYED / SKIPPED / UNCONFIRMED, then WORKING_SELF_REPORTED /
 * COMPLETED_SELF_REPORTED / RECOVERY_OFFERED.
 *
 * Alarm acknowledged is not task started, which is not progress, which is not completed. Only the user's own
 * report moves an occurrence past GUIDED_START.
 */
enum class OccurrenceState {
    SCHEDULED, ARMED, FIRING,
    ACKNOWLEDGED, SAFETY_SILENCED, MISSED,
    GUIDED_START,
    STARTED_SELF_REPORTED, BLOCKED, DELAYED, SKIPPED, UNCONFIRMED,
    WORKING_SELF_REPORTED, COMPLETED_SELF_REPORTED, RECOVERY_OFFERED;

    /** No command is legal from a finished state. */
    val isFinished: Boolean get() = this == SKIPPED || this == DELAYED || this == COMPLETED_SELF_REPORTED

    /** The user has told us they started (or finished). */
    val hasSelfReportedStart: Boolean
        get() = this == STARTED_SELF_REPORTED || this == WORKING_SELF_REPORTED || this == COMPLETED_SELF_REPORTED
}

enum class SkipReason { USER, CALENDAR_CANCELLED, CALENDAR_REMOVED }

enum class MissReason { TIMEOUT, DOWNTIME, PAUSED }

enum class RecoveryChoice { RESET, SMALLER }

/** How an alarm stands with the platform. REFUSED: the platform would not take it, because the app holds as many as it allows. */
enum class ArmMode { EXACT, INEXACT, NONE, REFUSED }

enum class WorkKind { PRERENDER, PREFETCH }

enum class ChildKind { DELAY }
