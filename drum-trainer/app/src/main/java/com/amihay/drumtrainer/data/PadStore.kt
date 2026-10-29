package com.amihay.drumtrainer.data

import com.amihay.drumtrainer.model.HitFeatures
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.TrainingSample
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Pad-calibration hits, kept as a small JSON file next to the database. */
class PadStore(private val file: File) {

    fun load(): List<TrainingSample> {
        if (!file.exists()) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(samples: List<TrainingSample>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(samples))
        tmp.renameTo(file)
    }

    companion object {
        fun encode(samples: List<TrainingSample>): String {
            val arr = JSONArray()
            for (s in samples) {
                arr.put(
                    JSONObject()
                        .put("pad", s.pad.id)
                        .put("decayDb", s.features.decayDb.toDouble())
                        .put("bandsDb", JSONArray(s.features.bandsDb.map { it.toDouble() })),
                )
            }
            return arr.toString()
        }

        fun decode(text: String): List<TrainingSample> {
            val arr = JSONArray(text)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val pad = Pad.byId(o.getString("pad")) ?: return@mapNotNull null
                val b = o.getJSONArray("bandsDb")
                if (b.length() != HitFeatures.NUM_BANDS) return@mapNotNull null
                TrainingSample(pad, HitFeatures(FloatArray(b.length()) { b.getDouble(it).toFloat() }, o.getDouble("decayDb").toFloat()))
            }
        }
    }
}
