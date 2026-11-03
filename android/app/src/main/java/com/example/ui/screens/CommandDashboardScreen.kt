package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.DEFAULT_PERSONALITIES
import com.example.data.HandshakeType
import com.example.data.MILESTONE_BADGES
import com.example.data.MissionEntity
import com.example.data.MissionStatus
import com.example.data.StreakChartTelemetry
import com.example.data.StreakProfileEntity
import com.example.data.VoicePackEntity
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.CommandCrimson
import com.example.ui.theme.DrillOlive
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.StreakFlameOrange
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CommandDashboardScreen(
    missions: List<MissionEntity>,
    voicePacks: List<VoicePackEntity>,
    streakProfile: StreakProfileEntity,
    streakChart: StreakChartTelemetry,
    calendarSyncSummary: String,
    onTriggerReveille: (MissionEntity) -> Unit,
    onToggleMissionLock: (MissionEntity) -> Unit,
    onSyncGoogleCalendar: () -> Unit,
    onResolveConflict: (MissionEntity, String) -> Unit,
    onCreateMission: (String, String, String, Int, Int, HandshakeType, Boolean) -> Unit,
    onPreviewActiveDirector: (VoicePackEntity) -> Unit,
    onNavigateToStreakVault: () -> Unit,
    onNavigateToVoiceStudio: () -> Unit,
    onNavigateToCalendarSync: () -> Unit = {},
    onReopenOnboarding: () -> Unit = {},
    onSelectStreakDay: (Int) -> Unit = {},
    onToggleStreakViewMode: () -> Unit = {},
    onBuyBackWithCredits: () -> Unit = {},
    onStartBriefingBuyBack: () -> Unit = {},
    presets: List<com.example.data.PersonalityPresetEntity> = emptyList(),
    onSelectPreset: (com.example.data.PersonalityPresetEntity) -> Unit = {},
    onNavigateToPresetStudio: () -> Unit = {},
    isSpeaking: Boolean = false,
    onSelectPersonality: (String) -> Unit = {}
) {
    var showCreateMissionDialog by remember { mutableStateOf(false) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onSyncGoogleCalendar()
    }

    val activePack = voicePacks.find { it.id == streakProfile.selectedVoicePackId }
        ?: voicePacks.firstOrNull()
    val activePersona = DEFAULT_PERSONALITIES.find { it.id == streakProfile.selectedPersonalityId }
        ?: DEFAULT_PERSONALITIES.first()

    val nextMilestone = MILESTONE_BADGES.firstOrNull { streakProfile.currentStreakDays < it.requiredDays }
        ?: MILESTONE_BADGES.last()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("command_dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Modular Command Streaks Bar Chart Component
        item {
            Spacer(modifier = Modifier.height(6.dp))
            CommandStreakChartCard(
                streakProfile = streakProfile,
                telemetry = streakChart,
                onSelectDay = onSelectStreakDay,
                onToggleViewMode = onToggleStreakViewMode,
                onNavigateToStreakVault = onNavigateToStreakVault,
                onBuyBackWithCredits = onBuyBackWithCredits,
                onStartBriefingBuyBack = onStartBriefingBuyBack
            )
        }

        // Active Personality Preset Quick-Switcher
        item {
            com.example.ui.components.PresetQuickSwitcher(
                presets = presets,
                activePresetId = streakProfile.activeCustomPresetId,
                onSelectPreset = onSelectPreset,
                onOpenPresetStudio = onNavigateToPresetStudio
            )
        }

        // Dynamic Animated Coach Avatar Hero Card (Drill Sergeant vs Personal Life Coach)
        item {
            val coachIndividual = com.example.ui.components.resolveCoachIndividual(
                personalityId = streakProfile.selectedPersonalityId,
                voicePackId = activePack?.id ?: streakProfile.selectedVoicePackId
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_director_hero_card"),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(
                    1.dp,
                    if (coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH) MissionEmerald else DrillOlive
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.example.ui.components.AnimatedCoachAvatar(
                            individual = coachIndividual,
                            isSpeaking = isSpeaking,
                            size = 82.dp,
                            showStatusBadge = true
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH)
                                    "PERSONAL & LIFE COACH • ${streakProfile.activePresetName.uppercase()}"
                                else
                                    "MILITARY DRILL SERGEANT • ${streakProfile.activePresetName.uppercase()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH) MissionEmerald else CommandAmber,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${activePersona.name} (${activePack?.actorName ?: "GySgt. Marcus Stone"})",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = activePack?.accentLabel ?: "US Athletic • Kinetic Motivation",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SteelTextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = { activePack?.let(onPreviewActiveDirector) },
                                    modifier = Modifier.testTag("dashboard_preview_voice_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        tint = if (coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH) MissionEmerald else CommandAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (isSpeaking) "Speaking..." else "Hear Voice",
                                        color = if (coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH) MissionEmerald else CommandAmber
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH) {
                                            onSelectPersonality("drill_sergeant")
                                        } else {
                                            onSelectPersonality("supportive_coach")
                                        }
                                    },
                                    modifier = Modifier.testTag("dashboard_toggle_coach_button")
                                ) {
                                    Text(
                                        if (coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH)
                                            "Switch to Drill Sgt"
                                        else
                                            "Switch to Life Coach"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Google Calendar Sync & Conflict Detection Bar
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToCalendarSync() }
                    .testTag("google_calendar_sync_card"),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = MissionEmerald,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "READ-ONLY GOOGLE CALENDAR SYNC • TAP FOR GUIDE",
                                style = MaterialTheme.typography.labelLarge,
                                color = MissionEmerald
                            )
                            Text(
                                text = calendarSyncSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SteelTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald),
                        modifier = Modifier.testTag("sync_google_calendar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Sync",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Section Header + Add Mission Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SCHEDULED COMMITMENTS (${missions.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandAmber
                    )
                    Text(
                        text = "Tap 'Launch Reveille' on any mission to validate the full loop",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )
                }

                Button(
                    onClick = { showCreateMissionDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                    modifier = Modifier.testTag("new_mission_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "New Mission",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        items(missions, key = { it.id }) { mission ->
            MissionCardItem(
                mission = mission,
                presets = presets,
                onTriggerReveille = { onTriggerReveille(mission) },
                onToggleLock = { onToggleMissionLock(mission) },
                onResolveConflict = { action -> onResolveConflict(mission, action) },
                onNavigateToCalendar = onNavigateToCalendarSync
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showCreateMissionDialog) {
        CreateMissionDialog(
            onDismiss = { showCreateMissionDialog = false },
            onCreate = { title, category, prompt, minsFromNow, duration, handshake, locked ->
                onCreateMission(title, category, prompt, minsFromNow, duration, handshake, locked)
                showCreateMissionDialog = false
            }
        )
    }
}

@Composable
private fun MissionCardItem(
    mission: MissionEntity,
    presets: List<com.example.data.PersonalityPresetEntity> = emptyList(),
    onTriggerReveille: () -> Unit,
    onToggleLock: () -> Unit,
    onResolveConflict: (String) -> Unit,
    onNavigateToCalendar: () -> Unit = {}
) {
    val formatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeText = formatter.format(Date(mission.scheduledEpochMs))
    val statusEnum = runCatching { MissionStatus.valueOf(mission.status) }
        .getOrDefault(MissionStatus.SCHEDULED)
    val handshakeEnum = runCatching { HandshakeType.valueOf(mission.handshakeType) }
        .getOrDefault(HandshakeType.HOLD)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mission_card_${mission.id}"),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                mission.hasConflict -> CommandCrimson
                statusEnum == MissionStatus.COMPLETED -> MissionEmerald
                mission.isMissionLocked -> CommandAmber
                else -> MaterialTheme.colorScheme.outline
            }
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = CommandAmber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = CommandAmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$timeText • ${mission.durationMinutes}m",
                                style = MaterialTheme.typography.labelLarge,
                                color = CommandAmber
                            )
                        }
                    }

                    if (mission.isFromGoogleCalendar) {
                        Surface(
                            color = MissionEmerald.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "G-Cal Sync",
                                style = MaterialTheme.typography.labelMedium,
                                color = MissionEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
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
                                    text = mission.recurrenceRule ?: "Recurring",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CommandAmber
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.testTag("toggle_lock_${mission.id}")
                ) {
                    Icon(
                        imageVector = if (mission.isMissionLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Toggle Mission Lock",
                        tint = if (mission.isMissionLocked) CommandAmber else SteelTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = mission.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "First 5m Starter: \"${mission.firstActionPrompt}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = SteelTextSecondary
            )

            // Conflict Resolution Box if Google Calendar detected an overlap
            if (mission.hasConflict) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = CommandCrimson.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CommandCrimson),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = CommandCrimson,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CALENDAR SYNC CONFLICT DETECTED",
                                style = MaterialTheme.typography.labelLarge,
                                color = CommandCrimson
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mission.conflictDescription ?: "Overlapping commitment window",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onNavigateToCalendar,
                                modifier = Modifier.testTag("resolve_merge_${mission.id}"),
                                border = BorderStroke(1.dp, CommandAmber)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = CommandAmber,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Merge Cards", color = CommandAmber, style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = { onResolveConflict("RESCHEDULE_30") },
                                modifier = Modifier.testTag("resolve_shift_${mission.id}")
                            ) {
                                Text("Shift +30m", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = { onResolveConflict("LOCK_PRIORITY") },
                                modifier = Modifier.testTag("resolve_lock_${mission.id}")
                            ) {
                                Text("Lock Priority", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = TacticalSurfaceElevated,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "Handshake: ${handshakeEnum.displayName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SteelTextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    val assignedPreset = presets.find { it.id == mission.presetId }
                    Surface(
                        color = if (mission.presetId != null) CommandAmber.copy(alpha = 0.2f) else TacticalSurfaceElevated,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = if (assignedPreset != null) "Preset: ${assignedPreset.name}" else "Preset: Auto-Routed",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (mission.presetId != null) CommandAmber else SteelTextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    Surface(
                        color = if (statusEnum == MissionStatus.COMPLETED) {
                            MissionEmerald.copy(alpha = 0.2f)
                        } else {
                            TacticalSurfaceElevated
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (statusEnum == MissionStatus.COMPLETED) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MissionEmerald,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = statusEnum.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (statusEnum == MissionStatus.COMPLETED) MissionEmerald else CommandAmber
                            )
                        }
                    }
                }

                Button(
                    onClick = onTriggerReveille,
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                    modifier = Modifier.testTag("launch_reveille_button_${mission.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.AlarmOn,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Launch Reveille",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateMissionDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Int, Int, HandshakeType, Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Career Work") }
    var firstAction by remember { mutableStateOf("Open the project and complete the first 5 minutes.") }
    var handshakeType by remember { mutableStateOf(HandshakeType.HOLD) }
    var missionLocked by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        title = {
            Text(
                text = "Schedule Command Commitment",
                style = MaterialTheme.typography.titleLarge,
                color = CommandAmber
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Mission Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_mission_title_input")
                )
                OutlinedTextField(
                    value = firstAction,
                    onValueChange = { firstAction = it },
                    label = { Text("First 5-Minute Action Directive") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_mission_action_input")
                )

                Text(
                    text = "Reveille Handshake Method:",
                    style = MaterialTheme.typography.labelMedium,
                    color = SteelTextSecondary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HandshakeType.entries.forEach { type ->
                        FilterChip(
                            selected = handshakeType == type,
                            onClick = { handshakeType = type },
                            label = { Text(type.displayName) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Enable Mission Lock Protection",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = missionLocked,
                        onCheckedChange = { missionLocked = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(title, category, firstAction, 10, 45, handshakeType, missionLocked)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald),
                modifier = Modifier.testTag("confirm_create_mission_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Schedule Mission", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
