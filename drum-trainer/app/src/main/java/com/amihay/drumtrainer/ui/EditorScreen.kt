package com.amihay.drumtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amihay.drumtrainer.model.CUSTOM_PREFIX
import com.amihay.drumtrainer.model.DraftKind
import com.amihay.drumtrainer.model.ExerciseDraft
import com.amihay.drumtrainer.model.ExerciseParser
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.RoundPlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MAX_BARS = 4

/** Grid order, as on a drum chart: cymbals at the top, kick at the bottom. */
private val EDITOR_PADS = listOf(Pad.CRASH, Pad.RIDE, Pad.HIHAT, Pad.TOM_HIGH, Pad.SNARE, Pad.TOM_MID, Pad.TOM_FLOOR, Pad.KICK)

/**
 * Exercise editor (stage 6): write a groove, fill or sticking in a grid, listen to it,
 * and save it. Saved exercises behave like the built-in ones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(app: AppState, exerciseId: String?) {
    val scope = rememberCoroutineScope()
    val runner = remember { RoundRunner(app.engine) }
    val existing = exerciseId?.let { id -> app.exercises.firstOrNull { it.id == id } }
    var draft by remember {
        mutableStateOf(existing?.let { ExerciseDraft.from(it) } ?: ExerciseDraft.blank(CUSTOM_PREFIX + System.currentTimeMillis()))
    }
    var bar by remember { mutableIntStateOf(0) }
    var groupsText by remember { mutableStateOf(draft.beatGroups?.joinToString("+") ?: "") }
    var playing by remember { mutableStateOf<Job?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showProblems by remember { mutableStateOf(false) }
    val isSaved = app.customExercises.any { it.id == draft.id }

    fun update(d: ExerciseDraft) {
        draft = d.normalized()
        bar = bar.coerceIn(0, draft.bars.lastIndex)
        showProblems = false
    }

    fun preview() {
        val ex = runCatching { ExerciseParser.parse(JSONArray().put(draft.toJson()).toString()).single() }.getOrNull() ?: return
        val p = RoundPlanner.preview(ex, draft.startBpm, maxOf(ex.cycleBars, 2), app.engine.scheduleStartFrame(), app.engine.sampleRate)
        playing = scope.launch {
            try {
                runner.run(p.clicks, p.endFrame, 0.0, tailMs = 800)
            } finally {
                playing = null
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isSaved) "עריכת תרגיל" else "תרגיל חדש", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                playing?.cancel()
                app.screen = Screen.Home
            }) { Text("חזרה") }
        }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { update(draft.copy(name = it)) },
            label = { Text("שם") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Choices("סוג", listOf(DraftKind.GROOVE to "גרוב / מילוי", DraftKind.STICKING to "סטיקינג (פד אחד)"), draft.kind) {
            update(draft.copy(kind = it))
        }
        Choices("נקישות בתיבה", (2..7).map { it to "$it" }, draft.beatsPerBar) { update(draft.copy(beatsPerBar = it)) }
        Choices("נקישה =", listOf(4 to "רבע", 8 to "שמינית"), draft.beatUnit) { unit ->
            update(draft.copy(beatUnit = unit, subdivision = if (unit == 8) 1 else maxOf(draft.subdivision, 2)))
        }
        val subdivisions = if (draft.beatUnit == 8) listOf(1 to "שמיניות", 2 to "שש-עשריות")
        else listOf(2 to "שמיניות", 3 to "טריולות", 4 to "שש-עשריות", 6 to "סקסטולות")
        Choices("חלוקה", subdivisions, draft.subdivision) { update(draft.copy(subdivision = it)) }
        if (draft.beatsPerBar >= 5 || draft.beatUnit == 8) {
            OutlinedTextField(
                value = groupsText,
                onValueChange = {
                    groupsText = it
                    update(draft.copy(beatGroups = ExerciseDraft.parseGroups(it)))
                },
                label = { Text("חלוקה לקבוצות (למשל 2+2+3)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (draft.kind == DraftKind.GROOVE) {
            Text("תופים:", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EDITOR_PADS.forEach { pad ->
                    val on = pad in draft.pads
                    val toggle = {
                        val pads = if (on) draft.pads - pad else draft.pads + pad
                        update(draft.copy(pads = EDITOR_PADS.filter { it in pads }))
                    }
                    if (on) Button(onClick = toggle) { Text(pad.he) } else FilledTonalButton(onClick = toggle) { Text(pad.he) }
                }
            }
        }

        // Bars: pick, add, remove, and mark as a fill.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            draft.bars.indices.forEach { i ->
                val label = "תיבה ${i + 1}" + if (draft.bars[i].fill) " · מילוי" else ""
                if (i == bar) Button(onClick = {}) { Text(label) } else FilledTonalButton(onClick = { bar = i }) { Text(label) }
            }
            if (draft.bars.size < MAX_BARS) {
                OutlinedButton(onClick = {
                    // A new bar starts as a copy of the current one: most bars are variations.
                    update(draft.copy(bars = draft.bars + draft.bars[bar].copy(fill = false)))
                    bar = draft.bars.lastIndex
                }) { Text("+ תיבה") }
            }
            if (draft.bars.size > 1) {
                OutlinedButton(onClick = { update(draft.copy(bars = draft.bars.filterIndexed { i, _ -> i != bar })) }) {
                    Text("− תיבה")
                }
            }
        }
        if (draft.kind == DraftKind.GROOVE && draft.bars.size > 1) {
            val fill = draft.bars[bar].fill
            OutlinedButton(onClick = {
                update(draft.copy(bars = draft.bars.toMutableList().also { it[bar] = it[bar].copy(fill = !fill) }))
            }) { Text(if (fill) "תיבה ${bar + 1} היא מילוי ✓" else "סמן את תיבה ${bar + 1} כמילוי") }
        }

        Text("לחיצה על משבצת: מכה → הדגשה → " + if (draft.kind == DraftKind.GROOVE) "מכת רפאים → ריק" else "ריק",
            style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        EditorGrid(draft, bar) { row, step -> update(draft.tap(bar, row, step)) }

        if (draft.subdivision % 2 == 0) {
            val swing = draft.swing
            OutlinedButton(onClick = { update(draft.copy(swing = if (swing == null) 66 else null)) }) {
                Text(if (swing == null) "הוסף סווינג" else "בלי סווינג")
            }
            if (swing != null) {
                Text("סווינג ברירת מחדל: $swing%", style = MaterialTheme.typography.titleMedium)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Slider(value = swing.toFloat(), onValueChange = { update(draft.copy(swing = it.roundToInt())) },
                        valueRange = 50f..75f, steps = 24)
                }
            }
        }

        Text("טמפו התחלתי", style = MaterialTheme.typography.bodyLarge)
        BpmStepper(draft.startBpm, { update(draft.copy(startBpm = it)) })
        Text("טמפו יעד", style = MaterialTheme.typography.bodyLarge)
        BpmStepper(draft.targetBpm, { update(draft.copy(targetBpm = it)) })

        val problems = draft.problems()
        if (showProblems && problems.isNotEmpty()) Banner(problems.joinToString(" · "), Palette.off)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playing == null) {
                FilledTonalButton(onClick = { if (problems.isEmpty()) preview() else showProblems = true }) { Text("🔊 השמע") }
            } else {
                FilledTonalButton(onClick = { playing?.cancel() }) { Text("עצור") }
            }
            Button(onClick = {
                if (problems.isNotEmpty()) {
                    showProblems = true
                } else {
                    app.saveCustom(draft)
                    app.screen = Screen.Practice(draft.id)
                }
            }) { Text("שמור ונגן") }
            if (isSaved) {
                OutlinedButton(onClick = {
                    if (confirmDelete) {
                        app.deleteCustom(draft.id)
                        app.screen = Screen.Home
                    } else {
                        confirmDelete = true
                    }
                }) { Text(if (confirmDelete) "בטוח? מחק" else "מחק") }
            }
        }
        if (playing != null) Text("מנגן…", style = MaterialTheme.typography.bodyMedium, color = Palette.accent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label:", style = MaterialTheme.typography.bodyLarge)
        options.forEach { (value, name) ->
            if (value == selected) Button(onClick = {}) { Text(name) }
            else FilledTonalButton(onClick = { onSelect(value) }) { Text(name) }
        }
    }
}

/** Tappable grid for one bar: a row per drum (or R/L), time left to right. */
@Composable
private fun EditorGrid(draft: ExerciseDraft, bar: Int, onTap: (row: String, step: Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val rows = draft.rowKeys
    val steps = draft.stepsPerBar
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val rowDp = if (rows.size > 5) 44 else 56
    val groupStarts = draft.beatGroups?.runningFold(0) { a, g -> a + g }?.toSet() ?: setOf(0)
    fun label(key: String) = Pad.byId(key)?.short ?: key

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            Modifier.fillMaxWidth().height((rows.size * rowDp).dp).pointerInput(draft.stepsPerBar, rows, bar) {
                detectTapGestures { pos ->
                    val labelW = 40.dp.toPx()
                    if (pos.x < labelW) return@detectTapGestures
                    val step = floor((pos.x - labelW) / ((size.width - labelW) / steps)).toInt().coerceIn(0, steps - 1)
                    val row = floor(pos.y / (size.height.toFloat() / rows.size)).toInt().coerceIn(0, rows.lastIndex)
                    onTap(rows[row], step)
                }
            },
        ) {
            val labelW = 40.dp.toPx()
            val cellW = (size.width - labelW) / steps
            val rowH = size.height / rows.size
            rows.forEachIndexed { r, key ->
                val y = r * rowH
                val t = measurer.ltr(label(key), TextStyle(color = lineColor, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                drawText(t, topLeft = Offset((labelW - t.size.width) / 2, y + (rowH - t.size.height) / 2))
                for (s in 0 until steps) {
                    val v = draft.cell(bar, key, s)
                    val x = labelW + s * cellW
                    val pad = cellW * 0.08f
                    val h = if (v == 'g') rowH * 0.4f else rowH * 0.76f
                    val color = when (v) {
                        'x' -> Palette.accent
                        'X' -> Palette.good
                        'g' -> Palette.muted
                        else -> Palette.pending.copy(alpha = 0.6f)
                    }
                    drawRoundRect(color, Offset(x + pad, y + (rowH - h) / 2), Size(cellW - 2 * pad, h), CornerRadius(6.dp.toPx()))
                    if (v == 'X') {
                        val a = measurer.ltr(">", TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        drawText(a, topLeft = Offset(x + (cellW - a.size.width) / 2, y + (rowH - a.size.height) / 2))
                    }
                }
            }
            for (s in 0..steps) {
                val x = labelW + s * cellW
                val isBeat = s % draft.subdivision == 0
                val isGroup = isBeat && draft.beatGroups != null && (s / draft.subdivision) in groupStarts
                drawLine(lineColor.copy(alpha = if (isBeat) 0.8f else 0.25f), Offset(x, 0f), Offset(x, size.height),
                    strokeWidth = if (isGroup) 6f else if (isBeat) 3f else 1f)
            }
        }
    }
}
