package com.command.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "Paused until 3:42 PM", with the day when the end is not today. A pause lasts a day at most, so it is today or the next day. */
@Composable
fun pauseTitle(untilUtcMs: Long, zone: ZoneId, today: LocalDate): String {
    val local = Instant.ofEpochMilli(untilUtcMs).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    return if (local.toLocalDate() == today) {
        stringResource(R.string.pause_title_today, time)
    } else {
        stringResource(R.string.pause_title_other, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local), time)
    }
}
