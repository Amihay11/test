package com.amihay.drumtrainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.amihay.drumtrainer.audio.AudioEngine
import com.amihay.drumtrainer.model.Click
import com.amihay.drumtrainer.model.Onset

/**
 * Plays a click schedule and collects the hits detected while it runs, updating
 * Compose state once per display frame.
 */
class RoundRunner(private val engine: AudioEngine) {
    var playhead by mutableDoubleStateOf(0.0)
        private set
    var running by mutableStateOf(false)
        private set
    val onsets = mutableStateListOf<Onset>()

    /**
     * Runs until the playhead passes [endFrame] plus a tail that leaves time for the
     * last hits to be detected. [offsetMs] is the calibrated latency.
     */
    suspend fun run(clicks: List<Click>, endFrame: Double, offsetMs: Double, tailMs: Int = 400) {
        onsets.clear()
        engine.pollOnsets(offsetMs)  // discard anything detected before the round
        engine.setClicks(clicks)
        running = true
        val stopAt = endFrame + engine.sampleRate * tailMs / 1000.0
        try {
            while (true) {
                withFrameNanos { }
                val fresh = engine.pollOnsets(offsetMs)
                if (fresh.isNotEmpty()) onsets.addAll(fresh)
                playhead = engine.playheadFrame().toDouble()
                if (playhead > stopAt) break
            }
        } finally {
            engine.clearClicks()
            running = false
        }
    }
}
