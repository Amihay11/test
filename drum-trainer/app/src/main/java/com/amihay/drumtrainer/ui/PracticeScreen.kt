package com.amihay.drumtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import com.amihay.drumtrainer.model.ExerciseKind
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
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.audio.AudioRoute
import com.amihay.drumtrainer.audio.currentAudioRoute
import com.amihay.drumtrainer.data.HitEntity
import com.amihay.drumtrainer.data.ProgressEntity
import com.amihay.drumtrainer.data.RunEntity
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.isCustom
import com.amihay.drumtrainer.model.MatchResult
import com.amihay.drumtrainer.model.ExpectedNote
import com.amihay.drumtrainer.model.Scoring
import com.amihay.drumtrainer.model.Progress
import com.amihay.drumtrainer.model.RoundPlan
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.RoundStats
import com.amihay.drumtrainer.model.SpeedTrainer
import com.amihay.drumtrainer.model.Stats
import com.amihay.drumtrainer.model.TOUCH_TAG
import com.amihay.drumtrainer.model.appLights
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private enum class Phase { SETUP, PREVIEW, RUNNING, RATING, RESULTS }

/** How the app takes part in a round. */
private enum class PlayMode(val label: String, val tag: String) {
    /** Only the metronome. */
    NORMAL("Normal", ""),
    /** The app plays the pattern at the same time as the player. */
    TOGETHER("Together", "-together"),
    /** The app plays a cycle, then the player plays it back. */
    ECHO("Echo", "-echo"),
}

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
fun PracticeScreen(app: AppState, ex: Exercise, initialBpm: Int? = null) {
    val scope = rememberCoroutineScope()
    val runner = remember { RoundRunner(app.engine) }
    var phase by remember { mutableStateOf(Phase.SETUP) }
    var bpm by remember { mutableIntStateOf(ex.startBpm) }
    var plan by remember { mutableStateOf<RoundPlan?>(null) }
    var match by remember { mutableStateOf<MatchResult?>(null) }
    var notes by remember { mutableStateOf<List<ExpectedNote>>(emptyList()) }
    var mode by remember { mutableStateOf(PlayMode.NORMAL) }
    // Swing for patterns that can swing (null = straight only).
    var swing by remember { mutableIntStateOf(ex.swing ?: 50) }
    val swingPct: Int? = if (ex.swing != null) swing else null
    val context = LocalContext.current
    var route by remember { mutableStateOf(currentAudioRoute(context)) }
    // Practising without drums: taps on the screen know their drum (or hand) exactly.
    val touch = app.settings.touchInput
    // Judge the drums only when every drum in the exercise has been learned.
    val padAware = if (touch) ex.usesPads else Scoring.padAware(ex, app.classifier.pads)
    // Progress on the screen is kept apart from progress on the drums.
    val progressId = app.progressId(ex.id)
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var byHand by remember { mutableStateOf(false) }

    LaunchedEffect(ex.id) {
        if (initialBpm != null) bpm = initialBpm else app.dao.progress(progressId)?.let { bpm = it.currentBpm }
    }
    KeepScreenOn()

    fun startPreview() {
        val p = RoundPlanner.preview(ex, bpm, maxOf(ex.cycleBars, 2), app.engine.scheduleStartFrame(), app.engine.sampleRate,
            swingPct)
        plan = p
        phase = Phase.PREVIEW
        job = scope.launch {
            runner.run(p.clicks, p.endFrame, app.settings.inputOffsetMs, tailMs = 1200)
            phase = Phase.SETUP
        }
    }

    fun startRound() {
        val start = app.engine.scheduleStartFrame()
        val sr = app.engine.sampleRate
        route = currentAudioRoute(context)
        val p = when (mode) {
            PlayMode.NORMAL -> RoundPlanner.forExercise(ex, bpm, ex.roundBars(app.settings.barsPerRound), start, sr,
                swingPct = swingPct)
            PlayMode.TOGETHER -> RoundPlanner.forTogether(ex, bpm, ex.roundBars(app.settings.barsPerRound), start, sr,
                realDrums = touch || route == AudioRoute.WIRED, swingPct = swingPct)
            PlayMode.ECHO -> RoundPlanner.forCallResponse(ex, bpm, echoReps(ex, app.settings.barsPerRound), start, sr,
                swingPct)
        }
        val aware = padAware
        plan = p
        notes = Scoring.notes(p, aware)
        val hands = touch && !ex.usesPads && Scoring.handAware(notes)
        byHand = hands
        phase = Phase.RUNNING
        runner.touchInput = touch
        job = scope.launch {
            runner.run(p.clicks, p.endFrame, app.settings.inputOffsetMs,
                classifier = if (aware && !touch) app.classifier else null)
            match = Scoring.match(notes, runner.onsets.toList(), p, aware, byHand = hands)
            phase = Phase.RATING
        }
    }

    fun finish(rating: Int?) {
        val p = plan ?: return
        val roundMode = mode
        val m = match ?: return
        // A tap has no loudness, so accents cannot be measured on the screen.
        val stats = Stats.compute(m, ex.subdivision).let { if (touch) it.copy(accentDb = null) else it }
        val passed = Stats.passes(stats, ex.passStdMs, ex.passHitPct)
        scope.launch {
            val old = app.dao.progress(progressId)
            val before = Progress(progressId, old?.currentBpm ?: bpm, old?.consecutivePasses ?: 0, old?.bestPassedBpm ?: 0)
            val (after, tempoUp) = SpeedTrainer.afterRound(before, bpm, passed)
            val runId = app.dao.insertRun(
                RunEntity(
                    exerciseId = ex.id, mode = ex.kind.name.lowercase() + roundMode.tag + if (touch) TOUCH_TAG else "", bpm = bpm, startedAt = System.currentTimeMillis(),
                    bars = p.bars, expected = stats.expected, hits = stats.hits, misses = stats.misses,
                    extras = stats.extras, meanMs = stats.meanMs, stdMs = stats.stdMs, hitPct = stats.hitPct,
                    accentDb = stats.accentDb, selfRating = rating, passed = passed,
                    sessionId = app.activeSession?.id,
                ),
            )
            app.activeSession?.markPlayed(ex.id)
            app.dao.insertHits(
                m.notes.map {
                    HitEntity(runId = runId, noteIndex = it.note.index, hand = it.note.hand.toString(),
                        accent = it.note.accent, deviationMs = it.deviationMs, strength = it.onset?.strength,
                        pad = it.note.pad?.id)
                } + (m.extras + m.wrongDrum).map {
                    HitEntity(runId = runId, noteIndex = null, hand = "-", accent = false, deviationMs = null,
                        strength = it.strength, pad = it.pads?.firstOrNull()?.id)
                },
            )
            app.dao.saveProgress(ProgressEntity(progressId, after.currentBpm, after.consecutivePasses, after.bestPassedBpm))
            outcome = Outcome(stats, passed, bpm, tempoUp, after.currentBpm, after.consecutivePasses)
            bpm = after.currentBpm
            phase = Phase.RESULTS
        }
    }

    val stop = {
        job?.cancel()
        phase = Phase.SETUP
    }
    val roundBars = if (mode == PlayMode.ECHO) echoReps(ex, app.settings.barsPerRound) * ex.cycleBars
    else ex.roundBars(app.settings.barsPerRound)

    Page(
        title = ex.name,
        subtitle = kindLabel(ex),
        onBack = {
            job?.cancel()
            app.screen = app.home
        },
        actions = {
            if (ex.isCustom) {
                IconButton(onClick = {
                    job?.cancel()
                    app.screen = Screen.Editor(ex.id)
                }) { Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Palette.muted) }
            }
            IconButton(onClick = {
                job?.cancel()
                app.screen = Screen.Drums(ex.id, back = Screen.Practice(ex.id))
            }) { Icon(AppIcons.Drum, contentDescription = "On the kit", tint = Palette.muted) }
        },
        bottomBar = when (phase) {
            Phase.SETUP -> ({
                PrimaryButton(
                    "Start round · $roundBars bars" + if (mode == PlayMode.ECHO) " (your turns)" else "",
                    ::startRound, icon = Icons.Filled.PlayArrow,
                )
            })
            Phase.PREVIEW, Phase.RUNNING -> ({ QuietButton("Stop", stop, Modifier.fillMaxWidth()) })
            Phase.RATING -> null
            Phase.RESULTS -> ({
                PrimaryButton("Again · $bpm BPM", ::startRound, icon = Icons.Filled.Refresh)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Settings", { phase = Phase.SETUP }, Modifier.weight(1f))
                    if (app.activeSession != null) {
                        SecondaryButton("Back to session", { app.screen = Screen.Session }, Modifier.weight(1f))
                    }
                }
            })
        },
    ) {
        when (phase) {
            Phase.SETUP -> {
                if (ex.tip.isNotEmpty()) Text(ex.tip, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                if (touch) {
                    if (app.settings.touchOffsetMs.isNaN()) {
                        Banner("The touch screen isn't calibrated yet, so taps will look late.", Palette.off) {
                            app.screen = Screen.Calibration
                        }
                    }
                } else if (ex.usesPads && !padAware) {
                    val missing = (ex.pads - app.classifier.pads).sortedBy { it.ordinal }.joinToString(", ") { it.label }
                    Banner("Not learned yet: $missing. This round checks timing only; learn these drums to check them too.",
                        Palette.off) { app.screen = Screen.Kit }
                }

                Section("Pattern", trailing = {
                    ViewToggle(app)
                    SmallAction("Listen", Icons.Filled.PlayArrow, ::startPreview)
                }) {
                    for ((bars, label) in distinctBars(ex)) {
                        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.muted)
                        PatternView(app, ex, bars.first, emptyMap(), null)
                    }
                }

                Section("Tempo") {
                    TempoControl(
                        bpm, { bpm = it },
                        caption = "Target ${ex.targetBpm} BPM" + if (ex.beatUnit == 8) " · counted in eighths" else "",
                    )
                    if (ex.swing != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Swing", style = MaterialTheme.typography.labelLarge, color = Palette.muted, modifier = Modifier.weight(1f))
                            Text(
                                "$swing% · " + when {
                                    swing <= 52 -> "straight"
                                    swing in 64..68 -> "triplet"
                                    swing > 68 -> "hard dotted"
                                    else -> "light"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        // A numeric control: 50 % on the left, like the tempo buttons.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Slider(value = swing.toFloat(), onValueChange = { swing = it.roundToInt() }, valueRange = 50f..75f, steps = 24)
                        }
                    }
                }

                Section("How to play") {
                    Segmented(PlayMode.entries.map { it to it.label }, mode, {
                        mode = it
                        route = currentAudioRoute(context)
                    })
                    Text(
                        when (mode) {
                            PlayMode.NORMAL -> "You play with the metronome."
                            PlayMode.ECHO ->
                                "The app plays ${if (ex.cycleBars == 1) "a bar" else "${ex.cycleBars} bars"}, then you play it back. Only your turns are scored."
                            PlayMode.TOGETHER -> if (touch) "The app plays the drums with you, and they light up on the screen." else when (route) {
                                AudioRoute.WIRED -> "The app plays the drums with you in your headphones. The microphone only hears you."
                                AudioRoute.SPEAKER -> "The app plays guide tones with you that the microphone filters out, so only your playing is measured. " +
                                    "With wired headphones you hear drums."
                                AudioRoute.BLUETOOTH -> "Bluetooth headphones delay the sound by tens of milliseconds or more. " +
                                    "Wired headphones or the phone speaker work better."
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mode == PlayMode.TOGETHER && !touch && route == AudioRoute.BLUETOOTH) Palette.off else Palette.muted,
                    )
                }

                if (touch) {
                    Section(if (ex.usesPads) "Your kit" else "Your pads") {
                        Text(
                            if (ex.usesPads) "Play the kit with your fingers. Try it now."
                            else "Left hand on the left pad, right hand on the right: timing and hand are both checked.",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                        )
                        TouchInput(app, runner, kit = ex.usesPads)
                    }
                }
                Text(
                    "Passes with a spread up to ${ex.passStdMs.roundToInt()}ms and at least ${ex.passHitPct.roundToInt()}% hits. " +
                        "Two passes in a row and the tempo goes up.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
            }

            Phase.PREVIEW -> plan?.let { p ->
                val beat = p.beatAt(runner.playhead)
                val bar = floor(beat / p.beatsPerBar).toInt().coerceIn(0, p.bars - 1)
                RoundHeader(if (beat < 0) "Count-in…" else "The app plays", Palette.accent,
                    if (beat >= 0) "Bar ${bar + 1} / ${p.bars}" else null)
                Panel { PatternView(app, ex, bar, emptyMap(), if (beat >= 0) (beat - bar * p.beatsPerBar) * ex.subdivision else null) }
                val lights = appLights(p.clicks, runner.playhead, p.sampleRate, byHand = !ex.usesPads)
                if (ex.usesPads) DrumKitView(app, appLights = lights) else HandPads(app, appLights = lights)
            }

            Phase.RUNNING -> plan?.let { p ->
                LiveRound(app, ex, p, notes, padAware, runner, byHand)
                if (touch) {
                    TouchInput(app, runner, kit = ex.usesPads,
                        appLights = appLights(p.clicks, runner.playhead, p.sampleRate, byHand = !ex.usesPads))
                }
            }

            Phase.RATING -> SelfRating(onRate = ::finish)

            Phase.RESULTS -> outcome?.let { o -> RoundResults(ex, o, byHand) }
        }
    }
}

/** The big line at the top of a running round. */
@Composable
private fun RoundHeader(title: String, color: Color, sub: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = color)
        if (sub != null) Text(sub, style = MaterialTheme.typography.titleMedium, color = Palette.muted)
    }
}

