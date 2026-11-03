package com.command.data

import com.command.data.db.OccurrenceRow
import com.command.domain.dials.AlertPlan

/**
 * When an occurrence's ring started and ends, and the cycles inside it, read back from the stored session. A restarted
 * service uses this to decide whether any ringing is left; null means the occurrence was never fired.
 */
class SessionPlan(val startUtc: Long, val endUtc: Long, val cycleWindows: List<LongRange>) {
    companion object {
        fun of(row: OccurrenceRow): SessionPlan? {
            val s = Codec.decodeSession(row.sessionJson)
            val start = s.ringStartUtc ?: return null
            val end = s.planEndUtc ?: return null
            if (s.ringCycleMs <= 0) return null
            val windows = runCatching { AlertPlan(s.ringCycles, s.ringCycleMs, s.ringGapMs).cycleWindows() }.getOrNull() ?: return null
            return SessionPlan(start, end, windows)
        }
    }
}
