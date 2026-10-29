package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.data.SessionEntity
import com.amihay.drumtrainer.model.DAY_MS
import com.amihay.drumtrainer.model.PartKind
import com.amihay.drumtrainer.model.RunRecord
import com.amihay.drumtrainer.model.SessionItem
import com.amihay.drumtrainer.model.SessionPart
import com.amihay.drumtrainer.model.SessionPlan
import com.amihay.drumtrainer.model.SessionPlanner
import com.amihay.drumtrainer.model.SessionReview
import com.amihay.drumtrainer.model.SessionSummary
import com.amihay.drumtrainer.model.isCustom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Builds today's plan from everything played so far. */
private suspend fun planFor(app: AppState, now: Long, runs: List<RunRecord>? = null): SessionPlan {
    val records = runs ?: app.dao.runRecords()
    val current = app.exercises.mapNotNull { ex -> app.dao.progress(ex.id)?.let { ex.id to it.currentBpm } }.toMap()
    val curriculum = app.exercises.filter { !it.isCustom }
    return SessionPlanner.plan(curriculum, records, current, now, app.settings.sessionMinutes)
}

private fun itemName(app: AppState, item: SessionItem): String = when {
    item.gap != null -> "מטרונום עם חורים · ${item.gap.onBars} עם, ${item.gap.offBars} בלי"
    else -> app.exercises.firstOrNull { it.id == item.exerciseId }?.nameHe ?: item.exerciseId.orEmpty()
}

private fun openItem(app: AppState, item: SessionItem, kind: PartKind) {
    app.screen = when {
        item.gap != null -> Screen.Gap(item.gap, item.bpm)
        kind == PartKind.FREE -> Screen.FreePlay(item.exerciseId!!, item.bpm)
        else -> Screen.Practice(item.exerciseId!!, item.bpm)
    }
}

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "$LRM%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<SessionPlan?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val session = app.activeSession

    LaunchedEffect(session, app.settings.sessionMinutes) {
        if (session == null) preview = planFor(app, System.currentTimeMillis())
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("אימון של היום", style = MaterialTheme.typography.headlineMedium)
                if (session != null) {
                    Text("עברו ${mmss(now - session.startedAt)} מתוך ${session.plan.minutes} דקות",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                }
            }
            OutlinedButton(onClick = { app.screen = Screen.Home }) { Text("בית") }
        }

        if (session == null) {
            Text("חמישה חלקים, לפי מה שניגנת עד עכשיו. אפשר לדלג על כל חלק או לשנות אותו.",
                style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("אורך:", style = MaterialTheme.typography.bodyLarge)
                listOf(30, 45, 60).forEach { m ->
                    if (app.settings.sessionMinutes == m) Button(onClick = {}) { Text("$m דק'") }
                    else FilledTonalButton(onClick = { app.settings.updateSessionMinutes(m) }) { Text("$m דק'") }
                }
            }
            val plan = preview
            if (plan == null) {
                CenteredNote("מכין תוכנית…")
            } else {
                plan.parts.forEach { PartCard(app, it, current = false, done = false, played = emptyList(), elapsedMs = null) }
                Button(onClick = {
                    scope.launch {
                        val start = System.currentTimeMillis()
                        val id = app.dao.insertSession(SessionEntity(startedAt = start, plannedMinutes = plan.minutes))
                        app.activeSession = ActiveSession(id, plan, start)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("התחל אימון", style = MaterialTheme.typography.titleLarge) }
            }
            return@Column
        }

        session.plan.parts.forEachIndexed { i, part ->
            PartCard(
                app, part,
                current = i == session.part,
                done = i < session.part,
                played = session.played,
                elapsedMs = if (i == session.part) now - session.partStartedAt else null,
                onSelect = { session.goTo(i, System.currentTimeMillis()) },
            )
        }
        val last = session.part == session.plan.parts.lastIndex
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!last) {
                Button(onClick = { session.goTo(session.part + 1, System.currentTimeMillis()) }) {
                    Text("לחלק הבא: ${session.plan.parts[session.part + 1].kind.he}")
                }
            }
            val finish: () -> Unit = {
                scope.launch {
                    app.dao.endSession(session.id, System.currentTimeMillis())
                    app.activeSession = null
                    app.screen = Screen.SessionSummary(session.id)
                }
            }
            if (last) Button(onClick = finish) { Text("סיים אימון") } else OutlinedButton(onClick = finish) { Text("סיים עכשיו") }
        }
    }
}

