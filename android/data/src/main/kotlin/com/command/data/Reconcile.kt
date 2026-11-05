package com.command.data

/** Why the plan and the platform's alarms are being matched up again. */
enum class ReconcileTrigger {
    /** The app came to the front. */
    OPEN,

    /** The periodic backstop. */
    PERIODIC,
    BOOT,
    PACKAGE_REPLACED,

    /** The wall clock was set. */
    TIME_CHANGED,
    ZONE_CHANGED,

    /** The person (or the system) changed the exact-alarm permission. */
    EXACT_ALARM_CHANGED,

    /** A pause ran out; the run was queued for the moment it ends. */
    PAUSE_ENDED,

    /** The person ended a pause early. */
    RESUMED,

    /** A pause was started or moved: the run takes down what it holds back and arms again what a shorter one released. */
    PAUSE_STARTED,

    /** A calendar sync changed the plan: this arms what it created and moved. It runs every hour, so it is not worth a line by itself. */
    CALENDAR_SYNC;

    /** A run the person did not start is worth a line in the audit log even when it found nothing to do. */
    val alwaysLogged: Boolean get() = this != OPEN && this != PERIODIC && this != CALENDAR_SYNC
}

/**
 * What one reconcile did. [rearmed] counts every armed alarm that was put back; [lost] is how many of those were found gone (a
 * floor, because the probe can miss a removal, see `AlarmScheduler.isArmed`); [precisionChanged] is how many came back with a
 * different precision than they had (an exact alarm that can only be inexact now because the permission was taken away, or the
 * other way round); [repaired] is how many had been stored but never armed (the app stopped between saving and arming) and now are;
 * [paused] is how many armed alarms a global pause had to take down in this run, and [swallowed] how many occurrences it had held back
 * were recorded as missed because of it when it ended.
 */
data class ReconcileReport(
    val created: Int = 0,
    val discarded: Int = 0,
    val rearmed: Int = 0,
    val lost: Int = 0,
    val missed: Int = 0,
    val precisionChanged: Int = 0,
    val repaired: Int = 0,
    val paused: Int = 0,
    val swallowed: Int = 0,
) {
    val changedAnything: Boolean get() = created + discarded + lost + missed + precisionChanged + repaired + paused + swallowed > 0
}
