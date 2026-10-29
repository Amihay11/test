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
import androidx.compose.material3.Button
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
    else -> c.pads.joinToString(" + ") { it.he }
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
                        message = "${pad.he}: נלמדו $HITS_PER_PAD מכות."
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

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ערכת תופים", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { app.screen = Screen.Home }) { Text("חזרה") }
        }

        val pad = recording
        if (pad != null) {
            Section("לימוד: ${pad.he}") {
                Text(
                    "הכה $HITS_PER_PAD פעמים על ה${pad.he} בלבד, בעוצמות שונות – חזק, בינוני וחלש. " +
                        "השאר רווח קטן בין המכות.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text("$LRM${recorded.size} / $HITS_PER_PAD", style = MaterialTheme.typography.displayMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(HITS_PER_PAD) { i ->
                        Box(Modifier.size(18.dp).background(if (i < recorded.size) Palette.good else Palette.pending, CircleShape))
                    }
                }
                FilledTonalButton(onClick = {
                    recorded.clear()
                    recording = null
                }) { Text("ביטול") }
            }
            return@Column
        }

        Text(
            "כל פד נשמע אחרת דרך הרמקול. מלמדים את האפליקציה פד אחרי פד, ואז בודקים שהיא מזהה נכון.",
            style = MaterialTheme.typography.bodyLarge,
        )
        message?.let { Banner(it, Palette.good) }

        Section("לימוד פדים") {
            val counts = app.padSamples.groupingBy { it.pad }.eachCount()
            Pad.entries.forEach { p ->
                val n = counts[p] ?: 0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.he, style = MaterialTheme.typography.titleMedium)
                        Text(if (n == 0) "לא נלמד" else "$n מכות", style = MaterialTheme.typography.bodyMedium,
                            color = if (n == 0) Palette.muted else Palette.good)
                    }
                    if (n > 0) OutlinedButton(onClick = { app.replacePadSamples(p, emptyList()) }) { Text("מחק") }
                    Button(onClick = {
                        message = null
                        recorded.clear()
                        recording = p
                    }) { Text(if (n == 0) "למד" else "למד מחדש") }
                }
            }
        }

        Section("בדיקה") {
            if (!app.classifier.isUsable) {
                Text("קודם למד לפחות פד אחד.", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
                return@Section
            }
            Text("הכה על הפדים – כאן מופיע מה זוהה בכל מכה.", style = MaterialTheme.typography.bodyLarge)
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
                CenteredNote("ביטחון ${(it.result.confidence * 100).roundToInt()}% · עוצמה $db dBFS")
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

            Text("בדיקת דיוק: בחר פד, הכה עליו כמה פעמים וראה כמה זוהו נכון.", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                app.classifier.pads.sortedBy { it.ordinal }.forEach { p ->
                    val selected = target == p
                    val onClick = {
                        target = if (selected) null else p
                        correct = 0
                        attempts = 0
                    }
                    if (selected) Button(onClick = onClick) { Text(p.he) } else FilledTonalButton(onClick = onClick) { Text(p.he) }
                }
            }
            target?.let { t ->
                val pct = if (attempts > 0) " (${100 * correct / attempts}%)" else ""
                Text("${t.he}: $LRM$correct / $attempts$LRM זוהו נכון$pct", style = MaterialTheme.typography.titleLarge,
                    color = if (attempts > 0 && correct * 10 >= attempts * 9) Palette.good else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