@Composable
private fun PartCard(
    app: AppState,
    part: SessionPart,
    current: Boolean,
    done: Boolean,
    played: List<String>,
    elapsedMs: Long?,
    onSelect: (() -> Unit)? = null,
) {
    val border = if (current) Modifier.border(2.dp, Palette.accent, RoundedCornerShape(12.dp)) else Modifier
    val base = Modifier.fillMaxWidth().then(border)
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
    Column(
        (if (onSelect != null && !current) base.clickable(onClick = onSelect) else base).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (done) "✓ " else "") + part.kind.he, style = MaterialTheme.typography.titleLarge,
                color = if (done) Palette.good else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            val over = elapsedMs != null && elapsedMs > part.minutes * 60_000L
            Text(
                if (elapsedMs != null) "${mmss(elapsedMs)} / ${part.minutes} דק'" else "${part.minutes} דק'",
                style = MaterialTheme.typography.titleMedium,
                color = if (over) Palette.off else Palette.muted,
            )
        }
        if (!current && onSelect != null) return@Column
        part.items.forEach { item ->
            val isPlayed = itemKey(item, part.kind) in played
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text((if (isPlayed) "✓ " else "") + itemName(app, item), style = MaterialTheme.typography.titleMedium,
                        color = if (isPlayed) Palette.good else MaterialTheme.colorScheme.onSurface)
                    Text("${item.bpm} BPM · ${item.reason}", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                }
                if (current) {
                    if (isPlayed) OutlinedButton(onClick = { openItem(app, item, part.kind) }) { Text("שוב") }
                    else Button(onClick = { openItem(app, item, part.kind) }) { Text("נגן") }
                }
            }
        }
    }
}

@Composable
fun SessionSummaryScreen(app: AppState, sessionId: Long) {
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var next by remember { mutableStateOf<SessionPlan?>(null) }
    LaunchedEffect(sessionId) {
        val runs = app.dao.runRecords()
        summary = SessionReview.summarize(app.exercises, runs, sessionId)
        next = planFor(app, System.currentTimeMillis() + DAY_MS, runs)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("סיכום אימון", style = MaterialTheme.typography.headlineMedium)
        val s = summary
        if (s == null) {
            CenteredNote("מסכם…")
            return@Column
        }
        if (s.mastered.isNotEmpty()) {
            SummarySection("נשלט", s.mastered.map { "$it – עבר את הסף בטמפו היעד בשני אימונים" }, Palette.good)
        }
        SummarySection("מה השתפר", s.improved.ifEmpty { listOf("אין שיא חדש הפעם – וזה בסדר, הדיוק נבנה לאט.") }, Palette.good)
        if (s.stuck.isNotEmpty()) {
            SummarySection("מה נתקע", s.stuck + "כדאי להוריד 4–8 BPM בפעם הבאה ולבנות מחדש.", Palette.off)
        }
        next?.let { plan ->
            SummarySection(
                "מה יחכה באימון הבא",
                plan.parts.flatMap { part -> part.items.map { "${part.kind.he}: ${itemName(app, it)} · ${it.bpm} BPM" } },
                Palette.accent,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.screen = Screen.Home }) { Text("סיום") }
            FilledTonalButton(onClick = { app.screen = Screen.Progress }) { Text("גרף התקדמות") }
        }
    }
}

@Composable
private fun SummarySection(title: String, lines: List<String>, color: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = color)
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
    }
}
