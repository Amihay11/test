package com.amihay.drumtrainer.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Small persisted settings, exposed as Compose state. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Onset-detection sensitivity, 0..1. */
    var sensitivity by mutableStateOf(prefs.getFloat("sensitivity", 0.6f))
        private set

    /** Phone speaker → microphone delay measured by the loopback step (ms). */
    var loopbackMs by mutableStateOf(prefs.getFloat("loopbackMs", Float.NaN).toDouble())
        private set

    /**
     * Total offset subtracted from every hit (ms): playing along with the click, the
     * median delay between click and detected pad sound.
     */
    var padOffsetMs by mutableStateOf(prefs.getFloat("padOffsetMs", Float.NaN).toDouble())
        private set

    var barsPerRound by mutableStateOf(prefs.getInt("barsPerRound", 4))
        private set

    val isCalibrated: Boolean get() = !padOffsetMs.isNaN()

    /** Offset to use when the pad step has not been run yet. */
    val effectiveOffsetMs: Double
        get() = when {
            !padOffsetMs.isNaN() -> padOffsetMs
            !loopbackMs.isNaN() -> loopbackMs
            else -> 0.0
        }

    fun updateSensitivity(v: Float) {
        sensitivity = v
        prefs.edit().putFloat("sensitivity", v).apply()
    }

    fun updateLoopback(ms: Double) {
        loopbackMs = ms
        prefs.edit().putFloat("loopbackMs", ms.toFloat()).apply()
    }

    fun updatePadOffset(ms: Double) {
        padOffsetMs = ms
        prefs.edit().putFloat("padOffsetMs", ms.toFloat()).apply()
    }

    fun updateBarsPerRound(bars: Int) {
        barsPerRound = bars
        prefs.edit().putInt("barsPerRound", bars).apply()
    }
}
