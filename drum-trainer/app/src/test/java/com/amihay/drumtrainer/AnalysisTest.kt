package com.amihay.drumtrainer

import com.amihay.drumtrainer.model.CalibrationMath
import com.amihay.drumtrainer.model.ExerciseKind
import com.amihay.drumtrainer.model.ExerciseParser
import com.amihay.drumtrainer.model.GapAnalysis
import com.amihay.drumtrainer.model.Matcher
import com.amihay.drumtrainer.model.Onset
import com.amihay.drumtrainer.model.Progress
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.SpeedTrainer
import com.amihay.drumtrainer.model.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class AnalysisTest {
    private val sr = 48000

    private fun exercises() =
        ExerciseParser.parse(File("src/main/assets/exercises/rudiments.json").readText())

    @Test
    fun parsesAllRudiments() {
        val ex = exercises()
        assertEquals(10, ex.size)
        assertEquals(ex.size, ex.map { it.id }.toSet().size)
        val para = ex.first { it.id == "single_paradiddle" }
        assertEquals(8, para.steps.size)
        assertTrue(para.steps[0].single().accent)
        assertEquals('L', para.steps[1].single().hand)
        val five = ex.first { it.id == "five_stroke_roll" }
        assertTrue(five.steps[5].isEmpty())
        // Every pattern must tile a whole number of beats.
        for (e in ex) assertEquals(e.id, 0, e.steps.size % e.subdivision)
        assertTrue(ex.all { it.kind == ExerciseKind.RUDIMENT && !it.usesPads })
    }

    @Test
    fun planPlacesNotesAfterCountIn() {
        val ex = exercises().first { it.id == "single_stroke_roll" }
        val plan = RoundPlanner.forExercise(ex, bpm = 120, bars = 2, startFrame = 1000, sampleRate = sr)
        assertEquals(32, plan.notes.size)
        assertEquals(1000 + 4 * 24000.0, plan.notes[0].frame, 1e-6)
        assertEquals(6000.0, plan.framesPerStep, 1e-6)
        assertEquals(4 + 8, plan.clicks.size)  // count-in + 2 bars of quarters
        assertEquals(1500.0, plan.windowFrames, 1e-6)
    }

    @Test
    fun perfectPlayingIsAllHits() {
        val ex = exercises().first { it.id == "single_paradiddle" }
        val plan = RoundPlanner.forExercise(ex, 100, 4, 0, sr)
        val onsets = plan.notes.map { Onset(it.frame + plan.msToFrames(3.0), if (it.accent) 0.8f else 0.4f) }
        val m = Matcher.match(plan.notes, onsets, plan.windowFrames, sr)
        val s = Stats.compute(m, ex.subdivision)
        assertEquals(plan.notes.size, s.hits)
        assertEquals(0, s.extras)
        assertEquals(3.0, s.meanMs, 1e-6)
        assertEquals(0.0, s.stdMs, 1e-6)
        assertEquals(100.0, s.hitPct, 1e-9)
        assertEquals(6.02, s.accentDb!!, 0.01)
        assertTrue(Stats.passes(s, 15.0, 90.0))
    }

    @Test
    fun missesExtrasAndDuplicates() {
        val ex = exercises().first { it.id == "single_stroke_roll" }
        val plan = RoundPlanner.forExercise(ex, 60, 1, 0, sr)
        val notes = plan.notes
        val onsets = ArrayList<Onset>()
        notes.forEachIndexed { i, n -> if (i != 5) onsets += Onset(n.frame, 0.5f) }  // miss #5
        onsets += Onset(notes[2].frame + plan.msToFrames(10.0), 0.5f)                  // duplicate
        onsets += Onset(notes[7].frame + plan.framesPerStep / 2, 0.5f)                 // between notes
        onsets += Onset(notes[0].frame - plan.msToFrames(2000.0), 0.5f)                // count-in, ignored
        val m = Matcher.match(notes, onsets, plan.windowFrames, sr)
        val s = Stats.compute(m, ex.subdivision)
        assertEquals(15, s.hits)
        assertEquals(1, s.misses)
        assertEquals(2, s.extras)
        assertEquals(0.0, m.notes[2].deviationMs!!, 1e-9)  // the closer of the two wins
    }

    @Test
    fun pendingNotesAreNotMisses() {
        val ex = exercises().first { it.id == "single_stroke_roll" }
        val plan = RoundPlanner.forExercise(ex, 60, 1, 0, sr)
        val onsets = plan.notes.take(4).map { Onset(it.frame, 0.5f) }
        val m = Matcher.match(plan.notes, onsets, plan.windowFrames, sr, upToFrame = plan.notes[4].frame)
        assertEquals(4, m.resolvedCount)
        assertEquals(100.0, Stats.compute(m, 4).hitPct, 1e-9)
    }

    @Test
    fun jitterGivesExpectedSpreadAndHandSplit() {
        val ex = exercises().first { it.id == "single_stroke_roll" }
        val plan = RoundPlanner.forExercise(ex, 90, 8, 0, sr)
        val rnd = Random(1)
        // Left hand plays 10 ms late on average.
        val onsets = plan.notes.map {
            val late = if (it.hand == 'L') 10.0 else 0.0
            Onset(it.frame + plan.msToFrames(late + (rnd.nextDouble() - 0.5) * 4), 0.5f)
        }
        val s = Stats.compute(Matcher.match(plan.notes, onsets, plan.windowFrames, sr), 4)
        assertEquals(10.0, s.perHand.getValue('L').meanMs - s.perHand.getValue('R').meanMs, 1.0)
        assertTrue(s.stdMs in 4.0..6.5)
        assertEquals(s.hits, s.histogram.sum())
    }

    @Test
    fun gapAnalysisFindsRushingInSilence() {
        val plan = RoundPlanner.forGap(bpm = 100, stepsPerBeat = 1, onBars = 2, offBars = 2, cycles = 3,
            startFrame = 0, sampleRate = sr)
        // In silent bars the player rushes: each beat comes 6 ms earlier than the last.
        val onsets = plan.notes.map { n ->
            val drift = if (n.clickAudible) 0.0 else {
                val segStart = (n.bar / 4) * 4 + 2
                val beatInSeg = (n.bar - segStart) * 4 + n.stepInBar
                -6.0 * beatInSeg
            }
            Onset(n.frame + plan.msToFrames(drift), 0.5f)
        }
        val g = GapAnalysis.compute(Matcher.match(plan.notes, onsets, plan.windowFrames, sr), plan)
        assertEquals(0.0, g.withClick.meanMs, 1e-6)
        assertEquals(3, g.segments.size)
        val expectedBpm = 60000.0 / (600.0 - 6.0)
        assertEquals(expectedBpm, g.silentBpm!!, 0.05)
        assertEquals(0.0, g.driftBpmPerMin!!, 0.05)
    }

    @Test
    fun calibrationMedianIgnoresOutliers() {
        val clicks = (0 until 10).map { it * 24000.0 }
        val onsets = clicks.mapIndexed { i, c -> Onset(c + (if (i == 3) 9600.0 else 1440.0), 0.5f) }
        val r = CalibrationMath.medianDelay(clicks, onsets, -50.0, 400.0, sr)
        assertNotNull(r)
        assertEquals(30.0, r!!.medianMs, 1e-9)
        assertEquals(10, r.matched)
        assertNull(CalibrationMath.medianDelay(clicks, emptyList(), -50.0, 400.0, sr))
    }

    @Test
    fun speedTrainerNeedsTwoPassesInARow() {
        var p = Progress("x", 60, 0, 0)
        var bumped: Boolean
        SpeedTrainer.afterRound(p, 60, true).let { p = it.first; bumped = it.second }
        assertFalse(bumped)
        SpeedTrainer.afterRound(p, 60, false).let { p = it.first; bumped = it.second }
        assertEquals(0, p.consecutivePasses)
        SpeedTrainer.afterRound(p, 60, true).let { p = it.first }
        SpeedTrainer.afterRound(p, 60, true).let { p = it.first; bumped = it.second }
        assertTrue(bumped)
        assertEquals(64, p.currentBpm)
        assertEquals(60, p.bestPassedBpm)
    }
}
