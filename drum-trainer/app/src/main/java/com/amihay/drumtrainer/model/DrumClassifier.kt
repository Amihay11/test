package com.amihay.drumtrainer.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** The order matters: the engine's drum sounds are indexed by it (DrumKitSounds.h). */
enum class Pad(val id: String, val he: String, val short: String) {
    KICK("kick", "קיק", "K"),
    SNARE("snare", "סנר", "S"),
    HIHAT("hihat", "היי-האט", "H"),
    TOM_HIGH("tom_high", "טום גבוה", "T1"),
    TOM_MID("tom_mid", "טום אמצעי", "T2"),
    TOM_FLOOR("tom_floor", "טום רצפה", "T3"),
    CRASH("crash", "קראש", "C"),
    RIDE("ride", "רייד", "R");

    companion object {
        fun byId(id: String): Pad? = entries.firstOrNull { it.id == id }
    }
}

/** What the native engine measures for each hit (see OnsetDetector::computeFeatures). */
class HitFeatures(val bandsDb: FloatArray, val decayDb: Float) {
    init {
        require(bandsDb.size == NUM_BANDS) { "expected $NUM_BANDS bands, got ${bandsDb.size}" }
    }

    companion object {
        /** Must match kNumBands in OnsetDetector.h. */
        const val NUM_BANDS = 20
    }
}

data class TrainingSample(val pad: Pad, val features: HitFeatures)

/** [pads] is empty when the hit does not resemble any trained pad. */
data class Classification(val pads: List<Pad>, val confidence: Double) {
    val primary: Pad? get() = pads.firstOrNull()
    val isCombo: Boolean get() = pads.size > 1
}

/**
 * Classifies a hit from its spectral shape.
 *
 * Single hits: k-nearest-neighbours on MFCC-style coefficients (DCT of the log
 * band energies without c0, so loudness does not matter) plus the decay.
 *
 * Simultaneous hits (e.g. kick + hi-hat): every pad also gets an average power
 * spectrum ("template"). When a non-negative mix of two templates explains the
 * hit much better than any single template, both pads are reported.
 */
class DrumClassifier(samples: List<TrainingSample>, private val k: Int = 3) {
    private val trainPads = samples.map { it.pad }
    private val mean: DoubleArray
    private val scale: DoubleArray
    private val train: List<DoubleArray>
    private val rejectDistance: Double
    private val templates: Map<Pad, DoubleArray>

    val pads: Set<Pad> = trainPads.toSet()
    val isUsable: Boolean get() = pads.isNotEmpty()

    init {
        val raw = samples.map { vector(it.features) }
        val dims = raw.firstOrNull()?.size ?: (NUM_COEFFS + 1)
        mean = DoubleArray(dims) { d -> raw.map { it[d] }.averageOrZero() }
        scale = DoubleArray(dims) { d ->
            val m = mean[d]
            max(sqrt(raw.map { (it[d] - m) * (it[d] - m) }.averageOrZero()), 0.5)
        }
        train = raw.map { standardize(it) }

        // Typical distance from a training hit to its nearest same-pad neighbour; hits much
        // further than that from everything are "unknown" (e.g. a pad that was not trained).
        val nn = train.indices.mapNotNull { i ->
            train.indices.filter { it != i && trainPads[it] == trainPads[i] }
                .minOfOrNull { dist(train[i], train[it]) }
        }
        rejectDistance = if (nn.isEmpty()) Double.MAX_VALUE else max(4.0 * median(nn), 2.0)

        val byPad = samples.groupBy { it.pad }
        val tmpl = byPad.mapValues { (_, s) ->
            val acc = DoubleArray(NUM_BANDS)
            s.forEach { sample -> unitPower(sample.features).forEachIndexed { b, v -> acc[b] += v / s.size } }
            acc
        }
        templates = tmpl
    }

    fun classify(f: HitFeatures): Classification {
        if (!isUsable) return Classification(emptyList(), 0.0)
        combo(f)?.let { return it }

        val x = standardize(vector(f))
        val nearest = train.indices.sortedBy { dist(x, train[it]) }.take(k)
        if (dist(x, train[nearest.first()]) > rejectDistance) return Classification(emptyList(), 0.0)
        val votes = HashMap<Pad, Double>()
        for (i in nearest) votes[trainPads[i]] = (votes[trainPads[i]] ?: 0.0) + 1.0 / (dist(x, train[i]) + 1e-3)
        val total = votes.values.sum()
        val (pad, v) = votes.maxBy { it.value }
        return Classification(listOf(pad), v / total)
    }

