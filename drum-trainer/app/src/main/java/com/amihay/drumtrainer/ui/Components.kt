package com.amihay.drumtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amihay.drumtrainer.model.NoteResult
import com.amihay.drumtrainer.model.Stats
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Measures canvas text left-to-right whatever the layout direction, so characters
 * such as ">" are never mirrored into "<".
 */
fun TextMeasurer.ltr(text: String, style: TextStyle) = measure(text, style, layoutDirection = LayoutDirection.Ltr)

/** Live state of one grid cell. */
enum class StepState { PENDING, GOOD, OFF, MISS, WRONG }

/** Hits within this many ms of the grid are shown green; further away, orange. */
const val GOOD_MS = 15.0

fun stepState(r: NoteResult?, resolved: Boolean): StepState = when {
    r?.deviationMs != null -> if (abs(r.deviationMs) <= GOOD_MS) StepState.GOOD else StepState.OFF
    resolved -> StepState.MISS
    else -> StepState.PENDING
}

fun stateColor(s: StepState): Color = when (s) {
    StepState.PENDING -> Palette.pending
    StepState.GOOD -> Palette.good
    StepState.OFF -> Palette.off
    StepState.MISS -> Palette.bad
    StepState.WRONG -> Palette.wrong
}

/** Histogram of deviations (−50…+50 ms). Time runs left (early) to right (late). */
@Composable
fun DeviationHistogram(bins: IntArray, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(modifier.fillMaxWidth().height(140.dp)) {
            val labelH = 20.dp.toPx()
            val h = size.height - labelH
            val maxCount = (bins.maxOrNull() ?: 0).coerceAtLeast(1)
            val w = size.width / bins.size
            val half = bins.size / 2
            bins.forEachIndexed { i, c ->
                val ms = abs(i - half) * Stats.HIST_BIN_MS
                val color = if (ms <= GOOD_MS) Palette.good else Palette.off
                val bh = h * c / maxCount
                drawRect(color, Offset(i * w + 2, h - bh), Size(w - 4, bh))
            }
            drawLine(muted, Offset(size.width / 2, 0f), Offset(size.width / 2, h), strokeWidth = 2f)
            listOf(0 to "−50ms", half to "0", bins.size - 1 to "+50ms").forEach { (i, label) ->
                val t = measurer.ltr(label, TextStyle(color = muted, fontSize = 12.sp))
                val x = (i * w + w / 2 - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
                drawText(t, topLeft = Offset(x, h + 2))
            }
        }
    }
}

/** Input level in dBFS with a −60…0 dB scale. */
@Composable
fun LevelMeter(peak: Float, modifier: Modifier = Modifier) {
    val db = if (peak > 0) 20 * log10(peak) else -90f
    val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val color = when {
        db > -3 -> Palette.bad
        db > -12 -> Palette.off
        else -> Palette.good
    }
    Column(modifier) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier.fillMaxWidth().height(18.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
            ) {
                Box(Modifier.fillMaxWidth(frac).height(18.dp).background(color, RoundedCornerShape(9.dp)))
            }
        }
        Text(
            if (db < -89) "Silent" else "${db.roundToInt()} dBFS",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BpmStepper(bpm: Int, onChange: (Int) -> Unit, enabled: Boolean = true) = TempoControl(bpm, onChange, enabled = enabled)

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, sub: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.surface)
            .border(1.dp, Palette.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.muted, maxLines = 1)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2)
    }
}


fun fmtMs(v: Double): String = String.format(java.util.Locale.US, "%+.1f", v)
fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

/** "early / late / on time" for a mean deviation. */
fun earlyLate(meanMs: Double): String = when {
    meanMs < -3 -> "early"
    meanMs > 3 -> "late"
    else -> "on time"
}
