package com.command.alarm

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.reliability.Precondition
import com.command.domain.reliability.PreconditionSnapshot
import com.command.domain.reliability.PreconditionValue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the platform, right now, about everything an alarm depends on. It only reads: it never asks for a permission, never
 * changes a setting and never touches the volume. (The one thing it may do is create the app's notification channels if they are
 * missing, which the app does at every start anyway, so that the ring channel's importance can be read instead of reported as
 * missing.) What it finds is recorded by [com.command.data.PreconditionRecorder] and shown on the reliability screen, so the app
 * tells the truth about whether the next alarm will ring the way it promises.
 */
@Singleton
class PreconditionProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: AlarmScheduler,
) {
    fun read(): PreconditionSnapshot {
        val values = LinkedHashMap<Precondition, String>()
        values[Precondition.EXACT_ALARMS] = granted(scheduler.canScheduleExact())
        values[Precondition.NOTIFICATIONS] = granted(NotificationManagerCompat.from(context).areNotificationsEnabled())
        values[Precondition.ALARM_CHANNEL] = alarmChannelImportance()
        values[Precondition.FULL_SCREEN_INTENT] = granted(canUseFullScreenIntent())
        values[Precondition.ALARM_VOLUME] = if (alarmStreamAudible()) PreconditionValue.AUDIBLE else PreconditionValue.MUTED
        values[Precondition.DO_NOT_DISTURB] = if (doNotDisturbBlocksAlarms()) PreconditionValue.DND_BLOCKS_ALARMS else PreconditionValue.DND_FREE
        values[Precondition.BATTERY_OPTIMIZATION] = if (ignoringBatteryOptimizations()) PreconditionValue.EXEMPT else PreconditionValue.OPTIMIZED
        values[Precondition.BACKGROUND_RESTRICTED] = if (backgroundRestricted()) PreconditionValue.YES else PreconditionValue.NO
        values[Precondition.STANDBY_BUCKET] = standbyBucket()
        return PreconditionSnapshot(values)
    }

    private fun granted(value: Boolean) = if (value) PreconditionValue.GRANTED else PreconditionValue.DENIED

    /** The ring's channel decides whether it can take over the screen; the user can lower it in settings at any time. */
    private fun alarmChannelImportance(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return PreconditionValue.IMPORTANCE_HIGH
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(AlarmContract.CHANNEL_ALARM) == null) RingNotifications.ensureChannels(context)
        val importance = manager.getNotificationChannel(AlarmContract.CHANNEL_ALARM)?.importance ?: return "MISSING"
        return when {
            importance >= NotificationManager.IMPORTANCE_HIGH -> PreconditionValue.IMPORTANCE_HIGH
            importance == NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
            importance == NotificationManager.IMPORTANCE_LOW -> "LOW"
            importance == NotificationManager.IMPORTANCE_MIN -> "MIN"
            else -> "NONE"
        }
    }

    /** Android 14 made this a permission that can be refused; before that it is always there. */
    private fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** A muted or zero alarm stream plays nothing, whatever else is right. Reading it changes nothing. */
    private fun alarmStreamAudible(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        return !audio.isStreamMute(AudioManager.STREAM_ALARM) && audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0
    }

    /**
     * Total silence blocks alarms; priority-only blocks them unless the alarms switch is on, which is the default. This is what Android
     * reports (the filter and the policy in force), not a model of every phone maker's own Do Not Disturb extras. Reading them needs no
     * special access; changing them would, and the app never asks for it.
     */
    private fun doNotDisturbBlocksAlarms(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return when (manager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> priorityPolicyBlocksAlarms(manager)
            else -> false
        }
    }

    /**
     * Before Android 9 there was no switch for alarms, and priority-only always let them through. From Android 11 the answer is in the
     * consolidated policy: the one in force now, with any automatic rule that is active folded in. The ordinary policy is only what the
     * person set for when no rule applies, and can disagree with the filter that is actually in effect; on Android 9 and 10, which have
     * no consolidated policy to ask for, it is all there is.
     */
    private fun priorityPolicyBlocksAlarms(manager: NotificationManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val inForce = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) manager.consolidatedNotificationPolicy else null
        val policy = inForce ?: manager.notificationPolicy
        return policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0
    }

    private fun ignoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    private fun backgroundRestricted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

    // The exempted bucket (5) is not part of the public set, and the restricted one (45) postdates the oldest platform the app runs on;
    // both are compile-time constants, so naming them is safe on every version.
    @SuppressLint("SwitchIntDef", "InlinedApi")
    private fun standbyBucket(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "ACTIVE"
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return "UNKNOWN"
        return when (usage.appStandbyBucket) {
            5 -> "EXEMPTED" // STANDBY_BUCKET_EXEMPTED, Android 11
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
            else -> "UNKNOWN"
        }
    }
}
