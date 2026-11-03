package com.command.alarm

import android.content.Intent

/** Names shared by the alarm scheduler, receivers, service and notifications. */
object AlarmContract {
    const val EXTRA_OCCURRENCE_ID = "com.command.extra.OCCURRENCE_ID"
    const val EXTRA_REQUEST_CODE = "com.command.extra.REQUEST_CODE"
    const val EXTRA_INDEX = "com.command.extra.INDEX"

    const val ACTION_FIRE = "com.command.action.FIRE"
    const val ACTION_CYCLE_END = "com.command.action.CYCLE_END"
    const val ACTION_CHECK_IN = "com.command.action.CHECK_IN"

    /** From the notification's Safety silence button. */
    const val ACTION_SILENCE = "com.command.action.SILENCE"

    /** From the notification being swiped away: on Android 14+ that is possible even for a foreground service. */
    const val ACTION_DISMISSED = "com.command.action.DISMISSED"

    const val ACTION_OPEN = "com.command.action.OPEN_REVEILLE"

    const val CHANNEL_ALARM = "reveille_alarm"
    const val CHANNEL_FALLBACK = "reveille_fallback"
    const val CHANNEL_CHECK_IN = "check_in"
    const val CHANNEL_STATUS = "status"

    /** The lock-screen activity lives in :app, so it is addressed by name. */
    const val ACTIVITY_CLASS = "com.command.ReveilleActivity"

    /**
     * Notification ids other than the ring itself, which uses the occurrence's request code. The check-in, missed and
     * fallback-ring notifications are tagged with their occurrence's id, so one id each is enough and any of them can be
     * found again knowing only the occurrence.
     */
    const val ID_CHECK_IN = 2
    const val ID_MISSED = 3
    const val ID_FALLBACK = 4

    fun occurrenceId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_OCCURRENCE_ID) ?: intent?.data?.takeIf { it.scheme == "command" }?.lastPathSegment

    fun requestCode(intent: Intent?): Int = intent?.getIntExtra(EXTRA_REQUEST_CODE, 0) ?: 0
}
