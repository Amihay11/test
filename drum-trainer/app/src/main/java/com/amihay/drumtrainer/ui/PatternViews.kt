package com.amihay.drumtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.Stroke

/** Key for a cell's live state: (step in bar, row key). Row key [ANY_ROW] = every row at that step. */
typealias CellKey = Pair<Int, String>

const val ANY_ROW = "*"

/** Top-to-bottom order of drums, as on a drum chart: cymbals up, kick at the bottom. */
private val PAD_ORDER = listOf(Pad.CRASH, Pad.RIDE, Pad.HIHAT, Pad.TOM_HIGH, Pad.SNARE, Pad.TOM_MID, Pad.TOM_FLOOR, Pad.KICK)

fun rowKey(stroke: Stroke): String = stroke.pad?.id ?: stroke.hand.toString()

private fun rowsOf(ex: Exercise): List<Pair<String, String>> =
    if (ex.usesPads) {
        PAD_ORDER.filter { it in ex.pads }.map { it.id to it.short }
    } else {
        val hands = ex.steps.flatten().map { it.hand }.distinct().sortedByDescending { it == 'R' }
        if (hands.isEmpty()) listOf("-" to "") else hands.map { it.toString() to if (it == '-') "" else it.toString() }
    }

private fun cellState(states: Map<CellKey, StepState>, step: Int, row: String) =
    states[step to row] ?: states[step to ANY_ROW] ?: StepState.PENDING

/**
 * Step grid for one bar: a row per drum (or per hand for rudiments), beats separated by
 * thicker lines. Ghost notes are drawn small, accents get a ">". Time runs left to right
 * even in the RTL UI.
 */
