package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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

data class SoundboardPadSpec(
    val title: String,
    val subtitle: String,
    val spokenQuote: String
)

/**
 * Tactical Soundboard Grid: High-density MPC-style tap pads grouped by persona
 * (Drill Sergeant vs. Motivational Coach vs. Corporate Executive) with instant phrase barks,
 * live audio wave ripples, and one-tap voice pack selection.
 */
@Composable
fun TacticalSoundboardGrid(
    voicePacks: List<VoicePackEntity>,
    selectedVoicePackId: String,
    playbackState: VoicePlaybackState,
    onPreviewVoicePack: (VoicePackEntity) -> Unit,
    onStopPreview: () -> Unit,
    onSelectVoicePack: (VoicePackEntity) -> Unit,
    onPreviewPhrase: ((VoicePackEntity, String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val stonePack = voicePacks.find { it.id == "pack_major_stone" }
        ?: voicePacks.find { it.id.contains("stone") || it.usesDrillSergeantArt }
        ?: voicePacks.firstOrNull()

    val jordanPack = voicePacks.find { it.id == "pack_coach_ray" }
        ?: voicePacks.find { it.id.contains("jordan") || it.id.contains("fitness") }

    val mayaPack = voicePacks.find { it.id == "pack_coach_maya" }
        ?: voicePacks.find { it.id.contains("maya") }

    val executivePack = voicePacks.find { it.id == "pack_eleanor_vance" }
        ?: voicePacks.find { it.id.contains("eleanor") || it.id.contains("executive") }
        ?: voicePacks.getOrNull(2)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tactical_soundboard_grid"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Deck 1: Drill Sergeant Deck (Stone)
        stonePack?.let { pack ->
            val pads = listOf(
                SoundboardPadSpec("EYEBALLS! SNAP TO!", "MCRD Pick-Up Day", "EYEBALLS! SNAP TO RIGHT NOW!"),
                SoundboardPadSpec("MOVE! NO EXCUSES!", "Diaphragm Roar", "MOVE! OBLITERATE ALL EXCUSES!"),
                SoundboardPadSpec("SQUAD BAY REVEILLE", "Morning Alert", "REVEILLE ON DECK! GET OFF THAT RACK!"),
                SoundboardPadSpec("5-MINUTE SPRINT", "Parade Cadence", "GIVE ME FIVE MINUTES OF PURE EXECUTION!")
            )
            SoundboardDeckCard(
                deckTitle = "USMC DRILL INSTRUCTOR DECK",
                deckSubtitle = "GySgt. Marcus Stone • Guttural Diaphragm Roar & Barks",
                individual = CoachAvatarIndividual.DRILL_SERGEANT,
                pack = pack,
                pads = pads,
                primaryColor = CommandAmber,
                secondaryColor = CommandCrimson,
                isSelected = pack.id == selectedVoicePackId,
                isPlaying = playbackState.isSpeaking && playbackState.activeVoicePackId == pack.id,
                spectrumBands = playbackState.spectrumBands,
                onPadTap = { pad ->
                    onSelectVoicePack(pack)
                    if (onPreviewPhrase != null) {
                        onPreviewPhrase(pack, pad.spokenQuote)
                    } else {
                        onPreviewVoicePack(pack)
                    }
                },
                onStopTap = onStopPreview
            )
        }

        // Deck 2: Personal & Life Coach Deck (Coach Jordan Vance)
        jordanPack?.let { pack ->
            val pads = listOf(
                SoundboardPadSpec("RESET & LOCK IN", "Solid Baritone", "Take a breath, reset your shoulders, and lock in."),
                SoundboardPadSpec("OWN THIS REP", "Steady Conviction", "We're not rushing this rep—we're owning it."),
                SoundboardPadSpec("YOU SHOWED UP", "Natural Ease", "You've already done the hard part by showing up."),
                SoundboardPadSpec("ONE SOLID SET", "Clear Focus", "Now let's just execute one solid set at a time.")
            )
            SoundboardDeckCard(
                deckTitle = "PERSONAL & LIFE COACH DECK",
                deckSubtitle = "Coach Jordan Vance • Effortless Rich Baritone & Reassuring Guidance",
                individual = CoachAvatarIndividual.PERSONAL_LIFE_COACH,
                pack = pack,
                pads = pads,
                primaryColor = Color(0xFF00E5A3),
                secondaryColor = Color(0xFF38BDF8),
                isSelected = pack.id == selectedVoicePackId,
                isPlaying = playbackState.isSpeaking && playbackState.activeVoicePackId == pack.id,
                spectrumBands = playbackState.spectrumBands,
                onPadTap = { pad ->
                    onSelectVoicePack(pack)
                    if (onPreviewPhrase != null) {
                        onPreviewPhrase(pack, pad.spokenQuote)
                    } else {
                        onPreviewVoicePack(pack)
                    }
                },
                onStopTap = onStopPreview
            )
        }

        // Deck 3: Radiant Motivational Coach Deck (Maya Lin)
        mayaPack?.let { pack ->
            val pads = listOf(
                SoundboardPadSpec("OWN YOUR ENERGY!", "Radiant Spark", "Own your energy! Claim this morning!"),
                SoundboardPadSpec("STEP INTO POWER", "Empowering Cadence", "Step into your power right now!"),
                SoundboardPadSpec("GOALS INEVITABLE", "Magnetic Optimism", "Make ambitious goals feel completely inevitable!"),
                SoundboardPadSpec("UNSTOPPABLE SURGE", "Daily Catalyst", "An unstoppable surge of confidence before feet touch the floor!")
            )
            SoundboardDeckCard(
                deckTitle = "RADIANT MOTIVATIONAL COACH DECK",
                deckSubtitle = "Coach Maya Lin • Magnetic Optimism & Radiant Pacing",
                individual = CoachAvatarIndividual.PERSONAL_LIFE_COACH,
                pack = pack,
                pads = pads,
                primaryColor = MissionEmerald,
                secondaryColor = Color(0xFF38BDF8),
                isSelected = pack.id == selectedVoicePackId,
                isPlaying = playbackState.isSpeaking && playbackState.activeVoicePackId == pack.id,
                spectrumBands = playbackState.spectrumBands,
                onPadTap = { pad ->
                    onSelectVoicePack(pack)
                    if (onPreviewPhrase != null) {
                        onPreviewPhrase(pack, pad.spokenQuote)
                    } else {
                        onPreviewVoicePack(pack)
                    }
                },
                onStopTap = onStopPreview
            )
        }

        // Deck 4: Corporate Executive Deck (Director Eleanor Vance)
        executivePack?.let { pack ->
            val pads = listOf(
                SoundboardPadSpec("THE BOTTOM LINE", "Boardroom Authority", "Let's look at the bottom line."),
                SoundboardPadSpec("DELIVER ON THIS", "Surgical Precision", "Here is how we deliver on this with complete precision."),
                SoundboardPadSpec("MOVE WITH INTENTION", "Strategic Focus", "We move forward with intention and unwavering discipline."),
                SoundboardPadSpec("MEASURED EXCELLENCE", "Executive Standard", "Expect nothing short of excellence from everyone at the table.")
            )
            SoundboardDeckCard(
                deckTitle = "CORPORATE EXECUTIVE DECK",
                deckSubtitle = "Director Eleanor Vance • Velvet-Textured Boardroom Authority",
                individual = CoachAvatarIndividual.CORPORATE_EXECUTIVE,
                pack = pack,
                pads = pads,
                primaryColor = Color(0xFFFFD700),
                secondaryColor = Color(0xFF818CF8),
                isSelected = pack.id == selectedVoicePackId,
                isPlaying = playbackState.isSpeaking && playbackState.activeVoicePackId == pack.id,
                spectrumBands = playbackState.spectrumBands,
                onPadTap = { pad ->
                    onSelectVoicePack(pack)
                    if (onPreviewPhrase != null) {
                        onPreviewPhrase(pack, pad.spokenQuote)
                    } else {
                        onPreviewVoicePack(pack)
                    }
                },
                onStopTap = onStopPreview
            )
        }
    }
}

@Composable
private fun SoundboardDeckCard(
    deckTitle: String,
    deckSubtitle: String,
    individual: CoachAvatarIndividual,
    pack: VoicePackEntity,
    pads: List<SoundboardPadSpec>,
    primaryColor: Color,
    secondaryColor: Color,
    isSelected: Boolean,
    isPlaying: Boolean,
    spectrumBands: List<Float>,
    onPadTap: (SoundboardPadSpec) -> Unit,
    onStopTap: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("soundboard_deck_${pack.id}"),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            brush = if (isSelected) {
                Brush.horizontalGradient(listOf(primaryColor, secondaryColor))
            } else {
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    )
                )
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Deck Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedCoachAvatar(
                        individual = individual,
                        isSpeaking = isPlaying,
                        size = 56.dp,
                        cornerRadius = 14.dp,
                        showStatusBadge = false,
                        enablePoseCycle = false
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (individual) {
                                    CoachAvatarIndividual.DRILL_SERGEANT -> Icons.Default.Shield
                                    CoachAvatarIndividual.PERSONAL_LIFE_COACH -> Icons.Default.FitnessCenter
                                    CoachAvatarIndividual.CORPORATE_EXECUTIVE -> Icons.Default.Business
                                },
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = deckTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = primaryColor,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Text(
                            text = deckSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = SteelTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isSelected) {
                    Surface(
                        color = MissionEmerald.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp),
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
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MissionEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Waveform strip for active deck
            if (isPlaying) {
                LiveWaveformVisualizer(
                    isPlaying = true,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    spectrumBands = spectrumBands,
                    barCount = 28,
                    height = 24.dp,
                    showPeakBadge = false
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 2x2 Tactical MPC Soundboard Pads Grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MpcPadButton(
                        pad = pads[0],
                        primaryColor = primaryColor,
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("soundboard_pad_${pack.id}_0"),
                        onClick = { onPadTap(pads[0]) }
                    )
                    MpcPadButton(
                        pad = pads[1],
                        primaryColor = primaryColor,
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("soundboard_pad_${pack.id}_1"),
                        onClick = { onPadTap(pads[1]) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MpcPadButton(
                        pad = pads[2],
                        primaryColor = primaryColor,
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("soundboard_pad_${pack.id}_2"),
                        onClick = { onPadTap(pads[2]) }
                    )
                    MpcPadButton(
                        pad = pads[3],
                        primaryColor = primaryColor,
                        isPlaying = isPlaying,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("soundboard_pad_${pack.id}_3"),
                        onClick = { onPadTap(pads[3]) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MpcPadButton(
    pad: SoundboardPadSpec,
    primaryColor: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pad_glow")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Surface(
        modifier = modifier
            .height(64.dp)
            .clickable(onClick = onClick),
        color = TacticalSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isPlaying) 1.5.dp else 1.dp,
            color = if (isPlaying) primaryColor.copy(alpha = pulseAlpha) else Color.White.copy(alpha = 0.12f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text(
                    text = pad.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pad.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelTextSecondary,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isPlaying) primaryColor else SteelTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.CenterEnd)
            )
        }
    }
}
