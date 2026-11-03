package com.command.alarm

import android.content.Intent

/** Names shared by the alarm scheduler, receivers, service and notifications. */
object AlarmContract {
    const val EXTRA_OCCURRENCE_ID = "com.command.extra.OCCURRENCE_ID"
    const val EXTRA_REQUEST_CODE = "com.command.extra.REQUEST_CODE"
    const val EXTRA_INDEX = "com.command.extra.INDEX"

    /** When an alarm was meant to go off, so that its receiver can tell how late the platform delivered it. */
    const val EXTRA_AT = "com.command.extra.AT"

    /** Which quick answer was tapped on a check-in notification (the name of a CheckInAnswer). */
    const val EXTRA_ANSWER = "com.command.extra.ANSWER"

    const val ACTION_FIRE = "com.command.action.FIRE"
    const val ACTION_CYCLE_END = "com.command.action.CYCLE_END"
    const val ACTION_CHECK_IN = "com.command.action.CHECK_IN"

    /** From the notification's Safety silence button. */
    const val ACTION_SILENCE = "com.command.action.SILENCE"

    /** From the notification being swiped away: on Android 14+ that is possible even for a foreground service. */
    const val ACTION_DISMISSED = "com.command.action.DISMISSED"

    const val ACTION_OPEN = "com.command.action.OPEN_REVEILLE"

    /** From a check-in notification's answer buttons, and from its Open action, which lands on that occurrence's question. */
    const val ACTION_CHECK_IN_ANSWER = "com.command.action.CHECK_IN_ANSWER"
    const val ACTION_OPEN_CHECK_IN = "com.command.action.OPEN_CHECK_IN"

    const val CHANNEL_ALARM = "reveille_alarm"
    const val CHANNEL_FALLBACK = "reveille_fallback"
    const val CHANNEL_CHECK_IN = "check_in"
    const val CHANNEL_STATUS = "status"

    /** The lock-screen activity lives in :app, so it is addressed by name. */
    const val ACTIVITY_CLASS = "com.command.ReveilleActivity"

    /** The app proper, which holds the check-in screens, is addressed by name too. */
    const val MAIN_ACTIVITY_CLASS = "com.command.MainActivity"

    /**
     * Notification ids other than the ring itself, which uses the occurrence's request code. The check-in, missed and
     * fallback-ring notifications are tagged with their occurrence's id, so one id each is enough and any of them can be
     * found again knowing only the occurrence.
     */
    const val ID_CHECK_IN = 2
    const val ID_MISSED = 3
    const val ID_FALLBACK = 4

    /**
     * The "checking your alarms" foreground notification of the reconcile worker before Android 12. Negative, because a ring
     * uses its occurrence's request code as its notification id and those count up from 1: this can never replace a ring.
     */
    const val ID_RECONCILE = -1

    fun occurrenceId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_OCCURRENCE_ID) ?: intent?.data?.takeIf { it.scheme == "command" }?.lastPathSegment

    fun requestCode(intent: Intent?): Int = intent?.getIntExtra(EXTRA_REQUEST_CODE, 0) ?: 0
}
