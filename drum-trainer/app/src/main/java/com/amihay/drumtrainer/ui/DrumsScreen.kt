package com.amihay.drumtrainer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Switch
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
import com.amihay.drumtrainer.data.SAMPLE_CREDIT
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

    val isTab = back == Screen.Home
    val p = plan
    Column(Modifier.fillMaxSize()) {
        if (!isTab) TopBar("Drums on screen", onBack = {
            stop()
            app.screen = back
        })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PagePadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isTab) TabHeader("Kit", "Tap the drums to hear them, or pick a pattern and watch it play")

            Panel(color = Palette.surface, padding = 10.dp) {
                DrumKitView(app, appLights = if (p != null) appLights(p.clicks, runner.playhead, p.sampleRate) else emptyMap())
            }

            if (p != null) QuietButton("Stop", ::stop, Modifier.fillMaxWidth())
            else PrimaryButton("Play ${ex.name} · $bpm BPM", ::play, icon = Icons.Filled.PlayArrow)

            val beat = p?.beatAt(runner.playhead)
            val bar = if (p != null && beat != null && beat >= 0) floor(beat / p.beatsPerBar).toInt().coerceIn(0, p.bars - 1) else 0
            val cycleBar = bar % ex.cycleBars
            Section(
                ex.name,
                trailing = {
                    if (p != null && beat != null) {
                        Pill(if (beat < 0) "Count-in…" else "Bar ${bar + 1} / ${p.bars}")
                    } else {
                        SmallAction("Change", Icons.Filled.Search) { picking = true }
                    }
                },
            ) {
                PatternView(
                    app, ex, cycleBar, emptyMap(),
                    if (p != null && beat != null && beat >= 0) (beat - bar * p.beatsPerBar) * ex.subdivision else null,
                )
                if (p == null) {
                    TempoControl(bpm, { bpm = it })
                    ListRow("Metronome", if (metronome) "Plays along with the drums" else "Count-in only") {
                        Switch(checked = metronome, onCheckedChange = { metronome = it })
                    }
                }
            }

            if (p == null) {
                SecondaryButton("Practice this pattern", { app.screen = Screen.Practice(ex.id) }, Modifier.fillMaxWidth())
            }
            Text(SAMPLE_CREDIT, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            Spacer(Modifier.height(12.dp))
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a pattern") },
        text = {
            // Patterns on the drums first; hand exercises are played on the snare.
            val sorted = exercises.sortedBy { if (it.usesPads) 0 else 1 }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sorted, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(e) }.padding(vertical = 10.dp)) {
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        Text("${kindLabel(e)} · ${e.startBpm}–${e.targetBpm} BPM", style = MaterialTheme.typography.bodyMedium,
                            color = Palette.muted)
                    }
                }
            }
        },
    )
}
