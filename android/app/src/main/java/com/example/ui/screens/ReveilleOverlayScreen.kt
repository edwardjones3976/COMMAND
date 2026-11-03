package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.HandshakeType
import com.example.data.MissionStatus
import com.example.service.VoicePlaybackState
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.CommandCrimson
import com.example.ui.theme.DrillOlive
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated
import com.example.viewmodel.ActiveExecutionPhase
import com.example.viewmodel.ActiveExecutionSession
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReveilleOverlayScreen(
    session: ActiveExecutionSession,
    playbackState: VoicePlaybackState,
    onReplayVoice: () -> Unit,
    onHandshakeVerified: () -> Unit,
    onToggleStarterPause: () -> Unit,
    onAdvanceToReporting: () -> Unit,
    onSubmitReport: (MissionStatus) -> Unit,
    onDismissSession: () -> Unit
) {
    BackHandler {
        onDismissSession()
    }

    val pulseTransition = rememberInfiniteTransition(label = "reveille_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (playbackState.isSpeaking) 1.07f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatar_scale"
    )

    var selectedHandshake by remember(session.mission.id) {
        mutableStateOf(
            runCatching { HandshakeType.valueOf(session.mission.handshakeType) }
                .getOrDefault(HandshakeType.HOLD)
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("reveille_overlay_screen"),
        color = ObsidianBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar with status pill and safety silence close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = CommandCrimson.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, CommandCrimson)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = CommandCrimson,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (session.phase) {
                                ActiveExecutionPhase.REVEILLE_HANDSHAKE -> "STAGE 1/3 • REVEILLE HANDSHAKE"
                                ActiveExecutionPhase.FIVE_MINUTE_STARTER -> "STAGE 2/3 • 5-MINUTE STARTER"
                                ActiveExecutionPhase.LIGHTWEIGHT_REPORTING -> "STAGE 3/3 • SELF-REPORT"
                                ActiveExecutionPhase.IDLE -> "COMMAND ACTIVE"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = CommandCrimson
                        )
                    }
                }

                IconButton(
                    onClick = onDismissSession,
                    modifier = Modifier.testTag("safety_silence_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Safety Silence / Dismiss Reveille",
                        tint = SteelTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Director Portrait + Spoken Command Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(
                    width = 1.5.dp,
                    brush = Brush.horizontalGradient(
                        listOf(CommandAmber, DrillOlive, CommandCrimson)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val coachIndividual = com.example.ui.components.resolveCoachIndividual(
                            personalityId = session.mission.personalityId,
                            voicePackId = session.voicePack.id
                        )
                        com.example.ui.components.AnimatedCoachAvatar(
                            individual = coachIndividual,
                            isSpeaking = true,
                            size = 86.dp,
                            showStatusBadge = true
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.voicePack.name.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = CommandAmber
                            )
                            Text(
                                text = "${session.voicePack.actorName} • ${session.voicePack.accentLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SteelTextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = onReplayVoice,
                                modifier = Modifier.testTag("replay_reveille_voice_button"),
                                contentPadding = ButtonDefaults.ContentPadding
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Speak command again",
                                    tint = CommandAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (playbackState.isSpeaking) "SPEAKING..." else "SPEAK DIRECTIVE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CommandAmber
                                )
                            }
                        }
                    }

                    if (playbackState.isSpeaking) {
                        Spacer(modifier = Modifier.height(10.dp))
                        com.example.ui.components.LiveWaveformVisualizer(
                            isPlaying = true,
                            primaryColor = CommandAmber,
                            secondaryColor = MissionEmerald,
                            spectrumBands = playbackState.spectrumBands,
                            barCount = 20,
                            height = 32.dp,
                            showPeakBadge = true,
                            label = playbackState.activeDspBadge
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { playbackState.progressFraction.coerceIn(0.05f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(50)),
                            color = CommandAmber,
                            trackColor = TacticalSurfaceElevated
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        color = TacticalSurfaceElevated,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "\"${session.activeSpokenBanner}\"",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mission Summary Banner
            Surface(
                color = TacticalSurfaceElevated,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "COMMITMENT TARGET",
                        style = MaterialTheme.typography.labelMedium,
                        color = MissionEmerald
                    )
                    Text(
                        text = session.mission.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "First Action: ${session.mission.firstActionPrompt}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CommandAmber
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Dynamic Content per Execution Stage
            when (session.phase) {
                ActiveExecutionPhase.REVEILLE_HANDSHAKE -> {
                    HandshakeStageContent(
                        selectedHandshake = selectedHandshake,
                        onSelectHandshake = { selectedHandshake = it },
                        onVerified = onHandshakeVerified
                    )
                }

                ActiveExecutionPhase.FIVE_MINUTE_STARTER -> {
                    FiveMinuteStarterContent(
                        remainingSeconds = session.starterRemainingSeconds,
                        isPaused = session.isStarterPaused,
                        onTogglePause = onToggleStarterPause,
                        onFinishStarter = onAdvanceToReporting
                    )
                }

                ActiveExecutionPhase.LIGHTWEIGHT_REPORTING -> {
                    LightweightReportingContent(
                        onSubmitReport = onSubmitReport
                    )
                }

                ActiveExecutionPhase.IDLE -> {}
            }
        }
    }
}

@Composable
private fun HandshakeStageContent(
    selectedHandshake: HandshakeType,
    onSelectHandshake: (HandshakeType) -> Unit,
    onVerified: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "COMPLETE REVEILLE HANDSHAKE",
                style = MaterialTheme.typography.titleLarge,
                color = CommandAmber
            )
            Text(
                text = "Switch or test any of the 3 active handshake protocols below:",
                style = MaterialTheme.typography.bodyMedium,
                color = SteelTextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(HandshakeType.HOLD, HandshakeType.SEQUENCE, HandshakeType.TYPING, HandshakeType.TWO_STEP).forEach { type ->
                    FilterChip(
                        selected = selectedHandshake == type,
                        onClick = { onSelectHandshake(type) },
                        label = {
                            Text(
                                text = type.displayName,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CommandAmber.copy(alpha = 0.22f),
                            selectedLabelColor = CommandAmber
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("handshake_mode_${type.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            when (selectedHandshake) {
                HandshakeType.HOLD -> HoldHandshakeChallenge(onVerified = onVerified)
                HandshakeType.SEQUENCE -> SequenceHandshakeChallenge(onVerified = onVerified)
                HandshakeType.TYPING -> TypingHandshakeChallenge(onVerified = onVerified)
                else -> HoldHandshakeChallenge(onVerified = onVerified)
            }
        }
    }
}

@Composable
private fun HoldHandshakeChallenge(onVerified: () -> Unit) {
    var isHolding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val stepMs = 40L
            val totalSteps = 45 // ~1.8 seconds sustained hold
            for (step in 1..totalSteps) {
                if (!isHolding) break
                delay(stepMs)
                progress = step.toFloat() / totalSteps
                if (progress >= 1f) {
                    onVerified()
                    break
                }
            }
        } else if (progress < 1f) {
            progress = 0f
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(164.dp)
                .clip(CircleShape)
                .background(
                    if (isHolding) CommandAmber.copy(alpha = 0.25f)
                    else TacticalSurfaceElevated
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isHolding = true
                            tryAwaitRelease()
                            isHolding = false
                        }
                    )
                }
                .testTag("hold_handshake_pad")
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = CommandAmber,
                strokeWidth = 10.dp,
                trackColor = MaterialTheme.colorScheme.outline
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = "Press and hold icon",
                    tint = CommandAmber,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isHolding) "HOLDING ${(progress * 100).toInt()}%" else "PRESS & HOLD",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onVerified,
            modifier = Modifier.testTag("instant_handshake_verify_button")
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MissionEmerald,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Complete Hold Acknowledgment Now")
        }
    }
}

