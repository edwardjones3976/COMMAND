package com.command.alarm

import android.content.Context
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
 * times itself out when the ring plan ends, and the backstop records the miss, so this path is bounded exactly like the
 * service's.
 */
@Singleton
class RingModeResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: OccurrenceRepository,
    private val clock: Clock,
) {
    suspend fun degrade(occurrenceId: String, requestCode: Int, reason: String) {
        val fired = repository.apply(occurrenceId, OccurrenceCommand.Fire, EventSource.RECEIVER)
        val row = (fired as? ApplyResult.Applied)?.occurrence
            ?: repository.get(occurrenceId)?.takeIf { it.state == OccurrenceState.FIRING }
            ?: return // stale: nothing to ring for
        repository.logEvent(EventType.RING_DEGRADED, row, EventSource.RECEIVER, EventPayload.of("reason" to reason))

        val remaining = SessionPlan.of(row)?.let { (it.endUtc - clock.nowMs()).coerceAtLeast(MIN_TIMEOUT_MS) } ?: MIN_TIMEOUT_MS
        val title = repository.missionTitle(row.missionId)
        val notification = RingNotifications.ring(context, occurrenceId, requestCode, title, fallback = true, timeoutMs = remaining)
        RingNotifications.post(context, null, requestCode, notification)
    }

    private companion object {
        const val MIN_TIMEOUT_MS = 1_000L
    }
}
