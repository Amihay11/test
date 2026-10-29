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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.data.ProgressEntity
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.ExerciseKind
import com.amihay.drumtrainer.model.isCustom

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
        item {
            Button(onClick = { app.screen = Screen.Session }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (app.activeSession != null) "המשך אימון" else "אימון של היום · ${app.settings.sessionMinutes} דק'",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
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
                FilledTonalButton(onClick = { app.screen = Screen.Progress }) { Text("התקדמות") }
                FilledTonalButton(onClick = { app.screen = Screen.Kit }) { Text("ערכת תופים") }
                FilledTonalButton(onClick = { app.screen = Screen.Gap() }) { Text("מטרונום עם חורים") }
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
        item(key = "custom-title") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("התרגילים שלי", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { app.screen = Screen.Editor() }) { Text("+ תרגיל חדש") }
            }
        }
        if (app.customExercises.isEmpty()) {
            item(key = "custom-empty") {
                Text("כאן יופיעו תרגילים שתכתוב בעורך – גרוב, מילוי או סטיקינג.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            }
        }
        items(app.customExercises, key = { it.id }) { ex -> ExerciseRow(app, ex, byId[ex.id]) }
        for ((title, list) in exerciseSections(app.exercises.filter { !it.isCustom })) {
            item(key = "title-$title") {
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(list, key = { it.id }) { ex -> ExerciseRow(app, ex, byId[ex.id]) }
        }
        item { Spacer(Modifier.padding(12.dp)) }
    }
}

/** Home-screen sections, in the order of the curriculum. */
private fun exerciseSections(all: List<Exercise>): List<Pair<String, List<Exercise>>> = listOf(
    "רודימנטים" to all.filter { it.kind == ExerciseKind.RUDIMENT },
    "עצמאות בין הגפיים" to all.filter { it.kind == ExerciseKind.INDEPENDENCE },
    "גרובים – רוק ופופ" to all.filter { it.kind == ExerciseKind.GROOVE && it.style == "rock" },
    "גרובים – פאנק וסול" to all.filter { it.kind == ExerciseKind.GROOVE && it.style == "funk" },
    "ג'אז ובלוז" to all.filter { it.kind == ExerciseKind.GROOVE && (it.style == "jazz" || it.style == "blues") },
    "לטיני" to all.filter { it.kind == ExerciseKind.GROOVE && it.style == "latin" },
    "מזרחי וים-תיכוני" to all.filter { it.kind == ExerciseKind.GROOVE && it.style == "mideast" },
    "אלקטרוני ומשקלים מורכבים" to all.filter { it.kind == ExerciseKind.GROOVE && it.style == "electronic" },
    "מעברים ומילויים" to all.filter { it.kind == ExerciseKind.FILL },
).filter { it.second.isNotEmpty() }.map { (t, l) -> t to l.sortedBy { it.level } }

@Composable
private fun ExerciseRow(app: AppState, ex: Exercise, p: ProgressEntity?) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .clickable { app.screen = Screen.Practice(ex.id) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ex.nameHe, style = MaterialTheme.typography.titleLarge)
            Text(
                ex.name + if (ex.kind != ExerciseKind.RUDIMENT) " · רמה ${ex.level}" else "",
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
            )
        }
        if (ex.isCustom) {
            OutlinedButton(onClick = { app.screen = Screen.Editor(ex.id) }, modifier = Modifier.padding(end = 8.dp)) { Text("ערוך") }
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

@Composable
fun Banner(text: String, color: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().background(color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(14.dp),
    )
}
