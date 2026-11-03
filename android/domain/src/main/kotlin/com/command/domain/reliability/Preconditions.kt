package com.command.domain.reliability

import com.command.domain.model.ConsentKey

/** How much a person should worry about a precondition's current value. */
enum class Severity { OK, INFO, WARNING, PROBLEM }

/** The short tokens a precondition can have as a value. They are stored in the audit log, so they only ever name a state. */
object PreconditionValue {
    const val GRANTED = "granted"
    const val DENIED = "denied"
    const val AUDIBLE = "audible"
    const val MUTED = "muted"
    const val EXEMPT = "exempt"
    const val OPTIMIZED = "optimized"
    const val YES = "yes"
    const val NO = "no"
    const val IMPORTANCE_HIGH = "HIGH"
}

/**
 * Things the platform decides that decide whether an alarm rings the way the person expects: the permissions it needs, the
 * volume it plays at, and the power management that can stop it from running at all. The app never changes these; it reads
 * them, tells the truth about them, and sends the person to the right system screen.
 */
enum class Precondition(val consent: ConsentKey?) {
    /** Without it an alarm can arrive minutes late. */
    EXACT_ALARMS(ConsentKey.EXACT_ALARM),

    /** Without it there is no lock-screen ring and no note when an alarm is missed; the sound still plays. */
    NOTIFICATIONS(ConsentKey.NOTIFICATIONS),

    /** Without it the ring cannot take over a locked screen. */
    FULL_SCREEN_INTENT(ConsentKey.FULL_SCREEN),

    /** The importance of the ring's notification channel; below high it cannot take over the screen. */
    ALARM_CHANNEL(null),

    /** The alarm stream is muted or at zero: an alarm that makes no sound is the failure this app exists to prevent. */
    ALARM_VOLUME(null),
    BATTERY_OPTIMIZATION(null),
    BACKGROUND_RESTRICTED(null),

    /** Android's standby bucket for the app; a rarely used app is given fewer chances to run. */
    STANDBY_BUCKET(null);

    /** Granted or denied by the person (or by the system on their behalf); a change is worth a PERMISSION_CHANGED event. */
    val isPermission: Boolean get() = consent != null

    fun severity(value: String): Severity = when (this) {
        EXACT_ALARMS, NOTIFICATIONS, FULL_SCREEN_INTENT -> if (value == PreconditionValue.GRANTED) Severity.OK else Severity.PROBLEM
        ALARM_CHANNEL -> if (value == PreconditionValue.IMPORTANCE_HIGH) Severity.OK else Severity.PROBLEM
        ALARM_VOLUME -> if (value == PreconditionValue.AUDIBLE) Severity.OK else Severity.PROBLEM
        BATTERY_OPTIMIZATION -> if (value == PreconditionValue.EXEMPT) Severity.OK else Severity.INFO
        BACKGROUND_RESTRICTED -> if (value == PreconditionValue.NO) Severity.OK else Severity.WARNING
        STANDBY_BUCKET -> if (value == "RARE" || value == "RESTRICTED") Severity.WARNING else Severity.OK
    }
}

data class PreconditionChange(val precondition: Precondition, val from: String?, val to: String)

/** What the platform said about every precondition at one moment. */
class PreconditionSnapshot(val values: Map<Precondition, String>) {

    /** Every precondition whose value differs from [previous] (all of them when there is no previous snapshot). */
    fun changesFrom(previous: PreconditionSnapshot?): List<PreconditionChange> =
        Precondition.values().mapNotNull { p ->
            val now = values[p] ?: return@mapNotNull null
            val before = previous?.values?.get(p)
            if (before == now) null else PreconditionChange(p, before, now)
        }

    /** The names and tokens only, for storage. */
    fun toTokens(): Map<String, String> = values.mapKeys { it.key.name }

    companion object {
        /** Reads stored tokens back; a name this version does not know is ignored. */
        fun fromTokens(tokens: Map<String, String>): PreconditionSnapshot {
            val known = HashMap<Precondition, String>()
            for ((name, value) in tokens) Precondition.values().firstOrNull { it.name == name }?.let { known[it] = value }
            return PreconditionSnapshot(known)
        }
    }
}