/** A small text button with an icon, for panel headers. */
@Composable
fun SmallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}


@Composable
private fun LiveRound(
    app: AppState, ex: Exercise, p: RoundPlan, notes: List<ExpectedNote>, padAware: Boolean, runner: RoundRunner, byHand: Boolean,
) {
    val playhead = runner.playhead
    val beat = p.beatAt(playhead)
    // Hits arrive ~50-100 ms after they sound (detection + feature window); don't call a
    // note missed before then.
    val upTo = playhead - p.msToFrames(150.0)
    val live = Scoring.match(notes, runner.onsets, p, padAware, upToFrame = upTo, byHand = byHand)
    val bar = floor(beat / p.beatsPerBar).toInt().coerceIn(0, p.bars - 1)
    val states = HashMap<CellKey, StepState>()
    var lastHit: Double? = null
    live.notes.forEach { r ->
        if (r.note.bar == bar) states[r.note.stepInBar to noteRowKey(r.note)] = stepState(r, r.note.frame + p.windowFrames <= upTo)
        if (r.deviationMs != null) lastHit = r.deviationMs
    }
    // A hit on the wrong drum colours the notes it should have been.
    for (w in live.wrongDrum) {
        val near = live.notes.filter { it.note.bar == bar && !it.hit && abs(it.note.frame - w.frame) <= p.windowFrames }
        near.forEach { states[it.note.stepInBar to noteRowKey(it.note)] = StepState.WRONG }
    }

    if (beat < 0) {
        RoundHeader("${(floor(beat).toInt() + p.countInBeats + 1).coerceIn(1, p.countInBeats)}", Palette.text, "Count-in")
    } else {
        val listening = p.listenBars.isNotEmpty() && bar in p.listenBars
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.listenBars.isNotEmpty()) {
                Text(if (listening) "Listen" else "Your turn", style = MaterialTheme.typography.displaySmall,
                    color = if (listening) Palette.accent else Palette.good)
            }
            Text("Bar ${bar + 1} / ${p.bars}" + if (ex.isFillBar(bar)) " · fill" else "",
                style = if (p.listenBars.isEmpty()) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                color = if (p.listenBars.isEmpty()) Palette.text else Palette.muted)
            if (p.bars <= 16) Dots(bar, p.bars, current = bar)
        }
    }
    val cursor = if (beat >= 0) (beat - bar * p.beatsPerBar) * ex.subdivision else null
    Panel { PatternView(app, ex, bar, states, cursor) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val lh = lastHit
        StatTile(
            "Last hit",
            lh?.let { "${fmtMs(it)}" } ?: "—",
            Modifier.weight(1f),
            color = when {
                lh == null -> Palette.muted
                abs(lh) <= GOOD_MS -> Palette.good
                else -> Palette.off
            },
            sub = lh?.let { "ms · ${earlyLate(it)}" },
        )
        if (padAware || byHand) {
            StatTile(if (byHand) "Wrong hand" else "Wrong drum", "${live.wrongDrum.size}", Modifier.weight(1f),
                color = if (live.wrongDrum.isEmpty()) Palette.muted else Palette.wrong)
        }
        StatTile("Extra", "${live.extras.size}", Modifier.weight(1f),
            color = if (live.extras.isEmpty()) Palette.muted else Palette.bad)
    }
}

