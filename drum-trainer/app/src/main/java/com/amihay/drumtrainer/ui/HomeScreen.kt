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
    in 5..11 -> "בוקר טוב"
    in 12..16 -> "צהריים טובים"
    in 17..21 -> "ערב טוב"
    else -> "לילה טוב"
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
        TabHeader(greeting(), "מוכן לנגן?") {
            IconButton(onClick = { app.screen = Screen.Settings }) {
                Icon(Icons.Filled.Settings, contentDescription = "הגדרות", tint = Palette.muted)
            }
        }

        app.engineError?.let { Banner(it, Palette.bad) }

        Field("מנגן על") {
            Segmented(
                listOf(false to "התופים", true to "המסך"),
                touch,
                { app.settings.updateTouchInput(it) },
            )
        }
        when {
            touch && app.settings.touchOffsetMs.isNaN() ->
                Banner("כדאי לכייל את מסך המגע – בלי כיול ההקשות ייראו מאוחרות.", Palette.off) { app.screen = Screen.Calibration }
            !touch && !app.settings.isCalibrated ->
                Banner("עוד לא כיילת – בלי כיול כל המכות ייראו מאוחרות. מתחילים כאן.", Palette.off) { app.screen = Screen.Calibration }
        }

        TodayCard(app)

        if (recent.isNotEmpty()) {
            SectionLabel("להמשיך מאיפה שעצרת")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.id }) { ex -> RecentCard(app, ex, byId[app.progressId(ex.id)]) }
            }
        }

        SectionLabel("כלים")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile("מטרונום עם חורים", "לשמור על הקצב בשקט", AppIcons.Metronome, Palette.off, Modifier.weight(1f)) {
                app.screen = Screen.Gap()
            }
            ToolTile("תופים על המסך", "לנגן ולראות קצבים", AppIcons.Drum, Palette.good, Modifier.weight(1f)) {
                app.screen = Screen.Drums()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile("תרגיל חדש", "לכתוב גרוב או מילוי", Icons.Filled.Add, Palette.accent, Modifier.weight(1f)) {
                app.screen = Screen.Editor()
            }
            ToolTile("כיול", "השהיה, מיקרופון ותופים", Icons.Filled.Settings, Palette.wrong, Modifier.weight(1f)) {
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
        Text("אימון של היום", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
        if (session == null) {
            Text("${app.settings.sessionMinutes} דקות · ${PartKind.entries.size} חלקים",
                style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(PartKind.entries.joinToString(" · ") { it.he }, style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f))
        } else {
            val part = session.plan.parts[session.part]
            Text("חלק ${session.part + 1} מתוך ${session.plan.parts.size}: ${part.kind.he}",
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
            Text(if (session == null) "התחל אימון" else "המשך אימון", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RecentCard(app: AppState, ex: Exercise, p: ProgressEntity?) {
    val bpm = p?.currentBpm ?: ex.startBpm
    Panel(Modifier.width(180.dp), onClick = { app.screen = Screen.Practice(ex.id) }, spacing = 8.dp, padding = 14.dp) {
        IconBadge(kindIcon(ex), kindColor(ex), size = 34.dp)
        Text(ex.nameHe, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$LRI$bpm / ${ex.targetBpm} BPM$PDI", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
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

/** Library categories, in the order of the curriculum. */
enum class Category(val he: String, val matches: (Exercise) -> Boolean) {
    ALL("הכל", { true }),
    MINE("שלי", { it.id.startsWith("custom_") }),
    RUDIMENTS("רודימנטים", { it.kind == ExerciseKind.RUDIMENT && !it.id.startsWith("custom_") }),
    INDEPENDENCE("עצמאות", { it.kind == ExerciseKind.INDEPENDENCE }),
    ROCK("רוק ופופ", { it.kind == ExerciseKind.GROOVE && it.style == "rock" }),
    FUNK("פאנק וסול", { it.kind == ExerciseKind.GROOVE && it.style == "funk" }),
    JAZZ("ג'אז ובלוז", { it.kind == ExerciseKind.GROOVE && (it.style == "jazz" || it.style == "blues") }),
    LATIN("לטיני", { it.kind == ExerciseKind.GROOVE && it.style == "latin" }),
    MIDEAST("מזרחי", { it.kind == ExerciseKind.GROOVE && it.style == "mideast" }),
    ELECTRONIC("אלקטרוני ומשקלים", { it.kind == ExerciseKind.GROOVE && it.style == "electronic" }),
    FILLS("מילויים", { it.kind == ExerciseKind.FILL && !it.id.startsWith("custom_") }),
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
                Text(ex.nameHe, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (mastered) Pill("נשלט", Palette.good)
            }
            Text(
                ex.name + if (ex.kind != ExerciseKind.RUDIMENT) " · רמה ${ex.level}" else "",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Meter(tempoProgress(ex, p), color = if (mastered) Palette.good else kindColor(ex))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${p?.currentBpm ?: ex.startBpm}", style = MaterialTheme.typography.titleLarge)
            Text("יעד ${ex.targetBpm}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
    }
}
