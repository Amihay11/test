package com.amihay.drumtrainer

import com.amihay.drumtrainer.model.ClickType
import com.amihay.drumtrainer.model.ExerciseKind
import com.amihay.drumtrainer.model.ExerciseParser
import com.amihay.drumtrainer.model.Onset
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.PadMatcher
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.Scoring
import com.amihay.drumtrainer.model.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GrooveTest {
    private val sr = 48000
    private val all = ExerciseParser.parse(File("src/main/assets/exercises/grooves.json").readText())
    private fun ex(id: String) = all.first { it.id == id }

    @Test
    fun parsesAllGroovesAndFills() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.none { it.kind == ExerciseKind.RUDIMENT })
        for (e in all) {
            assertTrue(e.id, e.usesPads)
            assertEquals(e.id, 0, e.steps.size % e.stepsPerBar)
            if (e.kind == ExerciseKind.FILL) {
                assertEquals(e.id, setOf(3), e.fillBars)
                // Every fill loop lands on crash + kick.
                assertEquals(e.id, setOf(Pad.CRASH, Pad.KICK), e.steps[0].map { it.pad }.toSet())
            }
        }
        val rock = ex("rock_1")
        assertEquals(setOf(Pad.HIHAT, Pad.SNARE, Pad.KICK), rock.pads)
        assertEquals(setOf(Pad.HIHAT, Pad.KICK), rock.steps[0].map { it.pad }.toSet())
        assertTrue(ex("rock_5").steps.flatten().any { it.ghost })
    }

    @Test
    fun fillRoundsUseWholeCyclesAndEndOnALanding() {
        val fill = ex("fill_snare_half")
        assertEquals(4, fill.roundBars(2))
        assertEquals(8, fill.roundBars(5))
        assertEquals(3, ex("rock_1").roundBars(3))

        val plan = RoundPlanner.forExercise(fill, 100, fill.roundBars(4), 0, sr)
        val landings = plan.notes.filter { it.landing }
        // One landing after the fill, played on the downbeat after the last bar.
        assertEquals(setOf(Pad.CRASH, Pad.KICK), landings.map { it.pad }.toSet())
        assertEquals(plan.endFrame, landings.first().frame, 1e-6)
        assertTrue(plan.notes.filter { it.bar == 3 }.all { it.inFill })
        assertTrue(plan.notes.filter { it.bar < 3 }.none { it.inFill })
        assertEquals(plan.endFrame.toLong(), plan.clicks.last().frame)
    }

    @Test
    fun comboHitClosesBothNotesAndWrongDrumIsCounted() {
        val plan = RoundPlanner.forExercise(ex("rock_1"), 100, 1, 0, sr)
        val byFrame = plan.notes.groupBy { it.frame }
        val onsets = byFrame.entries.mapIndexed { i, (frame, notes) ->
            val pads = notes.map { it.pad!! }
            // Step 2 (snare + hi-hat on beat 2): the player hits a tom instead of the snare.
            if (i == 2) Onset(frame, 0.5f, pads = listOf(Pad.TOM_HIGH)) else Onset(frame + plan.msToFrames(4.0), 0.5f, pads = pads)
        }
        val m = PadMatcher.match(plan.notes, onsets, plan.windowFrames, sr)
        val s = Stats.compute(m, 2)
        assertEquals(plan.notes.size - 2, s.hits)  // hi-hat and snare on beat 2 missed
        assertEquals(1, s.wrongDrum)
        assertEquals(0, s.extras)
        assertEquals(4.0, s.meanMs, 1e-6)
        assertEquals(setOf(Pad.HIHAT, Pad.SNARE, Pad.KICK), s.perPad.keys)
        assertFalse(Stats.passes(s, 20.0, 90.0))
    }

    @Test
    fun unrecognisedHitStillCountsForTiming() {
        val plan = RoundPlanner.forExercise(ex("ind_kick_quarters"), 100, 1, 0, sr)
        val first = plan.notes.first { it.pad == Pad.KICK }
        val m = PadMatcher.match(plan.notes, listOf(Onset(first.frame + plan.msToFrames(5.0), 0.5f, pads = emptyList())),
            plan.windowFrames, sr)
        assertEquals(1, m.notes.count { it.hit })
        assertEquals(5.0, m.notes.first { it.hit }.deviationMs!!, 1e-6)
    }

    @Test
    fun timingOnlyModeMergesSimultaneousNotes() {
        val plan = RoundPlanner.forExercise(ex("rock_1"), 100, 1, 0, sr)
        val merged = PadMatcher.timingNotes(plan.notes)
        assertEquals(8, merged.size)  // one hit per 8th note
        assertEquals(merged.indices.toList(), merged.map { it.index })
        assertTrue(merged.all { it.pad == null })
    }

    @Test
    fun rushedLandingFailsAFill() {
        val fill = ex("fill_snare_half")
        val plan = RoundPlanner.forExercise(fill, 100, 4, 0, sr)
        val onsets = plan.notes.groupBy { it.frame }.map { (frame, notes) ->
            val early = if (notes.first().landing) -40.0 else 0.0  // rushes into the downbeat
            Onset(frame + plan.msToFrames(early), 0.5f, pads = notes.map { it.pad!! })
        }
        val s = Stats.compute(PadMatcher.match(plan.notes, onsets, plan.windowFrames, sr), fill.subdivision)
        assertEquals(-40.0, s.landing.meanMs, 1e-6)
        assertEquals(0.0, s.groove.meanMs, 1e-6)
        assertFalse(Stats.passes(s, 20.0, 90.0))
    }
}

