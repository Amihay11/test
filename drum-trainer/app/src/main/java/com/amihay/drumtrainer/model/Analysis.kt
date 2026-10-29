package com.amihay.drumtrainer.model

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** A detected hit, already corrected for latency and expressed on the output clock. */
data class Onset(val frame: Double, val strength: Float, val features: HitFeatures? = null)

data class NoteResult(val note: ExpectedNote, val onset: Onset?, val deviationMs: Double?) {
    val hit: Boolean get() = onset != null
}

data class MatchResult(
    val notes: List<NoteResult>,
    val extras: List<Onset>,
    /** Notes whose window has fully passed (only these count as hit or miss). */
    val resolvedCount: Int,
)

object Matcher {

    /**
     * Assigns each onset to the nearest expected note within ±[windowFrames]. An onset
     * outside every window is an extra hit; a note with no onset is a miss. Notes whose
     * window ends after [upToFrame] are still pending and are not counted as misses.
     */
    fun match(
        notes: List<ExpectedNote>,
        onsets: List<Onset>,
        windowFrames: Double,
        sampleRate: Int,
        upToFrame: Double = Double.MAX_VALUE,
    ): MatchResult {
        if (notes.isEmpty()) return MatchResult(emptyList(), emptyList(), 0)
        val assigned = arrayOfNulls<Onset>(notes.size)
        val extras = ArrayList<Onset>()
        val lo = notes.first().frame - windowFrames
        val hi = notes.last().frame + windowFrames

        for (onset in onsets.sortedBy { it.frame }) {
            if (onset.frame < lo || onset.frame > hi) continue  // count-in or after the round
            val i = nearest(notes, onset.frame)
            val d = abs(onset.frame - notes[i].frame)
            if (d > windowFrames) {
                extras += onset
                continue
            }
            val existing = assigned[i]
            if (existing == null) {
                assigned[i] = onset
            } else if (d < abs(existing.frame - notes[i].frame)) {
                extras += existing
                assigned[i] = onset
            } else {
                extras += onset
            }
        }

        var resolved = 0
        val results = notes.mapIndexed { i, note ->
            val o = assigned[i]
            if (o != null || note.frame + windowFrames <= upToFrame) resolved++
            NoteResult(note, o, o?.let { (it.frame - note.frame) * 1000.0 / sampleRate })
        }
        return MatchResult(results, extras.sortedBy { it.frame }, resolved)
    }

    private fun nearest(notes: List<ExpectedNote>, frame: Double): Int {
        var lo = 0
        var hi = notes.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (notes[mid].frame < frame) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(notes[lo - 1].frame - frame) <= abs(notes[lo].frame - frame)) return lo - 1
        return lo
    }
}

data class Spread(val n: Int, val meanMs: Double, val stdMs: Double)

data class RoundStats(
    val expected: Int,
    val hits: Int,
    val misses: Int,
    val extras: Int,
    val meanMs: Double,
    val stdMs: Double,
    val hitPct: Double,
    val perHand: Map<Char, Spread>,
    /** Accent loudness relative to normal strokes, in dB (null without both kinds). */
    val accentDb: Double?,
    /** Mean deviation per position inside the beat (index = step % subdivision). */
    val byBeatPosition: List<Spread>,
    /** Deviations clamped to ±50 ms in 5 ms bins (21 bins, centre = on time). */
    val histogram: IntArray,
)

object Stats {
    const val HIST_BINS = 21
    const val HIST_BIN_MS = 5.0

    fun spread(values: List<Double>): Spread {
        if (values.isEmpty()) return Spread(0, 0.0, 0.0)
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return Spread(values.size, mean, sqrt(variance))
    }

    fun compute(match: MatchResult, subdivision: Int): RoundStats {
        val resolved = match.resolvedCount
        val hitNotes = match.notes.filter { it.hit }
        val devs = hitNotes.map { it.deviationMs!! }
        val all = spread(devs)

        val perHand = hitNotes.groupBy { it.note.hand }
            .mapValues { (_, v) -> spread(v.map { it.deviationMs!! }) }

        val accents = hitNotes.filter { it.note.accent }.map { it.onset!!.strength.toDouble() }
        val normals = hitNotes.filter { !it.note.accent }.map { it.onset!!.strength.toDouble() }
        val accentDb = if (accents.isNotEmpty() && normals.isNotEmpty() && normals.average() > 0) {
            20 * log10(accents.average() / normals.average())
        } else null

        val byPos = (0 until subdivision).map { pos ->
            spread(hitNotes.filter { it.note.stepInBar % subdivision == pos }.map { it.deviationMs!! })
        }

        val hist = IntArray(HIST_BINS)
        val half = HIST_BINS / 2
        for (d in devs) {
            val bin = (Math.round(d / HIST_BIN_MS).toInt() + half).coerceIn(0, HIST_BINS - 1)
            hist[bin]++
        }

        return RoundStats(
            expected = resolved,
            hits = hitNotes.size,
            misses = resolved - hitNotes.size,
            extras = match.extras.size,
            meanMs = all.meanMs,
            stdMs = all.stdMs,
            hitPct = if (resolved > 0) 100.0 * hitNotes.size / resolved else 0.0,
            perHand = perHand,
            accentDb = accentDb,
            byBeatPosition = byPos,
            histogram = hist,
        )
    }

