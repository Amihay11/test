package com.amihay.drumtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
    GapPreset(2, 2, "2 on · 2 off"),
    GapPreset(4, 4, "4 on · 4 off"),
    GapPreset(1, 3, "1 on · 3 off"),
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

    val stop = {
        job?.cancel()
        phase = GapPhase.SETUP
    }
    Page(
        "Gap click",
        onBack = {
            job?.cancel()
            app.screen = app.home
        },
        bottomBar = when (phase) {
            GapPhase.SETUP -> ({ PrimaryButton("Start", ::start, icon = Icons.Filled.PlayArrow) })
            GapPhase.RUNNING -> ({ QuietButton("Stop", stop, Modifier.fillMaxWidth()) })
            GapPhase.RATING -> null
            GapPhase.RESULTS -> ({
                PrimaryButton("Again", ::start, icon = Icons.Filled.Refresh)
                SecondaryButton("Settings", { phase = GapPhase.SETUP }, Modifier.fillMaxWidth())
            })
        },
    ) {
        when (phase) {
            GapPhase.SETUP -> {
                Text("The click plays for a few bars and then drops out. Keep playing at the same tempo, and when it comes back you see whether you held the time.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                Section("Tempo") { TempoControl(bpm, { bpm = it }) }
                Section("Round") {
                    Field("Pattern") { Segmented(presets.map { it to it.label }, preset, { preset = it }) }
                    Field("What to play") { Segmented(listOf(1 to "Quarters", 2 to "Eighths"), stepsPerBeat, { stepsPerBeat = it }) }
                    Field("Cycles") { Segmented(listOf(2, 4, 8).map { it to "$it" }, cycles, { cycles = it }) }
                }
            }

            GapPhase.RUNNING -> plan?.let { p ->
                val beat = p.beatAt(runner.playhead)
                val bar = floor(beat / p.beatsPerBar).toInt()
                val live = Matcher.match(p.notes, runner.onsets, p.windowFrames, p.sampleRate)
                val last = live.notes.lastOrNull { it.hit }?.deviationMs
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        beat < 0 -> Text("${(floor(beat).toInt() + p.countInBeats + 1).coerceIn(1, p.countInBeats)}",
                            style = MaterialTheme.typography.displayLarge)
                        bar < p.bars && p.clickBars[bar] -> Text("Click", style = MaterialTheme.typography.displayLarge, color = Palette.accent)
                        bar < p.bars -> Text("Silence", style = MaterialTheme.typography.displayLarge, color = Palette.off)
                        else -> Text("End", style = MaterialTheme.typography.displayLarge)
                    }
                    Text("Bar ${(bar + 1).coerceIn(1, p.bars)} / ${p.bars}", style = MaterialTheme.typography.titleMedium,
                        color = Palette.muted)
                }
                // Deliberately no beat indicator: during the silence the time must come from you.
                StatTile("Last hit", last?.let { fmtMs(it) } ?: "—", Modifier.fillMaxWidth(),
                    color = when {
                        last == null -> Palette.muted
                        abs(last) <= GOOD_MS -> Palette.good
                        else -> Palette.off
                    }, sub = last?.let { "ms · ${earlyLate(it)}" })
                if (runner.touchInput) TouchInput(app, runner, kit = false)
            }

            GapPhase.RATING -> Panel(padding = 20.dp, spacing = 16.dp) {
                Text("How well did you hold the time in the silence?", style = MaterialTheme.typography.headlineSmall)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (1..5).forEach { r ->
                            FilledTonalButton(onClick = { finish(r) }, shape = CircleShape, modifier = Modifier.size(56.dp),
                                contentPadding = PaddingValues(0.dp)) { Text("$r", style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
                TextButton(onClick = { finish(null) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip") }
            }

            GapPhase.RESULTS -> result?.let { g ->
                StatGrid {
                    StatTile("With click", "${fmt1(g.withClick.stdMs)}ms", Modifier.weight(1f), sub = "spread · avg ${fmtMs(g.withClick.meanMs)}")
                    StatTile("In silence", "${fmt1(g.silent.stdMs)}ms", Modifier.weight(1f), sub = "spread · avg ${fmtMs(g.silent.meanMs)}",
                        color = if (g.silent.stdMs <= 2 * GOOD_MS) Palette.good else Palette.off)
                    g.silentBpm?.let {
                        val diff = it - bpm
                        StatTile("Tempo in silence", fmt1(it), Modifier.weight(1f), sub = when {
                            diff > 1 -> "rushing by ${fmt1(diff)} BPM"
                            diff < -1 -> "dragging by ${fmt1(-diff)} BPM"
                            else -> "steady"
                        }, color = if (abs(diff) <= 1) Palette.good else Palette.off)
                    }
                    g.driftBpmPerMin?.let { StatTile("Drift", fmtMs(it), Modifier.weight(1f), sub = "BPM per minute, over the round") }
                }
                if (g.segments.isNotEmpty()) {
                    Section("Tempo in each silent stretch") {
                        StatGrid {
                            g.segments.forEachIndexed { i, s ->
                                StatTile("Stretch ${i + 1}", s.playedBpm?.let { fmt1(it) } ?: "—", Modifier.weight(1f),
                                    sub = "avg offset ${s.meanMs.roundToInt()}ms")
                            }
                        }
                    }
                }
            }
        }
    }
}

