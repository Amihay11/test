package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(app: AppState) {
    val progressFlow = remember { app.dao.allProgress() }
    val progress by progressFlow.collectAsState(initial = emptyList())
    val byId = progress.associateBy { it.exerciseId }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("מאמן תופים", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 16.dp))
            Text("למדוד במקום ללמד.", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
        }
        app.engineError?.let { err ->
            item { Banner(err, Palette.bad) }
        }
        if (!app.settings.isCalibrated) {
            item {
                Banner("עוד לא כיילת השהיה – בלי כיול כל המכות ייראו מאוחרות. מתחילים בכיול.", Palette.off) {
                    app.screen = Screen.Calibration
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.screen = Screen.Calibration }) { Text("כיול") }
                FilledTonalButton(onClick = { app.screen = Screen.Gap }) { Text("מטרונום עם חורים") }
                FilledTonalButton(onClick = { app.screen = Screen.History }) { Text("היסטוריה") }
            }
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text("תיבות בסבב:", style = MaterialTheme.typography.bodyLarge)
                listOf(2, 4, 8, 16).forEach { n ->
                    val selected = app.settings.barsPerRound == n
                    if (selected) {
                        Button(onClick = {}) { Text("$n") }
                    } else {
                        FilledTonalButton(onClick = { app.settings.updateBarsPerRound(n) }) { Text("$n") }
                    }
                }
            }
        }
        item { Text("רודימנטים", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(app.exercises, key = { it.id }) { ex ->
            val p = byId[ex.id]
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .clickable { app.screen = Screen.Practice(ex.id) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ex.nameHe, style = MaterialTheme.typography.titleLarge)
                    Text(ex.name, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${p?.currentBpm ?: ex.startBpm} BPM", style = MaterialTheme.typography.titleMedium)
                    val best = p?.bestPassedBpm ?: 0
                    Text(
                        if (best > 0) "שיא: $best · יעד: ${ex.targetBpm}" else "יעד: ${ex.targetBpm}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (best >= ex.targetBpm) Palette.good else Palette.muted,
                    )
                }
            }
        }
        item { Spacer(Modifier.padding(12.dp)) }
    }
}

@Composable
fun Banner(text: String, color: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().background(color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(14.dp),
    )
}
