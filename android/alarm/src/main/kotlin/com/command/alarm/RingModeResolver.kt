package com.command.alarm

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.command.data.ApplyResult
import com.command.data.OccurrenceRepository
import com.command.data.SessionPlan
import com.command.domain.model.EventSource
import com.command.domain.occurrence.EventPayload
import com.command.domain.occurrence.EventType
import com.command.domain.occurrence.OccurrenceCommand
import com.command.domain.occurrence.OccurrenceState
import com.command.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The fallback ring. When the foreground service cannot be started (the platform refuses, or the start fails), the alarm
 * still has to reach the user: the occurrence is fired through the state machine as usual, which arms the same cycle-end
 * backstop, and a full-screen notification on the fallback channel carries the sound and vibration. The notification
 * times itself out when the ring plan ends, the state change that answers the ring takes it down, and the backstop records
 * the miss, so this path is bounded exactly like the service's.
 *
 * Known limit: the sound belongs to the notification, so if the user has not allowed notifications this path is silent.
 * The event it records says so (`notified=false`), and the reliability screen of a later milestone warns about the
 * combination before it can happen.
 */
@Singleton
class RingModeResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: OccurrenceRepository,
    private val clock: Clock,
    private val latch: ErasureLatch,
) {
    suspend fun degrade(occurrenceId: String, requestCode: Int, reason: String) {
        // Everything is being erased: no ring, not even this one, may start behind the erase.
        if (latch.sealed) return
        val fired = repository.apply(occurrenceId, OccurrenceCommand.Fire, EventSource.RECEIVER)
        val row = (fired as? ApplyResult.Applied)?.occurrence
            ?: repository.get(occurrenceId)?.takeIf { it.state == OccurrenceState.FIRING }
            ?: return // stale: nothing to ring for

        val remaining = SessionPlan.of(row)?.let { it.totalMs - it.elapsedMs(clock.nowMs(), clock.elapsedRealtimeMs()) } ?: 0L
        if (remaining <= 0) {
            // None of the plan is left (it ran out while nothing could ring): record the miss and make no sound.
            repository.logEvent(EventType.RING_DEGRADED, row, EventSource.RECEIVER, EventPayload.of("reason" to reason, "notified" to false))
            timeOutRing(context, repository, row.id, requestCode, EventSource.RECEIVER)
            return
        }

        val title = repository.missionTitle(row.missionId)
        // The erase may have sealed the latch, silenced everything and cancelled every notice while the database was being asked, and
        // nothing waits for this receiver-owned work (RingSession.isBusy tracks the service only): look again before the sound is made...
        if (latch.sealed) return
        val notification = RingNotifications.ring(context, occurrenceId, requestCode, title, fallback = true, timeoutMs = remaining)
        val posted = RingNotifications.post(context, occurrenceId, AlarmContract.ID_FALLBACK, notification)
        // ...and once more after it: the erase seals first and cancels the notices after, so a post that the cancel did not see has to
        // take itself down (the same two looks as AndroidAlarmScheduler.arm).
        if (latch.sealed) {
            NotificationManagerCompat.from(context).cancel(occurrenceId, AlarmContract.ID_FALLBACK)
            return
        }
        repository.logEvent(EventType.RING_DEGRADED, row, EventSource.RECEIVER, EventPayload.of("reason" to reason, "notified" to posted))
    }
}