class PlaybackTest {
    private val sr = 48000
    private val all = ExerciseParser.parse(File("src/main/assets/exercises/grooves.json").readText())

    @Test
    fun previewPlaysEveryNoteOnItsDrum() {
        val ex = all.first { it.id == "rock_5" }
        val p = RoundPlanner.preview(ex, 90, 2, 0, sr)
        val drums = p.clicks.filter { it.type >= ClickType.pad(Pad.KICK) }
        assertEquals(p.notes.size, drums.size)
        assertEquals(p.notes.count { it.pad == Pad.KICK }, drums.count { it.type == ClickType.pad(Pad.KICK) })
        // Ghost notes are played softly, accents loudly.
        val ghostGain = drums.filter { it.type == ClickType.pad(Pad.SNARE) }.minOf { it.gain }
        val accentGain = drums.filter { it.type == ClickType.pad(Pad.SNARE) }.maxOf { it.gain }
        assertTrue(ghostGain < 0.5f && accentGain >= 1f)
        assertEquals(p.clicks.sortedBy { it.frame }, p.clicks)
    }

    @Test
    fun callResponseScoresOnlyThePlayersTurns() {
        val fill = all.first { it.id == "fill_snare_half" }
        val p = RoundPlanner.forCallResponse(fill, 100, 2, 0, sr)
        assertEquals(16, p.bars)
        assertEquals(setOf(0, 1, 2, 3, 8, 9, 10, 11), p.listenBars)
        assertTrue(p.notes.none { it.bar in p.listenBars })
        assertEquals(p.notes.indices.toList(), p.notes.map { it.index })
        // The bar after the player's fill is the app's turn, so no landing is scored.
        assertTrue(p.notes.none { it.landing })
        // The app plays drums only in its own bars.
        val beatsPerBar = p.beatsPerBar * p.framesPerBeat
        val drumBars = p.clicks.filter { it.type >= ClickType.pad(Pad.KICK) }
            .map { ((it.frame - p.firstNoteFrame) / beatsPerBar).toInt() }.toSet()
        assertEquals(p.listenBars, drumBars)
    }

    @Test
    fun appsOwnDrumsAreNotScored() {
        val ex = all.first { it.id == "rock_1" }
        val p = RoundPlanner.forCallResponse(ex, 100, 2, 0, sr)
        // The microphone hears the app (its notes) and a perfect player.
        val appOnsets = RoundPlanner.drumHits(RoundPlanner.forExercise(ex, 100, p.bars, 0, sr).notes)
            .map { Onset(it.frame.toDouble(), 0.5f) }.distinctBy { it.frame }
        val m = Scoring.match(Scoring.notes(p, false), appOnsets, p, padAware = false)
        val s = Stats.compute(m, ex.subdivision)
        assertEquals(s.expected, s.hits)
        assertEquals(0, s.extras)
    }
}
