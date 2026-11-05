package com.command.domain.time

import java.time.ZoneId

/**
 * Injectable time source. Nothing in the domain reads the system clock directly, so every rule that depends on
 * "now" is testable with a fake. On Android the real implementation wraps `System.currentTimeMillis()` and
 * `SystemClock.elapsedRealtime()`.
 */
interface Clock {
    /** Wall-clock time in epoch milliseconds (UTC). */
    fun nowMs(): Long

    /** Monotonic milliseconds since boot; unaffected by wall-clock changes. */
    fun elapsedRealtimeMs(): Long

    /** The device's current time zone. */
    fun zone(): ZoneId
}

/** JVM clock for tools and tests that do not need a fake. */
class SystemClock : Clock {
    private val startNanos = System.nanoTime()
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = (System.nanoTime() - startNanos) / 1_000_000
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
