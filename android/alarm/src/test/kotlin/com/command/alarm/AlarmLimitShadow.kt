package com.command.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Robolectric takes any number of alarms. Android does not: at its limit for one app it throws an IllegalStateException, and it counts before it
 * replaces, so an alarm that is already held is refused as well. A test says where the limit is.
 */
@Implements(AlarmManager::class)
class AlarmLimitShadow : ShadowAlarmManager() {

    private fun check() {
        if (scheduledAlarms.size >= limit) throw IllegalStateException("Maximum limit of concurrent alarms $limit reached")
    }

    @Implementation
    override fun setAlarmClock(info: AlarmManager.AlarmClockInfo, operation: PendingIntent) {
        check()
        super.setAlarmClock(info, operation)
    }

    @Implementation
    override fun setExactAndAllowWhileIdle(type: Int, triggerAtMs: Long, operation: PendingIntent) {
        check()
        super.setExactAndAllowWhileIdle(type, triggerAtMs, operation)
    }

    @Implementation
    override fun setAndAllowWhileIdle(type: Int, triggerAtMs: Long, operation: PendingIntent) {
        check()
        super.setAndAllowWhileIdle(type, triggerAtMs, operation)
    }

    companion object {
        @JvmStatic
        var limit = Int.MAX_VALUE
    }
}
