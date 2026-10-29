package com.amihay.drumtrainer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.amihay.drumtrainer.ui.AppState
import com.amihay.drumtrainer.ui.CalibrationScreen
import com.amihay.drumtrainer.ui.DrumTheme
import com.amihay.drumtrainer.ui.GapScreen
import com.amihay.drumtrainer.ui.HistoryScreen
import com.amihay.drumtrainer.ui.HomeScreen
import com.amihay.drumtrainer.ui.KitScreen
import com.amihay.drumtrainer.ui.PracticeScreen
import com.amihay.drumtrainer.ui.Screen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState
    private var hasMic by mutableStateOf(false)

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMic = granted
        if (granted) app.startEngine()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app = AppState(applicationContext)
        // Open an exercise directly, e.g. `adb shell am start ... --es exercise rock_1`.
        intent.getStringExtra("exercise")?.let { id ->
            if (app.exercises.any { it.id == id }) app.screen = Screen.Practice(id)
        }
        hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) askMic.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            DrumTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    // Surface provides the default (light) text colour for the dark theme.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.safeDrawingPadding()) {
                            if (hasMic) AppContent(app) else PermissionNeeded { askMic.launch(Manifest.permission.RECORD_AUDIO) }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasMic) app.startEngine()
    }

    override fun onStop() {
        super.onStop()
        app.stopEngine()
    }
}

@Composable
private fun AppContent(app: AppState) {
    // Recover from audio device changes (e.g. headphones plugged in mid-session).
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            app.engine.restartIfNeeded()
        }
    }
    BackHandler(enabled = app.screen != Screen.Home) { app.screen = Screen.Home }

    when (val s = app.screen) {
        Screen.Home -> HomeScreen(app)
        Screen.Calibration -> CalibrationScreen(app)
        Screen.Gap -> GapScreen(app)
        Screen.History -> HistoryScreen(app)
        Screen.Kit -> KitScreen(app)
        is Screen.Practice -> PracticeScreen(app, app.exercise(s.exerciseId))
    }
}

@Composable
private fun PermissionNeeded(onAsk: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("האפליקציה מקשיבה למכות דרך המיקרופון.", style = MaterialTheme.typography.titleLarge)
        Text("בלי הרשאת מיקרופון אין מה למדוד.", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAsk, modifier = Modifier.padding(top = 16.dp)) { Text("לאשר מיקרופון") }
    }
}
