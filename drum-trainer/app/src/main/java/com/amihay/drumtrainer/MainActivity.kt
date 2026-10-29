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
import com.amihay.drumtrainer.ui.DrumsScreen
import com.amihay.drumtrainer.ui.EditorScreen
import com.amihay.drumtrainer.ui.FreePlayScreen
import com.amihay.drumtrainer.ui.ProgressScreen
import com.amihay.drumtrainer.ui.SessionScreen
import com.amihay.drumtrainer.ui.SessionSummaryScreen
import com.amihay.drumtrainer.ui.GapScreen
import com.amihay.drumtrainer.ui.LibraryScreen
import com.amihay.drumtrainer.ui.SettingsScreen
import com.amihay.drumtrainer.ui.AppIcons
import com.amihay.drumtrainer.ui.Palette
import com.amihay.drumtrainer.ui.PagePadding
import com.amihay.drumtrainer.ui.IconBadge
import com.amihay.drumtrainer.ui.PrimaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.vector.ImageVector
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
        // Open an exercise or the editor directly, e.g.
        // `adb shell am start ... --es exercise rock_1` or `--ez editor true`.
        intent.getStringExtra("exercise")?.let { id ->
            if (app.exercises.any { it.id == id }) app.screen = Screen.Practice(id)
        }
        if (intent.getBooleanExtra("editor", false)) app.screen = Screen.Editor()
        when (intent.getStringExtra("screen")) {
            "library" -> app.screen = Screen.Library
            "progress" -> app.screen = Screen.Progress
            "settings" -> app.screen = Screen.Settings
            "session" -> app.screen = Screen.Session
            "calibration" -> app.screen = Screen.Calibration
            "gap" -> app.screen = Screen.Gap()
        }
        intent.getStringExtra("drums")?.let { id -> app.screen = Screen.Drums(id.takeIf { app.exercises.any { e -> e.id == it } }) }
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

/** The four main places, reached from the bar at the bottom. */
private enum class Tab(val label: String, val icon: ImageVector, val screen: Screen) {
    HOME("בית", Icons.Filled.Home, Screen.Home),
    LIBRARY("תרגילים", Icons.AutoMirrored.Filled.List, Screen.Library),
    DRUMS("מערכת", AppIcons.Drum, Screen.Drums()),
    PROGRESS("התקדמות", AppIcons.Chart, Screen.Progress),
}

private fun tabOf(screen: Screen): Tab? = when (screen) {
    Screen.Home -> Tab.HOME
    Screen.Library -> Tab.LIBRARY
    is Screen.Drums -> if (screen.back == Screen.Home) Tab.DRUMS else null
    Screen.Progress -> Tab.PROGRESS
    else -> null
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
    BackHandler(enabled = app.screen != Screen.Home) {
        app.screen = when (val s = app.screen) {
            is Screen.Practice, is Screen.Gap, is Screen.FreePlay -> app.home
            is Screen.Drums -> s.back
            Screen.Calibration, Screen.Kit -> Screen.Settings
            else -> Screen.Home
        }
    }

    val tab = tabOf(app.screen)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (val s = app.screen) {
                Screen.Home -> HomeScreen(app)
                Screen.Library -> LibraryScreen(app)
                Screen.Settings -> SettingsScreen(app)
                Screen.Calibration -> CalibrationScreen(app)
                is Screen.Gap -> GapScreen(app, s.setup, s.bpm)
                Screen.Kit -> KitScreen(app)
                is Screen.Practice -> PracticeScreen(app, app.exercise(s.exerciseId), s.bpm)
                Screen.Session -> SessionScreen(app)
                is Screen.SessionSummary -> SessionSummaryScreen(app, s.sessionId)
                Screen.Progress -> ProgressScreen(app)
                is Screen.FreePlay -> FreePlayScreen(app, app.exercise(s.exerciseId), s.bpm)
                is Screen.Editor -> EditorScreen(app, s.exerciseId)
                is Screen.Drums -> DrumsScreen(app, s.exerciseId, s.back)
            }
        }
        if (tab != null) {
            NavigationBar(containerColor = Palette.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { if (t != tab) app.screen = t.screen },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.accent,
                            selectedTextColor = Palette.accent,
                            indicatorColor = Palette.accentSoft,
                            unselectedIconColor = Palette.muted,
                            unselectedTextColor = Palette.muted,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionNeeded(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(AppIcons.Drum, size = 72.dp)
        Text("האפליקציה מקשיבה למכות", style = MaterialTheme.typography.headlineMedium)
        Text("דרך המיקרופון היא מודדת כל מכה. בלי הרשאת מיקרופון אין מה למדוד.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.muted, textAlign = TextAlign.Center)
        PrimaryButton("לאשר מיקרופון", onAsk)
    }
}
