package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.DEFAULT_PERSONALITIES
import com.example.data.HandshakeType
import com.example.data.RecoveryProtocol
import com.example.data.StreakProfileEntity
import com.example.data.VoiceModelType
import com.example.data.VoicePackEntity
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.DrillOlive
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    voicePacks: List<VoicePackEntity>,
    streakProfile: StreakProfileEntity,
    onSelectPersonality: (String) -> Unit,
    onSelectVoicePack: (VoicePackEntity) -> Unit,
    onPreviewVoicePack: (VoicePackEntity) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSyncGoogleCalendar: () -> Unit,
    onCompleteOnboarding: (
        launchFirstReveille: Boolean,
        firstMissionTitle: String,
        firstMissionAction: String,
        handshakeType: HandshakeType,
        recoveryProtocol: RecoveryProtocol
    ) -> Unit,
    onSkipOnboarding: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    val totalSteps = 5

    var selectedHandshake by rememberSaveable {
        mutableStateOf(
            runCatching { HandshakeType.valueOf(streakProfile.defaultHandshakeType) }
                .getOrDefault(HandshakeType.HOLD)
        )
    }
    var selectedRecovery by rememberSaveable {
        mutableStateOf(
            runCatching { RecoveryProtocol.valueOf(streakProfile.recoveryProtocol) }
                .getOrDefault(RecoveryProtocol.CLEAN_RESET_5M)
        )
    }

    var firstMissionTitle by rememberSaveable {
        mutableStateOf("Deep Work Sprint — Core Architecture")
    }
    var firstMissionAction by rememberSaveable {
        mutableStateOf("Close all distraction tabs, open the primary file, and execute for 5 minutes.")
    }

    var hasCalendarPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCalendarPerm = granted
        if (granted) {
            onSyncGoogleCalendar()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("onboarding_screen"),
        color = ObsidianBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Top Progress & Skip Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PROJECT COMMAND • TACTICAL ONBOARDING",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandAmber
                    )
                    Text(
                        text = "Step ${currentStep + 1} of $totalSteps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )
                }

                TextButton(
                    onClick = onSkipOnboarding,
                    modifier = Modifier.testTag("onboarding_skip_button")
                ) {
                    Text(
                        text = "Skip to Command Center",
                        color = SteelTextSecondary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { (currentStep + 1).toFloat() / totalSteps.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = CommandAmber,
                trackColor = TacticalSurfaceElevated
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Main Step Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (currentStep) {
                    0 -> Step1ExecutionTriad()
                    1 -> Step2PersonalityAndMcrdVoice(
                        voicePacks = voicePacks,
                        streakProfile = streakProfile,
                        onSelectPersonality = onSelectPersonality,
                        onSelectVoicePack = onSelectVoicePack,
                        onPreviewVoicePack = onPreviewVoicePack
                    )
                    2 -> Step3HandshakeAndRecovery(
                        streakProfile = streakProfile,
                        selectedHandshake = selectedHandshake,
                        onSelectHandshake = { selectedHandshake = it },
                        selectedRecovery = selectedRecovery,
                        onSelectRecovery = { selectedRecovery = it },
                        onApplyPreset = onApplyPreset
                    )
                    3 -> Step4CalendarPermissionOnboarding(
                        hasCalendarPerm = hasCalendarPerm,
                        onRequestPermission = {
                            calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                        },
                        onSyncFallback = onSyncGoogleCalendar
                    )
                    4 -> Step5FirstMissionSetup(
                        missionTitle = firstMissionTitle,
                        onTitleChange = { firstMissionTitle = it },
                        firstAction = firstMissionAction,
                        onActionChange = { firstMissionAction = it },
                        selectedHandshake = selectedHandshake,
                        selectedRecovery = selectedRecovery,
                        onCompleteAndLaunchReveille = {
                            onCompleteOnboarding(
                                true,
                                firstMissionTitle,
                                firstMissionAction,
                                selectedHandshake,
                                selectedRecovery
                            )
                        },
                        onCompleteAndEnterDashboard = {
                            onCompleteOnboarding(
                                false,
                                firstMissionTitle,
                                firstMissionAction,
                                selectedHandshake,
                                selectedRecovery
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Navigation Controls
            if (currentStep < totalSteps - 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (currentStep > 0) {
                        OutlinedButton(
                            onClick = { currentStep = (currentStep - 1).coerceAtLeast(0) },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("onboarding_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Previous")
                        }
                    }

                    Button(
                        onClick = { currentStep = (currentStep + 1).coerceAtMost(totalSteps - 1) },
                        colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                        modifier = Modifier
                            .weight(if (currentStep > 0) 1.5f else 1f)
                            .height(50.dp)
                            .testTag("onboarding_next_button")
                    ) {
                        Text(
                            text = "Continue",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Step1ExecutionTriad() {
    var isHoldingDemo by remember { mutableStateOf(false) }
    var demoProgress by remember { mutableFloatStateOf(0f) }
    var demoVerified by remember { mutableStateOf(false) }

    LaunchedEffect(isHoldingDemo) {
        if (isHoldingDemo && !demoVerified) {
            val start = System.currentTimeMillis()
            while (isHoldingDemo && demoProgress < 1f) {
                val elapsed = System.currentTimeMillis() - start
                demoProgress = (elapsed / 1200f).coerceIn(0f, 1f)
                if (demoProgress >= 1f) {
                    demoVerified = true
                    break
                }
                delay(16L)
            }
        } else if (!demoVerified) {
            demoProgress = 0f
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = if (demoVerified) 1f else demoProgress,
        label = "demo_handshake_progress"
    )

    Text(
        text = "How COMMAND Turns Intention Into Action",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = "Passive reminders get swiped away. COMMAND is a consent-driven execution director built around three core mechanisms:",
        style = MaterialTheme.typography.bodyLarge,
        color = SteelTextSecondary
    )

    ConceptTriadCard(
        stepBadge = "1 • SCHEDULED COMMITMENT",
        icon = Icons.Default.Schedule,
        accentColor = CommandAmber,
        title = "A Protected Contract With Your Future Self",
        description = "Every mission pairs a specific start time with a concrete 5-minute opening action. Optional Mission Lock prevents last-minute drift."
    )

    ConceptTriadCard(
        stepBadge = "2 • THE COMMAND REVEILLE",
        icon = Icons.Default.AlarmOn,
        accentColor = MissionEmerald,
        title = "High-Energy Vocal Interception",
        description = "When your commitment window opens, your chosen Director interrupts passive scrolling with a vocal Reveille briefing and tactile cadence."
    )

    ConceptTriadCard(
        stepBadge = "3 • THE HANDSHAKE",
        icon = Icons.Default.TouchApp,
        accentColor = CommandAmber,
        title = "Physical Proof of Readiness → 5-Minute Starter",
        description = "You cannot accidentally swipe away a Reveille. Completing the physical Handshake immediately launches your 5-minute initiation countdown."
    )

    // Interactive Mini Handshake Try-Out Pad
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(
            1.5.dp,
            if (demoVerified) MissionEmerald else CommandAmber
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TRY A MINI HANDSHAKE RIGHT NOW",
                style = MaterialTheme.typography.labelLarge,
                color = if (demoVerified) MissionEmerald else CommandAmber
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(TacticalSurfaceElevated)
                    .pointerInput(demoVerified) {
                        detectTapGestures(
                            onPress = {
                                if (!demoVerified) {
                                    isHoldingDemo = true
                                    tryAwaitRelease()
                                    isHoldingDemo = false
                                }
                            }
                        )
                    }
                    .testTag("onboarding_demo_handshake_pad")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .height(64.dp)
                        .align(Alignment.CenterStart)
                        .background(
                            if (demoVerified) MissionEmerald.copy(alpha = 0.32f)
                            else CommandAmber.copy(alpha = 0.32f)
                        )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (demoVerified) Icons.Default.CheckCircle else Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = if (demoVerified) MissionEmerald else CommandAmber
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (demoVerified) {
                            "HANDSHAKE VERIFIED! READY FOR STEP 2"
                        } else {
                            "PRESS & HOLD FOR 1.2 SECONDS TO TEST"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (demoVerified) MissionEmerald else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step2PersonalityAndMcrdVoice(
    voicePacks: List<VoicePackEntity>,
    streakProfile: StreakProfileEntity,
    onSelectPersonality: (String) -> Unit,
    onSelectVoicePack: (VoicePackEntity) -> Unit,
    onPreviewVoicePack: (VoicePackEntity) -> Unit
) {
    Text(
        text = "Choose Your Director Personality & Voice Model",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = "Test the upgraded MCRD San Diego Pick-Up Day Drill Instructor (with gravelly vocal overdrive DSP) or select one of the other 5 coaching archetypes:",
        style = MaterialTheme.typography.bodyLarge,
        color = SteelTextSecondary
    )

    // Featured Directors: Military Drill Sergeant & Personal/Life Coach
    val mcrdPack = voicePacks.firstOrNull { it.id == "pack_major_stone" } ?: voicePacks.firstOrNull { it.usesDrillSergeantArt }
    val lifeCoachPack = voicePacks.firstOrNull { it.id == "pack_coach_ray" }

    if (mcrdPack != null) {
        val isSelected = streakProfile.selectedVoicePackId == mcrdPack.id
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalSurface),
            border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) CommandAmber else DrillOlive),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.example.ui.components.AnimatedCoachAvatar(
                        individual = com.example.ui.components.CoachAvatarIndividual.DRILL_SERGEANT,
                        size = 76.dp,
                        showStatusBadge = true
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MILITARY DRILL SERGEANT • MCRD OVERDRIVE DSP",
                            style = MaterialTheme.typography.labelMedium,
                            color = CommandAmber,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = mcrdPack.actorName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Grit: ${mcrdPack.vocalGritPercent}% • Aggression: ${mcrdPack.aggressionPercent}% • Stern & Loud",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CommandAmber
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "\"${mcrdPack.sampleQuote}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPreviewVoicePack(mcrdPack) },
                        colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("onboarding_preview_mcrd_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Hear Drill Bark",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onSelectPersonality("drill_sergeant")
                            onSelectVoicePack(mcrdPack)
                        },
                        border = BorderStroke(1.dp, if (isSelected) MissionEmerald else MaterialTheme.colorScheme.outline),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isSelected) "Active Director" else "Use Drill Sergeant", color = if (isSelected) MissionEmerald else Color.White)
                    }
                }
            }
        }
    }

    if (lifeCoachPack != null) {
        val isSelected = streakProfile.selectedVoicePackId == lifeCoachPack.id
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalSurface),
            border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MissionEmerald else MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.example.ui.components.AnimatedCoachAvatar(
                        individual = com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH,
                        size = 76.dp,
                        showStatusBadge = true
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PERSONAL & LIFE COACH • KINETIC MOMENTUM",
                            style = MaterialTheme.typography.labelMedium,
                            color = MissionEmerald,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = lifeCoachPack.actorName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Baritone Projection • Athletic Pacing • No-Excuses Drive",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MissionEmerald
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "\"${lifeCoachPack.sampleQuote}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPreviewVoicePack(lifeCoachPack) },
                        colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("onboarding_preview_coach_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Hear Life Coach",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onSelectPersonality("supportive_coach")
                            onSelectVoicePack(lifeCoachPack)
                        },
                        border = BorderStroke(1.dp, if (isSelected) MissionEmerald else MaterialTheme.colorScheme.outline),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isSelected) "Active Director" else "Use Life Coach", color = if (isSelected) MissionEmerald else Color.White)
                    }
                }
            }
        }
    }

    // 6 Personalities Grid
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SELECT COACHING PERSONALITY",
                style = MaterialTheme.typography.labelLarge,
                color = CommandAmber
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DEFAULT_PERSONALITIES.forEach { persona ->
                    val selected = persona.id == streakProfile.selectedPersonalityId
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPersonality(persona.id) },
                        label = { Text(persona.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CommandAmber.copy(alpha = 0.25f),
                            selectedLabelColor = CommandAmber
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "VOICE MODEL SELECTIONS (${voicePacks.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MissionEmerald
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                voicePacks.forEach { pack ->
                    val isSelected = pack.id == streakProfile.selectedVoicePackId
                    val modelEnum = runCatching { VoiceModelType.valueOf(pack.voiceModelType) }
                        .getOrDefault(VoiceModelType.USMC_MCRD_BARK)
                    Surface(
                        color = if (isSelected) MissionEmerald.copy(alpha = 0.14f) else TacticalSurfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MissionEmerald else MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectVoicePack(pack) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pack.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${modelEnum.label} • ${pack.qualityTierLabel}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CommandAmber
                                )
                            }
                            OutlinedButton(
                                onClick = { onPreviewVoicePack(pack) },
                                border = BorderStroke(1.dp, CommandAmber)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = CommandAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test", color = CommandAmber)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step3HandshakeAndRecovery(
    streakProfile: StreakProfileEntity,
    selectedHandshake: HandshakeType,
    onSelectHandshake: (HandshakeType) -> Unit,
    selectedRecovery: RecoveryProtocol,
    onSelectRecovery: (RecoveryProtocol) -> Unit,
    onApplyPreset: (String) -> Unit
) {
    Text(
        text = "Configure Handshake & Forgiving Recovery",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = "Choose how you verify readiness during Reveille, and how COMMAND helps you recover cleanly without guilt if life interrupts:",
        style = MaterialTheme.typography.bodyLarge,
        color = SteelTextSecondary
    )

    // Handshake Method Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CommandAmber)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DEFAULT REVEILLE HANDSHAKE METHOD",
                style = MaterialTheme.typography.labelLarge,
                color = CommandAmber
            )
            Spacer(modifier = Modifier.height(8.dp))
            HandshakeType.entries.forEach { type ->
                val isSelected = selectedHandshake == type
                Surface(
                    color = if (isSelected) CommandAmber.copy(alpha = 0.16f) else TacticalSurfaceElevated,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) CommandAmber else MaterialTheme.colorScheme.outline
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSelectHandshake(type) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = if (isSelected) CommandAmber else SteelTextSecondary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = type.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = type.shortDesc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SteelTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // Execution Intensity Preset
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "INTENSITY PRESET",
                style = MaterialTheme.typography.labelLarge,
                color = MissionEmerald
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Nudge", "Steady", "Command", "Reveille MAX").forEach { preset ->
                    FilterChip(
                        selected = streakProfile.activePresetName == preset,
                        onClick = { onApplyPreset(preset) },
                        label = { Text(preset) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MissionEmerald.copy(alpha = 0.24f),
                            selectedLabelColor = MissionEmerald
                        )
                    )
                }
            }
        }
    }

    // Forgiving Recovery Protocol
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MissionEmerald)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    tint = MissionEmerald
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "FORGIVING RECOVERY PROTOCOL (ZERO SHAME)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MissionEmerald
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            RecoveryProtocol.entries.forEach { protocol ->
                val isSelected = selectedRecovery == protocol
                Surface(
                    color = if (isSelected) MissionEmerald.copy(alpha = 0.16f) else TacticalSurfaceElevated,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MissionEmerald else MaterialTheme.colorScheme.outline
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSelectRecovery(protocol) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = protocol.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MissionEmerald else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = protocol.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SteelTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Step4CalendarPermissionOnboarding(
    hasCalendarPerm: Boolean,
    onRequestPermission: () -> Unit,
    onSyncFallback: () -> Unit
) {
    Text(
        text = "Connect Read-Only Google Calendar",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = "Grant 'READ_CALENDAR' so COMMAND can automatically map your recurring commitments and warn you about schedule conflicts:",
        style = MaterialTheme.typography.bodyLarge,
        color = SteelTextSecondary
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(1.5.dp, if (hasCalendarPerm) MissionEmerald else CommandAmber),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = if (hasCalendarPerm) MissionEmerald else CommandAmber,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (hasCalendarPerm) {
                            "READ_CALENDAR ACCESS ACTIVE"
                        } else {
                            "WHY READ_CALENDAR PERMISSION IS NEEDED"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (hasCalendarPerm) MissionEmerald else CommandAmber
                    )
                    Text(
                        text = "100% On-Device • Strictly Read-Only • Zero Writes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "• Expands recurring Google Calendar events (RRULE/RDATE) over the next 7 days into ready-to-launch COMMAND missions.\n" +
                    "• Flags overlapping meetings or <15m buffer violations before your Reveille alarm sounds.\n" +
                    "• Never modifies, creates, or deletes any event on your calendar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (!hasCalendarPerm) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("onboarding_grant_calendar_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Grant Read-Only Calendar Permission",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSyncFallback,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Preview With Sample Recurring Schedule Instead")
                }
            } else {
                Surface(
                    color = MissionEmerald.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MissionEmerald
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Read-only Google Calendar access granted and recurring events synced!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MissionEmerald,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Step5FirstMissionSetup(
    missionTitle: String,
    onTitleChange: (String) -> Unit,
    firstAction: String,
    onActionChange: (String) -> Unit,
    selectedHandshake: HandshakeType,
    selectedRecovery: RecoveryProtocol,
    onCompleteAndLaunchReveille: () -> Unit,
    onCompleteAndEnterDashboard: () -> Unit
) {
    Text(
        text = "Schedule Your First Mission",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        text = "Define your first commitment and its 5-minute opening move. You can test the live Reveille & Handshake right now:",
        style = MaterialTheme.typography.bodyLarge,
        color = SteelTextSecondary
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(1.5.dp, CommandAmber),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = missionTitle,
                onValueChange = onTitleChange,
                label = { Text("First Mission Commitment Title") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_mission_title_input")
            )

            OutlinedTextField(
                value = firstAction,
                onValueChange = onActionChange,
                label = { Text("First 5-Minute Action Starter") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_mission_action_input")
            )

            Surface(
                color = TacticalSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "ACTIVE MISSION CONFIGURATION",
                        style = MaterialTheme.typography.labelMedium,
                        color = CommandAmber
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Handshake: ${selectedHandshake.displayName}\n• Recovery Protocol: ${selectedRecovery.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )
                }
            }

            Button(
                onClick = onCompleteAndLaunchReveille,
                colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("onboarding_complete_launch_reveille_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AlarmOn,
                    contentDescription = null,
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Create Mission & Test Live Reveille Now",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = onCompleteAndEnterDashboard,
                border = BorderStroke(1.dp, MissionEmerald),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("onboarding_complete_enter_dashboard_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MissionEmerald
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Save Mission & Enter Command Center",
                    color = MissionEmerald,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ConceptTriadCard(
    stepBadge: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.18f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stepBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
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
