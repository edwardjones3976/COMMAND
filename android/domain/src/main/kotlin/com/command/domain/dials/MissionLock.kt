package com.command.domain.dials

/** How firmly a fixed commitment resists being abandoned. A configured speed bump, never a device lockdown. */
enum class LockPolicy { NONE, CONFIRM, REASON, RECOVERY_OFFER }

enum class Flexibility { RIGID, FLEXIBLE }

/** A short, closed list of reasons so a history row never holds free text. */
enum class SkipReasonCode { EMERGENCY, UNWELL, SCHEDULE_CONFLICT, NEED_REST, NOT_RELEVANT_TODAY, OTHER }

/** What the user did to get past the friction. */
sealed interface FrictionEvidence {
    data object None : FrictionEvidence
    data object Confirmed : FrictionEvidence
    data class Reason(val code: SkipReasonCode) : FrictionEvidence
    data object RecoveryDeclined : FrictionEvidence

    private val level: Int
        get() = when (this) {
            None -> 0
            Confirmed -> 1
            is Reason -> 2
            RecoveryDeclined -> 3
        }

    fun satisfies(required: LockPolicy): Boolean = level >= when (required) {
        LockPolicy.NONE -> 0
        LockPolicy.CONFIRM -> 1
        LockPolicy.REASON -> 2
        LockPolicy.RECOVERY_OFFER -> 3
    }
}

object MissionLock {
    /**
     * The friction that applies to delaying or skipping a mission. Flexible missions never have any, at every
     * rigidity setting. A rigid mission uses its own override, otherwise the schedule-rigidity dial:
     * 1-2 none, 3 confirm, 4 reason, 5 recovery offer.
     */
    fun effectivePolicy(flexibility: Flexibility, override: LockPolicy?, scheduleRigidity: Int): LockPolicy {
        if (flexibility == Flexibility.FLEXIBLE) return LockPolicy.NONE
        if (override != null) return override
        return when (scheduleRigidity.coerceIn(1, 5)) {
            1, 2 -> LockPolicy.NONE
            3 -> LockPolicy.CONFIRM
            4 -> LockPolicy.REASON
            else -> LockPolicy.RECOVERY_OFFER
        }
    }
}
