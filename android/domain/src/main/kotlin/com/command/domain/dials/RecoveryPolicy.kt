package com.command.domain.dials

enum class RecoveryOption { RESET, SMALLER, RESCHEDULE }

/**
 * What the user is offered when a start did not happen: one finite, ordered menu, offered at most [maxOffers]
 * times per occurrence. Never an endless loop; silence ends in UNCONFIRMED.
 */
data class RecoveryPolicy(
    val menu: List<RecoveryOption>,
    val maxOffers: Int,
    val rescheduleMinutes: List<Int>,
    val offerTomorrow: Boolean,
) {
    companion object {
        fun forStructure(structure: Int): RecoveryPolicy = when (structure.coerceIn(1, 5)) {
            1 -> RecoveryPolicy(listOf(RecoveryOption.RESET), 1, emptyList(), false)
            2 -> RecoveryPolicy(listOf(RecoveryOption.RESET, RecoveryOption.RESCHEDULE), 1, listOf(30, 60), false)
            3 -> RecoveryPolicy(listOf(RecoveryOption.RESET, RecoveryOption.SMALLER, RecoveryOption.RESCHEDULE), 2, listOf(15, 30, 60), false)
            4 -> RecoveryPolicy(listOf(RecoveryOption.RESET, RecoveryOption.SMALLER, RecoveryOption.RESCHEDULE), 2, listOf(15, 30, 60), true)
            else -> RecoveryPolicy(listOf(RecoveryOption.SMALLER, RecoveryOption.RESET, RecoveryOption.RESCHEDULE), 2, listOf(15, 30, 60), true)
        }
    }
}
