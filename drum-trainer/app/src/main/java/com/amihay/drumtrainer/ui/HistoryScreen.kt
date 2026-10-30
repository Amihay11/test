package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.model.TOUCH_TAG
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Every round played, newest first. */
@Composable
fun HistoryList(app: AppState) {
    val runsFlow = remember { app.dao.recentRuns(200) }
    val runs by runsFlow.collectAsState(initial = emptyList())
    val names = app.exercises.associate { it.id to it.name }
    val dateFmt = remember { SimpleDateFormat("d/M · HH:mm", Locale.getDefault()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (runs.isEmpty()) {
            Text("No rounds saved yet.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        }
        runs.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(10.dp).background(if (r.passed) Palette.good else Palette.pending, CircleShape))
                Column(Modifier.weight(1f)) {
                    val title = (if (r.mode.startsWith("gap")) "Gap click" else names[r.exerciseId] ?: r.exerciseId) +
                        if (r.mode.endsWith(TOUCH_TAG)) " · on screen" else ""
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${dateFmt.format(Date(r.startedAt))} · ${r.bpm} BPM" + (r.selfRating?.let { " · felt $it/5" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("σ ${fmt1(r.stdMs)}ms", style = MaterialTheme.typography.titleMedium,
                        color = if (r.passed) Palette.good else MaterialTheme.colorScheme.onSurface)
                    Text("${r.hitPct.roundToInt()}% · ${fmtMs(r.meanMs)}ms", style = MaterialTheme.typography.bodySmall,
                        color = Palette.muted)
                }
            }
        }
    }
}
