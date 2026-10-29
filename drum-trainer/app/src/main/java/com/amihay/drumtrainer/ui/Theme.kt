package com.amihay.drumtrainer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val good = Color(0xFF4CC38A)
    val off = Color(0xFFF2A33A)
    val bad = Color(0xFFE5534B)
    val pending = Color(0xFF3A3F4B)
    val accent = Color(0xFF6CB6FF)
    val muted = Color(0xFF9AA4B2)
}

private val colors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF0B1220),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF222632),
    onSurfaceVariant = Palette.muted,
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun DrumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
