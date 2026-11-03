package com.example.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.CommandCrimson
import com.example.ui.theme.MissionEmerald
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

enum class CoachAvatarIndividual {
    DRILL_SERGEANT,
    PERSONAL_LIFE_COACH,
    CORPORATE_EXECUTIVE
}

/**
 * Resolves whether a given personality ID or voice pack ID belongs to the
 * Personal / Life Coach individual, Corporate Executive, or Military Drill Sergeant.
 */
fun resolveCoachIndividual(
    personalityId: String? = null,
    voicePackId: String? = null
): CoachAvatarIndividual {
    val pId = personalityId?.lowercase().orEmpty()
    val vId = voicePackId?.lowercase().orEmpty()

    if (
        pId.contains("executive") ||
        pId.contains("vance") ||
        vId.contains("eleanor") ||
        vId.contains("executive") ||
        vId == "pack_eleanor_vance"
    ) {
        return CoachAvatarIndividual.CORPORATE_EXECUTIVE
    }

    if (
        pId == "supportive_coach" ||
        pId == "accountability_partner" ||
        pId.contains("coach") ||
        pId.contains("motivational") ||
        vId == "pack_coach_ray" ||
        vId == "pack_coach_maya" ||
        vId == "pack_marcus_vance" ||
        vId == "pack_aris_thorne" ||
        vId.contains("coach")
    ) {
        return CoachAvatarIndividual.PERSONAL_LIFE_COACH
    }
    return CoachAvatarIndividual.DRILL_SERGEANT
}

/**
 * Animated portrait component that separates the Personal/Life Coach (blended from the
 * 3 uploaded trainer photos) from the Drill Sergeant, and animates the Life Coach
 * with drill-sergeant-level kinetic intensity, multi-pose morphing, vocal shockwaves,
 * and drill-cadence bark callouts.
 */
