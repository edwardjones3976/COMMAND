package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.TacticalSurfaceElevated
import kotlin.math.sin

/**
 * High-definition live audio waveform equalizer with multi-band frequency bars,
 * responsive to audio playback and DSP frequency modulation.
 */
@Composable
fun LiveWaveformVisualizer(
    isPlaying: Boolean,
    primaryColor: Color = CommandAmber,
    secondaryColor: Color = MissionEmerald,
    spectrumBands: List<Float> = emptyList(),
    barCount: Int = 24,
    height: Dp = 48.dp,
    showPeakBadge: Boolean = true,
    label: String = "NEURAL DSP WAVEFORM",
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")

    // Phase transitions for smooth fluid bar dancing during speech
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TacticalSurfaceElevated)
            .border(
                1.dp,
                if (isPlaying) primaryColor.copy(alpha = glowAlpha) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("live_waveform_visualizer")
    ) {
        Column {
            if (showPeakBadge) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = if (isPlaying) primaryColor else SteelTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isPlaying) primaryColor else SteelTextSecondary,
                            letterSpacing = 1.sp
                        )
                    }

                    Text(
                        text = if (isPlaying) "44.1kHz • LIVE OUTPUT" else "STANDBY / 0 dB",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPlaying) secondaryColor else SteelTextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val totalBars = barCount
                val gap = 3.dp.toPx()
                val barWidth = (canvasWidth - (gap * (totalBars - 1))) / totalBars

                for (i in 0 until totalBars) {
                    val normalizedIndex = i.toFloat() / totalBars.toFloat()

                    val heightFraction: Float = if (isPlaying) {
                        if (spectrumBands.isNotEmpty()) {
                            val bandIdx = (i * spectrumBands.size / totalBars).coerceIn(0, spectrumBands.lastIndex)
                            val raw = spectrumBands[bandIdx]
                            (raw.coerceIn(0.12f, 0.98f))
                        } else {
                            val waveA = ((sin(normalizedIndex * 6.28f + phase1) + 1f) / 2f)
                            val waveB = ((sin(normalizedIndex * 12.56f - phase2) + 1f) / 2f)
                            val centerEmphasis = 1f - (kotlin.math.abs(normalizedIndex - 0.5f) * 0.8f)
                            ((waveA * 0.6f + waveB * 0.4f) * centerEmphasis).coerceIn(0.15f, 0.95f)
                        }
                    } else {
                        // Resting state harmonic baseline
                        (0.08f + 0.05f * sin(normalizedIndex * 3.14f))
                    }

                    val barHeight = (canvasHeight * heightFraction).coerceAtLeast(3.dp.toPx())
                    val left = i * (barWidth + gap)
                    val top = (canvasHeight - barHeight) / 2f

                    val barBrush = Brush.verticalGradient(
                        colors = if (isPlaying) {
                            listOf(
                                primaryColor,
                                secondaryColor
                            )
                        } else {
                            listOf(
                                primaryColor.copy(alpha = 0.25f),
                                primaryColor.copy(alpha = 0.12f)
                            )
                        },
                        startY = top,
                        endY = top + barHeight
                    )

                    drawRoundRect(
                        brush = barBrush,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }
        }
    }
}
