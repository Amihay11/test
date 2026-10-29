package com.amihay.drumtrainer.audio

import com.amihay.drumtrainer.model.Click
import com.amihay.drumtrainer.model.Onset

/** JNI bindings to the C++ engine (see app/src/main/cpp). */
object NativeEngine {
    init {
        System.loadLibrary("drumengine")
    }

    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeIsRunning(): Boolean
    external fun nativeNeedsRestart(): Boolean
    external fun nativeSampleRate(): Int
    external fun nativePlayheadFrame(): Long
    external fun nativeWriteFrame(): Long
    external fun nativeSetClicks(frames: LongArray, types: IntArray)
    external fun nativeClearClicks()
    external fun nativeSetSensitivity(s: Float)
    external fun nativeSetNotch(enabled: Boolean)
    external fun nativePollOnsets(out: DoubleArray): Int
    external fun nativeInputPeak(): Float
    external fun nativeTimestampsAligned(): Boolean
    external fun nativeXruns(): Int
}

/** Kotlin-friendly facade over [NativeEngine]. */
class AudioEngine {
    private val pollBuffer = DoubleArray(512)

    val isRunning: Boolean get() = NativeEngine.nativeIsRunning()
    val sampleRate: Int get() = NativeEngine.nativeSampleRate()
    val timestampsAligned: Boolean get() = NativeEngine.nativeTimestampsAligned()

    fun start(): Boolean = NativeEngine.nativeStart()
    fun stop() = NativeEngine.nativeStop()

    /** Restarts the streams after a device change (e.g. headphones plugged in). */
    fun restartIfNeeded(): Boolean {
        if (!NativeEngine.nativeNeedsRestart()) return false
        stop()
        return start()
    }

    /** Output frame that is audible right now. */
    fun playheadFrame(): Long = NativeEngine.nativePlayheadFrame()

    /** A start frame far enough ahead that the first click is not rendered late. */
    fun scheduleStartFrame(leadMs: Int = 400): Long =
        NativeEngine.nativeWriteFrame() + sampleRate.toLong() * leadMs / 1000

    fun setClicks(clicks: List<Click>) {
        val sorted = clicks.sortedBy { it.frame }
        NativeEngine.nativeSetClicks(LongArray(sorted.size) { sorted[it].frame }, IntArray(sorted.size) { sorted[it].type })
    }

    fun clearClicks() = NativeEngine.nativeClearClicks()

    fun setSensitivity(s: Float) = NativeEngine.nativeSetSensitivity(s)

    /** Notch out the click tones (off only for the broadband loopback calibration). */
    fun setNotch(enabled: Boolean) = NativeEngine.nativeSetNotch(enabled)

    /** Peak input level since the last call, 0..1. */
    fun inputPeak(): Float = NativeEngine.nativeInputPeak()

    /**
     * Drains detected hits. Each is shifted by [offsetMs] (the calibrated latency), so
     * the returned frame is when the hit actually happened on the click's timeline.
     */
    fun pollOnsets(offsetMs: Double): List<Onset> {
        val out = ArrayList<Onset>()
        val offsetFrames = offsetMs * sampleRate / 1000.0
        while (true) {
            val n = NativeEngine.nativePollOnsets(pollBuffer)
            for (i in 0 until n) out += Onset(pollBuffer[2 * i] - offsetFrames, pollBuffer[2 * i + 1].toFloat())
            if (n < pollBuffer.size / 2) break
        }
        return out
    }
}
