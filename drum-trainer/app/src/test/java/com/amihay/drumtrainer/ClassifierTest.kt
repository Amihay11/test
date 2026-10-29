package com.amihay.drumtrainer

import com.amihay.drumtrainer.model.DrumClassifier
import com.amihay.drumtrainer.model.HitFeatures
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.TrainingSample
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses features produced by the real native detector from synthesized drums
 * (native-tests/features_gen.cpp). Training uses 10 hits per pad, like the
 * in-app pad calibration; everything else is held out.
 */
class ClassifierTest {
    private data class Hit(val label: String, val features: HitFeatures)

    private val hits: List<Hit> = run {
        val text = javaClass.classLoader!!.getResource("synthetic_hits.json")!!.readText()
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("bandsDb")
            Hit(o.getString("label"), HitFeatures(FloatArray(b.length()) { b.getDouble(it).toFloat() }, o.getDouble("decayDb").toFloat()))
        }
    }

    private fun split(pads: Set<Pad>, perPad: Int = 10): Pair<List<TrainingSample>, List<Hit>> {
        val train = ArrayList<TrainingSample>()
        val test = ArrayList<Hit>()
        val seen = HashMap<String, Int>()
        for (h in hits) {
            val pad = Pad.byId(h.label)
            val n = seen.getOrDefault(h.label, 0)
            if (pad != null && pad in pads && n < perPad) {
                train += TrainingSample(pad, h.features)
                seen[h.label] = n + 1
            } else {
                test += h
            }
        }
        return train to test
    }

    @Test
    fun classifiesSingleHitsOfAllPads() {
        val (train, test) = split(Pad.entries.toSet())
        val c = DrumClassifier(train)
        val singles = test.filter { Pad.byId(it.label) != null }
        val correct = singles.count { c.classify(it.features).let { r -> !r.isCombo && r.primary?.id == it.label } }
        val acc = correct.toDouble() / singles.size
        println("single-hit accuracy: $correct/${singles.size}")
        assertTrue("accuracy $acc", acc >= 0.95)
    }

    @Test
    fun detectsKickAndHihatTogether() {
        val (train, test) = split(Pad.entries.toSet())
        val c = DrumClassifier(train)
        val combos = test.filter { it.label == "kick+hihat" }
        val found = combos.count { c.classify(it.features).pads.toSet() == setOf(Pad.KICK, Pad.HIHAT) }
        val singles = test.filter { Pad.byId(it.label) != null }
        val falseCombos = singles.count { c.classify(it.features).isCombo }
        println("kick+hihat detected: $found/${combos.size}, false combos: $falseCombos/${singles.size}")
        assertTrue("combo recall $found/${combos.size}", found >= combos.size * 0.8)
        assertTrue("false combos $falseCombos", falseCombos <= singles.size * 0.03)
    }

    @Test
    fun untrainedPadIsNotForcedIntoATrainedOne() {
        // Only kick, snare and hi-hat are trained; crashes should mostly come out "unknown"
        // rather than be confidently called one of those.
        val (train, _) = split(setOf(Pad.KICK, Pad.SNARE, Pad.HIHAT))
        val c = DrumClassifier(train)
        val crashes = hits.filter { it.label == "crash" }
        val rejected = crashes.count { c.classify(it.features).pads.isEmpty() }
        println("untrained crash rejected: $rejected/${crashes.size}")
        assertTrue(rejected >= crashes.size * 0.7)
    }

    @Test
    fun emptyClassifierReturnsUnknown() {
        val c = DrumClassifier(emptyList())
        assertTrue(c.classify(hits.first().features).pads.isEmpty())
    }
}
