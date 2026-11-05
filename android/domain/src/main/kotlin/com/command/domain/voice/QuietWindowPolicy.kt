package com.command.domain.voice

import com.command.domain.schedule.RestWindow
import com.command.domain.schedule.RestWindows
import java.time.ZoneId

/** Whether the person is inside a time they asked to be left in peace, which is when a preview they asked to have ducked is ducked. */
fun interface QuietWindowPolicy {
    suspend fun isQuiet(atUtcMs: Long): Boolean

    companion object {
        val NEVER = QuietWindowPolicy { false }

        /** The person's own rest windows, read each time they are asked about, in the zone the phone is in then. */
        fun of(windows: suspend () -> List<RestWindow>, zone: () -> ZoneId) =
            QuietWindowPolicy { atUtcMs -> RestWindows.contains(windows(), atUtcMs, zone()) }
    }
}
