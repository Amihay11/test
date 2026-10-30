package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
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
    val current = app.exercises.mapNotNull { ex -> app.dao.progress(app.progressId(ex.id))?.let { ex.id to it.currentBpm } }.toMap()
    val curriculum = app.exercises.filter { !it.isCustom }
    return SessionPlanner.plan(curriculum, records, current, now, app.settings.sessionMinutes)
}

private fun itemName(app: AppState, item: SessionItem): String = when {
    item.gap != null -> "Gap click · ${item.gap.onBars} on, ${item.gap.offBars} off"
    else -> app.exercises.firstOrNull { it.id == item.exerciseId }?.name ?: item.exerciseId.orEmpty()
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
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun SessionScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<SessionPlan?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val session = app.activeSession

    LaunchedEffect(session, app.settings.sessionMinutes, app.settings.touchInput) {
        if (session == null) preview = planFor(app, System.currentTimeMillis())
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val finish: () -> Unit = {
        session?.let { s ->
            scope.launch {
                app.dao.endSession(s.id, System.currentTimeMillis())
                app.activeSession = null
                app.screen = Screen.SessionSummary(s.id)
            }
        }
    }

    Page(
        "Today's session",
        onBack = { app.screen = Screen.Home },
        subtitle = session?.let { "${mmss(now - it.startedAt)} of ${it.plan.minutes} minutes" },
        bottomBar = {
            if (session == null) {
                val plan = preview
                PrimaryButton("Start session", {
                    if (plan != null) scope.launch {
                        val start = System.currentTimeMillis()
                        val id = app.dao.insertSession(SessionEntity(startedAt = start, plannedMinutes = plan.minutes))
                        app.activeSession = ActiveSession(id, plan, start)
                    }
                }, icon = Icons.Filled.PlayArrow, enabled = plan != null)
            } else {
                val last = session.part == session.plan.parts.lastIndex
                if (last) {
                    PrimaryButton("Finish session", finish, icon = Icons.Filled.Check)
                } else {
                    PrimaryButton("Next: ${session.plan.parts[session.part + 1].kind.label}",
                        { session.goTo(session.part + 1, System.currentTimeMillis()) })
                    TextButton(onClick = finish, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Finish now", color = Palette.muted)
                    }
                }
            }
        },
    ) {
        if (session == null) {
            Text("Five parts, based on what you have played so far. Skip a part or play something else whenever you like.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            Field("Length") {
                Segmented(listOf(30, 45, 60).map { it to "$it min" }, app.settings.sessionMinutes, { app.settings.updateSessionMinutes(it) })
            }
            val plan = preview
            if (plan == null) {
                CenteredNote("Planning…")
            } else {
                plan.parts.forEachIndexed { i, part ->
                    PartCard(app, i, part, current = false, done = false, played = emptyList(), elapsedMs = null, expanded = true)
                }
            }
            return@Page
        }

        Dots(session.part, session.plan.parts.size, current = session.part)
        session.plan.parts.forEachIndexed { i, part ->
            PartCard(
                app, i, part,
                current = i == session.part,
                done = i < session.part,
                played = session.played,
                elapsedMs = if (i == session.part) now - session.partStartedAt else null,
                expanded = i == session.part,
                onSelect = { session.goTo(i, System.currentTimeMillis()) },
            )
        }
    }
}

@Composable
private fun PartCard(
    app: AppState,
    index: Int,
    part: SessionPart,
    current: Boolean,
    done: Boolean,
    played: List<String>,
    elapsedMs: Long?,
    expanded: Boolean,
    onSelect: (() -> Unit)? = null,
) {
    Panel(
        Modifier.then(if (current) Modifier.border(2.dp, Palette.accent, CardShape) else Modifier),
        onClick = if (onSelect != null && !current) onSelect else null,
        color = if (current) Palette.accentSoft else Palette.surface,
        spacing = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(32.dp).background(
                    when {
                        done -> Palette.good
                        current -> Palette.accent
                        else -> Palette.surfaceHigh
                    },
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.background, modifier = Modifier.size(18.dp))
                else Text("${index + 1}", style = MaterialTheme.typography.labelLarge,
                    color = if (current) Palette.background else Palette.text)
            }
            Text(part.kind.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                color = if (done) Palette.muted else Palette.text)
            val over = elapsedMs != null && elapsedMs > part.minutes * 60_000L
            Text(
                if (elapsedMs != null) "${mmss(elapsedMs)} / ${part.minutes} min" else "${part.minutes} min",
                style = MaterialTheme.typography.labelLarge,
                color = if (over) Palette.off else Palette.muted,
            )
        }
        if (!expanded) return@Panel
        part.items.forEach { item ->
            val isPlayed = itemKey(item, part.kind) in played
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.background.copy(alpha = 0.5f))
                    .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(itemName(app, item), style = MaterialTheme.typography.titleSmall,
                        color = if (isPlayed) Palette.good else Palette.text)
                    Text("${item.bpm} BPM · ${item.reason}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                if (current) {
                    if (isPlayed) {
                        TextButton(onClick = { openItem(app, item, part.kind) }) { Text("Again") }
                    } else {
                        FilledIconButton(onClick = { openItem(app, item, part.kind) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                        }
                    }
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

    Page(
        "Session summary",
        onBack = { app.screen = Screen.Home },
        bottomBar = {
            PrimaryButton("Done", { app.screen = Screen.Home }, icon = Icons.Filled.Check)
            TextButton(onClick = { app.screen = Screen.Progress }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("See progress")
            }
        },
    ) {
        val s = summary
        if (s == null) {
            CenteredNote("Summing up…")
            return@Page
        }
        if (s.mastered.isNotEmpty()) {
            SummarySection("Mastered", s.mastered.map { "$it: passed at the target tempo in two sessions" }, Palette.good)
        }
        SummarySection("What improved", s.improved.ifEmpty { listOf("No new best this time. That's fine: accuracy builds slowly.") }, Palette.good)
        if (s.stuck.isNotEmpty()) {
            SummarySection("What's stuck", s.stuck + "Drop 4–8 BPM next time and build it back up.", Palette.off)
        }
        next?.let { plan ->
            SummarySection(
                "Coming up next session",
                plan.parts.flatMap { part -> part.items.map { "${part.kind.label}: ${itemName(app, it)} · ${it.bpm} BPM" } },
                Palette.accent,
            )
        }
    }
}

@Composable
private fun SummarySection(title: String, lines: List<String>, color: androidx.compose.ui.graphics.Color) {
    Panel(color = color.copy(alpha = 0.08f), spacing = 8.dp) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = color)
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}