@Composable
fun ExerciseGrid(
    exercise: Exercise,
    bar: Int,
    states: Map<CellKey, StepState>,
    cursorStep: Double?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val rows = rowsOf(exercise)
    val steps = exercise.stepsPerBar
    val labelColor = MaterialTheme.colorScheme.onSurface
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rowDp = when {
        rows.size > 5 -> 40
        rows.size > 3 -> 52
        else -> 72
    }
    val isFill = exercise.isFillBar(bar)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(modifier.fillMaxWidth().height((rows.size * rowDp + 24).dp)) {
            val labelW = 40.dp.toPx()
            val cellW = (size.width - labelW) / steps
            val top = 24.dp.toPx()
            val rowH = (size.height - top) / rows.size
            if (isFill) {
                drawRect(Palette.off.copy(alpha = 0.10f), Offset(labelW, top), Size(size.width - labelW, size.height - top))
                val t = measurer.ltr("FILL", TextStyle(color = Palette.off, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                drawText(t, topLeft = Offset(size.width - t.size.width, 0f))
            }

            rows.forEachIndexed { r, (key, label) ->
                val y = top + r * rowH
                if (label.isNotEmpty()) {
                    val t = measurer.ltr(label, TextStyle(color = lineColor, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    drawText(t, topLeft = Offset((labelW - t.size.width) / 2, y + (rowH - t.size.height) / 2))
                }
                for (s in 0 until steps) {
                    val stroke = exercise.strokesAt(bar * steps + s).firstOrNull { rowKey(it) == key || key == "-" } ?: continue
                    val x = labelW + s * cellW
                    val pad = cellW * 0.1f
                    val h = if (stroke.ghost) rowH * 0.4f else rowH * 0.76f
                    drawRoundRect(
                        color = stateColor(cellState(states, s, key)),
                        topLeft = Offset(x + pad, y + (rowH - h) / 2),
                        size = Size(cellW - 2 * pad, h),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    if (stroke.accent) {
                        val t = measurer.ltr(">", TextStyle(color = labelColor, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        drawText(t, topLeft = Offset(x + (cellW - t.size.width) / 2, y + (rowH - t.size.height) / 2))
                    }
                }
            }
            for (s in 0..steps) {
                val x = labelW + s * cellW
                val isBeat = s % exercise.subdivision == 0
                drawLine(lineColor.copy(alpha = if (isBeat) 0.8f else 0.25f), Offset(x, top), Offset(x, size.height),
                    strokeWidth = if (isBeat) 3f else 1f)
            }
            drawCursor(cursorStep, labelW, cellW, top)
        }
    }
}

private fun DrawScope.drawCursor(cursorStep: Double?, left: Float, cellW: Float, top: Float) {
    if (cursorStep == null || cursorStep < 0) return
    val x = left + (cursorStep.toFloat() + 0.5f) * cellW
    drawLine(Color.White, Offset(x, top), Offset(x, size.height), strokeWidth = 4.dp.toPx())
}

/** Staff position in half-spaces below the top line (negative = above the staff). */
private fun staffPos(pad: Pad?): Int = when (pad) {
    Pad.CRASH -> -2      // A5, on a ledger line
    Pad.HIHAT -> -1      // G5
    Pad.RIDE -> 0        // F5
    Pad.TOM_HIGH -> 1    // E5
    Pad.TOM_MID -> 2     // D5
    Pad.SNARE, null -> 3 // C5 (rudiments are written on the snare)
    Pad.TOM_FLOOR -> 5   // A4
    Pad.KICK -> 7        // F4
}

private fun isCymbal(pad: Pad?) = pad == Pad.CRASH || pad == Pad.RIDE || pad == Pad.HIHAT

/**
 * Standard drum notation for one bar: hands with stems up, kick with stems down
 * (two voices), notes beamed per beat, cymbals as "x", ghost notes in parentheses,
 * accents as ">". Rests are left out; the beams show where each beat falls.
 */
@Composable
fun NotationView(
    exercise: Exercise,
    bar: Int,
    states: Map<CellKey, StepState>,
    cursorStep: Double?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val steps = exercise.stepsPerBar

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(modifier.fillMaxWidth().height(190.dp)) {
            val gap = 12.dp.toPx()                 // distance between staff lines
            val staffTop = 64.dp.toPx()
            val left = 34.dp.toPx()
            val cellW = (size.width - left - 8.dp.toPx()) / steps
            fun y(pos: Int) = staffTop + pos * gap / 2
            val stemUpEnd = y(-6)
            val stemDownEnd = y(14)

            for (l in 0..4) drawLine(muted, Offset(0f, staffTop + l * gap), Offset(size.width, staffTop + l * gap), 1.5f)
            // Percussion clef and bar lines.
            drawRect(ink, Offset(8.dp.toPx(), y(2)), Size(4.dp.toPx(), gap * 2))
            drawRect(ink, Offset(16.dp.toPx(), y(2)), Size(4.dp.toPx(), gap * 2))
            drawLine(ink, Offset(size.width - 2f, staffTop), Offset(size.width - 2f, staffTop + 4 * gap), 2f)
            if (exercise.isFillBar(bar)) {
                val t = measurer.ltr("FILL", TextStyle(color = Palette.off, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                drawText(t, topLeft = Offset(size.width - t.size.width - 4.dp.toPx(), 0f))
            }

            fun stepX(s: Int) = left + (s + 0.5f) * cellW
            val beams = when (exercise.subdivision) { 1 -> 0; 2, 3 -> 1; else -> 2 }

            // Two voices: hands (stems up) and feet (stems down).
            for (down in listOf(false, true)) {
                val stemEnd = if (down) stemDownEnd else stemUpEnd
                val stemSteps = ArrayList<Int>()
                for (s in 0 until steps) {
                    val strokes = exercise.strokesAt(bar * steps + s).filter { (it.pad == Pad.KICK) == down }
                    if (strokes.isEmpty()) continue
                    stemSteps += s
                    val x = stepX(s)
                    for (st in strokes) {
                        val color = when (val state = cellState(states, s, rowKey(st))) {
                            StepState.PENDING -> ink
                            else -> stateColor(state)
                        }
                        drawHead(measurer, x, y(staffPos(st.pad)), gap, color, isCymbal(st.pad), st.ghost)
                        if (staffPos(st.pad) <= -2) drawLine(ink, Offset(x - gap, y(-2)), Offset(x + gap, y(-2)), 1.5f)
                    }
                    val headX = if (down) x - gap * 0.55f else x + gap * 0.55f
                    val nearest = if (down) strokes.minOf { y(staffPos(it.pad)) } else strokes.maxOf { y(staffPos(it.pad)) }
                    drawLine(ink, Offset(headX, nearest), Offset(headX, stemEnd), 2f)
                    if (!down && strokes.any { it.accent }) {
                        val t = measurer.ltr(">", TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        drawText(t, topLeft = Offset(headX - t.size.width / 2, stemEnd - t.size.height - 2))
                    }
                }
                // Beams (or flags) per beat.
                for (beat in 0 until exercise.beatsPerBar) {
                    val inBeat = stemSteps.filter { it / exercise.subdivision == beat }
                    if (inBeat.isEmpty() || beams == 0) continue
                    val dx = if (down) -gap * 0.55f else gap * 0.55f
                    for (b in 0 until beams) {
                        val by = if (down) stemEnd - b * 6.dp.toPx() else stemEnd + b * 6.dp.toPx()
                        if (inBeat.size > 1) {
                            drawLine(ink, Offset(stepX(inBeat.first()) + dx, by), Offset(stepX(inBeat.last()) + dx, by), 4.dp.toPx())
                        } else {
                            val x0 = stepX(inBeat.first()) + dx
                            drawLine(ink, Offset(x0, by), Offset(x0 + gap * 0.9f, by + (if (down) -gap else gap)), 2.5f)
                        }
                    }
                    if (exercise.subdivision == 3 || exercise.subdivision == 6) {
                        val label = exercise.subdivision.toString()
                        val t = measurer.ltr(label, TextStyle(color = muted, fontSize = 11.sp))
                        val cx = (stepX(beat * exercise.subdivision) + stepX(beat * exercise.subdivision + exercise.subdivision - 1)) / 2
                        val ty = if (down) stemEnd + 4 else stemEnd - t.size.height - 14.dp.toPx()
                        drawText(t, topLeft = Offset(cx - t.size.width / 2, ty))
                    }
                }
            }
            drawCursor(cursorStep, left, cellW, 0f)
        }
    }
}

private fun DrawScope.drawHead(
    measurer: TextMeasurer, x: Float, y: Float, gap: Float, color: Color, cymbal: Boolean, ghost: Boolean,
) {
    val r = gap * 0.5f
    if (cymbal) {
        drawLine(color, Offset(x - r, y - r), Offset(x + r, y + r), 3f)
        drawLine(color, Offset(x - r, y + r), Offset(x + r, y - r), 3f)
    } else {
        drawOval(color, Offset(x - r * 1.2f, y - r * 0.8f), Size(r * 2.4f, r * 1.6f))
    }
    if (ghost) {
        drawArc(color, 120f, 120f, false, Offset(x - r * 2.4f, y - r * 1.4f), Size(r * 2.8f, r * 2.8f), style = DrawStroke(2f))
        drawArc(color, -60f, 120f, false, Offset(x - r * 0.4f, y - r * 1.4f), Size(r * 2.8f, r * 2.8f), style = DrawStroke(2f))
    }
}
