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
    NORMAL("רגיל", ""),
    /** The app plays the pattern at the same time as the player. */
    TOGETHER("ביחד", "-together"),
    /** The app plays a cycle, then the player plays it back. */
    ECHO("חזור אחריי", "-echo"),
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

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(ex.nameHe, style = MaterialTheme.typography.headlineMedium)
                Text(ex.name, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            }
            if (ex.isCustom) OutlinedButton(onClick = {
                job?.cancel()
                app.screen = Screen.Editor(ex.id)
            }) { Text("ערוך") }
            OutlinedButton(onClick = {
                job?.cancel()
                app.screen = app.home
            }) { Text(if (app.activeSession != null) "לאימון" else "חזרה") }
        }
        if (ex.tip.isNotEmpty()) Text(ex.tip, style = MaterialTheme.typography.bodyLarge)

        when (phase) {
            Phase.SETUP -> {
                if (touch) {
                    Text(
                        if (ex.usesPads) "אימון בלי תופים: נגן באצבעות על המערכת שבמסך. אפשר לנסות אותה כבר עכשיו."
                        else "אימון בלי תופים: הקש ביד שמאל על הפד השמאלי וביד ימין על הימני – נמדדים גם התזמון וגם היד.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                    )
                    if (app.settings.touchOffsetMs.isNaN()) {
                        Banner("עוד לא כיילת את מסך המגע – בלי כיול ההקשות ייראו מאוחרות.", Palette.off) {
                            app.screen = Screen.Calibration
                        }
                    }
                } else if (ex.usesPads && !padAware) {
                    val missing = (ex.pads - app.classifier.pads).sortedBy { it.ordinal }.joinToString(", ") { it.he }
                    Banner("לא נלמדו: $missing. בסבב הזה נמדד רק התזמון – כדי לבדוק גם את התופים, למד אותם ב'ערכת תופים'.",
                        Palette.off) { app.screen = Screen.Kit }
                }
                for ((bars, label) in distinctBars(ex)) {
                    if (label != null) Text(label, style = MaterialTheme.typography.titleMedium, color = Palette.muted)
                    PatternView(app, ex, bars.first, emptyMap(), null)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ViewToggle(app)
                    FilledTonalButton(onClick = ::startPreview) { Text("🔊 השמע") }
                    FilledTonalButton(onClick = { app.screen = Screen.Drums(ex.id, back = Screen.Practice(ex.id)) }) {
                        Text("🥁 על המערכת")
                    }
                }
                BpmStepper(bpm, { bpm = it })
                if (ex.beatUnit == 8) {
                    Text("הטמפו נמדד בשמיניות (BPM של שמינית).", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                }
                if (ex.swing != null) {
                    Text(
                        "סווינג: $swing% · " + when {
                            swing <= 52 -> "ישר"
                            swing in 64..68 -> "טריולה"
                            swing > 68 -> "מנוקד חזק"
                            else -> "קל"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // A numeric control: 50 % on the left, like the tempo buttons.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Slider(value = swing.toFloat(), onValueChange = { swing = it.roundToInt() }, valueRange = 50f..75f, steps = 24)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("מצב:", style = MaterialTheme.typography.bodyLarge)
                    PlayMode.entries.forEach { m ->
                        if (mode == m) Button(onClick = {}) { Text(m.label) }
                        else FilledTonalButton(onClick = {
                            mode = m
                            route = currentAudioRoute(context)
                        }) { Text(m.label) }
                    }
                }
                when (mode) {
                    PlayMode.NORMAL -> {}
                    PlayMode.ECHO -> Text(
                        "האפליקציה מנגנת ${if (ex.cycleBars == 1) "תיבה" else "${ex.cycleBars} תיבות"}, ואז אתה חוזר על אותו דבר. נמדדים רק התורות שלך.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                    )
                    PlayMode.TOGETHER -> Text(
                        if (touch) "האפליקציה מנגנת את התופים איתך, והם נדלקים על המסך." else when (route) {
                            AudioRoute.WIRED -> "האפליקציה מנגנת את התופים איתך באוזניות. המיקרופון שומע רק אותך."
                            AudioRoute.SPEAKER -> "האפליקציה מנגנת איתך צלילי הנחיה (צלילים מכוונים במקום תופים), " +
                                "שהמיקרופון מסנן – כך נמדדת רק הנגינה שלך. עם אוזניות חוטיות תשמע תופים."
                            AudioRoute.BLUETOOTH -> "אוזניות בלוטות' מאחרות את הצליל בעשרות עד מאות מילישניות, " +
                                "כך שתנגן אחרי האפליקציה. עדיף אוזניות חוטיות או רמקול הטלפון."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (route == AudioRoute.BLUETOOTH) Palette.off else Palette.muted,
                    )
                }
                if (touch) TouchInput(app, runner, kit = ex.usesPads)
                Text(
                    "יעד: ${ex.targetBpm} BPM · מעבר: סטיית תקן עד ${ex.passStdMs.roundToInt()}ms ו-${ex.passHitPct.roundToInt()}% פגיעות",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                )
                Button(onClick = ::startRound, modifier = Modifier.fillMaxWidth()) {
                    val echo = mode == PlayMode.ECHO
                    val bars = if (echo) echoReps(ex, app.settings.barsPerRound) * ex.cycleBars else ex.roundBars(app.settings.barsPerRound)
                    Text("התחל סבב ($bars תיבות${if (echo) " שלך" else ""})", style = MaterialTheme.typography.titleLarge)
                }
            }

            Phase.PREVIEW -> plan?.let { p ->
                val beat = p.beatAt(runner.playhead)
                val bar = floor(beat / p.beatsPerBar).toInt().coerceIn(0, p.bars - 1)
                Text(if (beat < 0) "ספירה…" else "האפליקציה מנגנת – הקשב", style = MaterialTheme.typography.headlineMedium,
                    color = Palette.accent)
                PatternView(app, ex, bar, emptyMap(), if (beat >= 0) (beat - bar * p.beatsPerBar) * ex.subdivision else null)
                val lights = appLights(p.clicks, runner.playhead, p.sampleRate, byHand = !ex.usesPads)
                if (ex.usesPads) DrumKitView(app, appLights = lights) else HandPads(app, appLights = lights)
                FilledTonalButton(onClick = {
                    job?.cancel()
                    phase = Phase.SETUP
                }, modifier = Modifier.fillMaxWidth()) { Text("עצור") }
            }

            Phase.RUNNING -> plan?.let { p ->
                LiveRound(app, ex, p, notes, padAware, runner, byHand)
                if (touch) {
                    TouchInput(app, runner, kit = ex.usesPads,
                        appLights = appLights(p.clicks, runner.playhead, p.sampleRate, byHand = !ex.usesPads))
                }
                FilledTonalButton(onClick = {
                    job?.cancel()
                    phase = Phase.SETUP
                }, modifier = Modifier.fillMaxWidth()) { Text("עצור") }
            }

            Phase.RATING -> SelfRating(onRate = ::finish)

            Phase.RESULTS -> outcome?.let { o ->
                RoundResults(ex, o, byHand)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::startRound) { Text("סבב נוסף ב-$bpm BPM") }
                    if (app.activeSession != null) FilledTonalButton(onClick = { app.screen = Screen.Session }) { Text("חזרה לאימון") }
                    FilledTonalButton(onClick = { phase = Phase.SETUP }) { Text("הגדרות") }
                }
            }
        }
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

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (beat < 0) {
            Text("${(floor(beat).toInt() + p.countInBeats + 1).coerceIn(1, p.countInBeats)}",
                style = MaterialTheme.typography.displayLarge)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (p.listenBars.isNotEmpty()) {
                    val listening = bar in p.listenBars
                    Text(if (listening) "הקשב" else "תורך", style = MaterialTheme.typography.displayMedium,
                        color = if (listening) Palette.accent else Palette.good)
                }
                Text(
                    "תיבה ${bar + 1} / ${p.bars}" + if (ex.isFillBar(bar)) " · FILL" else "",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
    val cursor = if (beat >= 0) (beat - bar * p.beatsPerBar) * ex.subdivision else null
    PatternView(app, ex, bar, states, cursor)
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
        if (padAware || byHand) {
            StatTile(if (byHand) "יד שגויה" else "תוף שגוי", "${live.wrongDrum.size}", color = if (live.wrongDrum.isEmpty()) Palette.muted else Palette.wrong)
        }
        StatTile("מכות מיותרות", "${live.extras.size}", color = if (live.extras.isEmpty()) Palette.muted else Palette.bad)
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
        val name = if (range.first == range.last) "תיבה ${range.first + 1}" else "תיבות $LRM${range.first + 1}–${range.last + 1}"
        out += range to name + if (ex.isFillBar(range.first)) " · מילוי" else ""
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
    OutlinedButton(onClick = { app.settings.updateNotation(!app.settings.notation) }) {
        Text(if (app.settings.notation) "הצג כרשת" else "הצג בתווים")
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
private fun RoundResults(ex: Exercise, o: Outcome, byHand: Boolean) {
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
        if (ex.usesPads || byHand) StatTile(if (byHand) "יד שגויה" else "תוף שגוי", "${s.wrongDrum}", color = if (s.wrongDrum == 0) Palette.good else Palette.wrong)
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
    if (s.landing.n > 0) {
        Text("מילוי ונחיתה", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("נחיתה על ה-1", "${fmtMs(s.landing.meanMs)}ms", sub = earlyLate(s.landing.meanMs),
                color = if (abs(s.landing.meanMs) <= ex.passStdMs * 1.5) Palette.good else Palette.off)
            if (s.fill.n > 0) StatTile("בתוך המילוי", "${fmtMs(s.fill.meanMs)}ms", sub = "פיזור ${fmt1(s.fill.stdMs)}ms")
            if (s.groove.n > 0) StatTile("בגרוב", "${fmtMs(s.groove.meanMs)}ms", sub = "פיזור ${fmt1(s.groove.stdMs)}ms")
        }
        if (s.fill.n > 0 && s.fill.meanMs < s.groove.meanMs - 5) {
            Text("במילוי אתה מקדים ביחס לגרוב – נסה לחשוב על המילוי כהמשך של אותו קצב.",
                style = MaterialTheme.typography.bodyLarge, color = Palette.off)
        }
    }
    if (s.perPad.size > 1) {
        Text("לפי תוף", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.perPad.toSortedMap(compareBy { it.ordinal }).forEach { (pad, sp) ->
                StatTile(pad.he, "${fmt1(sp.stdMs)}ms", sub = "ממוצע ${fmtMs(sp.meanMs)}ms (${earlyLate(sp.meanMs)})")
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
