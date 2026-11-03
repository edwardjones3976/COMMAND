package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.MissionEntity
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.CommandCrimson
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CalendarSyncScreen(
    missions: List<MissionEntity>,
    calendarSyncSummary: String,
    onSyncGoogleCalendar: () -> Unit,
    onResolveConflict: (MissionEntity, String) -> Unit,
    onTriggerReveille: (MissionEntity) -> Unit,
    isBackgroundSyncActive: Boolean = true,
    backgroundSyncIntervalMinutes: Int = 120,
    lastBackgroundSyncTimestamp: Long = 0L,
    onTriggerBackgroundService: () -> Unit = {},
    onTogglePeriodicBackgroundSync: (Boolean, Int) -> Unit = { _, _ -> },
    onUpdateSyncInterval: (Int) -> Unit = {},
    onConsolidateMissions: ((MissionEntity, MissionEntity) -> Unit)? = null,
    onPrioritizePrimary: ((MissionEntity, MissionEntity) -> Unit)? = null,
    onShiftSecondary: ((MissionEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasReadCalendarPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var permissionDeniedOnce by remember { mutableStateOf(false) }

    // Refresh permission badge automatically if user returns from Android App Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CALENDAR
                ) == PackageManager.PERMISSION_GRANTED
                if (granted && !hasReadCalendarPermission) {
                    hasReadCalendarPermission = true
                    onSyncGoogleCalendar()
                } else {
                    hasReadCalendarPermission = granted
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val readCalendarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasReadCalendarPermission = isGranted
        if (isGranted) {
            permissionDeniedOnce = false
            onSyncGoogleCalendar()
        } else {
            permissionDeniedOnce = true
        }
    }

    val calendarMissions = remember(missions) {
        missions.filter { it.isFromGoogleCalendar || it.isRecurring }
    }
    val conflictedMissions = remember(missions) {
        missions.filter { it.hasConflict }
    }
    val conflictClusters = remember(missions) { findConflictClusters(missions) }
    val clusteredIds = remember(conflictClusters) {
        conflictClusters.flatMap { listOf(it.primary.id, it.secondary.id) }.toSet()
    }
    val unclusteredConflicts = remember(missions, clusteredIds) {
        missions.filter { it.hasConflict && it.id !in clusteredIds }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("calendar_sync_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header & Permission Status Badge
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GOOGLE CALENDAR PROVIDER • READ-ONLY BRIDGE",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandAmber
                    )
                    Text(
                        text = "Recurring Commitment Sync & Permission Guide",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                Surface(
                    color = if (hasReadCalendarPermission) {
                        MissionEmerald.copy(alpha = 0.18f)
                    } else {
                        CommandAmber.copy(alpha = 0.18f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (hasReadCalendarPermission) MissionEmerald else CommandAmber
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.testTag("calendar_permission_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (hasReadCalendarPermission) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Shield
                            },
                            contentDescription = null,
                            tint = if (hasReadCalendarPermission) MissionEmerald else CommandAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (hasReadCalendarPermission) "READ_CALENDAR GRANTED" else "PERMISSION REQUIRED",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (hasReadCalendarPermission) MissionEmerald else CommandAmber
                        )
                    }
                }
            }
        }

        // Dedicated READ_CALENDAR Permission Request & Guidance Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("read_calendar_guidance_card"),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(
                    width = 1.5.dp,
                    color = if (hasReadCalendarPermission) MissionEmerald else CommandAmber
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(CommandAmber.copy(alpha = 0.18f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.PrivacyTip,
                                contentDescription = null,
                                tint = CommandAmber,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "WHY COMMAND REQUESTS 'READ_CALENDAR'",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CommandAmber
                            )
                            Text(
                                text = "Strict Read-Only Access • Zero Calendar Modifications",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SteelTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    PermissionReasonRow(
                        number = "01",
                        title = "Automatic Recurring Commitment Expansion (RRULE)",
                        description = "Reads your Android Calendar Provider (CalendarContract.Instances) over the next 7 days so daily standups, workouts, and deep-work blocks automatically become executable COMMAND missions with a 5-minute action starter."
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    PermissionReasonRow(
                        number = "02",
                        title = "Pre-Commitment Buffer & Conflict Guard",
                        description = "Detects when an external meeting overlaps or cuts within 15 minutes of a locked COMMAND mission so you can shift +30m or lock priority before Reveille sounds."
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    PermissionReasonRow(
                        number = "03",
                        title = "100% On-Device Read-Only Privacy Guarantee",
                        description = "COMMAND only requests android.permission.READ_CALENDAR. It never requests WRITE_CALENDAR, never alters or deletes your Google Calendar events, and never uploads your calendar data off your device."
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (!hasReadCalendarPermission) {
                        Button(
                            onClick = {
                                readCalendarLauncher.launch(Manifest.permission.READ_CALENDAR)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("grant_read_calendar_permission_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Grant Read-Only Calendar Access (READ_CALENDAR)",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        if (permissionDeniedOnce) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = CommandCrimson.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, CommandCrimson),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Permission was declined. You can grant 'Calendar' access anytime in Android App Settings, or continue testing with the built-in recurring calendar preview.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.fromParts("package", context.packageName, null)
                                                )
                                                context.startActivity(intent)
                                            },
                                            border = BorderStroke(1.dp, CommandAmber),
                                            modifier = Modifier.testTag("open_app_settings_calendar_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = null,
                                                tint = CommandAmber,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Open App Settings", color = CommandAmber)
                                        }

                                        OutlinedButton(
                                            onClick = onSyncGoogleCalendar,
                                            border = BorderStroke(1.dp, MissionEmerald),
                                            modifier = Modifier.testTag("sync_fallback_calendar_button")
                                        ) {
                                            Text("Run Recurring Preview Sync", color = MissionEmerald)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onSyncGoogleCalendar,
                                colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("sync_recurring_events_now_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = Color.Black
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Sync Recurring Google Calendar Events",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live Sync Status Summary Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurfaceElevated),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = MissionEmerald,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CALENDAR PROVIDER SYNC TELEMETRY",
                            style = MaterialTheme.typography.labelMedium,
                            color = MissionEmerald
                        )
                        Text(
                            text = calendarSyncSummary,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Conflict Inspector Section (Unified Merged Cards)
        if (conflictClusters.isNotEmpty() || unclusteredConflicts.isNotEmpty()) {
            val totalCollisions = conflictClusters.size + unclusteredConflicts.size
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DETECTED SCHEDULE CONFLICTS ($totalCollisions)",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandCrimson
                    )
                    Surface(
                        color = CommandCrimson.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "UNIFIED COMPARISON",
                            style = MaterialTheme.typography.labelSmall,
                            color = CommandCrimson,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            items(conflictClusters, key = { "merged_cluster_${it.primary.id}_${it.secondary.id}" }) { cluster ->
                MergedConflictCard(
                    cluster = cluster,
                    onConsolidate = {
                        onConsolidateMissions?.invoke(cluster.primary, cluster.secondary)
                    },
                    onShiftSecondary = {
                        onShiftSecondary?.invoke(cluster.secondary)
                            ?: onResolveConflict(cluster.secondary, "RESCHEDULE_30")
                    },
                    onPrioritizePrimary = {
                        onPrioritizePrimary?.invoke(cluster.primary, cluster.secondary)
                            ?: onResolveConflict(cluster.primary, "LOCK_PRIORITY")
                    }
                )
            }

            items(unclusteredConflicts, key = { "conflict_${it.id}" }) { mission ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("single_conflict_card_${mission.id}"),
                    colors = CardDefaults.cardColors(containerColor = CommandCrimson.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, CommandCrimson),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = CommandCrimson,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = mission.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mission.conflictDescription ?: "Overlapping Google Calendar window detected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SteelTextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onResolveConflict(mission, "RESCHEDULE_30") },
                                border = BorderStroke(1.dp, CommandAmber)
                            ) {
                                Text("Shift +30m", color = CommandAmber)
                            }
                            OutlinedButton(
                                onClick = { onResolveConflict(mission, "LOCK_PRIORITY") },
                                border = BorderStroke(1.dp, MissionEmerald)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MissionEmerald,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Lock Priority", color = MissionEmerald)
                            }
                        }
                    }
                }
            }
        }

        // Mapped Recurring Commitments List
        item {
            Text(
                text = "MAPPED RECURRING CALENDAR MISSIONS (${calendarMissions.size})",
                style = MaterialTheme.typography.labelLarge,
                color = CommandAmber
            )
        }

        items(calendarMissions, key = { "gcal_${it.id}" }) { mission ->
            val formatter = remember { SimpleDateFormat("EEE h:mm a", Locale.getDefault()) }
            val timeStr = formatter.format(Date(mission.scheduledEpochMs))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("synced_calendar_mission_${mission.id}"),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = MissionEmerald.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "$timeStr • ${mission.durationMinutes}m",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MissionEmerald,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            if (mission.isRecurring) {
                                Surface(
                                    color = CommandAmber.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Repeat,
                                            contentDescription = null,
                                            tint = CommandAmber,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = mission.recurrenceRule ?: "Recurring Series",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = CommandAmber
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { onTriggerReveille(mission) },
                            border = BorderStroke(1.dp, CommandAmber)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AlarmOn,
                                contentDescription = null,
                                tint = CommandAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Reveille", color = CommandAmber)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = mission.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Mapped 5m Starter: \"${mission.firstActionPrompt}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionReasonRow(
    number: String,
    title: String,
    description: String
) {
    Surface(
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = CommandAmber.copy(alpha = 0.22f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelLarge,
                    color = CommandAmber,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SteelTextSecondary
                )
            }
        }
    }
}

data class ConflictCluster(
    val primary: MissionEntity,
    val secondary: MissionEntity,
    val overlapMinutes: Int
)

fun findConflictClusters(allMissions: List<MissionEntity>): List<ConflictCluster> {
    val result = mutableListOf<ConflictCluster>()
    val seenPairKeys = mutableSetOf<String>()

    val conflictMissions = allMissions.filter { it.hasConflict }
    val sortedPool = allMissions.sortedBy { it.scheduledEpochMs }

    for (cMission in conflictMissions) {
        val partner = sortedPool.firstOrNull { other ->
            if (other.id == cMission.id) return@firstOrNull false
            val startA = cMission.scheduledEpochMs
            val endA = startA + cMission.durationMinutes * 60_000L
            val startB = other.scheduledEpochMs
            val endB = startB + other.durationMinutes * 60_000L
            val isOverlapping = startA < endB && startB < endA
            val isReferenced = (cMission.conflictDescription?.contains(other.title, ignoreCase = true) == true) ||
                    (other.conflictDescription?.contains(cMission.title, ignoreCase = true) == true)
            isOverlapping || isReferenced
        }

        if (partner != null) {
            val (first, second) = if (cMission.scheduledEpochMs <= partner.scheduledEpochMs) {
                Pair(cMission, partner)
            } else {
                Pair(partner, cMission)
            }
            val key = "${minOf(first.id, second.id)}_${maxOf(first.id, second.id)}"
            if (key !in seenPairKeys) {
                seenPairKeys.add(key)
                val startA = first.scheduledEpochMs
                val endA = startA + first.durationMinutes * 60_000L
                val startB = second.scheduledEpochMs
                val endB = startB + second.durationMinutes * 60_000L
                val overlapMs = if (startA < endB && startB < endA) {
                    minOf(endA, endB) - maxOf(startA, startB)
                } else {
                    0L
                }
                val overlapMin = maxOf(15, (overlapMs / 60_000L).toInt())
                result.add(ConflictCluster(primary = first, secondary = second, overlapMinutes = overlapMin))
            }
        }
    }
    return result
}

@Composable
fun MergedConflictCard(
    cluster: ConflictCluster,
    onConsolidate: () -> Unit,
    onShiftSecondary: () -> Unit,
    onPrioritizePrimary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val startA = cluster.primary.scheduledEpochMs
    val endA = startA + cluster.primary.durationMinutes * 60_000L
    val startB = cluster.secondary.scheduledEpochMs
    val endB = startB + cluster.secondary.durationMinutes * 60_000L

    val minStart = minOf(startA, startB)
    val maxEnd = maxOf(endA, endB)
    val combinedDurationMinutes = maxOf(15, ((maxEnd - minStart) / 60_000L).toInt())

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("merged_conflict_card"),
        colors = CardDefaults.cardColors(containerColor = CommandCrimson.copy(alpha = 0.08f)),
        border = BorderStroke(1.5.dp, CommandCrimson),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Warning badge & collision timing
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "Schedule Conflict Warning",
                        tint = CommandCrimson,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "SCHEDULE OVERLAP DETECTED",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = CommandCrimson
                        )
                        Text(
                            text = "Unified Comparison • Two Colliding Commitments",
                            style = MaterialTheme.typography.bodySmall,
                            color = SteelTextSecondary
                        )
                    }
                }

                Surface(
                    color = CommandCrimson.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CommandCrimson.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = "${cluster.overlapMinutes}M OVERLAP",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CommandCrimson,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Unified Timeline Comparison Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TacticalSurfaceElevated, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                // Event 1 (Plan A)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            color = CommandAmber.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "PLAN A",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = CommandAmber,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = cluster.primary.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${timeFormatter.format(Date(startA))} - ${timeFormatter.format(Date(endA))} (${cluster.primary.durationMinutes}m)",
                            style = MaterialTheme.typography.labelSmall,
                            color = CommandAmber,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = cluster.primary.firstActionPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = SteelTextSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                )

                // Overlap Collision Indicator Strip
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = CommandCrimson.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CommandCrimson.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = CommandCrimson,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "↕ DIRECT COLLISION: ${timeFormatter.format(Date(maxOf(startA, startB)))} — ${timeFormatter.format(Date(minOf(endA, endB)))} (${cluster.overlapMinutes} MIN)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CommandCrimson
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Event 2 (Plan B)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            color = MissionEmerald.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "PLAN B",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MissionEmerald,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = cluster.secondary.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${timeFormatter.format(Date(startB))} - ${timeFormatter.format(Date(endB))} (${cluster.secondary.durationMinutes}m)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MissionEmerald,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = cluster.secondary.firstActionPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = SteelTextSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Primary Consolidation Button: {Plan A} & {Plan B}
            Button(
                onClick = onConsolidate,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("consolidate_missions_button"),
                colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Consolidate into: '${cluster.primary.title.take(18)}... & ${cluster.secondary.title.take(18)}...'",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "Merge into 1 activity (${combinedDurationMinutes}m • ${timeFormatter.format(Date(minStart))} - ${timeFormatter.format(Date(maxEnd))})",
                            color = Color.Black.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary Quick Actions (Shift +30m, Prioritize Plan A)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onShiftSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("shift_conflict_button"),
                    border = BorderStroke(1.dp, CommandAmber.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = "Shift Plan B +30m",
                        color = CommandAmber,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                OutlinedButton(
                    onClick = onPrioritizePrimary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dismiss_secondary_button"),
                    border = BorderStroke(1.dp, MissionEmerald.copy(alpha = 0.7f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MissionEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Prioritize Plan A",
                        color = MissionEmerald,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

