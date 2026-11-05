package com.command.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.command.data.ApplyResult
import com.command.domain.model.CheckInAnswer
import com.command.domain.model.EventSource
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand

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
        ReceiverWakeLock.acquire(context, WAKE_LOCK_MS)

        val serviceIntent = Intent(context, ReveilleService::class.java)
            .setAction(AlarmContract.ACTION_FIRE)
            .setData(intent.data)
            .putExtra(AlarmContract.EXTRA_OCCURRENCE_ID, id)
            .putExtra(AlarmContract.EXTRA_REQUEST_CODE, code)
            .putExtra(AlarmContract.EXTRA_AT, intent.getLongExtra(AlarmContract.EXTRA_AT, AlarmContract.NO_TIME))
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
        runAsync(context) { entry -> timeOutRing(context, entry.repository(), id, code, EventSource.RECEIVER) }
    }
}

/** The Safety silence button, or the ring notification being swiped away. Neither is ever treated as success. */
class SilenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = AlarmContract.occurrenceId(intent) ?: return
        val code = AlarmContract.requestCode(intent)
        val dismissed = intent.action == AlarmContract.ACTION_DISMISSED
        runAsync(context) { entry ->
            // Silence first and never wait for the database: opening it after a cold start takes seconds. The state change
            // below asks for the same stop again once it commits, which is harmless.
            entry.ringController().stop(id, code)
            val repository = entry.repository()
            if (dismissed) {
                repository.get(id)?.let { repository.logEvent(EventType.NOTIFICATION_DISMISSED, it, EventSource.USER, EventPayload.EMPTY) }
            }
            repository.apply(id, OccurrenceCommand.SafetySilence, EventSource.USER)
        }
    }
}

/**
 * A follow-up alarm fired: ask the next question (or record the silence after the last one). How long after its time the platform
 * delivered it goes with the question, because a dozing phone may hold an alarm back and the record should say so.
 */
class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = AlarmContract.occurrenceId(intent) ?: return
        val index = intent.getIntExtra(AlarmContract.EXTRA_INDEX, -1)
        if (index < 0) return
        val dueAt = intent.getLongExtra(AlarmContract.EXTRA_AT, 0)
        runAsync(context) { entry ->
            val lateMs = if (dueAt > 0) (entry.clock().nowMs() - dueAt).coerceAtLeast(0) else 0
            entry.repository().apply(id, OccurrenceCommand.CheckInDue(index, lateMs), EventSource.RECEIVER)
        }
    }
}

/**
 * A quick answer tapped on a check-in notification: started, still working or done. The question leaves the shade whether or not the
 * answer was still legal (a notification can outlive its occurrence), and an answer that is not one of the three is ignored.
 */
class CheckInAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = AlarmContract.occurrenceId(intent) ?: return
        val command = when (intent.getStringExtra(AlarmContract.EXTRA_ANSWER)) {
            CheckInAnswer.STARTED.name -> OccurrenceCommand.ReportStarted
            CheckInAnswer.WORKING.name -> OccurrenceCommand.ReportWorking
            CheckInAnswer.DONE.name -> OccurrenceCommand.ReportDone
            else -> return
        }
        runAsync(context) { entry ->
            // An answer that was applied took the question out of the shade itself, in the repository's turn for the occurrence, and doing it
            // again out here could remove the next question, which a follow-up alarm may have posted in between. One that was refused (its
            // notification outlived the occurrence) left the question behind, and that is the one to take away.
            if (entry.repository().apply(id, command, EventSource.USER) !is ApplyResult.Applied) entry.checkInPoster().cancel(id)
        }
    }
}

internal fun reasonOf(e: Exception): String =
    e.javaClass.simpleName.filter { it.isLetterOrDigit() }.take(48).ifEmpty { "Error" }
