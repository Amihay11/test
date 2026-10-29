package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import com.amihay.drumtrainer.model.TOUCH_TAG
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(app: AppState) {
    val runsFlow = remember { app.dao.recentRuns(200) }
    val runs by runsFlow.collectAsState(initial = emptyList())
    val names = app.exercises.associate { it.id to it.nameHe }
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("היסטוריה", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { app.screen = Screen.Home }) { Text("חזרה") }
        }
        if (runs.isEmpty()) CenteredNote("עוד אין סבבים שמורים.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        val title = (if (r.mode.startsWith("gap")) "מטרונום עם חורים" else names[r.exerciseId] ?: r.exerciseId) +
                            if (r.mode.endsWith(TOUCH_TAG)) " · במסך" else ""
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${dateFmt.format(Date(r.startedAt))} · ${r.bpm} BPM" +
                                (r.selfRating?.let { " · תחושה $it/5" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "σ ${fmt1(r.stdMs)}ms · ${r.hitPct.roundToInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (r.passed) Palette.good else MaterialTheme.colorScheme.onSurface,
                        )
                        Text("ממוצע ${fmtMs(r.meanMs)}ms", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                    }
                }
            }
        }
    }
}
