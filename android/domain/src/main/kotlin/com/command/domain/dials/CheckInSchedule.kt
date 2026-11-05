package com.command.domain.dials

/** The follow-up status questions for one occurrence: how many, and the gap before each one. */
data class CheckInSchedule(val gapsMs: List<Long>) {
    val count: Int get() = gapsMs.size

    companion object {
        /** The most questions any persistence setting asks. */
        const val MAX_COUNT = 4
        private const val MINUTE = 60_000L

        /** Persistence 1: one question at +10 min; 2: two, 10 min apart; 3: two, 7 apart; 4: three, 5 apart; 5: four, 5 apart. */
        fun forPersistence(persistence: Int): CheckInSchedule = when (persistence.coerceIn(1, 5)) {
            1 -> CheckInSchedule(listOf(10 * MINUTE))
            2 -> CheckInSchedule(listOf(10 * MINUTE, 10 * MINUTE))
            3 -> CheckInSchedule(listOf(7 * MINUTE, 7 * MINUTE))
            4 -> CheckInSchedule(listOf(5 * MINUTE, 5 * MINUTE, 5 * MINUTE))
            else -> CheckInSchedule(listOf(5 * MINUTE, 5 * MINUTE, 5 * MINUTE, 5 * MINUTE))
        }
    }
}