/** "Repeat after me" turns per round: about as many of the player's bars as a normal round. */
private fun echoReps(ex: Exercise, barsPerRound: Int) = maxOf(1, (barsPerRound + ex.cycleBars - 1) / ex.cycleBars)

/**
 * The bars of one cycle, with runs of identical bars shown once ("Bars 2–3") so a
 * groove-plus-fill loop does not repeat the same groove three times.
 */
private fun distinctBars(ex: Exercise): List<Pair<IntRange, String?>> {
    if (ex.cycleBars == 1) return listOf(0..0 to null)
    fun content(bar: Int) = (0 until ex.stepsPerBar).map { ex.strokesAt(bar * ex.stepsPerBar + it) }
    val out = ArrayList<Pair<IntRange, String?>>()
    var start = 0
    for (b in 1..ex.cycleBars) {
        if (b < ex.cycleBars && content(b) == content(start)) continue
        val range = start until b
        val name = if (range.first == range.last) "Bar ${range.first + 1}" else "Bars ${range.first + 1}–${range.last + 1}"
        out += range to name + if (ex.isFillBar(range.first)) " · fill" else ""
        start = b
    }
    return out
}

/** Grid row of a note: its drum, its hand (rudiments), or every row (timing-only notes). */
private fun noteRowKey(n: ExpectedNote): String = when {
    n.pad != null -> n.pad.id
    n.hand == 'R' || n.hand == 'L' -> n.hand.toString()
    else -> ANY_ROW
}

