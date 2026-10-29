package com.amihay.drumtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.data.HitEntity
import com.amihay.drumtrainer.data.ProgressEntity
import com.amihay.drumtrainer.data.RunEntity
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.MatchResult
import com.amihay.drumtrainer.model.Matcher
import com.amihay.drumtrainer.model.Progress
import com.amihay.drumtrainer.model.RoundPlan
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.RoundStats
import com.amihay.drumtrainer.model.SpeedTrainer
import com.amihay.drumtrainer.model.Stats
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private enum class Phase { SETUP, RUNNING, RATING, RESULTS }

private data class Outcome(
    val stats: RoundStats,
    val passed: Boolean,
    val bpm: Int,
    val tempoUp: Boolean,
    val nextBpm: Int,
    val streak: Int,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeScreen(app: AppState, ex: Exercise) {
    val scope = rememberCoroutineScope()
    val runner = remember { RoundRunner(app.engine) }
    var phase by remember { mutableStateOf(Phase.SETUP) }
    var bpm by remember { mutableIntStateOf(ex.startBpm) }
    var plan by remember { mutableStateOf<RoundPlan?>(null) }
    var match by remember { mutableStateOf<MatchResult?>(null) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(ex.id) {
        app.dao.progress(ex.id)?.let { bpm = it.currentBpm }
    }
    KeepScreenOn()

    fun startRound() {
        val p = RoundPlanner.forExercise(
            ex, bpm, app.settings.barsPerRound, app.engine.scheduleStartFrame(), app.engine.sampleRate,
        )
        plan = p
        phase = Phase.RUNNING
        job = scope.launch {
            runner.run(p.clicks, p.endFrame, app.settings.effectiveOffsetMs)
            match = Matcher.match(p.notes, runner.onsets.toList(), p.windowFrames, p.sampleRate)
            phase = Phase.RATING
        }
    }

    fun finish(rating: Int?) {
        val p = plan ?: return
        val m = match ?: return
        val stats = Stats.compute(m, ex.subdivision)
        val passed = Stats.passes(stats, ex.passStdMs, ex.passHitPct)
        scope.launch {
            val old = app.dao.progress(ex.id)
            val before = Progress(ex.id, old?.currentBpm ?: bpm, old?.consecutivePasses ?: 0, old?.bestPassedBpm ?: 0)
            val (after, tempoUp) = SpeedTrainer.afterRound(before, bpm, passed)
            val runId = app.dao.insertRun(
                RunEntity(
                    exerciseId = ex.id, mode = "rudiment", bpm = bpm, startedAt = System.currentTimeMillis(),
                    bars = p.bars, expected = stats.expected, hits = stats.hits, misses = stats.misses,
                    extras = stats.extras, meanMs = stats.meanMs, stdMs = stats.stdMs, hitPct = stats.hitPct,
                    accentDb = stats.accentDb, selfRating = rating, passed = passed,
                ),
            )
            app.dao.insertHits(
                m.notes.map {
                    HitEntity(runId = runId, noteIndex = it.note.index, hand = it.note.hand.toString(),
                        accent = it.note.accent, deviationMs = it.deviationMs, strength = it.onset?.strength)
                } + m.extras.map {
                    HitEntity(runId = runId, noteIndex = null, hand = "-", accent = false, deviationMs = null, strength = it.strength)
                },
            )
            app.dao.saveProgress(ProgressEntity(ex.id, after.currentBpm, after.consecutivePasses, after.bestPassedBpm))
            outcome = Outcome(stats, passed, bpm, tempoUp, after.currentBpm, after.consecutivePasses)
            bpm = after.currentBpm
            phase = Phase.RESULTS
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ex.nameHe, style = MaterialTheme.typography.headlineMedium)
                Text(ex.name, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            }
            OutlinedButton(onClick = {
                job?.cancel()
                app.screen = Screen.Home
            }) { Text("חזרה") }
        }
        if (ex.tip.isNotEmpty()) Text(ex.tip, style = MaterialTheme.typography.bodyLarge)

        when (phase) {
            Phase.SETUP -> {
                StickingGrid(ex, bar = 0, states = emptyMap(), cursorStep = null)
                BpmStepper(bpm, { bpm = it })
                Text(
                    "יעד: ${ex.targetBpm} BPM · מעבר: סטיית תקן עד ${ex.passStdMs.roundToInt()}ms ו-${ex.passHitPct.roundToInt()}% פגיעות",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                )
                Button(onClick = ::startRound, modifier = Modifier.fillMaxWidth()) {
                    Text("התחל סבב (${app.settings.barsPerRound} תיבות)", style = MaterialTheme.typography.titleLarge)
                }
            }

            Phase.RUNNING -> plan?.let { p ->
                LiveRound(ex, p, runner)
                FilledTonalButton(onClick = {
                    job?.cancel()
                    phase = Phase.SETUP
                }, modifier = Modifier.fillMaxWidth()) { Text("עצור") }
            }

            Phase.RATING -> SelfRating(onRate = ::finish)

            Phase.RESULTS -> outcome?.let { o ->
                RoundResults(ex, o)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::startRound) { Text("סבב נוסף ב-$bpm BPM") }
                    FilledTonalButton(onClick = { phase = Phase.SETUP }) { Text("הגדרות") }
                }
            }
        }
    }
}

