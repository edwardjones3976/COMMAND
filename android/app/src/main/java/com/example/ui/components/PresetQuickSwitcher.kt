package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.PersonalityPresetEntity
import com.example.ui.theme.CommandAmber
import com.example.ui.theme.MissionEmerald
import com.example.ui.theme.SteelTextSecondary
import com.example.ui.theme.TacticalSurface
import com.example.ui.theme.TacticalSurfaceElevated

@Composable
fun PresetQuickSwitcher(
    presets: List<PersonalityPresetEntity>,
    activePresetId: String?,
    onSelectPreset: (PersonalityPresetEntity) -> Unit,
    onOpenPresetStudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TacticalSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF2A3441)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("preset_quick_switcher")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ACTIVE PERSONALITY PRESET",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SteelTextSecondary
                    )
                }

                AssistChip(
                    onClick = onOpenPresetStudio,
                    label = { Text("Studio & Library", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create or fine-tune presets",
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = TacticalSurfaceElevated,
                        labelColor = CommandAmber,
                        leadingIconContentColor = CommandAmber
                    ),
                    border = BorderStroke(1.dp, CommandAmber.copy(alpha = 0.5f)),
                    modifier = Modifier.testTag("open_preset_studio_btn")
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                presets.forEach { preset ->
                    val isSelected = preset.id == activePresetId || (activePresetId == null && preset.isActiveGlobal)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = preset.name,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Active preset",
                                        tint = Color.Black,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = if (isSelected) Color.Black else CommandAmber,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = TacticalSurfaceElevated,
                            selectedContainerColor = CommandAmber,
                            selectedLabelColor = Color.Black,
                            labelColor = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Color(0xFF3B4858),
                            selectedBorderColor = CommandAmber,
                            borderWidth = 1.dp
                        ),
                        modifier = Modifier.testTag("preset_chip_${preset.id}")
                    )
                }
            }
        }
    }
}
