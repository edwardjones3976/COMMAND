package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.DEFAULT_PERSONALITIES
import com.example.data.EmotionalTone
import com.example.data.EqPreset
import com.example.data.StreakProfileEntity
import com.example.data.VoiceModelType
import com.example.data.VoicePackEntity
import com.example.service.VoicePlaybackState
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.example.ui.components.CoachAvatarIndividual
import com.example.ui.components.TacticalSoundboardGrid
import com.example.ui.components.VoiceCarouselShowcase
import com.example.ui.components.resolveCoachIndividual
import java.util.Locale
import kotlin.math.roundToInt

enum class VoiceSwitcherMode(val label: String) {
    CAROUSEL("Showcase Carousel"),
    SOUNDBOARD("Tactical Soundboard"),
    CATALOG("Deep Catalog")
}

enum class PersonaStudioFilter(val label: String) {
    ALL("All Directors"),
    DRILL_SERGEANT("Drill Sgt. Stone"),
    MOTIVATIONAL_COACH("Coach Maya"),
    CORPORATE_EXECUTIVE("Director Vance")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceStudioScreen(
    voicePacks: List<VoicePackEntity>,
    streakProfile: StreakProfileEntity,
    playbackState: VoicePlaybackState,
    onPreviewVoicePack: (VoicePackEntity) -> Unit,
    onStopPreview: () -> Unit,
    onSelectVoicePack: (VoicePackEntity) -> Unit,
    onSelectPersonality: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onUpdateDimensions: (Int, Int, Int, Int, Int, Int, Int) -> Unit,
    onCreateCustomVoicePack: (String, String, String, String, EmotionalTone, Float, Float, String) -> Unit,
    onToggleFavorite: (VoicePackEntity) -> Unit = {},
    onUpdateEqPreset: (VoicePackEntity, EqPreset) -> Unit = { _, _ -> },
    onUpdateVolumeBoost: (VoicePackEntity, Int) -> Unit = { _, _ -> },
    onUpdateVoiceModel: (VoicePackEntity, VoiceModelType) -> Unit = { _, _ -> },
    onUpdateVocalGrit: (VoicePackEntity, Int) -> Unit = { _, _ -> },
    onUpdateAggression: (VoicePackEntity, Int) -> Unit = { _, _ -> },
    onUpdateCustomTestPhrase: (String) -> Unit = {},
    onPolishPhraseWithAi: () -> Unit = {},
    onToggleHeadphoneOnly: () -> Unit = {},
    onToggleQuietHours: () -> Unit = {},
    onSetCompareVoiceA: (String) -> Unit = {},
    onSetCompareVoiceB: (String) -> Unit = {},
    onRunAbComparison: () -> Unit = {},
    onUpdatePitch: (VoicePackEntity, Float) -> Unit = { _, _ -> },
    onUpdateFormantShift: (VoicePackEntity, Int) -> Unit = { _, _ -> },
    onPreviewPhrase: ((VoicePackEntity, String) -> Unit)? = null
) {
    var showCreatePackModal by remember { mutableStateOf(false) }
    var inspectedPackForLicense by remember { mutableStateOf<VoicePackEntity?>(null) }
    var activeSwitcherMode by remember { mutableStateOf(VoiceSwitcherMode.CAROUSEL) }
    var activePersonaFilter by remember { mutableStateOf(PersonaStudioFilter.ALL) }

    val filteredVoicePacks = remember(voicePacks, activePersonaFilter) {
        when (activePersonaFilter) {
            PersonaStudioFilter.ALL -> voicePacks
            PersonaStudioFilter.DRILL_SERGEANT -> voicePacks.filter {
                resolveCoachIndividual(voicePackId = it.id) == CoachAvatarIndividual.DRILL_SERGEANT
            }
            PersonaStudioFilter.MOTIVATIONAL_COACH -> voicePacks.filter {
                resolveCoachIndividual(voicePackId = it.id) == CoachAvatarIndividual.PERSONAL_LIFE_COACH
            }
            PersonaStudioFilter.CORPORATE_EXECUTIVE -> voicePacks.filter {
                resolveCoachIndividual(voicePackId = it.id) == CoachAvatarIndividual.CORPORATE_EXECUTIVE
            }
        }
    }

    inspectedPackForLicense?.let { pack ->
        LegalVoiceLicenseDialog(
            voicePack = pack,
            onDismiss = { inspectedPackForLicense = null }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("voice_studio_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header with Title and Add Voice Button
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NEURAL VOICE STUDIO & DSP ENGINE",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandAmber
                    )
                    Text(
                        text = "High-Definition Actors & Cadence Polish",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                Button(
                    onClick = { showCreatePackModal = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                    modifier = Modifier.testTag("add_custom_voice_pack_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Add Voice",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Crisp Broadcast Studio & Hybrid Master Engine Status Banner
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("broadcast_mastering_banner"),
                colors = CardDefaults.cardColors(
                    containerColor = if (playbackState.isSpeaking) TacticalSurfaceElevated else TacticalSurface
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (playbackState.isSpeaking) MissionEmerald else CommandAmber.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (playbackState.isSpeaking) MissionEmerald.copy(alpha = 0.2f) else CommandAmber.copy(alpha = 0.15f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (playbackState.isSpeaking) MissionEmerald else CommandAmber,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "CRISP BROADCAST MASTERING ACTIVE",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Black,
                                    color = if (playbackState.isSpeaking) MissionEmerald else Color.White,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = if (playbackState.isSpeaking) playbackState.activeDspBadge else "85Hz Rumble Cut • 4.2kHz Presence • De-Esser • Zero Echo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SteelTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (playbackState.isSpeaking) MissionEmerald.copy(alpha = 0.25f) else TacticalSurfaceElevated,
                            border = BorderStroke(1.dp, if (playbackState.isSpeaking) MissionEmerald else Color.White.copy(alpha = 0.15f))
                        ) {
                            Text(
                                text = if (playbackState.isSpeaking) "LIVE MASTER" else "STUDIO ON",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (playbackState.isSpeaking) MissionEmerald else CommandAmber
                            )
                        }
                    }

                    if (playbackState.isSpeaking) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.Black.copy(alpha = 0.5f)),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            playbackState.spectrumBands.take(16).forEach { band ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .background(
                                            when {
                                                band > 0.85f -> Color(0xFFFF5252)
                                                band > 0.60f -> CommandAmber
                                                else -> MissionEmerald
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Pinned Mode Switcher Bar & Persona Filter Chips
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("voice_mode_switcher_bar"),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, CommandAmber.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "VOICE PACK SWITCHER LAYOUT",
                        style = MaterialTheme.typography.labelSmall,
                        color = CommandAmber,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Segmented Button Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VoiceSwitcherMode.values().forEach { mode ->
                            val isSelectedMode = activeSwitcherMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clickable { activeSwitcherMode = mode }
                                    .testTag("mode_tab_${mode.name.lowercase()}"),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelectedMode) CommandAmber else TacticalSurfaceElevated,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelectedMode) CommandAmber else Color.White.copy(alpha = 0.1f)
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelectedMode) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isSelectedMode) Color.Black else Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Persona Filter Chips
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PersonaStudioFilter.values().forEach { filter ->
                            val isFilterActive = activePersonaFilter == filter
                            FilterChip(
                                selected = isFilterActive,
                                onClick = { activePersonaFilter = filter },
                                label = {
                                    Text(
                                        text = filter.label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MissionEmerald.copy(alpha = 0.22f),
                                    selectedLabelColor = MissionEmerald
                                ),
                                modifier = Modifier.testTag("persona_filter_${filter.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        // Live Test Phrase Sandbox & AI Command Polish
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_phrase_deck"),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, CommandAmber)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = CommandAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "STUDIO TEST PHRASE & CADENCE POLISH",
                                style = MaterialTheme.typography.labelLarge,
                                color = CommandAmber
                            )
                        }

                        OutlinedButton(
                            onClick = onPolishPhraseWithAi,
                            border = BorderStroke(1.dp, MissionEmerald),
                            modifier = Modifier.testTag("ai_polish_phrase_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MissionEmerald,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AI Command Polish",
                                color = MissionEmerald,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = streakProfile.customTestPhrase,
                        onValueChange = onUpdateCustomTestPhrase,
                        label = { Text("Interactive Voice Test Phrase (affects previews and A/B)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_test_phrase_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Practical Smart Guards (Headphones only & Quiet hours)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            color = TacticalSurfaceElevated,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Headphones,
                                        contentDescription = null,
                                        tint = if (streakProfile.headphoneOnlyMode) MissionEmerald else SteelTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Headphone Guard",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (streakProfile.headphoneOnlyMode) "Mute on speakers" else "Off (all audio)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = SteelTextSecondary
                                        )
                                    }
                                }
                                Switch(
                                    checked = streakProfile.headphoneOnlyMode,
                                    onCheckedChange = { onToggleHeadphoneOnly() },
                                    modifier = Modifier.testTag("toggle_headphone_only")
                                )
                            }
                        }

                        Surface(
                            color = TacticalSurfaceElevated,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NightsStay,
                                        contentDescription = null,
                                        tint = if (streakProfile.quietHoursDucking) CommandAmber else SteelTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Quiet-Hours Ducking",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (streakProfile.quietHoursDucking) "-55% at night" else "Full volume",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = SteelTextSecondary
                                        )
                                    }
                                }
                                Switch(
                                    checked = streakProfile.quietHoursDucking,
                                    onCheckedChange = { onToggleQuietHours() },
                                    modifier = Modifier.testTag("toggle_quiet_hours")
                                )
                            }
                        }
                    }
                }
            }
        }

        // Instant A/B Voice Comparison Bar
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ab_comparison_deck"),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CompareArrows,
                            contentDescription = null,
                            tint = MissionEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "INSTANT A/B VOICE COMPARISON",
                            style = MaterialTheme.typography.titleMedium,
                            color = MissionEmerald
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Listen to two voices speak the same phrase back-to-back to choose your favorite:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "VOICE A:",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (playbackState.activeAbSlot == "A") MissionEmerald else SteelTextSecondary
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                voicePacks.take(4).forEach { pack ->
                                    FilterChip(
                                        selected = streakProfile.compareVoiceAId == pack.id,
                                        onClick = { onSetCompareVoiceA(pack.id) },
                                        label = { Text(pack.actorName.substringBefore(" ")) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MissionEmerald.copy(alpha = 0.25f),
                                            selectedLabelColor = MissionEmerald
                                        )
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "VOICE B:",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (playbackState.activeAbSlot == "B") CommandAmber else SteelTextSecondary
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                voicePacks.take(4).forEach { pack ->
                                    FilterChip(
                                        selected = streakProfile.compareVoiceBId == pack.id,
                                        onClick = { onSetCompareVoiceB(pack.id) },
                                        label = { Text(pack.actorName.substringBefore(" ")) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CommandAmber.copy(alpha = 0.25f),
                                            selectedLabelColor = CommandAmber
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onRunAbComparison,
                        colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("run_ab_comparison_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CompareArrows,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (playbackState.isAbComparing) {
                                "COMPARING IN PROGRESS (SLOT ${playbackState.activeAbSlot ?: "A"})..."
                            } else {
                                "Run A ↔ B Voice Comparison"
                            },
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        // Selected Switcher Layout Mode (Carousel, Soundboard, or Catalog)
        when (activeSwitcherMode) {
            VoiceSwitcherMode.CAROUSEL -> {
                item {
                    Text(
                        text = "INTERACTIVE CAROUSEL SHOWCASE (${filteredVoicePacks.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandAmber
                    )
                }
                item {
                    VoiceCarouselShowcase(
                        voicePacks = filteredVoicePacks,
                        selectedVoicePackId = streakProfile.selectedVoicePackId,
                        playbackState = playbackState,
                        onPreviewVoicePack = onPreviewVoicePack,
                        onStopPreview = onStopPreview,
                        onSelectVoicePack = onSelectVoicePack,
                        onPreviewPhrase = onPreviewPhrase
                    )
                }
            }
            VoiceSwitcherMode.SOUNDBOARD -> {
                item {
                    Text(
                        text = "TACTICAL SOUNDBOARD & DIRECT AUDITION",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandAmber
                    )
                }
                item {
                    TacticalSoundboardGrid(
                        voicePacks = voicePacks,
                        selectedVoicePackId = streakProfile.selectedVoicePackId,
                        playbackState = playbackState,
                        onPreviewVoicePack = onPreviewVoicePack,
                        onStopPreview = onStopPreview,
                        onSelectVoicePack = onSelectVoicePack,
                        onPreviewPhrase = onPreviewPhrase
                    )
                }
            }
            VoiceSwitcherMode.CATALOG -> {
                item {
                    Text(
                        text = "DEEP PARAMETER CATALOG (${filteredVoicePacks.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandAmber
                    )
                }
                items(filteredVoicePacks, key = { it.id }) { pack ->
                    val isSelected = pack.id == streakProfile.selectedVoicePackId
                    val isPreviewingThis = playbackState.isSpeaking && playbackState.activeVoicePackId == pack.id
                    VoicePackCard(
                        pack = pack,
                        isSelected = isSelected,
                        isPreviewing = isPreviewingThis,
                        spectrumBands = if (isPreviewingThis) playbackState.spectrumBands else emptyList(),
                        onPreviewClick = {
                            if (isPreviewingThis) onStopPreview() else onPreviewVoicePack(pack)
                        },
                        onSelectClick = { onSelectVoicePack(pack) },
                        onToggleFavorite = { onToggleFavorite(pack) },
                        onSelectEqPreset = { preset -> onUpdateEqPreset(pack, preset) },
                        onUpdateVolumeBoost = { boost -> onUpdateVolumeBoost(pack, boost) },
                        onSelectVoiceModel = { model -> onUpdateVoiceModel(pack, model) },
                        onUpdateVocalGrit = { grit -> onUpdateVocalGrit(pack, grit) },
                        onUpdateAggression = { aggr -> onUpdateAggression(pack, aggr) },
                        onUpdatePitch = { pitch -> onUpdatePitch(pack, pitch) },
                        onUpdateFormantShift = { shift -> onUpdateFormantShift(pack, shift) },
                        onInspectLicense = { inspectedPackForLicense = pack }
                    )
                }
            }
        }

        // 6 Selectable Personalities
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DIRECTOR PERSONALITY ARCHETYPE",
                        style = MaterialTheme.typography.labelLarge,
                        color = CommandAmber
                    )
                    Text(
                        text = "Choose one of the 6 core coaching personalities:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DEFAULT_PERSONALITIES.forEach { persona ->
                            val active = persona.id == streakProfile.selectedPersonalityId
                            FilterChip(
                                selected = active,
                                onClick = { onSelectPersonality(persona.id) },
                                label = {
                                    Text(
                                        text = persona.name,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CommandAmber.copy(alpha = 0.24f),
                                    selectedLabelColor = CommandAmber
                                ),
                                modifier = Modifier.testTag("persona_chip_${persona.id}")
                            )
                        }
                    }

                    val activePersona = DEFAULT_PERSONALITIES.find { it.id == streakProfile.selectedPersonalityId }
                        ?: DEFAULT_PERSONALITIES.first()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = activePersona.tagline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MissionEmerald
                    )
                }
            }
        }

        // 5 Presets + 7 Independent 1-5 Sliders
        item {
            val presets = listOf("Nudge", "Steady", "Command", "Reveille MAX", "Custom")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = CommandAmber
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PRESETS & 7 INDEPENDENT DIMENSIONS (1–5)",
                            style = MaterialTheme.typography.titleMedium,
                            color = CommandAmber
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = streakProfile.activePresetName == preset,
                                onClick = { onApplyPreset(preset) },
                                label = { Text(preset) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MissionEmerald.copy(alpha = 0.22f),
                                    selectedLabelColor = MissionEmerald
                                ),
                                modifier = Modifier.testTag("preset_chip_${preset.lowercase().replace(" ", "_")}")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    DimensionSliderRow(
                        title = "Instruction Firmness",
                        lowLabel = "Gentle",
                        highLabel = "Commanding",
                        value = streakProfile.instructionFirmness,
                        onValueChange = {
                            onUpdateDimensions(
                                it,
                                streakProfile.obnoxiousness,
                                streakProfile.alertIntensity,
                                streakProfile.persistence,
                                streakProfile.handshakeDifficulty,
                                streakProfile.scheduleRigidity,
                                streakProfile.recoveryStructure
                            )
                        }
                    )
                    DimensionSliderRow(
                        title = "Obnoxiousness",
                        lowLabel = "Reserved",
                        highLabel = "Irritating",
                        value = streakProfile.obnoxiousness,
                        onValueChange = {
                            onUpdateDimensions(
                                streakProfile.instructionFirmness,
                                it,
                                streakProfile.alertIntensity,
                                streakProfile.persistence,
                                streakProfile.handshakeDifficulty,
                                streakProfile.scheduleRigidity,
                                streakProfile.recoveryStructure
                            )
                        }
                    )
                    DimensionSliderRow(
                        title = "Alert Intensity",
                        lowLabel = "Quiet",
                        highLabel = "Max Capture",
                        value = streakProfile.alertIntensity,
                        onValueChange = {
                            onUpdateDimensions(
                                streakProfile.instructionFirmness,
                                streakProfile.obnoxiousness,
                                it,
                                streakProfile.persistence,
                                streakProfile.handshakeDifficulty,
                                streakProfile.scheduleRigidity,
                                streakProfile.recoveryStructure
                            )
                        }
                    )
                    DimensionSliderRow(
                        title = "Persistence",
                        lowLabel = "1 Reminder",
                        highLabel = "Bounded Follow-Ups",
                        value = streakProfile.persistence,
                        onValueChange = {
                            onUpdateDimensions(
                                streakProfile.instructionFirmness,
                                streakProfile.obnoxiousness,
                                streakProfile.alertIntensity,
                                it,
                                streakProfile.handshakeDifficulty,
                                streakProfile.scheduleRigidity,
                                streakProfile.recoveryStructure
                            )
                        }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showCreatePackModal) {
        CreateVoicePackDialog(
            onDismiss = { showCreatePackModal = false },
            onSave = { name, actor, accent, lang, tone, pitch, rate, quote ->
                onCreateCustomVoicePack(name, actor, accent, lang, tone, pitch, rate, quote)
                showCreatePackModal = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePackCard(
    pack: VoicePackEntity,
    isSelected: Boolean,
    isPreviewing: Boolean,
    spectrumBands: List<Float>,
    onPreviewClick: () -> Unit,
    onSelectClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectEqPreset: (EqPreset) -> Unit,
    onUpdateVolumeBoost: (Int) -> Unit,
    onSelectVoiceModel: (VoiceModelType) -> Unit,
    onUpdateVocalGrit: (Int) -> Unit,
    onUpdateAggression: (Int) -> Unit,
    onUpdatePitch: (Float) -> Unit = {},
    onUpdateFormantShift: (Int) -> Unit = {},
    onInspectLicense: () -> Unit = {}
) {
    val toneEnum = runCatching { EmotionalTone.valueOf(pack.emotionalTone) }
        .getOrDefault(EmotionalTone.STERN)
    val currentEq = runCatching { EqPreset.valueOf(pack.eqPreset) }
        .getOrDefault(EqPreset.STUDIO_CRISP)
    val currentModel = runCatching { VoiceModelType.valueOf(pack.voiceModelType) }
        .getOrDefault(VoiceModelType.USMC_MCRD_BARK)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_pack_card_${pack.id}"),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MissionEmerald else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Avatar + Info + Favorite Pin
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val coachIndividual = com.example.ui.components.resolveCoachIndividual(voicePackId = pack.id)
                if (pack.usesDrillSergeantArt || coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH) {
                    com.example.ui.components.AnimatedCoachAvatar(
                        individual = coachIndividual,
                        isSpeaking = isPreviewing,
                        size = 68.dp,
                        showStatusBadge = false
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(toneEnum.badgeColorHex).copy(alpha = 0.18f))
                    ) {
                        if (isPreviewing) {
                            com.example.ui.components.LiveVocalEqualizerBars(
                                accentColor = Color(toneEnum.badgeColorHex)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = Color(toneEnum.badgeColorHex),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = pack.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.size(32.dp).testTag("fav_btn_${pack.id}")
                        ) {
                            Icon(
                                imageVector = if (pack.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Pin Favorite Voice Pack",
                                tint = if (pack.isFavorite) CommandAmber else SteelTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Text(
                        text = "${pack.actorName} • ${pack.accentLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (pack.isHumanStem) {
                            Surface(
                                color = Color(0xFF1B5E20).copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, Color(0xFF4CAF50)),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier
                                    .clickable { onInspectLicense() }
                                    .testTag("certified_human_badge_${pack.id}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Certified Human CC0",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "CERTIFIED HUMAN • CC0",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF81C784)
                                    )
                                }
                            }
                        }
                        Surface(
                            color = MissionEmerald.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = pack.qualityTierLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MissionEmerald,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            color = Color(toneEnum.badgeColorHex).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = toneEnum.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(toneEnum.badgeColorHex),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = TacticalSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "\"${pack.sampleQuote}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // Real-Time Spectrum Analyzer Visualizer (Bouncing 16 bands)
            if (isPreviewing) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TacticalSurfaceElevated)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val bands = if (spectrumBands.isNotEmpty()) spectrumBands else List(16) { 0.2f }
                    bands.forEachIndexed { idx, amplitude ->
                        val animatedHeight by animateFloatAsState(
                            targetValue = (amplitude * 24f).coerceIn(4f, 24f),
                            animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing),
                            label = "spectrum_bar_$idx"
                        )
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(animatedHeight.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (idx in 5..10) CommandAmber else MissionEmerald
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Voice Model Architecture Selector
            Text(
                text = "Voice Model Engine (${currentModel.description}):",
                style = MaterialTheme.typography.labelMedium,
                color = MissionEmerald
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                VoiceModelType.entries.forEach { model ->
                    FilterChip(
                        selected = currentModel == model,
                        onClick = { onSelectVoiceModel(model) },
                        label = { Text(model.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MissionEmerald.copy(alpha = 0.24f),
                            selectedLabelColor = MissionEmerald
                        ),
                        modifier = Modifier.testTag("model_${pack.id}_${model.name.lowercase()}")
                    )
                }
            }

            if (pack.isHumanStem) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF1B5E20).copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("human_stem_controls_${pack.id}")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF81C784),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "CERTIFIED HUMAN STEM DSP",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF81C784)
                                )
                            }
                            OutlinedButton(
                                onClick = onInspectLicense,
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("inspect_license_btn_${pack.id}"),
                                border = BorderStroke(1.dp, Color(0xFF81C784).copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "View Rights",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF81C784)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Human Stem Pitch Slider (0.75x to 1.25x)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Human Voice Pitch Transpose:",
                                style = MaterialTheme.typography.labelMedium,
                                color = SteelTextSecondary
                            )
                            Text(
                                text = "${String.format(Locale.US, "%.2f", pack.pitch)}x",
                                style = MaterialTheme.typography.labelMedium,
                                color = CommandAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = pack.pitch,
                            onValueChange = { onUpdatePitch(it) },
                            valueRange = 0.75f..1.25f,
                            steps = 9,
                            colors = SliderDefaults.colors(
                                thumbColor = CommandAmber,
                                activeTrackColor = CommandAmber
                            ),
                            modifier = Modifier.testTag("human_pitch_slider_${pack.id}")
                        )

                        // Vocal-Tract Formant Resonance Slider (-50% to +50%)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Vocal-Tract Formant Resonance:",
                                style = MaterialTheme.typography.labelMedium,
                                color = SteelTextSecondary
                            )
                            val shiftSign = if (pack.formantShiftPercent >= 0) "+${pack.formantShiftPercent}" else "${pack.formantShiftPercent}"
                            val charDesc = when {
                                pack.formantShiftPercent <= -20 -> "Deep Baritone Chest"
                                pack.formantShiftPercent < 0 -> "Warmer Body"
                                pack.formantShiftPercent == 0 -> "Neutral Studio"
                                pack.formantShiftPercent <= 20 -> "Crisp Projection"
                                else -> "High Studio Presence"
                            }
                            Text(
                                text = "$shiftSign% ($charDesc)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MissionEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = pack.formantShiftPercent.toFloat(),
                            onValueChange = { onUpdateFormantShift(it.roundToInt()) },
                            valueRange = -50f..50f,
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = MissionEmerald,
                                activeTrackColor = MissionEmerald
                            ),
                            modifier = Modifier.testTag("formant_slider_${pack.id}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Vocal Grit / Laryngeal Overdrive Slider (0% - 100%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MCRD Vocal Grit / Overdrive:",
                    style = MaterialTheme.typography.labelMedium,
                    color = SteelTextSecondary
                )
                Text(
                    text = "${pack.vocalGritPercent}% Rasp",
                    style = MaterialTheme.typography.labelMedium,
                    color = CommandAmber,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = pack.vocalGritPercent.toFloat(),
                onValueChange = { onUpdateVocalGrit(it.roundToInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = CommandAmber,
                    activeTrackColor = CommandAmber
                ),
                modifier = Modifier.testTag("grit_slider_${pack.id}")
            )

            // Command Aggression & Bark Attack Slider (0% - 100%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Command Aggression & Bark Cadence:",
                    style = MaterialTheme.typography.labelMedium,
                    color = SteelTextSecondary
                )
                Text(
                    text = "${pack.aggressionPercent}% Intensity",
                    style = MaterialTheme.typography.labelMedium,
                    color = MissionEmerald,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = pack.aggressionPercent.toFloat(),
                onValueChange = { onUpdateAggression(it.roundToInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = MissionEmerald,
                    activeTrackColor = MissionEmerald
                ),
                modifier = Modifier.testTag("aggression_slider_${pack.id}")
            )

            Spacer(modifier = Modifier.height(6.dp))

            // EQ Clarity Presets
            Text(
                text = "EQ Clarity DSP Preset:",
                style = MaterialTheme.typography.labelMedium,
                color = SteelTextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EqPreset.entries.forEach { eq ->
                    FilterChip(
                        selected = currentEq == eq,
                        onClick = { onSelectEqPreset(eq) },
                        label = { Text(eq.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CommandAmber.copy(alpha = 0.24f),
                            selectedLabelColor = CommandAmber
                        ),
                        modifier = Modifier.testTag("eq_${pack.id}_${eq.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Volume Boost Slider (100% to 150%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Loudness Boost:",
                    style = MaterialTheme.typography.labelMedium,
                    color = SteelTextSecondary
                )
                Text(
                    text = "${pack.volumeBoostPercent}% (+${((pack.volumeBoostPercent - 100) / 10.0)} dB)",
                    style = MaterialTheme.typography.labelMedium,
                    color = CommandAmber,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = pack.volumeBoostPercent.toFloat(),
                onValueChange = { onUpdateVolumeBoost(it.roundToInt()) },
                valueRange = 100f..150f,
                steps = 4,
                colors = SliderDefaults.colors(
                    thumbColor = CommandAmber,
                    activeTrackColor = CommandAmber
                ),
                modifier = Modifier.testTag("vol_boost_${pack.id}")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Actions Row: Preview & Select
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPreviewClick,
                    border = BorderStroke(1.dp, CommandAmber),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("preview_voice_${pack.id}")
                ) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.voice_preview_desc),
                        tint = CommandAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPreviewing) "Stop Preview" else "Preview Voice",
                        color = CommandAmber,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onSelectClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) MissionEmerald else TacticalSurfaceElevated
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("select_voice_${pack.id}")
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (isSelected) "Active Pack" else "Select Voice",
                        color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun DimensionSliderRow(
    title: String,
    lowLabel: String,
    highLabel: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$value / 5 ($lowLabel → $highLabel)",
                style = MaterialTheme.typography.labelMedium,
                color = CommandAmber
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(1, 5)) },
            valueRange = 1f..5f,
            steps = 3,
            colors = SliderDefaults.colors(
                thumbColor = CommandAmber,
                activeTrackColor = CommandAmber
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateVoicePackDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, EmotionalTone, Float, Float, String) -> Unit
) {
    var packName by remember { mutableStateOf("") }
    var actorName by remember { mutableStateOf("") }
    var selectedAccent by remember { mutableStateOf("US Tactical" to "en-US") }
    var selectedTone by remember { mutableStateOf(EmotionalTone.STERN) }
    var pitch by remember { mutableFloatStateOf(0.96f) }
    var speechRate by remember { mutableFloatStateOf(1.05f) }
    var sampleQuote by remember {
        mutableStateOf("Stand up, lock in the handshake, and execute your first five minutes right now!")
    }

    val accents = listOf(
        "US Tactical" to "en-US",
        "British RP" to "en-GB",
        "Australian" to "en-AU"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        title = {
            Text(
                text = "Create HD Voice Pack",
                style = MaterialTheme.typography.titleLarge,
                color = CommandAmber
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = packName,
                    onValueChange = { packName = it },
                    label = { Text("Voice Pack Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_voice_name_input")
                )
                OutlinedTextField(
                    value = actorName,
                    onValueChange = { actorName = it },
                    label = { Text("Voice Actor Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_voice_actor_input")
                )

                Text(
                    text = "Accent Profile:",
                    style = MaterialTheme.typography.labelMedium,
                    color = SteelTextSecondary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    accents.forEach { pair ->
                        FilterChip(
                            selected = selectedAccent.second == pair.second,
                            onClick = { selectedAccent = pair },
                            label = { Text(pair.first) }
                        )
                    }
                }

                Text(
                    text = "Emotional Tone:",
                    style = MaterialTheme.typography.labelMedium,
                    color = SteelTextSecondary
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EmotionalTone.entries.forEach { tone ->
                        FilterChip(
                            selected = selectedTone == tone,
                            onClick = { selectedTone = tone },
                            label = { Text(tone.name) }
                        )
                    }
                }

                Text(
                    text = "Stabilized Pitch (${"%.2f".format(pitch)}x) & Rate (${"%.2f".format(speechRate)}x)",
                    style = MaterialTheme.typography.labelMedium,
                    color = CommandAmber
                )
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    valueRange = 0.88f..1.05f
                )
                Slider(
                    value = speechRate,
                    onValueChange = { speechRate = it },
                    valueRange = 0.92f..1.2f
                )

                OutlinedTextField(
                    value = sampleQuote,
                    onValueChange = { sampleQuote = it },
                    label = { Text("Sample Reveille Quote") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        packName,
                        actorName,
                        selectedAccent.first,
                        selectedAccent.second,
                        selectedTone,
                        pitch,
                        speechRate,
                        sampleQuote
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald),
                modifier = Modifier.testTag("save_custom_voice_button")
            ) {
                Text("Save Pack", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun LegalVoiceLicenseDialog(
    voicePack: VoicePackEntity,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("legal_voice_license_dialog"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MissionEmerald,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "LEGAL VOICE CLEARANCE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MissionEmerald,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = voicePack.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = Color(0xFF1B5E20).copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "STATUS: 100% COMMERCIALLY CLEARED",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF81C784)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Cleared for commercial use, app store distribution, offline execution, and derivative audio altering.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Column {
                    Text(
                        text = "Voice Actor & Studio Origin:",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelTextSecondary
                    )
                    Text(
                        text = "${voicePack.actorName} (${voicePack.accentLabel})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column {
                    Text(
                        text = "License Type:",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelTextSecondary
                    )
                    Text(
                        text = voicePack.licenseType,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CommandAmber,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column {
                    Text(
                        text = "Legal Rights to Alter & Transform:",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelTextSecondary
                    )
                    Text(
                        text = "• Pitch Transposition (0.75x – 1.25x)\n• Resonant Vocal-Tract Formant Shifting (-50% to +50%)\n• Studio EQ, Overdrive Saturation & Offline Caching\n• Zero Royalties or Ongoing Telemetry Requirements",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column {
                    Text(
                        text = "Legal Attribution & Clearance Details:",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelTextSecondary
                    )
                    Surface(
                        color = TacticalSurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = voicePack.legalAttribution,
                            style = MaterialTheme.typography.bodySmall,
                            color = SteelTextSecondary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                modifier = Modifier.testTag("dismiss_license_modal")
            ) {
                Text("Understood", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}

