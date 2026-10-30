package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.model.Classification
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.TrainingSample
import kotlin.math.log10
import kotlin.math.roundToInt

/** Hits recorded per pad in the pad calibration. */
const val HITS_PER_PAD = 10

private data class LiveHit(val result: Classification, val strength: Float, val id: Long)

fun describe(c: Classification): String = when {
    c.pads.isEmpty() -> "?"
    else -> c.pads.joinToString(" + ") { it.label }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KitScreen(app: AppState) {
    var recording by remember { mutableStateOf<Pad?>(null) }
    val recorded = remember { mutableStateListOf<TrainingSample>() }
    val live = remember { mutableStateListOf<LiveHit>() }
    var target by remember { mutableStateOf<Pad?>(null) }
    var correct by remember { mutableIntStateOf(0) }
    var attempts by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var nextId by remember { mutableStateOf(0L) }
    KeepScreenOn()

    // One loop reads hits for both modes: recording a pad, or the live test.
    LaunchedEffect(recording) {
        app.engine.pollOnsets(0.0)  // drop hits from before this mode started
        while (true) {
            withFrameNanos { }
            for (onset in app.engine.pollOnsets(0.0)) {
                val f = onset.features ?: continue
                val pad = recording
                if (pad != null) {
                    recorded += TrainingSample(pad, f)
                    if (recorded.size >= HITS_PER_PAD) {
                        app.replacePadSamples(pad, recorded.toList())
                        message = "${pad.label}: learned $HITS_PER_PAD hits."
                        recorded.clear()
                        recording = null
                        break
                    }
                } else {
                    val result = app.classifier.classify(f)
                    live.add(0, LiveHit(result, onset.strength, nextId++))
                    if (live.size > 24) live.removeAt(live.size - 1)
                    target?.let { t ->
                        attempts++
                        if (result.pads == listOf(t)) correct++
                    }
                }
            }
        }
    }

    Page("Learn your drums", onBack = { app.screen = Screen.Settings }) {

        val pad = recording
        if (pad != null) {
            Section("Learning: ${pad.label}") {
                Text(
                    "Hit the ${pad.label} only, $HITS_PER_PAD times, at different strengths: loud, medium and soft. " +
                        "Leave a short gap between hits.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("${recorded.size} / $HITS_PER_PAD", style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Box(Modifier.align(Alignment.CenterHorizontally)) { Dots(recorded.size, HITS_PER_PAD, Palette.good) }
                QuietButton("Cancel", {
                    recorded.clear()
                    recording = null
                }, Modifier.fillMaxWidth())
            }
            return@Page
        }

        Text(
            "Every pad sounds different through the speaker. Teach the app one pad at a time, then check that it recognizes them.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
        )
        message?.let { Banner(it, Palette.good) }

        Section("Pads") {
            val counts = app.padSamples.groupingBy { it.pad }.eachCount()
            Pad.entries.forEach { p ->
                val n = counts[p] ?: 0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (n == 0) "Not learned" else "Learned · $n hits", style = MaterialTheme.typography.bodySmall,
                            color = if (n == 0) Palette.muted else Palette.good)
                    }
                    if (n > 0) TextButton(onClick = { app.replacePadSamples(p, emptyList()) }) { Text("Delete", color = Palette.muted) }
                    SecondaryButton(if (n == 0) "Learn" else "Redo", {
                        message = null
                        recorded.clear()
                        recording = p
                    })
                }
            }
        }

        Section("Test") {
            if (!app.classifier.isUsable) {
                Text("Learn at least one pad first.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                return@Section
            }
            Text("Hit the pads: what each hit was recognized as shows here.", style = MaterialTheme.typography.bodyMedium)
            val last = live.firstOrNull()
            Text(
                last?.let { describe(it.result) } ?: "—",
                style = MaterialTheme.typography.displayMedium,
                color = when {
                    last == null -> Palette.muted
                    last.result.pads.isEmpty() -> Palette.bad
                    else -> Palette.accent
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            last?.let {
                val db = if (it.strength > 0) (20 * log10(it.strength)).roundToInt() else -90
                CenteredNote("Confidence ${(it.result.confidence * 100).roundToInt()}% · level $db dBFS")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                live.forEach { h ->
                    Text(
                        if (h.result.pads.isEmpty()) "?" else h.result.pads.joinToString("+") { it.short },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .background(
                                if (h.result.pads.isEmpty()) Palette.bad.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            Text("Accuracy check: pick a pad, hit it a few times and see how many were recognized.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                app.classifier.pads.sortedBy { it.ordinal }.forEach { p ->
                    val selected = target == p
                    ChoiceChip(p.label, selected) {
                        target = if (selected) null else p
                        correct = 0
                        attempts = 0
                    }
                }
            }
            target?.let { t ->
                val pct = if (attempts > 0) " (${100 * correct / attempts}%)" else ""
                Text("${t.label}: $correct / $attempts recognized$pct", style = MaterialTheme.typography.titleLarge,
                    color = if (attempts > 0 && correct * 10 >= attempts * 9) Palette.good else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
