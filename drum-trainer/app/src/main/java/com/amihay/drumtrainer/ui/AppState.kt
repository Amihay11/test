package com.amihay.drumtrainer.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.amihay.drumtrainer.audio.AudioEngine
import com.amihay.drumtrainer.data.AppDatabase
import com.amihay.drumtrainer.data.Settings
import com.amihay.drumtrainer.model.Exercise
import com.amihay.drumtrainer.model.ExerciseParser

sealed interface Screen {
    data object Home : Screen
    data object Calibration : Screen
    data object Gap : Screen
    data object History : Screen
    data class Practice(val exerciseId: String) : Screen
}

/** Everything the screens share. Lives as long as the activity. */
class AppState(context: Context) {
    val engine = AudioEngine()
    val settings = Settings(context)
    val dao = AppDatabase.get(context).dao()
    val exercises: List<Exercise> = ExerciseParser.parse(
        context.assets.open("exercises/rudiments.json").bufferedReader().use { it.readText() },
    )

    var screen by mutableStateOf<Screen>(Screen.Home)
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
