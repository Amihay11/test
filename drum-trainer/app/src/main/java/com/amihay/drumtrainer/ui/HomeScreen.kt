package com.amihay.drumtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amihay.drumtrainer.data.ProgressEntity
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.ExerciseKind
import com.amihay.drumtrainer.model.PartKind
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

@Composable
fun HomeScreen(app: AppState) {
    val progressFlow = remember { app.dao.allProgress() }
    val progress by progressFlow.collectAsState(initial = emptyList())
    val byId = progress.associateBy { it.exerciseId }
    val recentFlow = remember { app.dao.recentRuns(60) }
    val recentRuns by recentFlow.collectAsState(initial = emptyList())
    val recent = recentRuns.map { it.exerciseId }.distinct()
        .mapNotNull { id -> app.exercises.firstOrNull { it.id == id } }.take(6)
    val touch = app.settings.touchInput

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PagePadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TabHeader(greeting(), "Ready to play?") {
            IconButton(onClick = { app.screen = Screen.Settings }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.muted)
            }
        }

        app.engineError?.let { Banner(it, Palette.bad) }

        Field("Playing on") {
            Segmented(
                listOf(false to "Drums", true to "Screen"),
                touch,
                { app.settings.updateTouchInput(it) },
            )
        }
        when {
            touch && app.settings.touchOffsetMs.isNaN() ->
                Banner("Calibrate the touch screen, or your taps will look late.", Palette.off) { app.screen = Screen.Calibration }
            !touch && !app.settings.isCalibrated ->
                Banner("Not calibrated yet, so every hit will look late. Start here.", Palette.off) { app.screen = Screen.Calibration }
        }

        TodayCard(app)

        if (recent.isNotEmpty()) {
            SectionLabel("Pick up where you left off")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.id }) { ex -> RecentCard(app, ex, byId[app.progressId(ex.id)]) }
            }
        }

        SectionLabel("Tools")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile("Gap click", "Keep time in the silence", AppIcons.Metronome, Palette.off, Modifier.weight(1f)) {
                app.screen = Screen.Gap()
            }
            ToolTile("Drums on screen", "Play and watch patterns", AppIcons.Drum, Palette.good, Modifier.weight(1f)) {
                app.screen = Screen.Drums()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile("New exercise", "Write a groove or a fill", Icons.Filled.Add, Palette.accent, Modifier.weight(1f)) {
                app.screen = Screen.Editor()
            }
            ToolTile("Calibration", "Latency, mic and drums", Icons.Filled.Settings, Palette.wrong, Modifier.weight(1f)) {
                app.screen = Screen.Calibration
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Today's session: what it holds and a big button to start (or go on). */
@Composable
private fun TodayCard(app: AppState) {
    val session = app.activeSession
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.hero).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Today's session", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
        if (session == null) {
            Text("${app.settings.sessionMinutes} minutes · ${PartKind.entries.size} parts",
                style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(PartKind.entries.joinToString(" · ") { it.label }, style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f))
        } else {
            val part = session.plan.parts[session.part]
            Text("Part ${session.part + 1} of ${session.plan.parts.size}: ${part.kind.label}",
                style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Dots(session.part, session.plan.parts.size, Color.White, current = session.part)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { app.screen = Screen.Session },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.accentDeep),
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(if (session == null) "Start session" else "Continue session", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RecentCard(app: AppState, ex: Exercise, p: ProgressEntity?) {
    val bpm = p?.currentBpm ?: ex.startBpm
    Panel(Modifier.width(180.dp), onClick = { app.screen = Screen.Practice(ex.id) }, spacing = 8.dp, padding = 14.dp) {
        IconBadge(kindIcon(ex), kindColor(ex), size = 34.dp)
        Text(ex.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$bpm / ${ex.targetBpm} BPM", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        Meter(tempoProgress(ex, p), color = kindColor(ex))
    }
}

@Composable
private fun ToolTile(title: String, subtitle: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Panel(modifier, onClick = onClick, spacing = 10.dp, padding = 14.dp) {
        IconBadge(icon, color)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How far from the start tempo to the target the player has come, 0..1. */
fun tempoProgress(ex: Exercise, p: ProgressEntity?): Float {
    val best = maxOf(p?.bestPassedBpm ?: 0, p?.currentBpm ?: ex.startBpm)
    val span = (ex.targetBpm - ex.startBpm).coerceAtLeast(1)
    return ((best - ex.startBpm).toFloat() / span).coerceIn(0.03f, 1f)
}

fun kindIcon(ex: Exercise): ImageVector = when (ex.kind) {
    ExerciseKind.RUDIMENT -> AppIcons.Sticks
    ExerciseKind.FILL -> AppIcons.Chart
    else -> AppIcons.Drum
}

fun kindColor(ex: Exercise): Color = when (ex.kind) {
    ExerciseKind.RUDIMENT -> Palette.off
    ExerciseKind.INDEPENDENCE -> Palette.wrong
    ExerciseKind.FILL -> Palette.bad
    else -> Palette.accent
}

/** "Rudiment", or the style and level of a groove or fill. */
fun kindLabel(ex: Exercise): String = when (ex.kind) {
    ExerciseKind.RUDIMENT -> if (ex.id.startsWith("custom_")) "Sticking · my exercise" else "Rudiment"
    ExerciseKind.INDEPENDENCE -> "Independence · level ${ex.level}"
    ExerciseKind.FILL -> "Fill · level ${ex.level}"
    else -> (Category.entries.drop(3).firstOrNull { it.matches(ex) }?.label ?: "Groove") + " · level ${ex.level}"
}

/** Library categories, in the order of the curriculum. */
enum class Category(val label: String, val matches: (Exercise) -> Boolean) {
    ALL("All", { true }),
    MINE("Mine", { it.id.startsWith("custom_") }),
    RUDIMENTS("Rudiments", { it.kind == ExerciseKind.RUDIMENT && !it.id.startsWith("custom_") }),
    INDEPENDENCE("Independence", { it.kind == ExerciseKind.INDEPENDENCE }),
    ROCK("Rock & pop", { it.kind == ExerciseKind.GROOVE && it.style == "rock" }),
    FUNK("Funk & soul", { it.kind == ExerciseKind.GROOVE && it.style == "funk" }),
    JAZZ("Jazz & blues", { it.kind == ExerciseKind.GROOVE && (it.style == "jazz" || it.style == "blues") }),
    LATIN("Latin", { it.kind == ExerciseKind.GROOVE && it.style == "latin" }),
    MIDEAST("Middle Eastern", { it.kind == ExerciseKind.GROOVE && it.style == "mideast" }),
    ELECTRONIC("Electronic & odd meters", { it.kind == ExerciseKind.GROOVE && it.style == "electronic" }),
    FILLS("Fills", { it.kind == ExerciseKind.FILL && !it.id.startsWith("custom_") }),
}

/** A row in the exercise library. */
@Composable
fun ExerciseCard(app: AppState, ex: Exercise, p: ProgressEntity?) {
    val best = p?.bestPassedBpm ?: 0
    val mastered = best >= ex.targetBpm
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.surface)
            .clickable { app.screen = Screen.Practice(ex.id) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(kindIcon(ex), kindColor(ex), size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ex.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (mastered) Pill("Mastered", Palette.good)
            }
            Text(
                kindLabel(ex),
                style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Meter(tempoProgress(ex, p), color = if (mastered) Palette.good else kindColor(ex))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${p?.currentBpm ?: ex.startBpm}", style = MaterialTheme.typography.titleLarge)
            Text("target ${ex.targetBpm}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
    }
}
