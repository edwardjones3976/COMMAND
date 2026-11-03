package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver triggered periodically by AlarmManager to awaken the app
 * and launch CalendarBackgroundSyncService in the background.
 */
class CalendarSyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == CalendarSyncScheduler.ACTION_PERIODIC_SYNC || action == Intent.ACTION_BOOT_COMPLETED) {
            // Launch background service to perform calendar sync and task queue scheduling
            val serviceIntent = Intent(context, CalendarBackgroundSyncService::class.java).apply {
                this.action = CalendarBackgroundSyncService.ACTION_SYNC_CALENDAR
                putExtra(CalendarBackgroundSyncService.EXTRA_WINDOW_HOURS, 72)
            }
            try {
                context.startService(serviceIntent)
            } catch (_: Exception) {
            }

            // Re-arm periodic schedule for next interval if enabled
            if (CalendarSyncScheduler.isPeriodicSyncScheduled(context)) {
                val interval = CalendarSyncScheduler.getPeriodicIntervalMinutes(context)
                CalendarSyncScheduler.schedulePeriodicSync(context, interval)
            }
        }
    }
}
