package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.Tune
import com.example.ui.screens.CalendarSyncScreen
import com.example.ui.screens.CommandDashboardScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PresetStudioScreen
import com.example.ui.screens.ReveilleOverlayScreen
import com.example.ui.screens.StreakVaultScreen
import com.example.ui.screens.VoiceStudioScreen
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.StreakFlameOrange
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import com.example.viewmodel.CommandViewModel

enum class CommandTab(val route: String, val label: String) {
    DASHBOARD("dashboard", "Command"),
    PRESET_STUDIO("preset_studio", "Presets"),
    CALENDAR_SYNC("calendar_sync", "Calendar"),
    STREAK_VAULT("streak_vault", "Streak"),
    VOICE_STUDIO("voice_studio", "Voices")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wire App Check attestation with launch intent before any UI or Firebase operation
        com.example.service.configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                CommandRootApp()
            }
        }
    }
}

@Composable
fun CommandRootApp(
    viewModel: CommandViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(CommandTab.DASHBOARD) }

    if (currentTab != CommandTab.DASHBOARD && uiState.activeSession == null) {
        BackHandler {
            currentTab = CommandTab.DASHBOARD
        }
    }

    val activeSession = uiState.activeSession
    if (activeSession != null) {
        ReveilleOverlayScreen(
            session = activeSession,
            playbackState = uiState.playbackState,
            onReplayVoice = {
                viewModel.previewVoicePack(
                    activeSession.voicePack,
                    activeSession.activeSpokenBanner
                )
            },
            onHandshakeVerified = viewModel::onHandshakeVerified,
            onToggleStarterPause = viewModel::toggleStarterPause,
            onAdvanceToReporting = viewModel::advanceToReporting,
            onSubmitReport = viewModel::submitLightweightReport,
            onDismissSession = viewModel::cancelActiveSession
        )
        return
    }

    if (!uiState.streakProfile.hasCompletedOnboarding) {
        OnboardingScreen(
            voicePacks = uiState.voicePacks,
            streakProfile = uiState.streakProfile,
            onSelectPersonality = viewModel::selectPersonality,
            onSelectVoicePack = viewModel::selectVoicePackForActivePersonality,
            onPreviewVoicePack = { pack -> viewModel.previewVoicePack(pack) },
            onApplyPreset = viewModel::applyPreset,
            onSyncGoogleCalendar = viewModel::syncWithGoogleCalendar,
            onCompleteOnboarding = viewModel::completeOnboarding,
            onSkipOnboarding = viewModel::skipOnboarding
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ObsidianBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = TacticalSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == CommandTab.DASHBOARD,
                    onClick = { currentTab = CommandTab.DASHBOARD },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Dashboard,
                            contentDescription = "Command Center"
                        )
                    },
                    label = { Text(CommandTab.DASHBOARD.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = CommandAmber,
                        indicatorColor = CommandAmber,
                        unselectedIconColor = SteelTextSecondary,
                        unselectedTextColor = SteelTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_tab_dashboard")
                )

                NavigationBarItem(
                    selected = currentTab == CommandTab.PRESET_STUDIO,
                    onClick = { currentTab = CommandTab.PRESET_STUDIO },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Personality Preset Studio & Library"
                        )
                    },
                    label = { Text(CommandTab.PRESET_STUDIO.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = CommandAmber,
                        indicatorColor = CommandAmber,
                        unselectedIconColor = SteelTextSecondary,
                        unselectedTextColor = SteelTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_tab_presets")
                )

                NavigationBarItem(
                    selected = currentTab == CommandTab.CALENDAR_SYNC,
                    onClick = { currentTab = CommandTab.CALENDAR_SYNC },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "Read-Only Google Calendar Sync & Permissions"
                        )
                    },
                    label = { Text(CommandTab.CALENDAR_SYNC.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MissionEmerald,
                        indicatorColor = MissionEmerald,
                        unselectedIconColor = SteelTextSecondary,
                        unselectedTextColor = SteelTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_tab_calendar")
                )

                NavigationBarItem(
                    selected = currentTab == CommandTab.STREAK_VAULT,
                    onClick = { currentTab = CommandTab.STREAK_VAULT },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = "Command Streak Vault"
                        )
                    },
                    label = {
                        Text("${CommandTab.STREAK_VAULT.label} (${uiState.streakProfile.currentStreakDays}d)")
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = StreakFlameOrange,
                        indicatorColor = StreakFlameOrange,
                        unselectedIconColor = SteelTextSecondary,
                        unselectedTextColor = SteelTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_tab_streak")
                )

                NavigationBarItem(
                    selected = currentTab == CommandTab.VOICE_STUDIO,
                    onClick = { currentTab = CommandTab.VOICE_STUDIO },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = "Voice Packs & Personalities"
                        )
                    },
                    label = { Text(CommandTab.VOICE_STUDIO.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = MissionEmerald,
                        indicatorColor = MissionEmerald,
                        unselectedIconColor = SteelTextSecondary,
                        unselectedTextColor = SteelTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_tab_voices")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedVisibility(visible = uiState.statusBannerMessage != null) {
                uiState.statusBannerMessage?.let { message ->
                    Surface(
                        color = TacticalSurfaceElevated,
                        border = BorderStroke(1.dp, MissionEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("status_feedback_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MissionEmerald,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = viewModel::dismissBanner,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss notification banner",
                                    tint = SteelTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {
                    CommandTab.DASHBOARD -> {
                        CommandDashboardScreen(
                            missions = uiState.missions,
                            voicePacks = uiState.voicePacks,
                            streakProfile = uiState.streakProfile,
                            streakChart = uiState.streakChart,
                            calendarSyncSummary = uiState.calendarSyncSummary,
                            onTriggerReveille = viewModel::triggerReveilleForMission,
                            onToggleMissionLock = viewModel::toggleMissionLock,
                            onSyncGoogleCalendar = viewModel::syncWithGoogleCalendar,
                            onResolveConflict = viewModel::resolveCalendarConflict,
                            onCreateMission = viewModel::createMission,
                            onPreviewActiveDirector = { pack -> viewModel.previewVoicePack(pack) },
                            onNavigateToStreakVault = { currentTab = CommandTab.STREAK_VAULT },
                            onNavigateToVoiceStudio = { currentTab = CommandTab.VOICE_STUDIO },
                            onNavigateToCalendarSync = { currentTab = CommandTab.CALENDAR_SYNC },
                            onReopenOnboarding = viewModel::reopenOnboarding,
                            onSelectStreakDay = viewModel::selectStreakChartDay,
                            onToggleStreakViewMode = viewModel::toggleStreakChartViewMode,
                            onBuyBackWithCredits = viewModel::buyBackMissedDayWithCredits,
                            onStartBriefingBuyBack = viewModel::startTacticalBriefingBuyBack,
                            presets = uiState.presets,
                            onSelectPreset = viewModel::applyCustomPresetGlobally,
                            onNavigateToPresetStudio = { currentTab = CommandTab.PRESET_STUDIO },
                            isSpeaking = uiState.playbackState.isSpeaking,
                            onSelectPersonality = viewModel::selectPersonality
                        )
                    }

                    CommandTab.PRESET_STUDIO -> {
                        PresetStudioScreen(
                            presets = uiState.presets,
                            voicePacks = uiState.voicePacks,
                            playbackState = uiState.playbackState,
                            routingRules = uiState.routingRules,
                            escalationPolicies = uiState.escalationPolicies,
                            handshakeCalibrations = uiState.handshakeCalibrations,
                            blueprints = uiState.blueprints,
                            auditionPhase = uiState.auditionPhase,
                            auditionDirectivesMap = uiState.auditionDirectivesMap,
                            isAuditionGenerating = uiState.isAuditionGenerating,
                            activeAuditionMissionTitle = uiState.activeAuditionMissionTitle,
                            activeAuditionCategory = uiState.activeAuditionCategory,
                            onSaveCompletePreset = viewModel::saveCompletePreset,
                            onClonePreset = viewModel::clonePreset,
                            onDeletePreset = viewModel::deleteCustomPreset,
                            onApplyPresetGlobally = viewModel::applyCustomPresetGlobally,
                            onPreviewVoice = viewModel::previewCustomPresetVoice,
                            onStopVoice = viewModel::stopVoicePreview,
                            onGenerateAiDirective = viewModel::generateGeminiDirectiveForPreset,
                            onSetAuditionPhase = viewModel::setAuditionPhase,
                            onSetAuditionTarget = viewModel::setAuditionTarget,
                            onSynthesizeAuditionDirectives = viewModel::synthesizeAuditionDirectives,
                            onSynthesizeSingleAudition = viewModel::synthesizeSingleAudition,
                            onAuditionVoiceForPersonality = viewModel::auditionVoiceForPersonality,
                            onSaveRoutingRule = viewModel::saveRoutingRule,
                            onDeleteRoutingRule = viewModel::deleteRoutingRule,
                            onSaveEscalationPolicy = viewModel::saveEscalationPolicy,
                            onDeleteEscalationPolicy = viewModel::deleteEscalationPolicy,
                            onSaveHandshakeCalibration = viewModel::saveHandshakeCalibration,
                            onDeleteHandshakeCalibration = viewModel::deleteHandshakeCalibration,
                            onSaveMissionBlueprint = viewModel::saveMissionBlueprint,
                            onDeleteMissionBlueprint = viewModel::deleteMissionBlueprint,
                            onDeployBlueprintToQueue = viewModel::deployBlueprintToQueue,
                            onApplyRoutingRulesToAllMissions = viewModel::applyRoutingRulesToAllMissions
                        )
                    }

                    CommandTab.CALENDAR_SYNC -> {
                        CalendarSyncScreen(
                            missions = uiState.missions,
                            calendarSyncSummary = uiState.calendarSyncSummary,
                            onSyncGoogleCalendar = viewModel::syncWithGoogleCalendar,
                            onResolveConflict = viewModel::resolveCalendarConflict,
                            onTriggerReveille = viewModel::triggerReveilleForMission,
                            isBackgroundSyncActive = uiState.isBackgroundSyncActive,
                            backgroundSyncIntervalMinutes = uiState.backgroundSyncIntervalMinutes,
                            lastBackgroundSyncTimestamp = uiState.lastBackgroundSyncTimestamp,
                            onTriggerBackgroundService = viewModel::triggerBackgroundCalendarService,
                            onTogglePeriodicBackgroundSync = viewModel::togglePeriodicBackgroundSync,
                            onUpdateSyncInterval = viewModel::updateBackgroundSyncInterval,
                            onConsolidateMissions = viewModel::consolidateConflictingMissions,
                            onPrioritizePrimary = viewModel::prioritizePrimaryMission,
                            onShiftSecondary = viewModel::shiftSecondaryMission
                        )
                    }

                    CommandTab.STREAK_VAULT -> {
                        StreakVaultScreen(
                            streakProfile = uiState.streakProfile,
                            isBriefingViewerOpen = uiState.isBriefingViewerOpen,
                            briefingRemainingSeconds = uiState.briefingRemainingSeconds,
                            onSimulateMissedDay = viewModel::simulateMissedDayForTesting,
                            onBuyBackWithCredits = viewModel::buyBackMissedDayWithCredits,
                            onStartBriefingBuyBack = viewModel::startTacticalBriefingBuyBack,
                            onCancelBriefing = viewModel::cancelTacticalBriefing
                        )
                    }

                    CommandTab.VOICE_STUDIO -> {
                        VoiceStudioScreen(
                            voicePacks = uiState.voicePacks,
                            streakProfile = uiState.streakProfile,
                            playbackState = uiState.playbackState,
                            onPreviewVoicePack = { pack -> viewModel.previewVoicePack(pack) },
                            onStopPreview = viewModel::stopVoicePreview,
                            onSelectVoicePack = viewModel::selectVoicePackForActivePersonality,
                            onSelectPersonality = viewModel::selectPersonality,
                            onApplyPreset = viewModel::applyPreset,
                            onUpdateDimensions = viewModel::updateDimension,
                            onCreateCustomVoicePack = viewModel::addCustomVoicePack,
                            onToggleFavorite = viewModel::toggleFavoriteVoicePack,
                            onUpdateEqPreset = viewModel::updateVoicePackEq,
                            onUpdateVolumeBoost = viewModel::updateVoicePackVolumeBoost,
                            onUpdateVoiceModel = viewModel::updateVoicePackModel,
                            onUpdateVocalGrit = viewModel::updateVoicePackGrit,
                            onUpdateAggression = viewModel::updateVoicePackAggression,
                            onUpdateCustomTestPhrase = viewModel::updateCustomTestPhrase,
                            onPolishPhraseWithAi = viewModel::polishPhraseWithAiCommandStyle,
                            onToggleHeadphoneOnly = viewModel::toggleHeadphoneOnlyMode,
                            onToggleQuietHours = viewModel::toggleQuietHoursDucking,
                            onSetCompareVoiceA = viewModel::setCompareVoiceA,
                            onSetCompareVoiceB = viewModel::setCompareVoiceB,
                            onRunAbComparison = viewModel::runAbComparison,
                            onUpdatePitch = viewModel::updateVoicePackPitch,
                            onUpdateFormantShift = viewModel::updateVoicePackFormantShift,
                            onPreviewPhrase = { pack, phrase -> viewModel.previewVoicePack(pack, phrase) }
                        )
                    }
                }
            }
        }
    }

    // Milestone Badge Unlock Celebration Modal
    uiState.unlockedBadgeCelebration?.let { badge ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBadgeCelebration,
            containerColor = TacticalSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = MissionEmerald,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MILESTONE BADGE UNLOCKED!",
                        style = MaterialTheme.typography.titleLarge,
                        color = MissionEmerald
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "${badge.title} (${badge.requiredDays}-Day Streak)",
                        style = MaterialTheme.typography.headlineMedium,
                        color = CommandAmber
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = badge.subtitle,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Reward Credited: +${badge.bonusCredits} Command Credits!",
                        style = MaterialTheme.typography.labelLarge,
                        color = MissionEmerald
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::dismissBadgeCelebration,
                    colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald)
                ) {
                    Text("Claim Badge", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