    /** Pass = steady enough, enough correct hits, and almost no stray hits. */
    fun passes(stats: RoundStats, passStdMs: Double, passHitPct: Double): Boolean =
        stats.hits >= 4 &&
            stats.stdMs <= passStdMs &&
            stats.hitPct >= passHitPct &&
            stats.extras <= maxOf(1, (stats.expected * 0.05).toInt())
}

data class SilentSegment(val startBar: Int, val bars: Int, val playedBpm: Double?, val meanMs: Double)

data class GapStats(
    val withClick: Spread,
    val silent: Spread,
    val segments: List<SilentSegment>,
    /** Average tempo played while the click was silent. */
    val silentBpm: Double?,
    /** Change of the silent-section tempo over the round, in BPM per minute. */
    val driftBpmPerMin: Double?,
)

object GapAnalysis {

    fun compute(match: MatchResult, plan: RoundPlan): GapStats {
        val hits = match.notes.filter { it.hit }
        val withClick = Stats.spread(hits.filter { it.note.clickAudible }.map { it.deviationMs!! })
        val silent = Stats.spread(hits.filter { !it.note.clickAudible }.map { it.deviationMs!! })

        val beatMs = plan.framesToMs(plan.framesPerBeat)
        val segments = ArrayList<SilentSegment>()
        var bar = 0
        while (bar < plan.bars) {
            if (plan.clickBars[bar]) { bar++; continue }
            val start = bar
            while (bar < plan.bars && !plan.clickBars[bar]) bar++
            val seg = hits.filter { it.note.bar in start until bar }
            // Deviation grows linearly with time if the tempo is off: slope = ms per beat.
            val xs = seg.map { (it.note.frame - plan.firstNoteFrame) / plan.framesPerBeat }
            val ys = seg.map { it.deviationMs!! }
            val slope = slope(xs, ys)
            val bpm = slope?.let { 60000.0 / (beatMs + it) }
            segments += SilentSegment(start, bar - start, bpm, if (ys.isEmpty()) 0.0 else ys.average())
        }

        val bpms = segments.mapNotNull { it.playedBpm }
        val silentBpm = if (bpms.isEmpty()) null else bpms.average()
        val drift = if (segments.count { it.playedBpm != null } >= 2) {
            val ts = segments.filter { it.playedBpm != null }.map { s ->
                (s.startBar + s.bars / 2.0) * plan.beatsPerBar * beatMs / 60000.0
            }
            slope(ts, bpms)
        } else null
        return GapStats(withClick, silent, segments, silentBpm, drift)
    }

    fun slope(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size < 3) return null
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (den == 0.0) null else num / den
    }
}

data class CalibrationResult(val medianMs: Double, val spreadMs: Double, val matched: Int)

object CalibrationMath {
    /**
     * For each click, takes the first onset in [click + minMs, click + maxMs] and returns
     * the median delay. Used both for the loopback and the play-along calibration.
     */
    fun medianDelay(
        clickFrames: List<Double>, onsets: List<Onset>, minMs: Double, maxMs: Double, sampleRate: Int,
    ): CalibrationResult? {
        val sorted = onsets.sortedBy { it.frame }
        val delays = clickFrames.mapNotNull { c ->
            sorted.firstOrNull {
                val ms = (it.frame - c) * 1000.0 / sampleRate
                ms in minMs..maxMs
            }?.let { (it.frame - c) * 1000.0 / sampleRate }
        }
        if (delays.size < 4) return null
        val median = median(delays)
        val mad = median(delays.map { abs(it - median) })
        return CalibrationResult(median, mad, delays.size)
    }

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

data class Progress(val exerciseId: String, val currentBpm: Int, val consecutivePasses: Int, val bestPassedBpm: Int)

object SpeedTrainer {
    const val STEP_BPM = 4
    const val PASSES_TO_ADVANCE = 2

    /** Returns the updated progress and whether the tempo went up. */
    fun afterRound(p: Progress, playedBpm: Int, passed: Boolean): Pair<Progress, Boolean> {
        if (!passed) return p.copy(currentBpm = playedBpm, consecutivePasses = 0) to false
        val best = maxOf(p.bestPassedBpm, playedBpm)
        val streak = if (playedBpm == p.currentBpm) p.consecutivePasses + 1 else 1
        return if (streak >= PASSES_TO_ADVANCE) {
            Progress(p.exerciseId, playedBpm + STEP_BPM, 0, best) to true
        } else {
            Progress(p.exerciseId, playedBpm, streak, best) to false
        }
    }
}
