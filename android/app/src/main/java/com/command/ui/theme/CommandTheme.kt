package com.command.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF241400),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF0F1114),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF181B1F),
    onSurface = Color(0xFFF2F2F2),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A4B00),
    onPrimary = Color.White,
    secondary = Color(0xFF00695C),
    background = Color(0xFFFFFBF5),
    onBackground = Color(0xFF1B1B1B),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1B),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** Large, plain type: the alarm screen is read half-awake, at arm's length, sometimes with the font scale raised. */
private val Bold = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun CommandTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Bold, content = content)
}
