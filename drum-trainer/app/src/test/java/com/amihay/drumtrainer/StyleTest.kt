package com.amihay.drumtrainer

import com.amihay.drumtrainer.model.Backing
import com.amihay.drumtrainer.model.BackingNote
import com.amihay.drumtrainer.model.ClickType
import com.amihay.drumtrainer.model.DAY_MS
import com.amihay.drumtrainer.model.ExerciseKind
import com.amihay.drumtrainer.model.ExerciseParser
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.PartKind
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.RunRecord
import com.amihay.drumtrainer.model.SessionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StyleTest {
    private val sr = 48000
    private val all = listOf("rudiments", "grooves", "styles").flatMap {
        ExerciseParser.parse(File("src/main/assets/exercises/$it.json").readText())
    }
    private fun ex(id: String) = all.first { it.id == id }

    @Test
    fun everyStyleFamilyFromTheSpecIsThere() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val styles = all.filter { it.kind == ExerciseKind.GROOVE }.mapNotNull { it.style }.toSet()
        assertEquals(setOf("rock", "funk", "jazz", "blues", "latin", "mideast", "electronic"), styles)
        for (e in all.filter { it.style != null }) assertEquals(e.id, 0, e.steps.size % e.stepsPerBar)
        // Middle Eastern rhythms: dum on the kick, tek on the snare.
        val maqsum = ex("maqsum")
        assertEquals(listOf(0, 4), maqsum.steps.indices.filter { s -> maqsum.steps[s].any { it.pad == Pad.KICK } })
        assertEquals(listOf(1, 3, 6), maqsum.steps.indices.filter { s -> maqsum.steps[s].any { it.pad == Pad.SNARE } })
    }

    @Test
    fun swingDelaysTheSecondEighthOfEachPair() {
        val e = ex("jazz_ride")
        val beat = sr * 60.0 / 120
        val straight = RoundPlanner.forExercise(e, 120, 1, 0, sr, swingPct = 50)
        val swung = RoundPlanner.forExercise(e, 120, 1, 0, sr, swingPct = 66)
        fun rideAt(p: com.amihay.drumtrainer.model.RoundPlan) = p.notes.filter { it.pad == Pad.RIDE }.map { it.frame - p.firstNoteFrame }
        // Ride: 1, 2, 2&, 3, 4, 4&.
        assertEquals(listOf(0.0, beat, 1.5 * beat, 2 * beat, 3 * beat, 3.5 * beat), rideAt(straight))
        assertEquals(1.66 * beat, rideAt(swung)[2], 1.0)
        assertEquals(2 * beat, rideAt(swung)[3], 1e-6)  // on-beat notes do not move
        // Default swing comes from the exercise; straight-only exercises ignore swing.
        assertEquals(66, e.swing)
        assertEquals(null, ex("rock_1").swing)
    }

    @Test
    fun sevenEightAccentsTheStartOfEachGroup() {
        val e = ex("seven_eight")
        assertEquals(setOf(0, 2, 4), e.accentBeats)
        val p = RoundPlanner.forExercise(e, 180, 1, 0, sr)
        val bar = p.clicks.filter { it.frame >= p.firstNoteFrame - 1 }
        assertEquals(7, bar.size)
        assertEquals(listOf(0, 2, 4), bar.indices.filter { bar[it].type == ClickType.ACCENT })
        assertEquals(setOf(0, 3), ex("five_four").accentBeats)
    }

    @Test
    fun backingLoopsFitTheirBarsForEveryStyle() {
        for (style in SessionPlanner.STYLE_ORDER) {
            val e = all.first { it.style == style }
            val loop = Backing.loop(e, 100, sr)
            val bar = (sr * 60.0 / 100 * e.beatsPerBar).toInt()
            assertEquals(style, 4, loop.bars)
            assertTrue(style, kotlin.math.abs(4 * bar - loop.loopFrames) <= 4)
            assertTrue(style, loop.notes.all { it.startFrame in 0 until loop.loopFrames && it.lengthFrames > 0 })
            assertTrue(style, loop.notes.count { it.instrument == BackingNote.BASS } >= 4)
            assertTrue(style, loop.notes.count { it.instrument == BackingNote.PAD } >= 12)
        }
        // Jazz walks: four bass notes per bar.
        assertEquals(16, Backing.loop(ex("jazz_ride"), 120, sr).notes.count { it.instrument == BackingNote.BASS })
        // Odd meters: the bass follows the groups (2+2+3: three hits per bar).
        assertEquals(12, Backing.loop(ex("seven_eight"), 180, sr).notes.count { it.instrument == BackingNote.BASS })
    }

    @Test
    fun plannerMovesToTheNextStyleOnceRockIsMastered() {
        val t0 = 1000 * DAY_MS
        val rockAndIndependence = all.filter { (it.kind == ExerciseKind.GROOVE && it.style == "rock") || it.kind == ExerciseKind.INDEPENDENCE }
        val runs = rockAndIndependence.flatMap { e ->
            listOf(RunRecord(e.id, e.targetBpm, true, t0, 1, 8.0), RunRecord(e.id, e.targetBpm, true, t0 + DAY_MS, 2, 8.0))
        }
        val plan = SessionPlanner.plan(all, runs, emptyMap(), t0 + 2 * DAY_MS)
        val groove = plan.parts.first { it.kind == PartKind.GROOVE }.items.first()
        assertEquals("funk_1", groove.exerciseId)
    }
}
