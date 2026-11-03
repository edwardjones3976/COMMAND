package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DailyHandshakeRecord
import com.example.data.HourlyHandshakeBucket
import com.example.data.StreakChartTelemetry
import com.example.data.StreakChartViewMode
import com.example.data.StreakProfileEntity
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.CommandCrimson
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.StreakFlameOrange
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandStreakChartCard(
    streakProfile: StreakProfileEntity,
    telemetry: StreakChartTelemetry,
    onSelectDay: (Int) -> Unit,
    onToggleViewMode: () -> Unit,
    onNavigateToStreakVault: () -> Unit,
    onBuyBackWithCredits: () -> Unit,
    onStartBriefingBuyBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMissed = streakProfile.isStreakMissed
    val selectedDay = telemetry.selectedDayIndex?.let { idx ->
        telemetry.weeklyRecords.getOrNull(idx)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("command_streak_chart_card"),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = 1.5.dp,
            brush = Brush.horizontalGradient(
                if (isMissed) {
                    listOf(CommandCrimson, CommandAmber)
                } else {
                    listOf(StreakFlameOrange, CommandAmber, MissionEmerald)
                }
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Title, Live Status, Week-over-Week Momentum, and View Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isMissed) CommandCrimson.copy(alpha = 0.2f)
                                    else StreakFlameOrange.copy(alpha = 0.2f)
                                )
                        ) {
                            Icon(
                                imageVector = if (isMissed) Icons.Default.WarningAmber else Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = if (isMissed) CommandCrimson else StreakFlameOrange,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "COMMAND STREAKS",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isMissed) CommandCrimson else CommandAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isMissed) {
                            "Streak Broken • Buy Back ${streakProfile.recoverableStreakDays}d"
                        } else {
                            "${streakProfile.currentStreakDays} Consecutive Days Verified"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Updated live from mission handshakes",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelTextSecondary
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Week-over-Week Momentum Badge (Reference Design: ▲ 106% from last week)
                    Surface(
                        color = MissionEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, MissionEmerald)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                contentDescription = null,
                                tint = MissionEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "+${telemetry.weekOverWeekPercent}% vs last wk",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MissionEmerald
                            )
                        }
                    }

                    // View Mode Segmented Pill (7D Bars ↔ 24H Cadence)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(TacticalSurfaceElevated)
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        StreakChartViewMode.entries.forEach { mode ->
                            val isSelected = telemetry.viewMode == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isSelected) CommandAmber else Color.Transparent)
                                    .clickable { if (!isSelected) onToggleViewMode() }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .testTag("toggle_view_mode_${mode.name.lowercase()}")
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.Black else SteelTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Chart Canvas Section: Either 7-Day Vertical Bars with Avg line OR 24-Hour Time-of-Day Cadence
            when (telemetry.viewMode) {
                StreakChartViewMode.WEEKLY_BARS -> {
                    WeeklyBarChartView(
                        records = telemetry.weeklyRecords,
                        avgPerDay = telemetry.averageHandshakesPerDay,
                        selectedDayIndex = telemetry.selectedDayIndex,
                        onSelectDay = onSelectDay
                    )
                }
                StreakChartViewMode.HOURLY_CADENCE -> {
                    HourlyCadenceChartView(
                        buckets = telemetry.hourlyBuckets
                    )
                }
            }

            // Inline Day Detail Inspector (Opens when a day bar is tapped)
            AnimatedVisibility(visible = selectedDay != null) {
                selectedDay?.let { record ->
                    Surface(
                        color = TacticalSurfaceElevated,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            if (record.isMissedDay) CommandCrimson else CommandAmber
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("day_detail_inspector_drawer")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (record.isMissedDay) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (record.isMissedDay) CommandCrimson else MissionEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${record.dateLabel} Commitment Audit",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Surface(
                                    color = if (record.isMissedDay) CommandCrimson.copy(alpha = 0.2f) else MissionEmerald.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (record.isMissedDay) "0 / ${record.scheduledTarget} (MISSED)" else "${record.verifiedHandshakes} / ${record.scheduledTarget} COMPLETED",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (record.isMissedDay) CommandCrimson else MissionEmerald,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (record.isMissedDay) {
                                Text(
                                    text = "Consecutive streak was interrupted on this day. Use your Command Credits or run a 15-second Tactical Recovery Briefing to restore your streak now:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onBuyBackWithCredits,
                                        colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("chart_buyback_credits_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Bolt,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Buy Back (50 CR)",
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = onStartBriefingBuyBack,
                                        border = BorderStroke(1.dp, MissionEmerald),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("chart_buyback_briefing_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = null,
                                            tint = MissionEmerald,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "15s Briefing",
                                            color = MissionEmerald,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Avg Response Speed",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SteelTextSecondary
                                        )
                                        Text(
                                            text = "${record.avgResponseSeconds} seconds",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = CommandAmber
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Lock Adherence",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SteelTextSecondary
                                        )
                                        Text(
                                            text = "${record.lockedCompletionPercent}% on-time",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MissionEmerald
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Handshake Type",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SteelTextSecondary
                                        )
                                        Text(
                                            text = record.dominantHandshakeType.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                if (record.completedMissionTitles.isNotEmpty()) {
                                    Text(
                                        text = "Verified: ${record.completedMissionTitles.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SteelTextSecondary,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2-Column Modular Telemetry Sub-Cards (Handshake Velocity & Locked Mission Integrity)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModularTelemetrySubCard(
                    title = "Handshake Velocity",
                    metricValue = "${telemetry.avgResponseSecondsOverall}s",
                    metricSubtext = "Fastest: ${telemetry.fastestResponseSeconds}s",
                    icon = Icons.Default.Speed,
                    accentColor = CommandAmber,
                    modifier = Modifier.weight(1f),
                    sparkFractions = listOf(0.4f, 0.7f, 0.5f, 0.9f, 0.8f, 0.6f, 0.95f)
                )

                ModularTelemetrySubCard(
                    title = "Locked Integrity",
                    metricValue = "${telemetry.lockedAdherencePercent}%",
                    metricSubtext = "${telemetry.totalHandshakesThisWeek} verified handshakes",
                    icon = Icons.Default.Lock,
                    accentColor = MissionEmerald,
                    modifier = Modifier.weight(1f),
                    sparkFractions = listOf(0.85f, 0.9f, 0.75f, 0.95f, 0.9f, 1.0f, 0.94f)
                )
            }

            // Footer Link: Full Streak Vault Navigation (Reference Design: "See All Activity >")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToStreakVault() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "See All Streak Milestones & Buy-Back Vault",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Navigate to Streak Vault",
                    tint = CommandAmber,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun WeeklyBarChartView(
    records: List<DailyHandshakeRecord>,
    avgPerDay: Float,
    selectedDayIndex: Int?,
    onSelectDay: (Int) -> Unit
) {
    val maxTarget = 5f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(TacticalSurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("weekly_streak_bar_chart")
    ) {
        // Average Reference Dashed Line (Reference: Wellness Dashboard Red Dashed "Avg" Line)
        val avgFraction = (avgPerDay / maxTarget).coerceIn(0.1f, 0.95f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val yPos = size.height * (1f - avgFraction)
            drawLine(
                color = Color(0xFFFF5252).copy(alpha = 0.75f),
                start = Offset(0f, yPos),
                end = Offset(size.width - 70f, yPos),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            )
        }

        // "Avg" Label Callout on the right of the dashed line
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = ((1f - avgFraction) * 120).dp)
        ) {
            Surface(
                color = Color(0xFFFF5252).copy(alpha = 0.18f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "Avg ${avgPerDay}/d",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        // 7 Vertical Columns (S, M, T, W, T, F, S)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            records.forEach { record ->
                val isSelected = selectedDayIndex == record.dayIndex
                val targetFraction = (record.scheduledTarget.toFloat() / maxTarget).coerceIn(0.2f, 1f)
                val fillFraction = if (record.scheduledTarget > 0) {
                    (record.verifiedHandshakes.toFloat() / record.scheduledTarget.toFloat()).coerceIn(0f, 1f)
                } else 0f

                val animatedFill by animateFloatAsState(
                    targetValue = fillFraction,
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                    label = "bar_fill_${record.dayIndex}"
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelectDay(record.dayIndex) }
                        .padding(horizontal = 4.dp)
                        .testTag("streak_bar_${record.dayIndex}")
                ) {
                    // Bar Stack: Background Track (Scheduled target) + Filled Foreground (Verified handshakes)
                    Box(
                        contentAlignment = Alignment.BottomCenter,
                        modifier = Modifier
                            .width(26.dp)
                            .height(105.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) CommandAmber.copy(alpha = 0.25f)
                                else Color.White.copy(alpha = 0.12f)
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else if (record.isMissedDay) 1.dp else 0.dp,
                                color = if (isSelected) CommandAmber else if (record.isMissedDay) CommandCrimson else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                    ) {
                        // Inner filled progress bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(animatedFill * targetFraction)
                                .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp, topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    when {
                                        record.isMissedDay -> CommandCrimson
                                        animatedFill >= 0.99f -> MissionEmerald
                                        animatedFill > 0f -> CommandAmber
                                        else -> Color.Transparent
                                    }
                                )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Day of Week Label: S, M, T, W, T, F, S
                    Text(
                        text = record.dayShortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (record.isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            record.isMissedDay -> CommandCrimson
                            isSelected -> CommandAmber
                            record.isToday -> MissionEmerald
                            else -> SteelTextSecondary
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HourlyCadenceChartView(
    buckets: List<HourlyHandshakeBucket>
) {
    val maxCount = (buckets.maxOfOrNull { it.count } ?: 4).coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(TacticalSurfaceElevated)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("hourly_streak_cadence_chart"),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "24-HOUR EXECUTION DENSITY",
                style = MaterialTheme.typography.labelSmall,
                color = MissionEmerald,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Peak: 9 AM & 5 PM",
                style = MaterialTheme.typography.labelSmall,
                color = SteelTextSecondary
            )
        }

        // 24 Micro-Bars across the day (Reference: Apple Fitness Step Count card)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            buckets.forEach { bucket ->
                val fraction = (bucket.count.toFloat() / maxCount.toFloat()).coerceIn(0.06f, 1f)
                val animatedHeight by animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
                    label = "hour_bar_${bucket.hour}"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(animatedHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (bucket.count > 0) {
                                if (bucket.hour in 8..17) MissionEmerald else CommandAmber
                            } else {
                                Color.White.copy(alpha = 0.08f)
                            }
                        )
                )
            }
        }

        // Grid Labels at 12 AM, 6 AM, 12 PM, 6 PM (Matching Apple Fitness card design)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "12 AM", style = MaterialTheme.typography.labelSmall, color = SteelTextSecondary, fontSize = 10.sp)
            Text(text = "6 AM", style = MaterialTheme.typography.labelSmall, color = SteelTextSecondary, fontSize = 10.sp)
            Text(text = "12 PM", style = MaterialTheme.typography.labelSmall, color = SteelTextSecondary, fontSize = 10.sp)
            Text(text = "6 PM", style = MaterialTheme.typography.labelSmall, color = SteelTextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ModularTelemetrySubCard(
    title: String,
    metricValue: String,
    metricSubtext: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    sparkFractions: List<Float>,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelTextSecondary
                )
            }

            Text(
                text = metricValue,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = metricSubtext,
                style = MaterialTheme.typography.labelSmall,
                color = SteelTextSecondary
            )

            // Mini 7-bar sparkline visualizer inside the sub-card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                sparkFractions.forEach { fraction ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accentColor.copy(alpha = 0.8f))
                    )
                }
            }
        }
    }
}
