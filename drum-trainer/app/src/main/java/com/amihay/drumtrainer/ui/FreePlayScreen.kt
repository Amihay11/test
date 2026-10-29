package com.amihay.drumtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.amihay.drumtrainer.model.Backing
import com.amihay.drumtrainer.model.Click
import com.amihay.drumtrainer.model.ClickType
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.FreePlayStats
import com.amihay.drumtrainer.model.RoundPlan
import com.amihay.drumtrainer.model.RoundPlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

private const val FREE_PLAY_BARS = 32

/**
 * Free play (last part of a session): the app plays a groove, you play whatever you
 * like over it. No score; at the end only a short summary of how steady the time was.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreePlayScreen(app: AppState, ex: Exercise, initialBpm: Int) {
    val scope = rememberCoroutineScope()
    val runner = remember { RoundRunner(app.engine) }
    var bpm by remember { mutableIntStateOf(initialBpm) }
    var plan by remember { mutableStateOf<RoundPlan?>(null) }
    var result by remember { mutableStateOf<FreePlayStats.Summary?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    KeepScreenOn()

    fun start() {
        val sr = app.engine.sampleRate
        val loop = Backing.loop(ex, bpm, sr)
        app.engine.setBacking(loop)
        val base = RoundPlanner.forExercise(ex, bpm, FREE_PLAY_BARS, app.engine.scheduleStartFrame(), sr)
        // Count-in clicks, then the bass-and-chords loop repeated over the round.
        val countIn = base.clicks.filter { it.frame < base.firstNoteFrame - 1 }
        val loops = (0 until FREE_PLAY_BARS / loop.bars).map {
            Click((base.firstNoteFrame + it.toLong() * loop.loopFrames).toLong(), ClickType.BACKING)
        }
        val p = base.copy(clicks = countIn + loops)
        plan = p
        result = null
        runner.touchInput = app.settings.touchInput
        job = scope.launch {
            try {
                runner.run(p.clicks, p.endFrame, app.settings.inputOffsetMs)
            } finally {
                result = FreePlayStats.summarize(runner.onsets.toList(), p)
                plan = null
                app.activeSession?.markPlayed("free:${ex.id}")
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("נגינה חופשית", style = MaterialTheme.typography.headlineMedium)
                Text("עם ${ex.nameHe}", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            }
            OutlinedButton(onClick = {
                job?.cancel()
                app.screen = app.home
            }) { Text(if (app.activeSession != null) "לאימון" else "חזרה") }
        }
        Text(
            "האפליקציה מנגנת לופ של בס ואקורדים בסגנון של ${ex.nameHe}, ואתה מנגן מה שבא לך: הגרוב, וריאציות, " +
                "מילויים, שקט. אין ציון – בסוף רק תמונה של הזמן.",
            style = MaterialTheme.typography.bodyLarge,
        )

        val p = plan
        if (p != null) {
            val beat = p.beatAt(runner.playhead)
            Text(
                if (beat < 0) "${(floor(beat).toInt() + p.countInBeats + 1).coerceIn(1, p.countInBeats)}"
                else "תיבה ${(floor(beat / p.beatsPerBar).toInt() + 1).coerceAtMost(p.bars)} / ${p.bars}",
                style = MaterialTheme.typography.displayMedium,
            )
            Text("${runner.onsets.size} מכות", style = MaterialTheme.typography.titleLarge, color = Palette.muted)
            if (runner.touchInput) TouchInput(app, runner, kit = ex.usesPads)
            FilledTonalButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("סיום") }
            return@Column
        }

        result?.let { r ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("מכות", "${r.hits}")
                if (r.hits > 0) {
                    StatTile("על הגריד", "${r.onGridPct.roundToInt()}%", sub = "בתוך רבע שש-עשרית")
                    StatTile("פיזור", "${fmt1(r.stdMs)}ms", sub = "ממוצע ${fmtMs(r.meanMs)}ms (${earlyLate(r.meanMs)})")
                }
            }
        }
        BpmStepper(bpm, { bpm = it })
        Button(onClick = ::start, modifier = Modifier.fillMaxWidth()) {
            Text(if (result == null) "התחל ($FREE_PLAY_BARS תיבות)" else "עוד פעם", style = MaterialTheme.typography.titleLarge)
        }
    }
}
