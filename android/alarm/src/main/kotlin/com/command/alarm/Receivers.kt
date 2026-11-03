package com.command.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.command.data.ApplyResult
import com.command.domain.model.EventSource
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.EventPayload

/**
 * The alarm fired. This does no database work: it keeps the CPU awake for a moment and starts the foreground service at
 * once, because the allowance to start a foreground service from the background that an exact alarm brings is short, and a
 * cold start of the database could use it up. If the platform refuses, the ring degrades to a full-screen notification
 * with its own sound.
 */
class ReveilleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = AlarmContract.occurrenceId(intent) ?: return
        val code = AlarmContract.requestCode(intent)
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "command:alarm-receiver")
            ?.acquire(WAKE_LOCK_MS)

        val serviceIntent = Intent(context, ReveilleService::class.java)
            .setAction(AlarmContract.ACTION_FIRE)
            .setData(intent.data)
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, id)
            .putExtra(AlarmContract.EXTRA_REQUEST_CODE, code)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            runAsync(context) { it.resolver().degrade(id, code, reasonOf(e)) }
        }
    }

    private companion object {
        const val WAKE_LOCK_MS = 10_000L
    }
}

/** The ring plan is over: end it even if the process that was ringing has died. */
class CycleEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = AlarmContract.occurrenceId(intent) ?: return
        val code = AlarmContract.requestCode(intent)
        runAsync(context) { entry ->
            val result = entry.repository().apply(id, OccurrenceCommand.Miss(MissReason.TIMEOUT), EventSource.RECEIVER)
            runCatching { NotificationManagerCompat.from(context).cancel(code) }
            if (result is ApplyResult.Applied) {
                RingNotifications.post(context, id, AlarmContract.ID_MISSED, RingNotifications.missed(context, id, code))
            }
        }
    }
}

/** The Safety silence button, or the ring notification being swiped away. Neither is ever treated as success. */
class SilenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = AlarmContract.occurrenceId(intent) ?: return
        val dismissed = intent.action == AlarmContract.ACTION_DISMISSED
        runAsync(context) { entry ->
            val repository = entry.repository()
            if (dismissed) {
                repository.get(id)?.let { repository.logEvent(EventType.NOTIFICATION_DISMISSED, it, EventSource.USER, EventPayload.EMPTY) }
            }
            repository.apply(id, OccurrenceCommand.SafetySilence, EventSource.USER)
            // The state change asks the service to stop the sound. Tell it directly as well: silence must never wait.
            entry.ringController().stop(id)
        }
    }
}

/** A follow-up alarm fired: ask the next question (or record the silence after the last one). */
class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = AlarmContract.occurrenceId(intent) ?: return
        val index = intent.getIntExtra(AlarmContract.EXTRA_INDEX, -1)
        if (index < 0) return
        runAsync(context) { entry ->
            entry.repository().apply(id, OccurrenceCommand.CheckInDue(index), EventSource.RECEIVER)
        }
    }
}

internal fun reasonOf(e: Exception): String =
    e.javaClass.simpleName.filter { it.isLetterOrDigit() }.take(48).ifEmpty { "Error" }
