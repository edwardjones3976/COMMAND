package com.command.domain.occurrence

import kotlin.math.ceil

/**
 * The optional starter, as `BeginStarter` wrote it down: when it began and when it ends. All the countdown knows comes from these two and the
 * time, so a screen that is drawn again, or a process that restarts, shows the same thing.
 *
 * The times are wall-clock times. A phone clock set back cannot make the countdown longer than the starter was; one set forward ends it early.
 */
data class StarterWindow(val startUtc: Long, val deadlineUtc: Long) {
    /** The starter's whole length in minutes, as it was when it began, whatever the mission says now. */
    val minutes: Int get() = ((deadlineUtc - startUtc) / MINUTE_MS).toInt()

    /** What is left, in whole seconds rounded up: a full clock when it begins, and zero only when it is over. */
    fun remainingSeconds(nowMs: Long): Int =
        ceil((deadlineUtc - nowMs).coerceIn(0, deadlineUtc - startUtc) / 1000.0).toInt()

    fun pastMidpoint(nowMs: Long): Boolean = nowMs >= startUtc + (deadlineUtc - startUtc) / 2

    fun isOver(nowMs: Long): Boolean = nowMs >= deadlineUtc

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