/** The bar as a step grid or in drum notation, as chosen in [ViewToggle]. */
@Composable
fun PatternView(app: AppState, ex: Exercise, bar: Int, states: Map<CellKey, StepState>, cursor: Double?) {
    if (app.settings.notation) NotationView(ex, bar, states, cursor) else ExerciseGrid(ex, bar, states, cursor)
}

@Composable
fun ViewToggle(app: AppState) {
    TextButton(onClick = { app.settings.updateNotation(!app.settings.notation) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(if (app.settings.notation) "Grid" else "Notation", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SelfRating(onRate: (Int?) -> Unit) {
    Panel(padding = 20.dp, spacing = 16.dp) {
        Text("How did that feel?", style = MaterialTheme.typography.headlineSmall)
        Text("Feeling first, numbers second: that's how your inner ear learns to judge itself.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (1..5).forEach { r ->
                    FilledTonalButton(
                        onClick = { onRate(r) }, shape = CircleShape, modifier = Modifier.size(56.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("$r", style = MaterialTheme.typography.titleLarge) }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("All over", style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.weight(1f))
                Text("Locked in", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
        }
        TextButton(onClick = { onRate(null) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoundResults(ex: Exercise, o: Outcome, byHand: Boolean) {
    val s = o.stats
    val tone = if (o.passed) Palette.good else Palette.off
    Panel(color = tone.copy(alpha = 0.10f), padding = 20.dp, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(if (o.passed) Icons.Filled.Check else Icons.Filled.Refresh, tone, size = 52.dp)
            Column {
                Text(if (o.passed) "Passed" else "Not yet", style = MaterialTheme.typography.displaySmall, color = tone)
                Text("${o.bpm} BPM", style = MaterialTheme.typography.titleMedium, color = Palette.muted)
            }
        }
        Text(
            when {
                o.tempoUp -> "Two passes in a row: the tempo goes up to ${o.nextBpm} BPM."
                o.passed -> "One more pass and the tempo goes up."
                else -> "The tempo stays at ${o.bpm} BPM. Accuracy before speed."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (s.hits == 0) {
        Banner("No hits detected. Check on the calibration screen that the level meter moves when you play.", Palette.off)
        return
    }
    StatGrid {
        StatTile("Spread (SD)", "${fmt1(s.stdMs)}ms", Modifier.weight(1f),
            color = if (s.stdMs <= ex.passStdMs) Palette.good else Palette.off, sub = "Steadiness · the key number")
        StatTile("Average offset", "${fmtMs(s.meanMs)}ms", Modifier.weight(1f), sub = earlyLate(s.meanMs))
        StatTile("Hits", "${s.hitPct.roundToInt()}%", Modifier.weight(1f),
            color = if (s.hitPct >= ex.passHitPct) Palette.good else Palette.off, sub = "${s.hits} / ${s.expected}")
        StatTile("Extra hits", "${s.extras}", Modifier.weight(1f), color = if (s.extras == 0) Palette.good else Palette.bad)
        if (ex.usesPads || byHand) {
            StatTile(if (byHand) "Wrong hand" else "Wrong drum", "${s.wrongDrum}", Modifier.weight(1f),
                color = if (s.wrongDrum == 0) Palette.good else Palette.wrong)
        }
        s.accentDb?.let { StatTile("Accent vs normal", "${fmt1(it)}dB", Modifier.weight(1f), sub = "How much louder accents are") }
    }
    Section("Offsets") {
        DeviationHistogram(s.histogram)
        Text("Green: within ${GOOD_MS.roundToInt()}ms of the click. Left is early, right is late.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted)
    }
    if (s.perHand.size > 1) {
        Section("By hand") {
            StatGrid {
                s.perHand.toSortedMap(compareByDescending { it == 'R' }).forEach { (hand, sp) ->
                    StatTile(if (hand == 'R') "Right hand" else "Left hand", "${fmt1(sp.stdMs)}ms", Modifier.weight(1f),
                        sub = "avg ${fmtMs(sp.meanMs)}ms · ${earlyLate(sp.meanMs)}")
                }
            }
        }
    }
    if (s.landing.n > 0) {
        Section("Fill and landing") {
            StatGrid {
                StatTile("Landing on 1", "${fmtMs(s.landing.meanMs)}ms", Modifier.weight(1f), sub = earlyLate(s.landing.meanMs),
                    color = if (abs(s.landing.meanMs) <= ex.passStdMs * 1.5) Palette.good else Palette.off)
                if (s.fill.n > 0) StatTile("In the fill", "${fmtMs(s.fill.meanMs)}ms", Modifier.weight(1f), sub = "spread ${fmt1(s.fill.stdMs)}ms")
                if (s.groove.n > 0) StatTile("In the groove", "${fmtMs(s.groove.meanMs)}ms", Modifier.weight(1f), sub = "spread ${fmt1(s.groove.stdMs)}ms")
            }
            if (s.fill.n > 0 && s.fill.meanMs < s.groove.meanMs - 5) {
                Text("You rush the fill compared with the groove. Think of the fill as the same time, continued.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.off)
            }
        }
    }
    if (s.perPad.size > 1) {
        Section("By drum") {
            StatGrid {
                s.perPad.toSortedMap(compareBy { it.ordinal }).forEach { (pad, sp) ->
                    StatTile(pad.label, "${fmt1(sp.stdMs)}ms", Modifier.weight(1f), sub = "avg ${fmtMs(sp.meanMs)}ms · ${earlyLate(sp.meanMs)}")
                }
            }
        }
    }
    if (ex.subdivision > 1) {
        val names = positionNames(ex.subdivision)
        Section("By position in the beat") {
            StatGrid {
                s.byBeatPosition.forEachIndexed { i, sp ->
                    if (sp.n > 0) StatTile(names[i], "${fmtMs(sp.meanMs)}ms", Modifier.weight(1f), sub = "spread ${fmt1(sp.stdMs)}ms")
                }
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