@Composable
private fun SequenceHandshakeChallenge(onVerified: () -> Unit) {
    val targetOrder = listOf(1, 2, 3, 4)
    var nextExpected by remember { mutableIntStateOf(1) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Tap the tactical nodes in ascending order (1 → 2 → 3 → 4)",
            style = MaterialTheme.typography.bodyMedium,
            color = SteelTextSecondary
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            targetOrder.forEach { number ->
                val isDone = number < nextExpected
                Button(
                    onClick = {
                        if (number == nextExpected) {
                            if (number == 4) {
                                onVerified()
                            } else {
                                nextExpected++
                            }
                        } else {
                            nextExpected = 1
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDone) MissionEmerald else TacticalSurfaceElevated
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("sequence_node_$number")
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (isDone) Color.Black else CommandAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun TypingHandshakeChallenge(onVerified: () -> Unit) {
    val requiredPhrase = "I START NOW"
    var typedText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter commitment phrase: \"$requiredPhrase\"",
            style = MaterialTheme.typography.labelLarge,
            color = CommandAmber
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = typedText,
            onValueChange = {
                typedText = it
                if (it.trim().equals(requiredPhrase, ignoreCase = true)) {
                    onVerified()
                }
            },
            label = { Text("Type '$requiredPhrase'") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("typing_handshake_input")
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    typedText = requiredPhrase
                    onVerified()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("autofill_typing_handshake_button")
            ) {
                Text("Quick-Fill & Verify")
            }
        }
    }
}

@Composable
private fun FiveMinuteStarterContent(
    remainingSeconds: Int,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onFinishStarter: () -> Unit
) {
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val formatted = "%02d:%02d".format(minutes, seconds)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MissionEmerald)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "FIVE-MINUTE STARTER BLOCK",
                style = MaterialTheme.typography.labelLarge,
                color = MissionEmerald
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatted,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (300 - remainingSeconds).toFloat() / 300f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = MissionEmerald,
                trackColor = TacticalSurfaceElevated
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onTogglePause,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("starter_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isPaused) "Resume" else "Pause")
                }

                Button(
                    onClick = onFinishStarter,
                    colors = ButtonDefaults.buttonColors(containerColor = MissionEmerald),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("complete_starter_button")
                ) {
                    Text(
                        text = "Log Progress →",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightweightReportingContent(
    onSubmitReport: (MissionStatus) -> Unit
) {
    val reportStatuses = listOf(
        MissionStatus.STARTED,
        MissionStatus.WORKING,
        MissionStatus.COMPLETED,
        MissionStatus.NEED_RESET,
        MissionStatus.DELAYED,
        MissionStatus.SKIPPED
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CommandAmber)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = CommandAmber
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "WHAT HAPPENED NEXT?",
                    style = MaterialTheme.typography.titleLarge,
                    color = CommandAmber
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Zero mandatory receipts or screenshots. Select your honest operational state:",
                style = MaterialTheme.typography.bodyMedium,
                color = SteelTextSecondary
            )
            Spacer(modifier = Modifier.height(14.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2
            ) {
                reportStatuses.forEach { status ->
                    val isPositive = status == MissionStatus.COMPLETED ||
                            status == MissionStatus.STARTED ||
                            status == MissionStatus.WORKING
                    Button(
                        onClick = { onSubmitReport(status) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPositive) {
                                MissionEmerald.copy(alpha = 0.2f)
                            } else {
                                TacticalSurfaceElevated
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isPositive) MissionEmerald else MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .testTag("report_status_${status.name.lowercase()}")
                    ) {
                        Text(
                            text = status.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isPositive) MissionEmerald else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
