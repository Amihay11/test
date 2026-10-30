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
import androidx.compose.material3.TextButton
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

private enum class CalPhase { IDLE, LOOPBACK, PAD, TOUCH }

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
            "Not enough clicks detected. Turn the phone up, move it closer, or raise the sensitivity."
        } else {
            app.settings.updateLoopback(r.medianMs)
            "Speaker → mic delay: ${fmt1(r.medianMs)}ms (${r.matched}/12 clicks, spread ${fmt1(r.spreadMs)}ms)"
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
            r == null -> "Not enough hits detected. Check that the module's speaker is on and the level meter moves when you play."
            r.spreadMs > 12 -> "The spread is high (${fmt1(r.spreadMs)}ms). Try again, as tight to the click as you can."
                .also { app.settings.updatePadOffset(r.medianMs) }
            else -> {
                app.settings.updatePadOffset(r.medianMs)
                "Total delay: ${fmt1(r.medianMs)}ms (${r.matched}/$PAD_BEATS hits, spread ${fmt1(r.spreadMs)}ms). Saved."
            }
        }
        phase = CalPhase.IDLE
    }

    fun runTouch() = scope.launch {
        phase = CalPhase.TOUCH
        message = null
        val sr = app.engine.sampleRate
        val beat = sr * 60.0 / PAD_BPM
        val start = app.engine.scheduleStartFrame()
        padStart = start
        padBeatFrames = beat
        val clicks = RoundPlanner.calibrationClicks(start, beat, PAD_COUNT_IN + PAD_BEATS, ClickType.NORMAL)
            .mapIndexed { i, c -> if (i % 4 == 0) c.copy(type = ClickType.ACCENT) else c }
        runner.touchInput = true
        try {
            runner.run(clicks, clicks.last().frame.toDouble(), offsetMs = 0.0)
        } finally {
            runner.touchInput = false
        }
        val r = CalibrationMath.medianDelay(clicks.drop(PAD_COUNT_IN).map { it.frame.toDouble() }, runner.onsets, -100.0, 400.0, sr)
        message = if (r == null) {
            "Not enough taps. Tap the pad together with every click."
        } else {
            app.settings.updateTouchOffset(r.medianMs)
            "Screen delay: ${fmt1(r.medianMs)}ms (${r.matched}/$PAD_BEATS taps, spread ${fmt1(r.spreadMs)}ms). Saved."
        }
        phase = CalPhase.IDLE
    }

    Page("Calibration", onBack = if (phase == CalPhase.IDLE) ({ app.screen = Screen.Settings }) else null) {
        Text(
            "Put the phone where it will sit during practice, about a meter from the drum speaker. " +
                "Calibrate once, and again only if you move the speaker, the phone or the module.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
        )
        message?.let { Banner(it, Palette.accent) }
        if (!app.engine.timestampsAligned) {
            Banner("This device doesn't report audio timestamps, so repeat the calibration every time you open the app.", Palette.off)
        }

        Section("Microphone and sensitivity") {
            Text("Hit the pad: the circle should flash once per hit, and never in silence.", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val flashing = now - lastHitAt < 120
                Box(Modifier.size(48.dp).background(if (flashing) Palette.good else Palette.pending, CircleShape))
                Text("$hitCount hits", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { hitCount = 0 }) { Text("Reset") }
            }
            LevelMeter(level, Modifier.fillMaxWidth())
            Text("Sensitivity: ${(app.settings.sensitivity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = app.settings.sensitivity,
                onValueChange = {
                    app.settings.updateSensitivity(it)
                    app.engine.setSensitivity(it)
                },
            )
        }

        Section("Step 1 · Phone latency") {
            Text("The phone plays 12 clicks and listens to them. Keep the room quiet and don't play.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Measured: " + if (app.settings.loopbackMs.isNaN()) "not yet" else "${fmt1(app.settings.loopbackMs)}ms",
                style = MaterialTheme.typography.titleMedium,
            )
            SecondaryButton(if (phase == CalPhase.LOOPBACK) "Measuring…" else "Measure latency", { runLoopback() }, Modifier.fillMaxWidth(), enabled = phase == CalPhase.IDLE)
        }

        Section("Step 2 · Play with the click") {
            Text(
                "After a count of 4, play quarter notes on the snare with the click: $PAD_BEATS hits at $PAD_BPM BPM. " +
                    "The app measures the delay of your module and speaker and takes it off every measurement.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Total delay: " + if (app.settings.padOffsetMs.isNaN()) "not yet" else "${fmt1(app.settings.padOffsetMs)}ms",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!app.settings.padOffsetMs.isNaN() && !app.settings.loopbackMs.isNaN()) {
                Text(
                    "Of which module and speaker: about ${fmt1(app.settings.padOffsetMs - app.settings.loopbackMs)}ms",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                )
            }
            if (phase == CalPhase.PAD) {
                val beatIdx = floor((runner.playhead - padStart) / padBeatFrames).toInt()
                Text(
                    when {
                        beatIdx < 0 -> "…"
                        beatIdx < PAD_COUNT_IN -> "${beatIdx + 1}"
                        else -> "Play! ${(beatIdx - PAD_COUNT_IN + 1).coerceAtMost(PAD_BEATS)} / $PAD_BEATS"
                    },
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            SecondaryButton(if (phase == CalPhase.PAD) "Listening…" else "Start", { runPad() }, Modifier.fillMaxWidth(), enabled = phase == CalPhase.IDLE)
        }

        Section("Practice without drums · Screen latency") {
            Text(
                "A touch screen reports a tap tens of milliseconds late. After a count of 4, tap $PAD_BEATS quarter notes " +
                    "on either pad with the click, and the app takes that delay off.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Screen delay: " + if (app.settings.touchOffsetMs.isNaN()) "not yet" else "${fmt1(app.settings.touchOffsetMs)}ms",
                style = MaterialTheme.typography.titleMedium,
            )
            if (phase == CalPhase.TOUCH) {
                val beatIdx = floor((runner.playhead - padStart) / padBeatFrames).toInt()
                Text(
                    when {
                        beatIdx < 0 -> "…"
                        beatIdx < PAD_COUNT_IN -> "${beatIdx + 1}"
                        else -> "Tap! ${(beatIdx - PAD_COUNT_IN + 1).coerceAtMost(PAD_BEATS)} / $PAD_BEATS"
                    },
                    style = MaterialTheme.typography.displayMedium,
                )
                HandPads(app, onTap = { hand, t -> runner.tap(t, hand = hand) })
            }
            SecondaryButton(if (phase == CalPhase.TOUCH) "Listening…" else "Start", { runTouch() }, Modifier.fillMaxWidth(), enabled = phase == CalPhase.IDLE)
        }

    }
}

