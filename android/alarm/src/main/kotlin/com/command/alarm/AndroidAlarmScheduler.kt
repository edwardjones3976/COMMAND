package com.command.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.command.domain.alarm.AlarmKind
import com.command.domain.alarm.AlarmRequest
import com.command.domain.alarm.AlarmScheduler
import com.command.domain.occurrence.ArmMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms platform alarms. Every alarm of an occurrence carries a data URI (`command://occurrence/{id}`,
 * `command://cycle-end/{id}`, `command://checkin/{id}/{n}`), so `Intent.filterEquals` tells them apart even though they
 * share the occurrence's one request code. Arming the same request again replaces it; cancelling finds it again.
 */
@Singleton
class AndroidAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val latch: ErasureLatch,
) : AlarmScheduler {

    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    override fun arm(request: AlarmRequest): ArmMode {
        // Erasing everything is under way (or done): whatever is still running must not set an alarm that the erase has taken back or will.
        if (latch.sealed) return ArmMode.NONE
        val mode = armNow(request)
        // The erase can close the latch between the check and the call, after its last pass over the alarms: look again, and undo what was set.
        // Either this sees the latch closed and takes the alarm back, or the erase's later pass finds the alarm.
        if (latch.sealed) {
            cancel(request)
            return ArmMode.NONE
        }
        return mode
    }

    private fun armNow(request: AlarmRequest): ArmMode {
        val operation = pendingIntent(request, create = true)!!
        if (canScheduleExact()) {
            try {
                when (request.kind) {
                    // The alarm-clock call is the one that wakes a dozing phone and is shown to the user as an alarm.
                    AlarmKind.FIRE -> alarms.setAlarmClock(AlarmManager.AlarmClockInfo(request.atUtcMs, showIntent()), operation)
                    AlarmKind.CYCLE_END, AlarmKind.CHECK_IN ->
                        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.atUtcMs, operation)
                }
                return ArmMode.EXACT
            } catch (e: SecurityException) {
                // The permission was revoked between the check and the call. Fall through to the honest inexact path.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.atUtcMs, operation)
        return ArmMode.INEXACT
    }

    override fun cancel(request: AlarmRequest) {
        val operation = pendingIntent(request, create = false) ?: return
        alarms.cancel(operation)
        operation.cancel()
    }

    override fun isArmed(request: AlarmRequest): Boolean = pendingIntent(request, create = false) != null

    private fun pendingIntent(request: AlarmRequest, create: Boolean): PendingIntent? {
        val target = when (request.kind) {
            AlarmKind.FIRE -> ReveilleAlarmReceiver::class.java
            AlarmKind.CYCLE_END -> CycleEndReceiver::class.java
            AlarmKind.CHECK_IN -> CheckInReceiver::class.java
        }
        val action = when (request.kind) {
            AlarmKind.FIRE -> AlarmContract.ACTION_FIRE
            AlarmKind.CYCLE_END -> AlarmContract.ACTION_CYCLE_END
            AlarmKind.CHECK_IN -> AlarmContract.ACTION_CHECK_IN
        }
        val intent = Intent(context, target)
            .setAction(action)
            .setData(request.identity.toUri())
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, request.occurrenceId)
            .putExtra(AlarmContract.EXTRA_REQUEST_CODE, request.requestCode)
            .putExtra(AlarmContract.EXTRA_INDEX, request.index)
            .putExtra(AlarmContract.EXTRA_AT, request.atUtcMs)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, request.requestCode, intent, flags)
    }

    /** What tapping the status-bar alarm icon opens: the app's launcher activity. */
    private fun showIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        return PendingIntent.getActivity(context, SHOW_REQUEST_CODE, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        /** Request codes from the database start at 1 and count up, so this can never collide with an occurrence's. */
        const val SHOW_REQUEST_CODE = 0
    }
}
