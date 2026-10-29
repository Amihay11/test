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
import com.amihay.drumtrainer.data.HitEntity
import com.amihay.drumtrainer.data.RunEntity
import com.amihay.drumtrainer.model.GapAnalysis
import com.amihay.drumtrainer.model.GapSetup
import com.amihay.drumtrainer.model.GapStats
import com.amihay.drumtrainer.model.MatchResult
import com.amihay.drumtrainer.model.Matcher
import com.amihay.drumtrainer.model.RoundPlan
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.Stats
import com.amihay.drumtrainer.model.TOUCH_TAG
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private enum class GapPhase { SETUP, RUNNING, RATING, RESULTS }

private data class GapPreset(val on: Int, val off: Int, val label: String)

private val presets = listOf(
    GapPreset(2, 2, "2 עם · 2 בלי"),
    GapPreset(4, 4, "4 עם · 4 בלי"),
    GapPreset(1, 3, "1 עם · 3 בלי"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GapScreen(app: AppState, setup: GapSetup? = null, initialBpm: Int? = null) {
    val scope = rememberCoroutineScope()
    val runner = remember { RoundRunner(app.engine) }
    var phase by remember { mutableStateOf(GapPhase.SETUP) }
    var bpm by remember { mutableIntStateOf(initialBpm ?: 80) }
    var preset by remember {
        mutableStateOf(presets.firstOrNull { it.on == setup?.onBars && it.off == setup.offBars } ?: presets[0])
    }
    var stepsPerBeat by remember { mutableIntStateOf(1) }
    var cycles by remember { mutableIntStateOf(4) }
    var plan by remember { mutableStateOf<RoundPlan?>(null) }
    var match by remember { mutableStateOf<MatchResult?>(null) }
    var result by remember { mutableStateOf<GapStats?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    KeepScreenOn()

    fun start() {
        val p = RoundPlanner.forGap(bpm, stepsPerBeat, preset.on, preset.off, cycles,
            app.engine.scheduleStartFrame(), app.engine.sampleRate)
        plan = p
        phase = GapPhase.RUNNING
        runner.touchInput = app.settings.touchInput
        job = scope.launch {
            runner.run(p.clicks, p.endFrame, app.settings.inputOffsetMs)
            match = Matcher.match(p.notes, runner.onsets.toList(), p.windowFrames, p.sampleRate)
            phase = GapPhase.RATING
        }
    }

    fun finish(rating: Int?) {
        val p = plan ?: return
        val m = match ?: return
        val g = GapAnalysis.compute(m, p)
        val s = Stats.compute(m, stepsPerBeat)
        result = g
        phase = GapPhase.RESULTS
        scope.launch {
            val details = JSONObject()
                .put("on", preset.on).put("off", preset.off).put("stepsPerBeat", stepsPerBeat)
                .put("clickStdMs", g.withClick.stdMs).put("silentStdMs", g.silent.stdMs)
                .put("silentMeanMs", g.silent.meanMs)
                .put("silentBpm", g.silentBpm ?: JSONObject.NULL)
                .put("driftBpmPerMin", g.driftBpmPerMin ?: JSONObject.NULL)
                .put("segmentBpm", JSONArray(g.segments.map { it.playedBpm ?: JSONObject.NULL }))
            val runId = app.dao.insertRun(
                RunEntity(
                    exerciseId = "gap_${preset.on}_${preset.off}",
                    mode = "gap" + if (runner.touchInput) TOUCH_TAG else "", bpm = bpm,
                    startedAt = System.currentTimeMillis(), bars = p.bars, expected = s.expected, hits = s.hits,
                    misses = s.misses, extras = s.extras, meanMs = s.meanMs, stdMs = s.stdMs, hitPct = s.hitPct,
                    accentDb = null, selfRating = rating, passed = false, detailsJson = details.toString(),
                    sessionId = app.activeSession?.id,
                ),
            )
            app.activeSession?.markPlayed("gap_${preset.on}_${preset.off}")
            app.dao.insertHits(m.notes.map {
                HitEntity(runId = runId, noteIndex = it.note.index, hand = "-", accent = false,
                    deviationMs = it.deviationMs, strength = it.onset?.strength)
            })
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("מטרונום עם חורים", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                job?.cancel()
                app.screen = app.home
            }) { Text(if (app.activeSession != null) "לאימון" else "חזרה") }
        }
        Text(
            "הקליק מנגן כמה תיבות ונעלם. המשך לנגן באותו טמפו – כשהקליק חוזר רואים אם החזקת את הזמן.",
            style = MaterialTheme.typography.bodyLarge,
        )

        when (phase) {
            GapPhase.SETUP -> {
                BpmStepper(bpm, { bpm = it })
                Choice("דפוס", presets, preset, { it.label }) { preset = it }
                Choice("מה לנגן", listOf(1, 2), stepsPerBeat, { if (it == 1) "רבעים" else "שמיניות" }) { stepsPerBeat = it }
                Choice("מחזורים", listOf(2, 4, 8), cycles, { "$it" }) { cycles = it }
                Button(onClick = ::start, modifier = Modifier.fillMaxWidth()) {
                    Text("התחל", style = MaterialTheme.typography.titleLarge)
                }
            }

            GapPhase.RUNNING -> plan?.let { p ->
                val beat = p.beatAt(runner.playhead)
                val bar = floor(beat / p.beatsPerBar).toInt()
                val live = Matcher.match(p.notes, runner.onsets, p.windowFrames, p.sampleRate)
                val last = live.notes.lastOrNull { it.hit }?.deviationMs
                when {
                    beat < 0 -> Text("${(floor(beat).toInt() + p.countInBeats + 1).coerceIn(1, p.countInBeats)}",
                        style = MaterialTheme.typography.displayLarge)
                    bar < p.bars && p.clickBars[bar] -> Text("קליק", style = MaterialTheme.typography.displayLarge, color = Palette.accent)
                    bar < p.bars -> Text("שקט", style = MaterialTheme.typography.displayLarge, color = Palette.off)
                    else -> Text("סוף", style = MaterialTheme.typography.displayLarge)
                }
                Text("תיבה ${(bar + 1).coerceIn(1, p.bars)} / ${p.bars}", style = MaterialTheme.typography.headlineMedium)
                // Deliberately no beat indicator: during the silence the time must come from you.
                StatTile("מכה אחרונה", last?.let { "${fmtMs(it)}ms" } ?: "—",
                    color = when {
                        last == null -> Palette.muted
                        abs(last) <= GOOD_MS -> Palette.good
                        else -> Palette.off
                    })
                if (runner.touchInput) TouchInput(app, runner, kit = false)
                FilledTonalButton(onClick = {
                    job?.cancel()
                    phase = GapPhase.SETUP
                }, modifier = Modifier.fillMaxWidth()) { Text("עצור") }
            }

            GapPhase.RATING -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("כמה החזקת את הזמן בשקט?", style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { r -> Button(onClick = { finish(r) }) { Text("$r", style = MaterialTheme.typography.titleLarge) } }
                }
                OutlinedButton(onClick = { finish(null) }) { Text("דלג") }
            }

            GapPhase.RESULTS -> result?.let { g ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("עם קליק", "${fmt1(g.withClick.stdMs)}ms", sub = "פיזור · ממוצע ${fmtMs(g.withClick.meanMs)}")
                    StatTile("בשקט", "${fmt1(g.silent.stdMs)}ms", sub = "פיזור · ממוצע ${fmtMs(g.silent.meanMs)}",
                        color = if (g.silent.stdMs <= 2 * GOOD_MS) Palette.good else Palette.off)
                    g.silentBpm?.let {
                        val diff = it - bpm
                        StatTile("טמפו בשקט", "${fmt1(it)}", sub = when {
                            diff > 1 -> "מאיץ ב-${fmt1(diff)} BPM"
                            diff < -1 -> "מאט ב-${fmt1(-diff)} BPM"
                            else -> "יציב"
                        }, color = if (abs(diff) <= 1) Palette.good else Palette.off)
                    }
                    g.driftBpmPerMin?.let { StatTile("זחילה", "${fmtMs(it)}", sub = "BPM לדקה, לאורך הסבב") }
                }
                if (g.segments.isNotEmpty()) {
                    Text("טמפו בכל קטע שקט", style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        g.segments.forEachIndexed { i, s ->
                            StatTile("קטע ${i + 1}", s.playedBpm?.let { fmt1(it) } ?: "—",
                                sub = "סטייה ממוצעת ${s.meanMs.roundToInt()}ms")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::start) { Text("סבב נוסף") }
                    FilledTonalButton(onClick = { phase = GapPhase.SETUP }) { Text("הגדרות") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text("$label:", style = MaterialTheme.typography.bodyLarge)
        options.forEach { o ->
            if (o == selected) Button(onClick = {}) { Text(name(o)) }
            else FilledTonalButton(onClick = { onSelect(o) }) { Text(name(o)) }
        }
    }
}
