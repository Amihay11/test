package com.amihay.drumtrainer.audio

import android.os.SystemClock
import com.amihay.drumtrainer.model.BackingLoop
import com.amihay.drumtrainer.model.Click
import com.amihay.drumtrainer.model.HitFeatures
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
    external fun nativeSetClicks(frames: LongArray, types: IntArray, gains: FloatArray)
    external fun nativeClearClicks()
    external fun nativeTrigger(type: Int, gain: Float)
    external fun nativeSetBacking(
        starts: IntArray, lengths: IntArray, midis: IntArray, gains: FloatArray, instruments: IntArray, loopFrames: Int,
    )
    external fun nativeSetSensitivity(s: Float)
    external fun nativeSetNotch(enabled: Boolean)
    external fun nativePollOnsets(out: DoubleArray): Int
    external fun nativeOnsetStride(): Int
    external fun nativeInputPeak(): Float
    external fun nativeTimestampsAligned(): Boolean
    external fun nativeXruns(): Int
}

/** Kotlin-friendly facade over [NativeEngine]. */
class AudioEngine {
    private val stride = NativeEngine.nativeOnsetStride().also {
        check(it == 3 + HitFeatures.NUM_BANDS) { "native onset layout ($it) does not match Kotlin" }
    }
    private val pollBuffer = DoubleArray(stride * 128)

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
        NativeEngine.nativeSetClicks(
            LongArray(sorted.size) { sorted[it].frame },
            IntArray(sorted.size) { sorted[it].type },
            FloatArray(sorted.size) { sorted[it].gain },
        )
    }

    fun clearClicks() = NativeEngine.nativeClearClicks()

    /** Plays a sound right away (a tap on the on-screen drums). */
    fun trigger(type: Int, gain: Float = 0.8f) = NativeEngine.nativeTrigger(type, gain)

    /**
     * The output frame that was audible at [uptimeMs] (a touch event's time, on the
     * SystemClock.uptimeMillis clock), so a tap can be compared with the clicks.
     */
    fun frameAtUptime(uptimeMs: Long): Double =
        playheadFrame() - (SystemClock.uptimeMillis() - uptimeMs) * sampleRate / 1000.0

    /** Renders a backing loop; schedule it with [ClickType.BACKING] clicks at each loop start. */
    fun setBacking(loop: BackingLoop) {
        val n = loop.notes
        NativeEngine.nativeSetBacking(
            IntArray(n.size) { n[it].startFrame }, IntArray(n.size) { n[it].lengthFrames },
            IntArray(n.size) { n[it].midi }, FloatArray(n.size) { n[it].gain },
            IntArray(n.size) { n[it].instrument }, loop.loopFrames,
        )
    }

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
        val nb = HitFeatures.NUM_BANDS
        while (true) {
            val n = NativeEngine.nativePollOnsets(pollBuffer)
            for (i in 0 until n) {
                val o = i * stride
                val bands = FloatArray(nb) { pollBuffer[o + 2 + it].toFloat() }
                out += Onset(pollBuffer[o] - offsetFrames, pollBuffer[o + 1].toFloat(),
                    HitFeatures(bands, pollBuffer[o + 2 + nb].toFloat()))
            }
            if (n < pollBuffer.size / stride) break
        }
        return out
    }
}
