package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.model.CalibrationMath
import com.amihay.drumtrainer.model.ClickType
import com.amihay.drumtrainer.model.RoundPlanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

private enum class CalPhase { IDLE, LOOPBACK, PAD }

private const val PAD_BPM = 90
private const val PAD_COUNT_IN = 4
private const val PAD_BEATS = 16

@Composable
fun CalibrationScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    val runner = remember { RoundRunner(app.engine) }
    var phase by remember { mutableStateOf(CalPhase.IDLE) }
    var message by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    var hitCount by remember { mutableIntStateOf(0) }
    var lastHitAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    var padStart by remember { mutableLongStateOf(0L) }
    var padBeatFrames by remember { mutableDoubleStateOf(1.0) }
    KeepScreenOn()

    // Live input monitor while no calibration step is running.
    LaunchedEffect(phase) {
        if (phase != CalPhase.IDLE) return@LaunchedEffect
        while (true) {
            withFrameNanos { now = it / 1_000_000 }
            level = maxOf(app.engine.inputPeak(), level * 0.9f)
            val hits = app.engine.pollOnsets(0.0)
            if (hits.isNotEmpty()) {
                hitCount += hits.size
                lastHitAt = now
            }
        }
    }

    fun runLoopback() = scope.launch {
        phase = CalPhase.LOOPBACK
        message = null
        app.engine.setNotch(false)
        delay(100)
        val sr = app.engine.sampleRate
        val clicks = RoundPlanner.calibrationClicks(app.engine.scheduleStartFrame(), sr * 0.5, 12, ClickType.BROADBAND)
        try {
            runner.run(clicks, clicks.last().frame.toDouble(), offsetMs = 0.0, tailMs = 500)
        } finally {
            app.engine.setNotch(true)
        }
        val r = CalibrationMath.medianDelay(clicks.map { it.frame.toDouble() }, runner.onsets, -50.0, 400.0, sr)
        message = if (r == null) {
            "לא זוהו מספיק קליקים. הגבר את עוצמת הטלפון, הקרב אותו או הגבר את הרגישות."
        } else {
            app.settings.updateLoopback(r.medianMs)
            "השהיית רמקול→מיקרופון: ${fmt1(r.medianMs)}ms (${r.matched}/12 קליקים, פיזור ${fmt1(r.spreadMs)}ms)"
        }
        phase = CalPhase.IDLE
    }

    fun runPad() = scope.launch {
        phase = CalPhase.PAD
        message = null
        val sr = app.engine.sampleRate
        val beat = sr * 60.0 / PAD_BPM
        val start = app.engine.scheduleStartFrame()
        padStart = start
        padBeatFrames = beat
        val clicks = RoundPlanner.calibrationClicks(start, beat, PAD_COUNT_IN + PAD_BEATS, ClickType.NORMAL)
            .mapIndexed { i, c -> if (i % 4 == 0) c.copy(type = ClickType.ACCENT) else c }
        runner.run(clicks, clicks.last().frame.toDouble(), offsetMs = 0.0)
        val measured = clicks.drop(PAD_COUNT_IN).map { it.frame.toDouble() }
        val r = CalibrationMath.medianDelay(measured, runner.onsets, -100.0, 400.0, sr)
        message = when {
            r == null -> "לא זוהו מספיק מכות. בדוק שהרמקול של המודול פתוח ושמד הרמה זז כשאתה מכה."
            r.spreadMs > 12 -> "הפיזור גבוה (${fmt1(r.spreadMs)}ms) – כדאי לנסות שוב, הכי צמוד לקליק שאפשר."
                .also { app.settings.updatePadOffset(r.medianMs) }
            else -> {
                app.settings.updatePadOffset(r.medianMs)
                "השהיה כוללת: ${fmt1(r.medianMs)}ms (${r.matched}/$PAD_BEATS מכות, פיזור ${fmt1(r.spreadMs)}ms). נשמר."
            }
        }
        phase = CalPhase.IDLE
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("כיול", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { app.screen = Screen.Home }, enabled = phase == CalPhase.IDLE) { Text("חזרה") }
        }
        Text(
            "הנח את הטלפון במקום שבו הוא יעמוד באימון, במרחק של כמטר מהרמקול של התופים. " +
                "את הכיול מספיק לעשות פעם אחת, ושוב רק אם מזיזים רמקול, טלפון או מודול.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!app.engine.timestampsAligned) {
            Banner("המכשיר לא מדווח חותמות זמן לאודיו – חזור על הכיול בכל פתיחה של האפליקציה.", Palette.off)
        }

        Section("מיקרופון ורגישות") {
            Text("הכה על הפד: העיגול צריך להבהב פעם אחת לכל מכה, ולא להבהב בשקט.", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val flashing = now - lastHitAt < 120
                Box(Modifier.size(48.dp).background(if (flashing) Palette.good else Palette.pending, CircleShape))
                Text("$hitCount מכות", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { hitCount = 0 }) { Text("איפוס") }
            }
            LevelMeter(level, Modifier.fillMaxWidth())
            Text("רגישות: ${(app.settings.sensitivity * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = app.settings.sensitivity,
                onValueChange = {
                    app.settings.updateSensitivity(it)
                    app.engine.setSensitivity(it)
                },
            )
        }

        Section("שלב 1 · השהיית הטלפון") {
            Text("הטלפון ישמיע 12 קליקים ויקשיב להם. שקט בחדר, אל תנגן.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "נמדד: " + if (app.settings.loopbackMs.isNaN()) "עוד לא" else "${fmt1(app.settings.loopbackMs)}ms",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = { runLoopback() }, enabled = phase == CalPhase.IDLE) {
                Text(if (phase == CalPhase.LOOPBACK) "מודד…" else "מדוד השהיה")
            }
        }

        Section("שלב 2 · נגינה עם הקליק") {
            Text(
                "אחרי ספירה של 4, נגן רבעים על הסנר יחד עם הקליק – $PAD_BEATS מכות ב-$PAD_BPM BPM. " +
                    "האפליקציה מודדת את ההשהיה של המודול והרמקול ומקזזת אותה מכל מדידה.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "השהיה כוללת: " + if (app.settings.padOffsetMs.isNaN()) "עוד לא" else "${fmt1(app.settings.padOffsetMs)}ms",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!app.settings.padOffsetMs.isNaN() && !app.settings.loopbackMs.isNaN()) {
                Text(
                    "מתוכה מודול ורמקול: כ-${fmt1(app.settings.padOffsetMs - app.settings.loopbackMs)}ms",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                )
            }
            if (phase == CalPhase.PAD) {
                val beatIdx = floor((runner.playhead - padStart) / padBeatFrames).toInt()
                Text(
                    when {
                        beatIdx < 0 -> "…"
                        beatIdx < PAD_COUNT_IN -> "${beatIdx + 1}"
                        else -> "נגן! ${(beatIdx - PAD_COUNT_IN + 1).coerceAtMost(PAD_BEATS)} / $PAD_BEATS"
                    },
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Button(onClick = { runPad() }, enabled = phase == CalPhase.IDLE) {
                Text(if (phase == CalPhase.PAD) "מקשיב…" else "התחל")
            }
        }

        message?.let { Banner(it, Palette.accent) }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}
