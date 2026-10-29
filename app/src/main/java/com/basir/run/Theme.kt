package com.basir.run

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// One dark navy theme. Blue, teal and green carry the interface; the warm yellow of tactile
// paving is the brand accent (icon, background shapes); red is kept for stops and emergencies.
val Navy = Color(0xFF081226)
val NavyDeep = Color(0xFF050B18)
val NavyCard = Color(0xFF0F1D38)
val NavyRaised = Color(0xFF172A4D)
val Blue = Color(0xFF3B82F6)
val Teal = Color(0xFF19C3B4)
val Good = Color(0xFF22C55E)
val Tactile = Color(0xFFFFD54A)
val Amber = Color(0xFFF59E0B)
val Warn = Amber
val Danger = Color(0xFFEF4444)
val Ink = Color(0xFFF3F6FB)
val InkMuted = Color(0xFFA9B6CC)

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold),
)

private val scheme = darkColorScheme(
    primary = Teal,
    onPrimary = NavyDeep,
    secondary = Blue,
    onSecondary = Color.White,
    tertiary = Tactile,
    onTertiary = NavyDeep,
    background = Navy,
    onBackground = Ink,
    surface = NavyCard,
    onSurface = Ink,
    surfaceVariant = NavyRaised,
    onSurfaceVariant = InkMuted,
    outline = Color(0xFF2C4166),
    error = Danger,
    onError = Color.White,
)

private fun style(size: Int, line: Int, weight: FontWeight) =
    TextStyle(fontFamily = Tajawal, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val type = Typography(
    displayLarge = style(44, 54, FontWeight.ExtraBold),
    headlineLarge = style(32, 42, FontWeight.ExtraBold),
    headlineSmall = style(24, 32, FontWeight.Bold),
    titleLarge = style(22, 30, FontWeight.Bold),
    titleMedium = style(18, 26, FontWeight.Bold),
    bodyLarge = style(18, 30, FontWeight.Normal),
    bodyMedium = style(16, 26, FontWeight.Normal),
    labelLarge = style(16, 22, FontWeight.Bold),
    labelMedium = style(14, 20, FontWeight.Medium),
)

@Composable
fun BasirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
