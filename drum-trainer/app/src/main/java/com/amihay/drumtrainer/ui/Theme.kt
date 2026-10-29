package com.amihay.drumtrainer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.amihay.drumtrainer.R

object Palette {
    /** Brand colour: buttons, selection, the playing cursor. */
    val accent = Color(0xFF8B9DFF)
    val accentDeep = Color(0xFF5B6CF0)
    val accentSoft = Color(0xFF8B9DFF).copy(alpha = 0.16f)

    // Timing feedback.
    val good = Color(0xFF3DDC97)
    val off = Color(0xFFFFB547)
    val bad = Color(0xFFFF6B6B)
    /** Hit on time but on the wrong drum. */
    val wrong = Color(0xFFD08BFF)

    val background = Color(0xFF0D0F14)
    val surface = Color(0xFF161922)
    val surfaceHigh = Color(0xFF1F2330)
    val outline = Color(0xFF2B3040)
    val pending = Color(0xFF353B4C)
    val text = Color(0xFFEDEFF5)
    val muted = Color(0xFF9199AB)

    /** The big card on the home screen. */
    val hero = Brush.linearGradient(listOf(Color(0xFF5B6CF0), Color(0xFF9B6CF0)))
}

@OptIn(ExperimentalTextApi::class)
private fun rubik(weight: Int) = Font(
    R.font.rubik,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Rubik: Hebrew and Latin from the same design, with clear figures for the numbers. */
val Rubik = FontFamily(rubik(400), rubik(500), rubik(600), rubik(700))

private val colors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF0B1030),
    primaryContainer = Palette.accentDeep,
    onPrimaryContainer = Color.White,
    secondaryContainer = Palette.surfaceHigh,
    onSecondaryContainer = Palette.text,
    background = Palette.background,
    onBackground = Palette.text,
    surface = Palette.background,
    onSurface = Palette.text,
    surfaceVariant = Palette.surfaceHigh,
    onSurfaceVariant = Palette.muted,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surfaceHigh,
    surfaceContainerHighest = Palette.surfaceHigh,
    surfaceContainerLow = Palette.surface,
    outline = Palette.outline,
    outlineVariant = Palette.outline,
    error = Palette.bad,
)

private fun style(size: Int, weight: Int, line: Int = (size * 1.3).toInt()) =
    TextStyle(fontFamily = Rubik, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp)

private val typography = Typography(
    displayLarge = style(88, 700, 96),
    displayMedium = style(52, 700, 60),
    displaySmall = style(40, 700, 48),
    headlineLarge = style(30, 700),
    headlineMedium = style(25, 600),
    headlineSmall = style(21, 600),
    titleLarge = style(19, 600),
    titleMedium = style(16, 500),
    titleSmall = style(14, 500),
    bodyLarge = style(16, 400, 24),
    bodyMedium = style(14, 400, 20),
    bodySmall = style(12, 400, 16),
    labelLarge = style(15, 500),
    labelMedium = style(13, 500),
    labelSmall = style(11, 500),
)

@Composable
fun DrumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
