package com.command.data

import com.command.data.db.OccurrenceRow
import com.command.domain.dials.AlertPlan

/**
 * When an occurrence's ring started and ends, and the cycles inside it, read back from the stored session. A restarted
 * service uses this to decide whether any ringing is left; null means the occurrence was never fired.
 */
class SessionPlan(
    val startUtc: Long,
    val endUtc: Long,
    val cycleWindows: List<LongRange>,
    /** The monotonic deadline stored when the ring began; trusted only while it still fits the same boot. */
    private val endElapsed: Long? = null,
) {
    val totalMs: Long get() = endUtc - startUtc

    /**
     * How much of the ring has already passed. Within the same boot the stored monotonic deadline is the truth, because
     * setting the phone clock cannot move it. After a reboot that reference counts from the new boot and means nothing, which
     * shows as more time left than the whole plan; the wall clock is used then. Never negative.
     */
    fun elapsedMs(nowUtc: Long, nowElapsed: Long): Long {
        val remaining = endElapsed?.let { it - nowElapsed }
        val sinceStart = if (remaining != null && remaining <= totalMs) totalMs - remaining else nowUtc - startUtc
        return sinceStart.coerceAtLeast(0)
    }

    fun isOver(nowUtc: Long, nowElapsed: Long): Boolean = elapsedMs(nowUtc, nowElapsed) >= totalMs

    companion object {
        fun of(row: OccurrenceRow): SessionPlan? {
            val s = Codec.decodeSession(row.sessionJson)
            val start = s.ringStartUtc ?: return null
            val end = s.planEndUtc ?: return null
            if (s.ringCycleMs <= 0) return null
            val windows = runCatching { AlertPlan(s.ringCycles, s.ringCycleMs, s.ringGapMs).cycleWindows() }.getOrNull() ?: return null
            return SessionPlan(start, end, windows, s.planEndElapsed)
        }
    }
}
