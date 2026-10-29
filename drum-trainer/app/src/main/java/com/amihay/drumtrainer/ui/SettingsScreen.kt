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
    Page("הגדרות", onBack = { app.screen = Screen.Home }) {
        Section("נגינה") {
            ListRow(
                "אימון בלי תופים",
                "מקישים על המסך במקום לנגן – בשתי ידיים, או על מערכת מצוירת",
                AppIcons.Tap,
                onClick = { s.updateTouchInput(!s.touchInput) },
            ) { Switch(checked = s.touchInput, onCheckedChange = { s.updateTouchInput(it) }) }
            Field("תיבות בכל סבב") {
                Segmented(listOf(2, 4, 8, 16).map { it to "$it" }, s.barsPerRound, { s.updateBarsPerRound(it) })
            }
            Field("אורך אימון יומי") {
                Segmented(listOf(30, 45, 60).map { it to "$it דק'" }, s.sessionMinutes, { s.updateSessionMinutes(it) })
            }
        }
        Section("כיול וזיהוי") {
            ListRow(
                "כיול",
                when {
                    s.isCalibrated -> "השהיה: ${fmt1(s.padOffsetMs)}ms"
                    else -> "עוד לא כויל"
                } + if (!s.touchOffsetMs.isNaN()) " · מסך: ${fmt1(s.touchOffsetMs)}ms" else "",
                Icons.Filled.Settings,
                onClick = { app.screen = Screen.Calibration },
            )
            ListRow(
                "לימוד התופים שלך",
                "${app.classifier.pads.size} מתוך 8 תופים נלמדו",
                AppIcons.Drum,
                onClick = { app.screen = Screen.Kit },
            )
        }
        Section("קרדיטים") {
            ListRow("צלילים", SAMPLE_CREDIT, Icons.Filled.Info)
            ListRow("גופן", "Rubik, ברישיון SIL Open Font License 1.1", Icons.Filled.Info)
        }
        Text("מאמן תופים · למדוד במקום ללמד", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
    }
}
