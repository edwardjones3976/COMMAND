package com.command.data

import com.command.data.db.CommandDb
import com.command.domain.schedule.RestWindow
import com.command.domain.schedule.RestWindows
import com.command.domain.time.Clock
import com.command.domain.voice.QuietWindowPolicy
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

/** The voice engine's quiet window: the rest windows the person saved, read when the line is about to play. A row that cannot be read is no window. */
class RestWindowQuietPolicy @Inject constructor(private val db: CommandDb, private val clock: Clock) : QuietWindowPolicy {
    override suspend fun isQuiet(atUtcMs: Long): Boolean {
        val windows = db.restWindows().all().mapNotNull { row ->
            runCatching {
                RestWindow(DayOfWeek.of(row.weekday), LocalTime.parse(row.startLocal), LocalTime.parse(row.endLocal), row.kind)
            }.getOrNull()
        }
        return RestWindows.contains(windows, atUtcMs, clock.zone())
    }
}
