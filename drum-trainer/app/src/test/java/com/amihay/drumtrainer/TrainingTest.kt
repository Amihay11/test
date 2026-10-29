package com.amihay.drumtrainer

import com.amihay.drumtrainer.model.DAY_MS
import com.amihay.drumtrainer.model.ExerciseParser
import com.amihay.drumtrainer.model.GapSetup
import com.amihay.drumtrainer.model.Mastery
import com.amihay.drumtrainer.model.PartKind
import com.amihay.drumtrainer.model.RunRecord
import com.amihay.drumtrainer.model.SessionPlanner
import com.amihay.drumtrainer.model.SessionReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrainingTest {
    private val exercises = listOf("rudiments", "grooves").flatMap {
        ExerciseParser.parse(File("src/main/assets/exercises/$it.json").readText())
    }
    private fun ex(id: String) = exercises.first { it.id == id }
    private val t0 = 1_000 * DAY_MS

    private fun run(id: String, bpm: Int, passed: Boolean, day: Int, session: Long?, std: Double = 10.0) =
        RunRecord(id, bpm, passed, t0 + day * DAY_MS, session, std)

    @Test
    fun masteryNeedsTargetTempoInTwoSessions() {
        val e = ex("single_stroke_roll")  // target 120
        val oneSession = listOf(run(e.id, 120, true, 0, 1), run(e.id, 124, true, 0, 1))
        assertFalse(Mastery.isMastered(e, oneSession))
        assertFalse(Mastery.isMastered(e, oneSession + run(e.id, 116, true, 3, 2)))  // below target
        assertTrue(Mastery.isMastered(e, oneSession + run(e.id, 120, true, 3, 2)))
        // Outside sessions, different days count as different occasions.
        assertTrue(Mastery.isMastered(e, listOf(run(e.id, 120, true, 0, null), run(e.id, 120, true, 2, null))))
    }

    @Test
    fun firstSessionHasAllFivePartsWithStartingMaterial() {
        val plan = SessionPlanner.plan(exercises, emptyList(), emptyMap(), t0)
        assertEquals(PartKind.entries.toList(), plan.parts.map { it.kind })
        assertEquals(45, plan.minutes)
        val technique = plan.parts.first { it.kind == PartKind.TECHNIQUE }.items
        assertEquals(listOf("single_stroke_roll", "double_stroke_roll"), technique.map { it.exerciseId })
        val timing = plan.parts.first { it.kind == PartKind.TIMING }.items.single()
        assertEquals(GapSetup(2, 2), timing.gap)
        val groove = plan.parts.first { it.kind == PartKind.GROOVE }.items
        assertEquals(listOf("ind_kick_quarters", "fill_snare_half"), groove.map { it.exerciseId })
        assertTrue(plan.parts.all { it.items.isNotEmpty() })
    }

    @Test
    fun warmUpDoesNotRepeatTodaysTechnique() {
        // Played outside any session, so there is nothing to review yet.
        val runs = listOf(run("single_stroke_roll", 64, false, 0, null))
        val plan = SessionPlanner.plan(exercises, runs, emptyMap(), t0 + DAY_MS)
        val warmUp = plan.parts.first { it.kind == PartKind.REVIEW }.items.map { it.exerciseId }
        val technique = plan.parts.first { it.kind == PartKind.TECHNIQUE }.items.map { it.exerciseId }
        assertEquals(1, warmUp.size)
        assertTrue(warmUp.intersect(technique.toSet()).isEmpty())
    }

    @Test
    fun reviewRepeatsLastSessionAtNinetyPercent() {
        val runs = listOf(
            run("single_paradiddle", 80, true, 0, 7),
            run("single_paradiddle", 84, false, 0, 7),
            run("rock_1", 90, false, 0, 7),
            run("double_stroke_roll", 70, true, -10, 3),  // older session: not reviewed
        )
        val plan = SessionPlanner.plan(exercises, runs, mapOf("single_paradiddle" to 84), t0 + DAY_MS)
        val review = plan.parts.first { it.kind == PartKind.REVIEW }.items
        assertEquals(listOf("single_paradiddle", "rock_1"), review.map { it.exerciseId })
        assertEquals(72, review[0].bpm)  // 90 % of the 80 BPM it passed at
        assertEquals(81, review[1].bpm)  // never passed: 90 % of the tempo played
        // Technique: the in-progress rudiment played longest ago, plus one new one.
        val technique = plan.parts.first { it.kind == PartKind.TECHNIQUE }.items.map { it.exerciseId }
        assertEquals(listOf("double_stroke_roll", "single_stroke_roll"), technique)
    }

    @Test
    fun restedMasteredExerciseComesBackForReview() {
        val e = ex("single_stroke_roll")
        val runs = listOf(run(e.id, 120, true, 0, 1), run(e.id, 124, true, 3, 2), run("rock_1", 80, true, 30, 9))
        val plan = SessionPlanner.plan(exercises, runs, emptyMap(), t0 + 31 * DAY_MS)
        val review = plan.parts.first { it.kind == PartKind.REVIEW }.items
        assertEquals(listOf("rock_1", e.id), review.map { it.exerciseId })
        assertEquals(112, review[1].bpm)  // 90 % of best passed (124)
    }

    @Test
    fun gapMetronomeMovesUpTheLadderWhenSteady() {
        fun next(vararg runs: RunRecord) = SessionPlanner.plan(exercises, runs.toList(), emptyMap(), t0 + 5 * DAY_MS)
            .parts.first { it.kind == PartKind.TIMING }.items.single()
        assertEquals(GapSetup(2, 2), next(run("gap_2_2", 80, false, 0, 1, std = 35.0)).gap)
        assertEquals(GapSetup(4, 4), next(run("gap_2_2", 80, false, 0, 1, std = 12.0)).gap)
        val top = next(run("gap_1_3", 80, false, 0, 1, std = 12.0))
        assertEquals(GapSetup(1, 3), top.gap)
        assertEquals(84, top.bpm)
    }

    @Test
    fun shorterSessionsScaleEveryPart() {
        val plan = SessionPlanner.plan(exercises, emptyList(), emptyMap(), t0, totalMinutes = 30)
        assertEquals(listOf(3, 8, 5, 10, 3), plan.parts.map { it.minutes })
    }

    @Test
    fun summaryReportsImprovementsStuckAndNewlyMastered() {
        val sse = ex("single_stroke_roll")
        val runs = listOf(
            run(sse.id, 120, true, 0, 1),
            run("single_paradiddle", 70, true, 0, 1),
            // Session 2:
            run(sse.id, 120, true, 3, 2),
            run("single_paradiddle", 74, true, 3, 2),
            run("rock_1", 80, false, 3, 2),
            run("rock_1", 80, false, 3, 2),
            run("funk_1", 60, true, 3, 2),
        )
        val s = SessionReview.summarize(exercises, runs, 2)
        assertTrue(s.improved.any { it.contains("70 → 74") })
        assertTrue(s.improved.any { it.contains("עבר לראשונה") })
        assertEquals(1, s.stuck.size)
        assertEquals(listOf(sse.nameHe), s.mastered)
    }

    @Test
    fun historyKeepsTheBestPassedTempoPerDay() {
        val runs = listOf(run("rock_1", 80, true, 0, 1), run("rock_1", 84, true, 0, 1), run("rock_1", 90, false, 0, 1),
            run("rock_1", 88, true, 4, 2))
        assertEquals(listOf(84, 88), Mastery.tempoHistory("rock_1", runs).map { it.second })
    }
}

class FreePlayTest {
    @Test
    fun steadyPlayingIsOnTheGrid() {
        val ex = com.amihay.drumtrainer.model.ExerciseParser.parse(java.io.File("src/main/assets/exercises/grooves.json").readText())
            .first { it.id == "rock_1" }
        val p = com.amihay.drumtrainer.model.RoundPlanner.forTogether(ex, 100, 4, 0, 48000, realDrums = false)
        val grid = p.framesPerBeat / 4
        // Hits on assorted 16ths, 3 ms late, plus one far off the grid.
        val onsets = (0 until 40 step 3).map { com.amihay.drumtrainer.model.Onset(p.firstNoteFrame + it * grid + p.msToFrames(3.0), 0.5f) } +
            com.amihay.drumtrainer.model.Onset(p.firstNoteFrame + 5.5 * grid, 0.5f)
        val s = com.amihay.drumtrainer.model.FreePlayStats.summarize(onsets, p)
        assertEquals(15, s.hits)
        assertEquals(100.0 * 14 / 15, s.onGridPct, 1e-9)
    }
}
