package com.command.domain.calendar

import com.command.domain.model.ConflictKind

/** A stretch of time an occurrence takes up, half open: one that ends as another begins does not overlap it. */
data class TimeInterval(val id: String, val startMs: Long, val endMs: Long)

/** Two occurrences that overlap; [a] starts first ([NESTED] when [b] lies wholly inside it). */
data class Conflict(val a: String, val b: String, val kind: ConflictKind)

/**
 * A sweep over the intervals sorted by start. Every interval is compared with all those still open, not only the one before it, so
 * that a short one nested in a long one does not hide the next one that the long one also overlaps.
 */
object ConflictDetector {
    fun detect(intervals: List<TimeInterval>): List<Conflict> {
        val open = ArrayList<TimeInterval>()
        val out = ArrayList<Conflict>()
        for (next in intervals.filter { it.endMs > it.startMs }.sortedWith(compareBy({ it.startMs }, { -it.endMs }, { it.id }))) {
            open.removeAll { it.endMs <= next.startMs }
            for (earlier in open) {
                out += Conflict(earlier.id, next.id, if (next.endMs <= earlier.endMs) ConflictKind.NESTED else ConflictKind.OVERLAP)
            }
            open += next
        }
        return out
    }
}
