package com.amihay.drumtrainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.amihay.drumtrainer.audio.AudioEngine
import com.amihay.drumtrainer.model.Click
import com.amihay.drumtrainer.model.DrumClassifier
import com.amihay.drumtrainer.model.Onset
import com.amihay.drumtrainer.model.Pad

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

    /** Taps on the screen replace the microphone (practice without drums). */
    var touchInput = false
    private var offsetFrames = 0.0

    /**
     * A tap at [uptimeMs] on [pad] or with [hand]. Counted only while a round runs;
     * the sound is played by the caller either way.
     */
    fun tap(uptimeMs: Long, pad: Pad? = null, hand: Char? = null) {
        if (!running || !touchInput) return
        onsets += Onset(engine.frameAtUptime(uptimeMs) - offsetFrames, 1f, pads = pad?.let { listOf(it) }, hand = hand)
    }

    /**
     * Runs until the playhead passes [endFrame] plus a tail that leaves time for the
     * last hits to be detected. [offsetMs] is the calibrated latency. With a [classifier],
     * each hit is also tagged with the drums it was recognised as.
     */
    suspend fun run(
        clicks: List<Click>, endFrame: Double, offsetMs: Double, tailMs: Int = 400,
        classifier: DrumClassifier? = null,
    ) {
        onsets.clear()
        offsetFrames = offsetMs * engine.sampleRate / 1000.0
        engine.pollOnsets(offsetMs)  // discard anything detected before the round
        engine.setClicks(clicks)
        running = true
        val stopAt = endFrame + engine.sampleRate * tailMs / 1000.0
        try {
            while (true) {
                withFrameNanos { }
                val heard = engine.pollOnsets(offsetMs)
                val fresh = if (touchInput) emptyList() else heard.map { o ->
                    if (classifier == null || o.features == null) o
                    else o.copy(pads = classifier.classify(o.features).pads)
                }
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
