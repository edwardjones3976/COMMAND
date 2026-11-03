package com.command.domain.alarm

import com.command.domain.occurrence.ArmMode
import com.command.domain.occurrence.WorkKind

enum class AlarmKind { FIRE, CYCLE_END, CHECK_IN }

/**
 * One alarm the platform must deliver. [identity] is the string every platform alarm for this request is keyed
 * by (on Android, the data URI of its PendingIntent), so arming the same request twice replaces it and cancelling
 * finds it again. [requestCode] is the unique per-occurrence integer issued by the database.
 */
data class AlarmRequest(
    val occurrenceId: String,
    val requestCode: Int,
    val kind: AlarmKind,
    val atUtcMs: Long,
    val index: Int = 0,
) {
    val identity: String
        get() = when (kind) {
            AlarmKind.FIRE -> "command://occurrence/$occurrenceId"
            AlarmKind.CYCLE_END -> "command://cycle-end/$occurrenceId"
            AlarmKind.CHECK_IN -> "command://checkin/$occurrenceId/$index"
        }
}

interface AlarmScheduler {
    /** Whether the platform will deliver precisely timed alarms right now. Checked before every arm. */
    fun canScheduleExact(): Boolean

    /** Arms (or replaces) the alarm and reports the precision actually obtained: EXACT or INEXACT, never NONE. */
    fun arm(request: AlarmRequest): ArmMode

    fun cancel(request: AlarmRequest)

    /**
     * Whether the platform still holds this alarm's PendingIntent. A reboot and a force-stop cancel it, and some vendors' battery
     * managers drop alarms too, so false is proof that the alarm is gone. True is not proof that it is there: Android 12 removes
     * a revoked app's exact alarms without cancelling their PendingIntents, and the platform offers no way to ask about the alarm
     * itself. Callers log the answer and never treat it as a reason not to arm.
     */
    fun isArmed(request: AlarmRequest): Boolean
}

/** Background work the state machine can ask for (pre-rendering voice lines, prefetching directives). */
interface WorkEnqueuer {
    fun enqueue(kind: WorkKind, occurrenceId: String)
}

/** Posts a follow-up status question to the user (a notification with quick answers). */
interface CheckInPoster {
    fun post(occurrenceId: String, requestCode: Int, index: Int)
}

/** Tops up the rolling window of armed alarms after one fires. */
fun interface WindowRoller {
    fun roll()
}

/**
 * Ends the ring of an occurrence: the sound and vibration, and every notification the ring left behind. Naming the
 * occurrence matters: if a different one is ringing by now, it is left alone. The request code is the one its alarms and
 * its notification use.
 */
fun interface RingController {
    fun stop(occurrenceId: String, requestCode: Int)
}
