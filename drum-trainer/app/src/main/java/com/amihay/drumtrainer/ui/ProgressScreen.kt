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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
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
 * Progress (spec: "גרף של הטמפו המקסימלי שבו עברת את הסף בכל תרגיל, לאורך השבועות"):
 * a summary, a chart per exercise played so far, and the list of rounds.
 */
@Composable
fun ProgressScreen(app: AppState) {
    val runsFlow = remember { app.dao.runRecordsFlow() }
    val runs by runsFlow.collectAsState(initial = emptyList())
    val played = app.exercises.filter { ex -> runs.any { it.exerciseId == ex.id } }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { TabHeader("התקדמות", "הטמפו הגבוה ביותר שבו עברת את הסף, לאורך הזמן") }
        item(key = "summary") {
            val days = runs.map { it.startedAt / DAY_MS }.distinct().size
            val mastered = played.count { Mastery.isMastered(it, runs) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("סבבים", "${runs.size}", Modifier.weight(1f))
                StatTile("ימי אימון", "$days", Modifier.weight(1f))
                StatTile("נשלטו", "$mastered", Modifier.weight(1f), color = if (mastered > 0) Palette.good else Color.Unspecified)
            }
        }
        item(key = "toggle") { Segmented(listOf(false to "גרפים", true to "היסטוריה"), showHistory, { showHistory = it }) }
        if (showHistory) {
            item(key = "history") { HistoryList(app) }
        } else {
            if (played.isEmpty()) {
                item(key = "empty") {
                    Panel {
                        Text("עוד אין סבבים", style = MaterialTheme.typography.titleMedium)
                        Text("הגרפים יתמלאו אחרי האימונים הראשונים.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                    }
                }
            }
            items(played, key = { it.id }) { ex ->
                val history = Mastery.tempoHistory(ex.id, runs)
                val mastered = Mastery.isMastered(ex, runs)
                Panel(onClick = { app.screen = Screen.Practice(ex.id) }, spacing = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ex.nameHe, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        when {
                            mastered -> Pill("נשלט", Palette.good)
                            history.isEmpty() -> Pill("עוד לא עבר", Palette.muted)
                            else -> Pill("$LRM${history.maxOf { it.second }} / $LRI${ex.targetBpm} BPM$PDI")
                        }
                    }
                    if (history.isNotEmpty()) TempoChart(history, ex.startBpm, ex.targetBpm)
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
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
