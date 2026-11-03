package com.command.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.command.data.ReconcileTrigger

/**
 * The system events that can leave alarms wrong: a reboot (which clears every alarm), an update of this app, the clock or
 * the time zone being set, and the exact-alarm permission changing. It does nothing but queue a reconcile: opening the
 * database is not a job for a receiver, and a boot receiver may not start the ring service on Android 15.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ReconcileTrigger.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileTrigger.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED -> ReconcileTrigger.TIME_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> ReconcileTrigger.ZONE_CHANGED
            ACTION_EXACT_ALARM_PERMISSION_CHANGED -> ReconcileTrigger.EXACT_ALARM_CHANGED
            else -> return
        }
        ReconcileScheduler.enqueueNow(context, trigger)
    }

    companion object {
        /** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, which only exists from Android 12. */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
