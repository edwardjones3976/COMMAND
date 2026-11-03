package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CommandDarkColorScheme = darkColorScheme(
    primary = CommandAmber,
    onPrimary = Color(0xFF140D00),
    primaryContainer = CommandAmberContainer,
    onPrimaryContainer = Color(0xFFFFDFB3),
    secondary = MissionEmerald,
    onSecondary = Color(0xFF002116),
    secondaryContainer = MissionEmeraldContainer,
    onSecondaryContainer = Color(0xFF9FFFE0),
    tertiary = DrillOlive,
    onTertiary = Color(0xFF111608),
    tertiaryContainer = DrillOliveContainer,
    onTertiaryContainer = Color(0xFFDCE8B5),
    error = CommandCrimson,
    onError = Color.White,
    errorContainer = CommandCrimsonContainer,
    onErrorContainer = Color(0xFFFFB3C2),
    background = ObsidianBackground,
    onBackground = TitaniumText,
    surface = TacticalSurface,
    onSurface = TitaniumText,
    surfaceVariant = TacticalSurfaceElevated,
    onSurfaceVariant = SteelTextSecondary,
    outline = TacticalBorder
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CommandDarkColorScheme,
        typography = Typography,
        content = content
    )
}
