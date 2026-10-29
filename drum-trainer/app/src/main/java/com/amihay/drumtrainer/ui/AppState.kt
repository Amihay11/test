package com.amihay.drumtrainer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.amihay.drumtrainer.audio.AudioEngine
import com.amihay.drumtrainer.data.AppDatabase
import com.amihay.drumtrainer.data.PadStore
import com.amihay.drumtrainer.data.Settings
import com.amihay.drumtrainer.model.DrumClassifier
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.ExerciseParser
import com.amihay.drumtrainer.model.GapSetup
import com.amihay.drumtrainer.model.PartKind
import com.amihay.drumtrainer.model.SessionItem
import com.amihay.drumtrainer.model.SessionPlan
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.TrainingSample
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object Calibration : Screen
    /** Gap metronome, optionally preset by a training session. */
    data class Gap(val setup: GapSetup? = null, val bpm: Int? = null) : Screen
    data object History : Screen
    data object Kit : Screen
    /** An exercise; [bpm] overrides the saved tempo (e.g. 90 % for a review). */
    data class Practice(val exerciseId: String, val bpm: Int? = null) : Screen
    data object Session : Screen
    data class SessionSummary(val sessionId: Long) : Screen
    data object Progress : Screen
    data class FreePlay(val exerciseId: String, val bpm: Int) : Screen
}

/** A training session in progress: its plan, the current part, and what was played. */
class ActiveSession(val id: Long, val plan: SessionPlan, val startedAt: Long) {
    var part by mutableIntStateOf(0)
        private set
    var partStartedAt by mutableLongStateOf(startedAt)
        private set
    /** Keys of played items: exercise id, gap id, or "free:<id>". */
    val played = mutableStateListOf<String>()

    fun goTo(index: Int, now: Long) {
        part = index.coerceIn(0, plan.parts.lastIndex)
        partStartedAt = now
    }

    fun markPlayed(key: String) {
        if (key !in played) played += key
    }
}

fun itemKey(item: SessionItem, kind: PartKind): String = when {
    item.gap != null -> item.gap.id
    kind == PartKind.FREE -> "free:${item.exerciseId}"
    else -> item.exerciseId!!
}

/** Everything the screens share. Lives as long as the activity. */
class AppState(context: Context) {
    val engine = AudioEngine()
    val settings = Settings(context)
    val dao = AppDatabase.get(context).dao()
    val exercises: List<Exercise> = listOf("rudiments", "grooves", "styles").flatMap { name ->
        ExerciseParser.parse(context.assets.open("exercises/$name.json").bufferedReader().use { it.readText() })
    }

    private val padStore = PadStore(File(context.filesDir, "pads.json"))

    /** Hits recorded in the pad calibration, and the classifier trained on them. */
    var padSamples by mutableStateOf(padStore.load())
        private set
    var classifier by mutableStateOf(DrumClassifier(padSamples))
        private set

    fun replacePadSamples(pad: Pad, samples: List<TrainingSample>) {
        padSamples = padSamples.filter { it.pad != pad } + samples
        padStore.save(padSamples)
        classifier = DrumClassifier(padSamples)
    }

    var screen by mutableStateOf<Screen>(Screen.Home)
    var activeSession by mutableStateOf<ActiveSession?>(null)

    /** Where "back" leads from an exercise: the session in progress, or home. */
    val home: Screen get() = if (activeSession != null) Screen.Session else Screen.Home
    var engineError by mutableStateOf<String?>(null)

    fun exercise(id: String) = exercises.first { it.id == id }

    fun startEngine() {
        if (engine.isRunning) return
        engineError = if (engine.start()) null else "לא הצלחתי לפתוח את המיקרופון או הרמקול"
        engine.setSensitivity(settings.sensitivity)
        engine.setNotch(true)
    }

    fun stopEngine() = engine.stop()
}