@Composable
private fun LiveRound(ex: Exercise, p: RoundPlan, runner: RoundRunner) {
    val playhead = runner.playhead
    val beat = p.beatAt(playhead)
    // Hits are detected a few tens of ms after they sound; don't call a note missed before then.
    val upTo = playhead - p.msToFrames(80.0)
    val live = Matcher.match(p.notes, runner.onsets, p.windowFrames, p.sampleRate, upToFrame = upTo)
    val bar = floor(beat / p.beatsPerBar).toInt().coerceIn(0, p.bars - 1)
    val states = HashMap<Int, StepState>()
    var lastHit: Double? = null
    live.notes.forEach { r ->
        if (r.note.bar == bar) states[r.note.stepInBar] = stepState(r, r.note.frame + p.windowFrames <= upTo)
        if (r.deviationMs != null) lastHit = r.deviationMs
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (beat < 0) {
            Text("${(floor(beat).toInt() + p.countInBeats + 1).coerceIn(1, p.countInBeats)}",
                style = MaterialTheme.typography.displayLarge)
        } else {
            Text(
                "תיבה ${bar + 1} / ${p.bars}",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
    val cursor = if (beat >= 0) (beat - bar * p.beatsPerBar) * ex.subdivision else null
    StickingGrid(ex, bar, states, cursor)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val lh = lastHit
        StatTile(
            "מכה אחרונה",
            lh?.let { "${fmtMs(it)}ms" } ?: "—",
            color = when {
                lh == null -> Palette.muted
                abs(lh) <= GOOD_MS -> Palette.good
                else -> Palette.off
            },
        )
        StatTile("מכות מיותרות", "${live.extras.size}", color = if (live.extras.isEmpty()) Palette.muted else Palette.bad)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelfRating(onRate: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("איך הרגיש הסבב?", style = MaterialTheme.typography.headlineMedium)
        Text("קודם תחושה, אחר כך מספרים – כך האוזן הפנימית לומדת לשפוט את עצמה.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { r -> Button(onClick = { onRate(r) }) { Text("$r", style = MaterialTheme.typography.titleLarge) } }
        }
        Text("1 = מפוזר לגמרי · 5 = נעול על הקליק", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        OutlinedButton(onClick = { onRate(null) }) { Text("דלג") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoundResults(ex: Exercise, o: Outcome) {
    val s = o.stats
    Text(
        if (o.passed) "עבר ✓" else "עוד לא",
        style = MaterialTheme.typography.displayMedium,
        color = if (o.passed) Palette.good else Palette.off,
    )
    Text(
        when {
            o.tempoUp -> "שני סבבים רצופים מעל הסף – הטמפו עולה ל-${o.nextBpm} BPM."
            o.passed -> "עוד סבב אחד מעל הסף ב-${o.bpm} BPM והטמפו יעלה."
            else -> "הטמפו נשאר ${o.bpm} BPM. דיוק לפני מהירות."
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.hits == 0) {
            StatTile("סטיית תקן", "—", sub = "לא זוהו מכות")
        } else {
            StatTile("סטיית תקן", "${fmt1(s.stdMs)}ms",
                color = if (s.stdMs <= ex.passStdMs) Palette.good else Palette.off, sub = "יציבות – המדד המרכזי")
            StatTile("סטייה ממוצעת", "${fmtMs(s.meanMs)}ms", sub = earlyLate(s.meanMs))
        }
        StatTile("פגיעות", "${s.hitPct.roundToInt()}%",
            color = if (s.hitPct >= ex.passHitPct) Palette.good else Palette.off, sub = "${s.hits}/${s.expected}")
        StatTile("מכות מיותרות", "${s.extras}", color = if (s.extras == 0) Palette.good else Palette.bad)
        s.accentDb?.let { StatTile("הדגשה מול רגיל", "${fmt1(it)}dB", sub = "כמה ההדגשה חזקה יותר") }
    }
    if (s.perHand.size > 1) {
        Text("לפי יד", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.perHand.toSortedMap(compareByDescending { it == 'R' }).forEach { (hand, sp) ->
                StatTile(if (hand == 'R') "יד ימין" else "יד שמאל", "${fmt1(sp.stdMs)}ms",
                    sub = "ממוצע ${fmtMs(sp.meanMs)}ms (${earlyLate(sp.meanMs)})")
            }
        }
    }
    if (s.hits == 0) {
        Text("לא זוהו מכות. בדוק במסך הכיול שמד הרמה זז ושהעיגול מהבהב כשאתה מכה.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.off)
        return
    }
    Text("התפלגות הסטיות", style = MaterialTheme.typography.titleLarge)
    DeviationHistogram(s.histogram)
    if (ex.subdivision > 1) {
        Text("לפי מיקום בנקישה", style = MaterialTheme.typography.titleLarge)
        val names = positionNames(ex.subdivision)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.byBeatPosition.forEachIndexed { i, sp ->
                if (sp.n > 0) StatTile(names[i], "${fmtMs(sp.meanMs)}ms", sub = "פיזור ${fmt1(sp.stdMs)}ms")
            }
        }
    }
}

private fun positionNames(subdivision: Int): List<String> = when (subdivision) {
    4 -> listOf("1", "e", "&", "a")
    3 -> listOf("1", "trip", "let")
    2 -> listOf("1", "&")
    else -> (1..subdivision).map { "$it" }
}

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun CenteredNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.muted, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth())
}
