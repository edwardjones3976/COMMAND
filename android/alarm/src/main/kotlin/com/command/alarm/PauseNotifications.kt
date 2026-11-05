package com.command.alarm

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import java.util.Date

/**
 * The notice that stays in the shade while COMMAND is paused: until when, and a button that ends the pause. It is how a person who has
 * forgotten that they paused finds out why nothing rings, so it is ongoing. It names no mission and says nothing private, so the same
 * notice serves a locked screen.
 */
object PauseNotifications {

    fun notice(context: Context, untilUtcMs: Long): Notification {
        RingNotifications.ensureChannels(context)
        val until = DateFormat.getTimeFormat(context).format(Date(untilUtcMs))
        return NotificationCompat.Builder(context, AlarmContract.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_command)
            .setContentTitle(context.getString(R.string.pause_notice_title, until))
            .setContentText(context.getString(R.string.pause_notice_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open(context))
            .addAction(0, context.getString(R.string.action_resume), resume(context))
            .build()
    }

    /** Posts the notice, or nothing when notifications are off: the schedule and Settings say that COMMAND is paused as well. */
    fun show(context: Context, untilUtcMs: Long) {
        RingNotifications.post(context, null, AlarmContract.ID_PAUSED, notice(context, untilUtcMs))
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(null, AlarmContract.ID_PAUSED) }
    }

    /** Opens the app on its first screen. The app proper lives in :app, so it is addressed by name. */
    private fun open(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(context.packageName, AlarmContract.MAIN_ACTIVITY_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun resume(context: Context): PendingIntent {
        val intent = Intent(context, PauseResumeReceiver::class.java)
            .setAction(AlarmContract.ACTION_RESUME_PAUSE)
            .setData("command://pause/resume".toUri())
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