@Composable
fun AnimatedCoachAvatar(
    individual: CoachAvatarIndividual,
    isSpeaking: Boolean = false,
    size: Dp = 84.dp,
    cornerRadius: Dp = 16.dp,
    showStatusBadge: Boolean = true,
    enablePoseCycle: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "coach_drill_anim")

    // High-energy drill-sergeant cadence bounce & scale when speaking, steady athletic breathing when idle
    val cadenceScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isSpeaking) 1.09f else 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeaking) 260 else 1400,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cadence_scale"
    )

    // Subtle commanding head-tilt / drill-bark vibration when speaking
    val barkTiltDegrees by infiniteTransition.animateFloat(
        initialValue = if (isSpeaking) -2.2f else -0.4f,
        targetValue = if (isSpeaking) 2.2f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeaking) 180 else 1800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bark_tilt"
    )

    // Rotating kinetic radar sweep angle
    val radarSweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking) 1800 else 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_sweep"
    )

    // Shockwave ring expansion
    val shockwaveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking) 650 else 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shockwave"
    )

    // Cycle through the blended portrait + the 3 aligned trainer stances when speaking or active
    // so the Life Coach dynamically shifts between his blended portrait, clipboard check,
    // crossed-arms drill scrutiny, and stopwatch/whistle bark!
    var activePoseIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(individual, isSpeaking, enablePoseCycle) {
        if (individual == CoachAvatarIndividual.PERSONAL_LIFE_COACH && enablePoseCycle) {
            while (true) {
                delay(if (isSpeaking) 650L else 2800L)
                activePoseIndex = (activePoseIndex + 1) % 4
            }
        } else {
            activePoseIndex = 0
        }
    }

    val primaryAccent = when (individual) {
        CoachAvatarIndividual.PERSONAL_LIFE_COACH -> if (isSpeaking) CommandAmber else MissionEmerald
        CoachAvatarIndividual.DRILL_SERGEANT -> if (isSpeaking) CommandCrimson else CommandAmber
        CoachAvatarIndividual.CORPORATE_EXECUTIVE -> if (isSpeaking) Color(0xFFFFD700) else Color(0xFF818CF8)
    }

    val secondaryAccent = when (individual) {
        CoachAvatarIndividual.PERSONAL_LIFE_COACH -> Color(0xFF38BDF8)
        CoachAvatarIndividual.DRILL_SERGEANT -> CommandAmber
        CoachAvatarIndividual.CORPORATE_EXECUTIVE -> Color(0xFF38BDF8)
    }

    val calloutText = when (individual) {
        CoachAvatarIndividual.PERSONAL_LIFE_COACH -> {
            if (isSpeaking) {
                when (activePoseIndex) {
                    0 -> "COACH BARK!"
                    1 -> "CLIPBOARD CHECK!"
                    2 -> "NO EXCUSES!"
                    else -> "ON THE CLOCK!"
                }
            } else {
                "LIFE COACH"
            }
        }
        CoachAvatarIndividual.DRILL_SERGEANT -> {
            if (isSpeaking) "MCRD BARK!" else "DRILL SGT"
        }
        CoachAvatarIndividual.CORPORATE_EXECUTIVE -> {
            if (isSpeaking) "EXECUTIVE BRIEF" else "DIRECTOR"
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size)
    ) {
        // Outer Canvas for drill-intensity sonic shockwaves & kinetic whistle/cadence rings
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = (minOf(this.size.width, this.size.height) / 2f)
            val centerOffset = Offset(this.size.width / 2f, this.size.height / 2f)

            if (isSpeaking) {
                // Expanding vocal pressure shockwave ring
                drawCircle(
                    color = primaryAccent.copy(alpha = (1f - shockwaveProgress) * 0.65f),
                    radius = radius * (0.88f + shockwaveProgress * 0.24f),
                    center = centerOffset,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // Kinetic rotating drill-intensity arc segments
            val angleRad = Math.toRadians(radarSweepAngle.toDouble())
            val dotX = centerOffset.x + (radius * 0.94f * cos(angleRad)).toFloat()
            val dotY = centerOffset.y + (radius * 0.94f * sin(angleRad)).toFloat()
            drawCircle(
                color = primaryAccent,
                radius = if (isSpeaking) 3.5.dp.toPx() else 2.dp.toPx(),
                center = Offset(dotX, dotY)
            )
        }

        // Portrait Frame with cadence scale & drill-bark tilt
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .scale(cadenceScale)
                .graphicsLayer {
                    rotationZ = barkTiltDegrees
                }
                .clip(RoundedCornerShape(cornerRadius))
                .border(
                    width = if (isSpeaking) 2.5.dp else 2.dp,
                    brush = Brush.linearGradient(listOf(primaryAccent, secondaryAccent)),
                    shape = RoundedCornerShape(cornerRadius)
                )
                .background(Color(0xFF0F172A))
        ) {
            when (individual) {
                CoachAvatarIndividual.PERSONAL_LIFE_COACH -> {
                    val drawableRes: Int = when (activePoseIndex) {
                        0 -> R.drawable.img_life_coach_avatar
                        1 -> R.drawable.img_life_coach_frame1
                        2 -> R.drawable.img_life_coach_frame2
                        else -> R.drawable.img_life_coach_frame3
                    }
                    Crossfade(
                        targetState = drawableRes,
                        animationSpec = tween(durationMillis = if (isSpeaking) 240 else 600),
                        label = "life_coach_pose_blend"
                    ) { resId: Int ->
                        Image(
                            painter = painterResource(id = resId),
                            contentDescription = stringResource(R.string.life_coach_avatar_desc),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                CoachAvatarIndividual.CORPORATE_EXECUTIVE -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF2E1065),
                                        Color(0xFF0F172A),
                                        Color(0xFF020617)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF818CF8).copy(alpha = 0.25f),
                                border = BorderStroke(1.5.dp, Color(0xFFFFD700)),
                                modifier = Modifier.size((size.value * 0.45f).coerceAtLeast(24f).dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "EV",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFFFD700),
                                        fontSize = (size.value * 0.20f).coerceAtLeast(10f).sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "FORTUNE 500",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF818CF8),
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
                CoachAvatarIndividual.DRILL_SERGEANT -> {
                    Image(
                        painter = painterResource(id = R.drawable.drill_sergeant_avatar),
                        contentDescription = stringResource(R.string.drill_sergeant_avatar_desc),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Live vocal equalizer bars overlay at the bottom of the portrait when speaking
            if (isSpeaking) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (showStatusBadge) 16.dp else 4.dp)
                ) {
                    LiveVocalEqualizerBars(
                        accentColor = primaryAccent,
                        phaseProgress = shockwaveProgress
                    )
                }
            }

            // Bottom tactical / coach identity badge
            if (showStatusBadge && size >= 64.dp) {
                Surface(
                    color = Color(0xDD090D16),
                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (isSpeaking) CommandAmber else primaryAccent)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = calloutText,
                            color = if (isSpeaking) CommandAmber else Color.White,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveVocalEqualizerBars(
    accentColor: Color,
    modifier: Modifier = Modifier,
    phaseProgress: Float? = null
) {
    val progress = if (phaseProgress != null) {
        phaseProgress
    } else {
        val transition = rememberInfiniteTransition(label = "eq_bars")
        val anim by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "eq_phase"
        )
        anim
    }

    Canvas(
        modifier = modifier
            .width(36.dp)
            .height(18.dp)
    ) {
        val barCount = 5
        val spacing = size.width / barCount
        for (i in 0 until barCount) {
            val wave = kotlin.math.abs(sin((progress * 6.28f) + i * 1.1f)).toFloat()
            val barHeight = (size.height * (0.25f + 0.75f * wave)).coerceAtLeast(3f)
            val x = (i * spacing) + spacing / 2f
            drawLine(
                color = accentColor,
                start = Offset(x, size.height),
                end = Offset(x, size.height - barHeight),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}
