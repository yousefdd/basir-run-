package com.basir.run

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dark, high contrast palette: easier for low vision and kinder to the battery on OLED screens.
// The accent is the yellow of tactile paving for blind pedestrians.
val Tactile = Color(0xFFF2C200)
val OnTactile = Color(0xFF121416)
val Danger = Color(0xFFFF5A4E)
val Good = Color(0xFF43D17A)
val Warn = Color(0xFFFFB547)

private val colors = darkColorScheme(
    primary = Tactile,
    onPrimary = OnTactile,
    secondary = Good,
    onSecondary = Color(0xFF07170D),
    background = Color(0xFF0E1113),
    onBackground = Color(0xFFF1F3F2),
    surface = Color(0xFF171C1F),
    onSurface = Color(0xFFF1F3F2),
    surfaceVariant = Color(0xFF222A2E),
    onSurfaceVariant = Color(0xFFA9B2AE),
    outline = Color(0xFF3A4449),
    error = Danger,
    onError = Color.White,
)

private val type = Typography(
    displayLarge = TextStyle(fontSize = 104.sp, lineHeight = 108.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
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
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
