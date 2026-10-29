package com.amihay.drumtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.NoteResult
import com.amihay.drumtrainer.model.Stats
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Live state of one grid cell. */
enum class StepState { PENDING, GOOD, OFF, MISS }

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
}

/**
 * Step grid for one bar: a row per hand (or a single row for "any hand"), beats
 * separated by thicker lines. Time always runs left to right, even in the RTL UI.
 */
@Composable
fun StickingGrid(
    exercise: Exercise,
    bar: Int,
    states: Map<Int, StepState>,
    cursorStep: Double?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val steps = exercise.stepsPerBar
    val hands = exercise.pattern.filterNotNull().map { it.hand }.distinct().sortedByDescending { it == 'R' }
    val rows = if (hands.isEmpty()) listOf('-') else hands
    val labelColor = MaterialTheme.colorScheme.onSurface
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(modifier.fillMaxWidth().height((rows.size * 72 + 24).dp)) {
            val labelW = 36.dp.toPx()
            val cellW = (size.width - labelW) / steps
            val rowH = (size.height - 24.dp.toPx()) / rows.size
            val top = 24.dp.toPx()

            rows.forEachIndexed { r, hand ->
                val y = top + r * rowH
                if (hand != '-') {
                    val t = measurer.measure(hand.toString(), TextStyle(color = lineColor, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                    drawText(t, topLeft = Offset((labelW - t.size.width) / 2, y + (rowH - t.size.height) / 2))
                }
                for (s in 0 until steps) {
                    val stroke = exercise.strokeAt(bar * steps + s) ?: continue
                    if (stroke.hand != hand && hand != '-') continue
                    val x = labelW + s * cellW
                    val pad = cellW * 0.1f
                    drawRoundRect(
                        color = stateColor(states[s] ?: StepState.PENDING),
                        topLeft = Offset(x + pad, y + rowH * 0.12f),
                        size = Size(cellW - 2 * pad, rowH * 0.76f),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                    if (stroke.accent) {
                        val t = measurer.measure(">", TextStyle(color = labelColor, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                        drawText(t, topLeft = Offset(x + (cellW - t.size.width) / 2, top - t.size.height))
                    }
                }
            }
            for (s in 0..steps) {
                val x = labelW + s * cellW
                val isBeat = s % exercise.subdivision == 0
                drawLine(
                    color = lineColor.copy(alpha = if (isBeat) 0.8f else 0.25f),
                    start = Offset(x, top),
                    end = Offset(x, size.height),
                    strokeWidth = if (isBeat) 3f else 1f,
                )
            }
            if (cursorStep != null && cursorStep >= 0) {
                val x = labelW + (cursorStep.toFloat() + 0.5f) * cellW
                drawLine(Color.White, Offset(x, top), Offset(x, size.height), strokeWidth = 4.dp.toPx())
            }
        }
    }
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
            listOf(0 to "$LRM−50ms", half to "0", bins.size - 1 to "$LRM+50ms").forEach { (i, label) ->
                val t = measurer.measure(label, TextStyle(color = muted, fontSize = 12.sp))
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
            if (db < -89) "שקט" else "${db.roundToInt()} dBFS",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BpmStepper(bpm: Int, onChange: (Int) -> Unit, enabled: Boolean = true) {
    // Numeric control: minus on the left, plus on the right, like any tempo knob.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onChange((bpm - 4).coerceAtLeast(30)) }, enabled = enabled) { Text("−4") }
            FilledTonalButton(onClick = { onChange((bpm - 1).coerceAtLeast(30)) }, enabled = enabled) { Text("−1") }
            Text("$bpm", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp))
            FilledTonalButton(onClick = { onChange((bpm + 1).coerceAtMost(260)) }, enabled = enabled) { Text("+1") }
            FilledTonalButton(onClick = { onChange((bpm + 4).coerceAtMost(260)) }, enabled = enabled) { Text("+4") }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, sub: String? = null) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .width(150.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Left-to-right mark: keeps "+3.2ms" from rendering as "3.2ms+" inside Hebrew text. */
const val LRM = "\u200E"

fun fmtMs(v: Double): String = LRM + String.format(java.util.Locale.US, "%+.1f", v)
fun fmt1(v: Double): String = LRM + String.format(java.util.Locale.US, "%.1f", v)

/** "מקדים / מאחר / במקום" for a mean deviation. */
fun earlyLate(meanMs: Double): String = when {
    meanMs < -3 -> "מקדים"
    meanMs > 3 -> "מאחר"
    else -> "במקום"
}
