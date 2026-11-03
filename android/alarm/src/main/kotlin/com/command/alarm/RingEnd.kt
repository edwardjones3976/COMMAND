package com.command.alarm

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.command.data.ApplyResult
import com.command.data.OccurrenceRepository
import com.command.domain.model.EventSource
import com.command.domain.occurrence.MissReason
import com.command.domain.occurrence.OccurrenceCommand
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The ring plan ran out with no answer. Records the miss, takes the ring's notifications down and leaves one quiet note
 * so the user can pick the occurrence up again. Whichever of the service, the cycle-end alarm or the fallback path gets
 * here first wins the state change; the others find it already made and post nothing.
 */
internal suspend fun timeOutRing(
    context: Context,
    repository: OccurrenceRepository,
    occurrenceId: String,
    requestCode: Int,
    source: EventSource,
) {
    val result = withContext(NonCancellable) { repository.apply(occurrenceId, OccurrenceCommand.Miss(MissReason.TIMEOUT), source) }
    runCatching {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(requestCode)
        manager.cancel(occurrenceId, AlarmContract.ID_FALLBACK)
    }
    if (result is ApplyResult.Applied) {
        RingNotifications.post(context, occurrenceId, AlarmContract.ID_MISSED, RingNotifications.missed(context, occurrenceId, requestCode))
    }
}

/**
 * The CPU hold the alarm receiver takes the instant an alarm arrives. It is kept in one place, not in a local variable, so
 * it cannot be collected before it times out, and it is handed over: once the service holds the CPU itself it releases this
 * one, and the timeout is only the safety net if the service never starts.
 */
internal object ReceiverWakeLock {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context, timeoutMs: Long) {
        release()
        lock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "command:alarm-receiver")
            ?.apply { acquire(timeoutMs) }
    }

    @Synchronized
    fun release() {
        lock?.let { held -> runCatching { if (held.isHeld) held.release() } }
        lock = null
    }
}
