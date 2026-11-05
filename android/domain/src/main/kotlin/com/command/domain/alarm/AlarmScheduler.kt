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
}

/** Background work the state machine can ask for (pre-rendering voice lines, prefetching directives). */
interface WorkEnqueuer {
    fun enqueue(kind: WorkKind, occurrenceId: String)
}

/** Stops the sound and vibration of a ring that has ended (on Android: an intent to the alarm service). */
fun interface RingController {
    fun stop(occurrenceId: String)
}
