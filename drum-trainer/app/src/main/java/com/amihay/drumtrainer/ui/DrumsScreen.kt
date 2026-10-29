package com.amihay.drumtrainer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.model.ClickType
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.RoundPlan
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.appLights
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Bars the kit plays per "play": long enough to watch a pattern a few times over. */
private const val SHOW_BARS = 16

/**
 * A drum kit on the screen: tap it to play, or pick a pattern and watch the app play
 * it, each drum lighting up as it sounds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrumsScreen(app: AppState, exerciseId: String?, back: Screen) {
    val scope = rememberCoroutineScope()
    val runner = remember { RoundRunner(app.engine) }
    var ex by remember { mutableStateOf(app.exercises.firstOrNull { it.id == exerciseId } ?: app.exercises.first { it.usesPads }) }
    var bpm by remember { mutableIntStateOf(ex.startBpm) }
    var metronome by remember { mutableStateOf(true) }
    var plan by remember { mutableStateOf<RoundPlan?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var picking by remember { mutableStateOf(false) }
    KeepScreenOn()

    fun stop() {
        job?.cancel()
        plan = null
    }

    fun play() {
        val bars = ex.cycleBars * maxOf(1, SHOW_BARS / ex.cycleBars)
        val p = RoundPlanner.preview(ex, bpm, bars, app.engine.scheduleStartFrame(), app.engine.sampleRate)
        // Without the metronome, the count-in still plays so the start is clear.
        val clicks = if (metronome) p.clicks
        else p.clicks.filter { ClickType.padOf(it.type) != null || it.frame < p.firstNoteFrame - 1 }
        val shown = p.copy(clicks = clicks)
        plan = shown
        job = scope.launch {
            try {
                runner.run(shown.clicks, shown.endFrame, 0.0, tailMs = 600)
            } finally {
                plan = null
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("תופים על המסך", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                stop()
                app.screen = back
            }) { Text("חזרה") }
        }
        Text("הקש על התופים כדי לשמוע אותם. בחר קצב ולחץ \"נגן\" כדי לראות אותו מתנגן.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.muted)

        val p = plan
        DrumKitView(
            app,
            appLights = if (p != null) appLights(p.clicks, runner.playhead, p.sampleRate) else emptyMap(),
        )

        val beat = p?.beatAt(runner.playhead)
        val bar = if (p != null && beat != null && beat >= 0) floor(beat / p.beatsPerBar).toInt().coerceIn(0, p.bars - 1) else 0
        val cycleBar = bar % ex.cycleBars
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ex.nameHe, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (p != null && beat != null) {
                Text(
                    if (beat < 0) "ספירה…" else "תיבה ${bar + 1} / ${p.bars}",
                    style = MaterialTheme.typography.titleMedium, color = Palette.accent,
                )
            }
        }
        PatternView(
            app, ex, cycleBar, emptyMap(),
            if (p != null && beat != null && beat >= 0) (beat - bar * p.beatsPerBar) * ex.subdivision else null,
        )

        if (p != null) {
            Button(onClick = ::stop, modifier = Modifier.fillMaxWidth()) {
                Text("עצור", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { picking = true }) { Text("בחר קצב") }
                ViewToggle(app)
                OutlinedButton(onClick = { metronome = !metronome }) { Text(if (metronome) "מטרונום: פועל" else "מטרונום: כבוי") }
                FilledTonalButton(onClick = { app.screen = Screen.Practice(ex.id) }) { Text("לתרגל את זה") }
            }
            BpmStepper(bpm, { bpm = it })
            Button(onClick = ::play, modifier = Modifier.fillMaxWidth()) {
                Text("▶ נגן", style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    if (picking) {
        ExercisePicker(
            app.exercises,
            onPick = {
                ex = it
                bpm = it.startBpm
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ExercisePicker(exercises: List<Exercise>, onPick: (Exercise) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
        title = { Text("בחר קצב") },
        text = {
            // Patterns on the drums first; hand exercises are played on the snare.
            val sorted = exercises.sortedBy { if (it.usesPads) 0 else 1 }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sorted, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(e) }.padding(vertical = 10.dp)) {
                        Text(e.nameHe, style = MaterialTheme.typography.titleMedium)
                        Text("${e.name} · ${e.startBpm}–${e.targetBpm} BPM", style = MaterialTheme.typography.bodyMedium,
                            color = Palette.muted)
                    }
                }
            }
        },
    )
}
