package com.amihay.drumtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amihay.drumtrainer.model.DAY_MS
import com.amihay.drumtrainer.model.Mastery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Progress graph (spec: "גרף של הטמפו המקסימלי שבו עברת את הסף בכל תרגיל, לאורך
 * השבועות"): one chart per exercise played so far, with its target tempo.
 */
@Composable
fun ProgressScreen(app: AppState) {
    val runsFlow = remember { app.dao.runRecordsFlow() }
    val runs by runsFlow.collectAsState(initial = emptyList())
    val played = app.exercises.filter { ex -> runs.any { it.exerciseId == ex.id } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("התקדמות", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { app.screen = Screen.Home }) { Text("חזרה") }
        }
        if (played.isEmpty()) CenteredNote("עוד אין סבבים. הגרף יתמלא אחרי האימונים הראשונים.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(played, key = { it.id }) { ex ->
                val history = Mastery.tempoHistory(ex.id, runs)
                val mastered = Mastery.isMastered(ex, runs)
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .clickable { app.screen = Screen.Practice(ex.id) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ex.nameHe, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                mastered -> "נשלט ✓"
                                history.isEmpty() -> "עוד לא עבר"
                                else -> "$LRM${history.maxOf { it.second }} / ${ex.targetBpm} BPM"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (mastered) Palette.good else Palette.muted,
                        )
                    }
                    if (history.isNotEmpty()) TempoChart(history, ex.startBpm, ex.targetBpm)
                }
            }
        }
    }
}

/** Best passed tempo per practice day, with the target as a dashed line. */
@Composable
fun TempoChart(history: List<Pair<Long, Int>>, startBpm: Int, targetBpm: Int) {
    val measurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fmt = remember { SimpleDateFormat("d/M", Locale.getDefault()) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val left = 36.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val top = 8.dp.toPx()
            val lo = minOf(startBpm, history.minOf { it.second }) - 4
            val hi = maxOf(targetBpm, history.maxOf { it.second }) + 4
            val d0 = history.first().first
            val d1 = maxOf(history.last().first, d0 + 6)  // at least a week wide
            fun x(day: Long) = left + (size.width - left - 8.dp.toPx()) * (day - d0) / (d1 - d0).toFloat()
            fun y(bpm: Int) = bottom - (bottom - top) * (bpm - lo) / (hi - lo).toFloat()

            drawLine(Palette.good.copy(alpha = 0.7f), Offset(left, y(targetBpm)), Offset(size.width, y(targetBpm)),
                strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            listOf(lo + 4, targetBpm).forEach { bpm ->
                val t = measurer.ltr("$bpm", TextStyle(color = muted, fontSize = 11.sp))
                drawText(t, topLeft = Offset(0f, y(bpm) - t.size.height / 2))
            }
            for ((i, p) in history.withIndex()) {
                val px = x(p.first)
                val py = y(p.second)
                if (i > 0) {
                    val prev = history[i - 1]
                    drawLine(Palette.accent, Offset(x(prev.first), y(prev.second)), Offset(px, py), strokeWidth = 4f)
                }
                drawCircle(Palette.accent, 5.dp.toPx(), Offset(px, py))
            }
            listOf(d0, history.last().first).distinct().forEach { day ->
                val t = measurer.ltr(fmt.format(Date(day * DAY_MS)), TextStyle(color = muted, fontSize = 11.sp))
                drawText(t, topLeft = Offset((x(day) - t.size.width / 2).coerceIn(0f, size.width - t.size.width), bottom + 2))
            }
        }
    }
}
