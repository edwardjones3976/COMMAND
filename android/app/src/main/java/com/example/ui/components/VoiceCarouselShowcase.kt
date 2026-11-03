package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.VoicePackEntity
import com.example.service.VoicePlaybackState
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.CommandCrimson
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import kotlinx.coroutines.launch

/**
 * Interactive Carousel Showcase: Full-bleed swipeable cards displaying animated portraits,
 * audio telemetry metrics, real-time waveform visualizers, execution phase audition chips, and one-touch activation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceCarouselShowcase(
    voicePacks: List<VoicePackEntity>,
    selectedVoicePackId: String,
    playbackState: VoicePlaybackState,
    onPreviewVoicePack: (VoicePackEntity) -> Unit,
    onStopPreview: () -> Unit,
    onSelectVoicePack: (VoicePackEntity) -> Unit,
    onPreviewPhrase: ((VoicePackEntity, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (voicePacks.isEmpty()) return

    val initialIndex = voicePacks.indexOfFirst { it.id == selectedVoicePackId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { voicePacks.size }
    )
    val coroutineScope = rememberCoroutineScope()
    var selectedPhaseMap by remember { mutableStateOf(mapOf<String, String>()) }

    LaunchedEffect(selectedVoicePackId) {
        val targetIdx = voicePacks.indexOfFirst { it.id == selectedVoicePackId }
        if (targetIdx >= 0 && targetIdx != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetIdx)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_carousel_showcase"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 4.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("voice_carousel_pager")
        ) { page ->
            val pack = voicePacks[page]
            val isSelected = pack.id == selectedVoicePackId
            val isSpeakingThis = playbackState.isSpeaking && playbackState.activeVoicePackId == pack.id
            val coachIndividual = resolveCoachIndividual(voicePackId = pack.id)

            val primaryAccent = when (coachIndividual) {
                CoachAvatarIndividual.DRILL_SERGEANT -> CommandAmber
                CoachAvatarIndividual.PERSONAL_LIFE_COACH -> MissionEmerald
                CoachAvatarIndividual.CORPORATE_EXECUTIVE -> Color(0xFFFFD700)
            }
            val secondaryAccent = when (coachIndividual) {
                CoachAvatarIndividual.DRILL_SERGEANT -> CommandCrimson
                CoachAvatarIndividual.PERSONAL_LIFE_COACH -> Color(0xFF38BDF8)
                CoachAvatarIndividual.CORPORATE_EXECUTIVE -> Color(0xFF818CF8)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("carousel_card_${pack.id}")
                    .clickable {
                        onSelectVoicePack(pack)
                        if (!isSpeakingThis) {
                            onPreviewVoicePack(pack)
                        }
                    },
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    brush = if (isSelected) {
                        Brush.horizontalGradient(listOf(primaryAccent, secondaryAccent))
                    } else {
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        )
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Banner
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = primaryAccent.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (coachIndividual) {
                                        CoachAvatarIndividual.DRILL_SERGEANT -> Icons.Default.Shield
                                        CoachAvatarIndividual.PERSONAL_LIFE_COACH -> Icons.Default.FitnessCenter
                                        CoachAvatarIndividual.CORPORATE_EXECUTIVE -> Icons.Default.Business
                                    },
                                    contentDescription = null,
                                    tint = primaryAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (coachIndividual) {
                                        CoachAvatarIndividual.DRILL_SERGEANT -> "USMC DRILL INSTRUCTOR"
                                        CoachAvatarIndividual.PERSONAL_LIFE_COACH -> "RADIANT MOTIVATION"
                                        CoachAvatarIndividual.CORPORATE_EXECUTIVE -> "FORTUNE 500 EXECUTIVE"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = primaryAccent,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        if (isSelected) {
                            Surface(
                                color = MissionEmerald.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MissionEmerald)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MissionEmerald,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "ACTIVE DIRECTOR",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MissionEmerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Animated Coach Avatar
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(136.dp)
                    ) {
                        AnimatedCoachAvatar(
                            individual = coachIndividual,
                            isSpeaking = isSpeakingThis,
                            size = 126.dp,
                            cornerRadius = 22.dp,
                            showStatusBadge = true,
                            enablePoseCycle = true
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = pack.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pack.accentLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Live Waveform Equalizer
                    LiveWaveformVisualizer(
                        isPlaying = isSpeakingThis,
                        primaryColor = primaryAccent,
                        secondaryColor = secondaryAccent,
                        spectrumBands = if (isSpeakingThis) playbackState.spectrumBands else emptyList(),
                        barCount = 26,
                        height = 36.dp,
                        showPeakBadge = true,
                        label = if (isSpeakingThis) "PLAYING MASTER • ${pack.qualityTierLabel}" else "TAP CARD TO AUDITION"
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Audio Telemetry Metrics Grid
                    Surface(
                        color = TacticalSurfaceElevated,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                MetricColumn(
                                    label = "VOCAL GRIT",
                                    value = "${pack.vocalGritPercent}%",
                                    progress = pack.vocalGritPercent / 100f,
                                    color = primaryAccent,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                MetricColumn(
                                    label = "INTENSITY",
                                    value = "${pack.aggressionPercent}%",
                                    progress = pack.aggressionPercent / 100f,
                                    color = secondaryAccent,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                MetricColumn(
                                    label = "TEMPO RATE",
                                    value = "${String.format("%.2f", pack.speechRate)}x",
                                    progress = (pack.speechRate - 0.7f) / 0.8f,
                                    color = primaryAccent,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val phaseOptions = listOf(
                        "Quote" to pack.sampleQuote,
                        "Reveille" to pack.reveilleScript,
                        "Handshake" to pack.handshakeSuccessScript,
                        "5-Min Starter" to pack.starterScript,
                        "Check-In" to "Accountability check. Confirm your momentum and report current status.",
                        "Reset" to "Zero guilt, clean reset. Downscale task scope to 5 minutes and restart."
                    )
                    val activePhaseName = selectedPhaseMap[pack.id] ?: "Quote"
                    val activePhraseText = phaseOptions.firstOrNull { it.first == activePhaseName }?.second ?: pack.sampleQuote

                    // Execution Phase Audition Selector
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "AUDITION EXECUTION PHASE SCRIPT",
                                style = MaterialTheme.typography.labelSmall,
                                color = primaryAccent,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "AI SPECTRAL CLONE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MissionEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            phaseOptions.forEach { (phaseLabel, phrase) ->
                                val isPhaseActive = activePhaseName == phaseLabel
                                FilterChip(
                                    selected = isPhaseActive,
                                    onClick = {
                                        selectedPhaseMap = selectedPhaseMap + (pack.id to phaseLabel)
                                        if (onPreviewPhrase != null) {
                                            onPreviewPhrase(pack, phrase)
                                        } else {
                                            onPreviewVoicePack(pack)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = phaseLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isPhaseActive) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = primaryAccent.copy(alpha = 0.25f),
                                        selectedLabelColor = primaryAccent
                                    ),
                                    modifier = Modifier.testTag("phase_chip_${pack.id}_${phaseLabel.lowercase().replace(" ", "_").replace("-", "_")}")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quote Callout Box
                    Surface(
                        color = TacticalSurfaceElevated.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "ACTIVE SCRIPT ($activePhaseName):",
                                style = MaterialTheme.typography.labelSmall,
                                color = primaryAccent,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "\"$activePhraseText\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.90f),
                                textAlign = TextAlign.Start,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Direct Interactive Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isSpeakingThis) {
                                    onStopPreview()
                                } else {
                                    if (onPreviewPhrase != null) {
                                        onPreviewPhrase(pack, activePhraseText)
                                    } else {
                                        onPreviewVoicePack(pack)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSpeakingThis) CommandCrimson else primaryAccent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("carousel_audition_button_${pack.id}")
                        ) {
                            Icon(
                                imageVector = if (isSpeakingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSpeakingThis) "Stop Voice" else "Audition ($activePhaseName)",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        if (!isSelected) {
                            OutlinedButton(
                                onClick = {
                                    onSelectVoicePack(pack)
                                    if (onPreviewPhrase != null) {
                                        onPreviewPhrase(pack, activePhraseText)
                                    } else {
                                        onPreviewVoicePack(pack)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MissionEmerald),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("carousel_activate_button_${pack.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = MissionEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Make Director",
                                    color = MissionEmerald,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        } else {
                            Surface(
                                color = MissionEmerald.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MissionEmerald),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "Current Director",
                                        color = MissionEmerald,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Pagination indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (pagerState.currentPage > 0) {
                        coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }
                },
                enabled = pagerState.currentPage > 0,
                modifier = Modifier.testTag("carousel_prev_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Previous Voice Pack",
                    tint = if (pagerState.currentPage > 0) CommandAmber else SteelTextSecondary.copy(alpha = 0.3f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                voicePacks.indices.forEach { index ->
                    val isCurrent = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .size(if (isCurrent) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) CommandAmber else Color.White.copy(alpha = 0.25f)
                            )
                            .clickable {
                                coroutineScope.launch { pagerState.animateScrollToPage(index) }
                            }
                    )
                }
            }

            IconButton(
                onClick = {
                    if (pagerState.currentPage < voicePacks.size - 1) {
                        coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                enabled = pagerState.currentPage < voicePacks.size - 1,
                modifier = Modifier.testTag("carousel_next_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next Voice Pack",
                    tint = if (pagerState.currentPage < voicePacks.size - 1) CommandAmber else SteelTextSecondary.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = SteelTextSecondary,
                fontSize = 9.sp
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = Color.White.copy(alpha = 0.08f),
            strokeCap = StrokeCap.Round
        )
    }
}
