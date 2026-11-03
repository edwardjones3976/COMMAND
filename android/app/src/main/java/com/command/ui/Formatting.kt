package com.command.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A moment in the person's own words: "Today, 4:00 AM", "Tomorrow, 4:00 AM", or the date and time. */
@Composable
fun whenLabel(atUtcMs: Long, zone: ZoneId, today: LocalDate): String {
    val local = Instant.ofEpochMilli(atUtcMs).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    return when (local.toLocalDate()) {
        today -> stringResource(R.string.schedule_when_today, time)
        today.plusDays(1) -> stringResource(R.string.schedule_when_tomorrow, time)
        else -> stringResource(R.string.schedule_when_other, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local), time)
    }
}
