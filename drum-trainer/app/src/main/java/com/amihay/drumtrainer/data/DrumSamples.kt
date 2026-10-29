package com.amihay.drumtrainer.data

import android.content.Context
import com.amihay.drumtrainer.audio.AudioEngine
import com.amihay.drumtrainer.model.Pad
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Who recorded the drum sounds (CC BY 3.0 asks for credit). */
const val SAMPLE_CREDIT = "דגימות התופים: Pearl Master Studio Pack 1 מאת enoe, ברישיון CC BY 3.0"

/** A mono 16-bit WAV decoded to floats. */
class PcmSound(val samples: FloatArray, val sampleRate: Int)

object WavReader {
    /** Reads a 16-bit PCM WAV (mixed to mono); null if the format is anything else. */
    fun read(bytes: ByteArray): PcmSound? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var channels = 0
        var rate = 0
        var bits = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > bytes.size) return null
            when (id) {
                "fmt " -> {
                    if (b.getShort(body).toInt() != 1) return null  // PCM only
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (bits != 16 || channels < 1) return null
                    val frames = size / (2 * channels)
                    val out = FloatArray(frames) { f ->
                        var sum = 0f
                        for (c in 0 until channels) sum += b.getShort(body + (f * channels + c) * 2) / 32768f
                        sum / channels
                    }
                    return PcmSound(out, rate)
                }
            }
            pos = body + size + (size and 1)
        }
        return null
    }
}

/**
 * Loads the recorded drum sounds (assets/sounds/<pad id>.wav) into the engine. A drum
 * without a sample keeps its synthesized sound.
 */
fun loadDrumSamples(context: Context, engine: AudioEngine) {
    for (pad in Pad.entries) {
        val bytes = runCatching { context.assets.open("sounds/${pad.id}.wav").use { it.readBytes() } }.getOrNull() ?: continue
        val sound = WavReader.read(bytes) ?: continue
        engine.setPadSample(pad, sound.samples, sound.sampleRate)
    }
}
