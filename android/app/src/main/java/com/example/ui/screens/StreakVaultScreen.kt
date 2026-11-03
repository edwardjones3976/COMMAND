package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.MILESTONE_BADGES
import com.example.data.MilestoneBadgeSpec
import com.example.data.StreakProfileEntity
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.CommandCrimson
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.StreakFlameOrange
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated

@Composable
fun StreakVaultScreen(
    streakProfile: StreakProfileEntity,
    isBriefingViewerOpen: Boolean,
    briefingRemainingSeconds: Int,
    onSimulateMissedDay: () -> Unit,
    onBuyBackWithCredits: () -> Unit,
    onStartBriefingBuyBack: () -> Unit,
    onCancelBriefing: () -> Unit
) {
    val unlockedSet = streakProfile.unlockedBadgesCsv
        .split(",")
        .filter { it.isNotBlank() }
        .toSet()

    val nextMilestone = MILESTONE_BADGES.firstOrNull { streakProfile.currentStreakDays < it.requiredDays }
        ?: MILESTONE_BADGES.last()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("streak_vault_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "COMMAND STREAK & MILESTONE VAULT",
                style = MaterialTheme.typography.labelLarge,
                color = CommandAmber
            )
            Text(
                text = "Consecutive Daily Execution Tracker",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Main Prominent Streak Hero Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("streak_hero_card"),
                colors = CardDefaults.cardColors(containerColor = TacticalSurface),
                border = BorderStroke(
                    width = 1.5.dp,
                    brush = Brush.horizontalGradient(
                        if (streakProfile.isStreakMissed) {
                            listOf(CommandCrimson, CommandAmber)
                        } else {
                            listOf(StreakFlameOrange, CommandAmber, MissionEmerald)
                        }
                    )
                ),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (streakProfile.isStreakMissed) CommandCrimson.copy(alpha = 0.2f)
                                        else StreakFlameOrange.copy(alpha = 0.2f)
                                    )
                            ) {
                                Icon(
                                    imageVector = if (streakProfile.isStreakMissed) {
                                        Icons.Default.WarningAmber
                                    } else {
                                        Icons.Default.LocalFireDepartment
                                    },
                                    contentDescription = stringResource(R.string.streak_flame_desc),
                                    tint = if (streakProfile.isStreakMissed) CommandCrimson else StreakFlameOrange,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "${streakProfile.currentStreakDays} DAY STREAK",
                                    style = MaterialTheme.typography.displayMedium,
                                    color = if (streakProfile.isStreakMissed) CommandCrimson else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (streakProfile.isStreakMissed) {
                                        "STREAK RESET TO 0 • PREVIOUS ${streakProfile.recoverableStreakDays}D RECOVERABLE"
                                    } else {
                                        "Longest Record: ${streakProfile.longestStreakDays} Days"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (streakProfile.isStreakMissed) CommandCrimson else SteelTextSecondary
                                )
                            }
                        }

                        // Command Credits Balance Pill
                        Surface(
                            color = TacticalSurfaceElevated,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, CommandAmber)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = CommandAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${streakProfile.commandCredits} CR",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CommandAmber
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val progressRatio = (streakProfile.currentStreakDays.toFloat() / nextMilestone.requiredDays.toFloat())
                        .coerceIn(0f, 1f)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Next Badge: ${nextMilestone.title} (${nextMilestone.requiredDays}d)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SteelTextSecondary
                        )
                        Text(
                            text = "${streakProfile.currentStreakDays}/${nextMilestone.requiredDays} days",
                            style = MaterialTheme.typography.labelMedium,
                            color = CommandAmber
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { progressRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50)),
                        color = StreakFlameOrange,
                        trackColor = TacticalSurfaceElevated
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Missed-Day Simulation Trigger so user can immediately validate buy-back
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Complete 1+ mission daily to advance your streak.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SteelTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onSimulateMissedDay,
                            modifier = Modifier.testTag("simulate_missed_day_button")
                        ) {
                            Text(
                                text = "Simulate Missed Day",
                                style = MaterialTheme.typography.labelMedium,
                                color = CommandAmber
                            )
                        }
                    }
                }
            }
        }

        // Missed-Day Buy-Back Recovery Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("missed_day_buyback_card"),
                colors = CardDefaults.cardColors(
                    containerColor = if (streakProfile.isStreakMissed) {
                        CommandCrimson.copy(alpha = 0.14f)
                    } else {
                        TacticalSurface
                    }
                ),
                border = BorderStroke(
                    1.dp,
                    if (streakProfile.isStreakMissed) CommandCrimson else MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            tint = if (streakProfile.isStreakMissed) CommandCrimson else CommandAmber
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (streakProfile.isStreakMissed) {
                                "MISSED DAY DETECTED — BUY BACK YOUR ${streakProfile.recoverableStreakDays}-DAY STREAK"
                            } else {
                                "STREAK SHIELD & MISSED-DAY BUY-BACK PROTOCOL"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (streakProfile.isStreakMissed) CommandCrimson else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (streakProfile.isStreakMissed) {
                            "Your streak reset to 0 after a missed day. Restore your ${streakProfile.recoverableStreakDays}-day momentum now using earned Command Credits or by watching a 15-second Drill Sergeant Recovery Briefing."
                        } else {
                            "If you miss a calendar day, your streak resets to 0—but you can buy back the missed day with 50 Command Credits or a 15-second Tactical Recovery Briefing. Tap 'Simulate Missed Day' above to test now."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelTextSecondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onBuyBackWithCredits,
                            enabled = streakProfile.isStreakMissed && streakProfile.commandCredits >= 50,
                            colors = ButtonDefaults.buttonColors(containerColor = CommandAmber),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("buyback_credits_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Buy Back (50 CR)",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        OutlinedButton(
                            onClick = onStartBriefingBuyBack,
                            enabled = streakProfile.isStreakMissed,
                            border = BorderStroke(1.dp, MissionEmerald),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("buyback_briefing_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.OndemandVideo,
                                contentDescription = null,
                                tint = MissionEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Watch 15s Briefing",
                                color = MissionEmerald,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }

        // Milestone Badges Header
        item {
            Text(
                text = "TACTICAL MILESTONE BADGES (3 • 7 • 14 • 30 DAYS)",
                style = MaterialTheme.typography.labelLarge,
                color = CommandAmber
            )
        }

        items(MILESTONE_BADGES, key = { it.id }) { badge ->
            val isUnlocked = unlockedSet.contains(badge.id) || streakProfile.currentStreakDays >= badge.requiredDays
            MilestoneBadgeCard(
                badge = badge,
                isUnlocked = isUnlocked,
                currentStreakDays = streakProfile.currentStreakDays
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // 15-Second Tactical Recovery Briefing Dialog
    if (isBriefingViewerOpen) {
        AlertDialog(
            onDismissRequest = onCancelBriefing,
            containerColor = TacticalSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = CommandAmber
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TACTICAL RECOVERY BRIEFING",
                        style = MaterialTheme.typography.titleLarge,
                        color = CommandAmber
                    )
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val coachIndividual = com.example.ui.components.resolveCoachIndividual(
                        personalityId = streakProfile.selectedPersonalityId,
                        voicePackId = streakProfile.selectedVoicePackId
                    )
                    com.example.ui.components.AnimatedCoachAvatar(
                        individual = coachIndividual,
                        isSpeaking = true,
                        size = 110.dp,
                        showStatusBadge = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (coachIndividual == com.example.ui.components.CoachAvatarIndividual.PERSONAL_LIFE_COACH)
                            "\"I SEE YOU MISSED YESTERDAY! SHAKE IT OFF—WE'RE GETTING RIGHT BACK ON TRACK! HOLD THIS BRIEFING FOR $briefingRemainingSeconds SECONDS TO RESTORE YOUR MOMENTUM!\""
                        else
                            "\"YOU MISSED YESTERDAY, RECRUIT! STAY LOCKED ON THIS RECOVERY BRIEFING FOR $briefingRemainingSeconds SECONDS TO RESTORE YOUR STREAK!\"",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { (15 - briefingRemainingSeconds).toFloat() / 15f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50)),
                        color = MissionEmerald,
                        trackColor = TacticalSurfaceElevated
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Restoring streak in ${briefingRemainingSeconds}s...",
                        style = MaterialTheme.typography.labelLarge,
                        color = MissionEmerald
                    )
                }
            },
            confirmButton = {
                OutlinedButton(onClick = onCancelBriefing) {
                    Text("Cancel Briefing")
                }
            }
        )
    }
}

@Composable
private fun MilestoneBadgeCard(
    badge: MilestoneBadgeSpec,
    isUnlocked: Boolean,
    currentStreakDays: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("milestone_badge_${badge.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnlocked) TacticalSurfaceElevated else TacticalSurface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isUnlocked) MissionEmerald else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isUnlocked) MissionEmerald.copy(alpha = 0.2f)
                        else TacticalSurfaceElevated
                    )
            ) {
                Icon(
                    imageVector = if (isUnlocked) Icons.Default.EmojiEvents else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (isUnlocked) MissionEmerald else SteelTextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = badge.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isUnlocked) MissionEmerald else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = CommandAmber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "+${badge.bonusCredits} CR",
                            style = MaterialTheme.typography.labelMedium,
                            color = CommandAmber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = badge.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SteelTextSecondary
                )
            }

            if (isUnlocked) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Unlocked milestone badge",
                    tint = MissionEmerald,
                    modifier = Modifier.size(26.dp)
                )
            } else {
                Text(
                    text = "$currentStreakDays/${badge.requiredDays}d",
                    style = MaterialTheme.typography.labelLarge,
                    color = SteelTextSecondary
                )
            }
        }
    }
}
