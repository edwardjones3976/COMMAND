package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * Manages background scheduling of Google Calendar synchronizations
 * using Android's AlarmManager. Ensures COMMAND periodically polls
 * CalendarContract.Instances in the background and updates the task queue.
 */
object CalendarSyncScheduler {

    const val ACTION_PERIODIC_SYNC = "com.example.action.CALENDAR_PERIODIC_WAKEUP"
    private const val PREFS_NAME = "command_calendar_sync_prefs"
    private const val KEY_PERIODIC_ENABLED = "periodic_sync_enabled"
    private const val KEY_INTERVAL_MINUTES = "periodic_interval_minutes"
    private const val KEY_LAST_SYNC_EPOCH_MS = "last_sync_epoch_ms"
    private const val KEY_LAST_SYNC_COUNT = "last_sync_count"
    private const val KEY_LAST_CONFLICT_COUNT = "last_conflict_count"
    private const val PENDING_INTENT_REQUEST_CODE = 9021

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isPeriodicSyncScheduled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PERIODIC_ENABLED, true)
    }

    fun getPeriodicIntervalMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_INTERVAL_MINUTES, 120) // default 2 hours
    }

    fun getLastSyncTimestamp(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SYNC_EPOCH_MS, 0L)
    }

    fun setLastSyncResult(context: Context, timestamp: Long, syncedCount: Int, conflictsCount: Int) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_SYNC_EPOCH_MS, timestamp)
            .putInt(KEY_LAST_SYNC_COUNT, syncedCount)
            .putInt(KEY_LAST_CONFLICT_COUNT, conflictsCount)
            .apply()
    }

    fun getLastSyncCounts(context: Context): Pair<Int, Int> {
        val prefs = getPrefs(context)
        return Pair(
            prefs.getInt(KEY_LAST_SYNC_COUNT, 0),
            prefs.getInt(KEY_LAST_CONFLICT_COUNT, 0)
        )
    }

    fun triggerImmediateSync(context: Context) {
        val serviceIntent = Intent(context, CalendarBackgroundSyncService::class.java).apply {
            action = CalendarBackgroundSyncService.ACTION_SYNC_CALENDAR
            putExtra(CalendarBackgroundSyncService.EXTRA_WINDOW_HOURS, 72)
        }
        try {
            context.startService(serviceIntent)
        } catch (_: Exception) {
            // Android 8+ background service start restriction handling:
            // if backgrounded, service will be invoked when app is foregrounded or via AlarmManager
        }
    }

    fun schedulePeriodicSync(context: Context, intervalMinutes: Int = 120) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val clampedInterval = intervalMinutes.coerceIn(15, 720)

        getPrefs(context).edit()
            .putBoolean(KEY_PERIODIC_ENABLED, true)
            .putInt(KEY_INTERVAL_MINUTES, clampedInterval)
            .apply()

        val intent = Intent(context, CalendarSyncAlarmReceiver::class.java).apply {
            action = ACTION_PERIODIC_SYNC
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(clampedInterval.toLong())
        val intervalMillis = TimeUnit.MINUTES.toMillis(clampedInterval.toLong())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                intervalMillis,
                pendingIntent
            )
        }
    }

    fun cancelPeriodicSync(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        getPrefs(context).edit()
            .putBoolean(KEY_PERIODIC_ENABLED, false)
            .apply()

        val intent = Intent(context, CalendarSyncAlarmReceiver::class.java).apply {
            action = ACTION_PERIODIC_SYNC
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