    /**
     * Two-pad explanation of the hit, if it is clearly better than any single pad.
     * The fit uses relative error per band (weights 1/x²), so a quiet hi-hat on top of
     * a loud kick counts as much as the kick: the ear hears both.
     */
    private fun combo(f: HitFeatures): Classification? {
        if (templates.size < 2) return null
        val x = unitPower(f)
        val floor = x.max() * 1e-3
        val w = DoubleArray(NUM_BANDS) { 1.0 / ((x[it] + floor) * (x[it] + floor)) }
        val xx = wdot(w, x, x)

        val single = templates.values.minOf { t ->
            val a = max(0.0, wdot(w, x, t) / wdot(w, t, t))
            residual(w, x, t, a, null, 0.0) / xx
        }
        if (single < MIN_SINGLE_RESIDUAL) return null

        var best: Triple<Pad, Pad, Double>? = null
        val entries = templates.entries.toList()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            val (p1, t1) = entries[i]
            val (p2, t2) = entries[j]
            val g11 = wdot(w, t1, t1); val g12 = wdot(w, t1, t2); val g22 = wdot(w, t2, t2)
            val h1 = wdot(w, t1, x); val h2 = wdot(w, t2, x)
            val det = g11 * g22 - g12 * g12
            if (det <= 1e-12 * g11 * g22) continue
            val a1 = (h1 * g22 - h2 * g12) / det
            val a2 = (h2 * g11 - h1 * g12) / det
            if (a1 <= 0 || a2 <= 0) continue
            // Share of each pad: average fraction of the modelled band level it provides.
            var s1 = 0.0
            for (b in 0 until NUM_BANDS) s1 += a1 * t1[b] / (a1 * t1[b] + a2 * t2[b])
            s1 /= NUM_BANDS
            if (minOf(s1, 1 - s1) < MIN_COMPONENT_SHARE) continue
            val r = residual(w, x, t1, a1, t2, a2) / xx
            if (best == null || r < best.third) {
                best = Triple(if (s1 >= 0.5) p1 else p2, if (s1 >= 0.5) p2 else p1, r)
            }
        }
        val b = best ?: return null
        if (b.third > COMBO_GAIN * single) return null
        return Classification(listOf(b.first, b.second), 1.0 - b.third / single)
    }

    private fun residual(w: DoubleArray, x: DoubleArray, t1: DoubleArray, a1: Double, t2: DoubleArray?, a2: Double): Double {
        var s = 0.0
        for (b in x.indices) {
            val d = x[b] - a1 * t1[b] - (if (t2 != null) a2 * t2[b] else 0.0)
            s += w[b] * d * d
        }
        return s
    }

    private fun standardize(v: DoubleArray) = DoubleArray(v.size) { (v[it] - mean[it]) / scale[it] }

    companion object {
        const val NUM_BANDS = HitFeatures.NUM_BANDS
        const val NUM_COEFFS = 12

        // On synthetic kits, single hits leave <= 0.11 unexplained and a mix improves that by
        // a few percent at most; kick+hi-hat leaves 0.14-0.30 and a mix cuts it to <= 23%.

        /** A single pad must leave at least this much unexplained before a mix is considered. */
        const val MIN_SINGLE_RESIDUAL = 0.08
        /** The mix must cut the unexplained part to at most this fraction of the single fit. */
        const val COMBO_GAIN = 0.35
        /** Each pad in a mix must carry at least this share of the fitted energy. */
        const val MIN_COMPONENT_SHARE = 0.08

        /** MFCC-style coefficients c1..c12 of the log bands, plus the decay. */
        fun vector(f: HitFeatures): DoubleArray {
            val n = NUM_BANDS
            val out = DoubleArray(NUM_COEFFS + 1)
            for (c in 1..NUM_COEFFS) {
                var s = 0.0
                for (b in 0 until n) s += f.bandsDb[b] * cos(PI * c * (b + 0.5) / n)
                out[c - 1] = s * sqrt(2.0 / n) / 10.0
            }
            out[NUM_COEFFS] = f.decayDb / 3.0
            return out
        }

        /** Linear band powers normalised to sum to 1 (loudness removed). */
        fun unitPower(f: HitFeatures): DoubleArray {
            val maxDb = f.bandsDb.max()
            val p = DoubleArray(NUM_BANDS) { 10.0.pow((f.bandsDb[it] - maxDb) / 10.0) }
            val sum = p.sum()
            return DoubleArray(NUM_BANDS) { p[it] / sum }
        }

        private fun wdot(w: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (i in a.indices) s += w[i] * a[i] * b[i]
            return s
        }

        private fun dist(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
            return sqrt(s)
        }

        private fun median(v: List<Double>): Double = v.sorted()[v.size / 2]

        private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
    }
}
