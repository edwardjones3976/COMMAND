package com.command.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri

/** Notification channels and the notifications built on them. */
object RingNotifications {

    private val VIBRATION = longArrayOf(0, 600, 300, 600, 300, 600)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // The ring itself: the service plays the sound, so this channel is silent but high enough to take over the screen.
        manager.createNotificationChannel(
            NotificationChannel(AlarmContract.CHANNEL_ALARM, context.getString(R.string.channel_alarm_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alarm_description)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        // Used when the service cannot start: the channel itself carries the alarm sound and vibration.
        manager.createNotificationChannel(
            NotificationChannel(AlarmContract.CHANNEL_FALLBACK, context.getString(R.string.channel_fallback_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_fallback_description)
                setSound("android.resource://${context.packageName}/${R.raw.reveille_tone}".toUri(), alarmAudio)
                enableVibration(true)
                vibrationPattern = VIBRATION
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(AlarmContract.CHANNEL_CHECK_IN, context.getString(R.string.channel_check_in_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_check_in_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(AlarmContract.CHANNEL_STATUS, context.getString(R.string.channel_status_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_status_description)
            },
        )
    }

    /**
     * The one notification of a ring. It carries the full-screen intent, an Open action and a Safety silence action; if
     * it is swiped away that counts as the user silencing the alarm. A public version without the mission title still
     * offers the same actions, so Safety silence is reachable on a locked screen that hides sensitive content.
     *
     * Delay and Skip are not on it, by decision (amendment 33): a fixed commitment asks for a confirmation or a reason first, which a notification
     * cannot ask, and one tap in a shade or a pocket would end a ring unseen. Both are on the ring's own screen, which shows over the lock screen.
     */
    fun ring(
        context: Context,
        occurrenceId: String,
        requestCode: Int,
        title: String?,
        fallback: Boolean = false,
        timeoutMs: Long? = null,
    ): Notification {
        ensureChannels(context)
        val channel = if (fallback) AlarmContract.CHANNEL_FALLBACK else AlarmContract.CHANNEL_ALARM
        val open = openActivity(context, occurrenceId, requestCode)
        val silence = silenceBroadcast(context, occurrenceId, requestCode, AlarmContract.ACTION_SILENCE)
        val dismissed = silenceBroadcast(context, occurrenceId, requestCode, AlarmContract.ACTION_DISMISSED)

        fun builder(withTitle: Boolean) = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_command)
            .setContentTitle(if (withTitle && title != null) title else context.getString(R.string.ring_title_generic))
            .setContentText(context.getString(R.string.ring_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_open), open)
            .addAction(0, context.getString(R.string.action_silence), silence)

        val publicVersion = builder(withTitle = false).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        return builder(withTitle = true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setFullScreenIntent(open, true)
            .setDeleteIntent(dismissed)
            .apply { if (timeoutMs != null) setTimeoutAfter(timeoutMs) }
            .apply { if (fallback) alertWithoutChannels(context) }
            .build()
            .also { n -> if (fallback) n.flags = n.flags or Notification.FLAG_INSISTENT }
    }

    /** Before Android 8 there are no channels, so the fallback ring sound and vibration belong to the notification itself. */
    @Suppress("DEPRECATION")
    private fun NotificationCompat.Builder.alertWithoutChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        setSound("android.resource://${context.packageName}/${R.raw.reveille_tone}".toUri(), AudioManager.STREAM_ALARM)
        setVibrate(VIBRATION)
    }

    /** The notification of the background check that puts alarms back after a reboot (shown only before Android 12). */
    fun checking(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, AlarmContract.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_command)
            .setContentTitle(context.getString(R.string.reconcile_title))
            .setOngoing(true)
            .build()
    }

    /** The notification of a service start that has nothing to ring for; it exists only to answer the foreground-start contract. */
    fun starting(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, AlarmContract.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_command)
            .setContentTitle(context.getString(R.string.ring_title_generic))
            .build()
    }

    /** A quiet note that an alarm went unanswered, with Open as the way to pick it up again. */
    fun missed(context: Context, occurrenceId: String, requestCode: Int): Notification {
        ensureChannels(context)
        val open = openActivity(context, occurrenceId, requestCode)
        fun builder() = NotificationCompat.Builder(context, AlarmContract.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_command)
            .setContentTitle(context.getString(R.string.missed_title))
            .setContentText(context.getString(R.string.missed_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.action_open), open)
        return builder()
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(builder().setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build())
            .build()
    }

    /**
     * Posts a notification if the user has allowed notifications. Returns false (and posts nothing) when they have not: the
     * ring itself does not depend on this, since the service plays the sound either way.
     */
    fun post(context: Context, tag: String?, id: Int, notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun openActivity(context: Context, occurrenceId: String, requestCode: Int): PendingIntent {
        val intent = Intent(AlarmContract.ACTION_OPEN)
            .setClassName(context.packageName, AlarmContract.ACTIVITY_CLASS)
            .setData("command://open/$occurrenceId".toUri())
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, occurrenceId)
            .putExtra(AlarmContract.EXTRA_REQUEST_CODE, requestCode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun silenceBroadcast(context: Context, occurrenceId: String, requestCode: Int, action: String): PendingIntent {
        val kind = if (action == AlarmContract.ACTION_SILENCE) "silence" else "dismissed"
        val intent = Intent(context, SilenceReceiver::class.java)
            .setAction(action)
            .setData("command://$kind/$occurrenceId".toUri())
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, occurrenceId)
            .putExtra(AlarmContract.EXTRA_REQUEST_CODE, requestCode)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
