package com.amihay.drumtrainer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.amihay.drumtrainer.data.SAMPLE_CREDIT

@Composable
fun SettingsScreen(app: AppState) {
    val s = app.settings
    Page("Settings", onBack = { app.screen = Screen.Home }) {
        Section("Playing") {
            ListRow(
                "Practice without drums",
                "Tap the screen instead of playing: two hand pads, or a drawn kit",
                AppIcons.Tap,
                onClick = { s.updateTouchInput(!s.touchInput) },
            ) { Switch(checked = s.touchInput, onCheckedChange = { s.updateTouchInput(it) }) }
            Field("Bars per round") {
                Segmented(listOf(2, 4, 8, 16).map { it to "$it" }, s.barsPerRound, { s.updateBarsPerRound(it) })
            }
            Field("Daily session length") {
                Segmented(listOf(30, 45, 60).map { it to "$it min" }, s.sessionMinutes, { s.updateSessionMinutes(it) })
            }
        }
        Section("Calibration and detection") {
            ListRow(
                "Calibration",
                when {
                    s.isCalibrated -> "Latency: ${fmt1(s.padOffsetMs)}ms"
                    else -> "Not calibrated yet"
                } + if (!s.touchOffsetMs.isNaN()) " · screen: ${fmt1(s.touchOffsetMs)}ms" else "",
                Icons.Filled.Settings,
                onClick = { app.screen = Screen.Calibration },
            )
            ListRow(
                "Learn your drums",
                "${app.classifier.pads.size} of 8 drums learned",
                AppIcons.Drum,
                onClick = { app.screen = Screen.Kit },
            )
        }
        Section("Credits") {
            ListRow("Sounds", SAMPLE_CREDIT, Icons.Filled.Info)
            ListRow("Font", "Rubik, under the SIL Open Font License 1.1", Icons.Filled.Info)
        }
        Text("Drum Trainer · measure, don't lecture", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
    }
}
