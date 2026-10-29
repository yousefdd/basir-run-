package com.basir.run

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

// High contrast palettes. The accent is the yellow of tactile paving for blind pedestrians;
// on light backgrounds text uses a darker gold so it stays readable.
val Tactile = Color(0xFFF2C200)
val OnTactile = Color(0xFF121416)
val Danger = Color(0xFFE5392C)
val Good = Color(0xFF2FB866)
val Warn = Color(0xFFF59E0B)

private val dark = darkColorScheme(
    primary = Tactile,
    onPrimary = OnTactile,
    secondary = Good,
    onSecondary = Color(0xFF07170D),
    background = Color(0xFF0E1113),
    onBackground = Color(0xFFF1F3F2),
    surface = Color(0xFF171C1F),
    onSurface = Color(0xFFF1F3F2),
    surfaceVariant = Color(0xFF222A2E),
    onSurfaceVariant = Color(0xFFB3BCB8),
    outline = Color(0xFF4A555B),
    error = Color(0xFFFF6B5E),
    onError = Color.White,
)

private val light = lightColorScheme(
    primary = Color(0xFF6E5800),
    onPrimary = Color.White,
    secondary = Color(0xFF1B7A43),
    onSecondary = Color.White,
    background = Color(0xFFF6F7F4),
    onBackground = Color(0xFF111416),
    surface = Color.White,
    onSurface = Color(0xFF111416),
    surfaceVariant = Color(0xFFE6E9E5),
    onSurfaceVariant = Color(0xFF3F4744),
    outline = Color(0xFF6B7470),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val type = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, lineHeight = 100.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun BasirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = type, content = content)
}
