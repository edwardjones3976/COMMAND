package com.command.domain.dials

/**
 * How long and how often one occurrence rings. This is the single source for both the in-process ring cycle and
 * the cycle-end backstop alarm, so the alarm can never ring longer than the plan says.
 */
data class AlertPlan(val cycles: Int, val cycleMs: Long, val gapMs: Long) {
    init {
        require(cycles >= 1 && cycleMs > 0 && gapMs >= 0)
        require(totalMs <= MAX_TOTAL_MS) { "an alert plan may not exceed $MAX_TOTAL_MS ms (was $totalMs)" }
    }

    val totalMs: Long get() = cycles * cycleMs + (cycles - 1) * gapMs

    /** Start/end offsets, in ms from the first ring, of every ring cycle. */
    fun cycleWindows(): List<LongRange> =
        (0 until cycles).map { i -> val start = i * (cycleMs + gapMs); start until (start + cycleMs) }

    companion object {
        const val MAX_TOTAL_MS = 10 * 60_000L
        private const val SECOND = 1_000L
        private const val MINUTE = 60_000L

        /** Intensity 1: one 60 s ring; 2: one 2 min ring; 3: two 2 min rings; 4: three 2 min rings; 5: three 3 min rings. */
        fun forIntensity(intensity: Int): AlertPlan = when (intensity.coerceIn(1, 5)) {
            1 -> AlertPlan(1, MINUTE, 0)
            2 -> AlertPlan(1, 2 * MINUTE, 0)
            3 -> AlertPlan(2, 2 * MINUTE, MINUTE)
            4 -> AlertPlan(3, 2 * MINUTE, MINUTE)
            else -> AlertPlan(3, 3 * MINUTE, 30 * SECOND) // exactly the 10 minute cap
        }
    }
}
