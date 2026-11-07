package com.command.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.command.R
import java.time.DayOfWeek

/** The days of the week and their short names, Monday first. */
internal val WEEK_DAYS = listOf(
    DayOfWeek.MONDAY to R.string.editor_day_mon,
    DayOfWeek.TUESDAY to R.string.editor_day_tue,
    DayOfWeek.WEDNESDAY to R.string.editor_day_wed,
    DayOfWeek.THURSDAY to R.string.editor_day_thu,
    DayOfWeek.FRIDAY to R.string.editor_day_fri,
    DayOfWeek.SATURDAY to R.string.editor_day_sat,
    DayOfWeek.SUNDAY to R.string.editor_day_sun,
)

/** Some days in week order, by their short names: "Mon, Tue". */
@Composable
internal fun dayNames(days: Set<DayOfWeek>): String = WEEK_DAYS.filter { (day, _) -> day in days }.map { (_, label) -> stringResource(label) }.joinToString(", ")
