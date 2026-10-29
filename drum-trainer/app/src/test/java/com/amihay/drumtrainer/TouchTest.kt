package com.amihay.drumtrainer

import com.amihay.drumtrainer.model.ClickType
import com.amihay.drumtrainer.model.ExerciseParser
import com.amihay.drumtrainer.model.KitLayout
import com.amihay.drumtrainer.model.Onset
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.Scoring
import com.amihay.drumtrainer.model.appLights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class TouchTest {
    private val sr = 48000
    private val rudiments = ExerciseParser.parse(File("src/main/assets/exercises/rudiments.json").readText())
    private val grooves = ExerciseParser.parse(File("src/main/assets/exercises/grooves.json").readText())

    @Test
    fun everyPieceIsHitAtItsCentreAndPiecesDoNotOverlap() {
        for (p in KitLayout.pieces) assertEquals(p.pad, KitLayout.hitTest(p.x, p.y))
        for (a in KitLayout.pieces) for (b in KitLayout.pieces) {
            if (a !== b) assertTrue("${a.pad} / ${b.pad}", hypot(a.x - b.x, a.y - b.y) >= a.r + b.r)
        }
        assertEquals(Pad.entries.toSet(), KitLayout.pieces.map { it.pad }.toSet())
        for (p in KitLayout.pieces) {
            assertTrue(p.x - p.r >= 0 && p.x + p.r <= KitLayout.WIDTH && p.y - p.r >= 0 && p.y + p.r <= KitLayout.HEIGHT)
        }
        // A tap just outside the snare still plays it; empty space plays nothing.
        val snare = KitLayout.pieces.first { it.pad == Pad.SNARE }
        assertEquals(Pad.SNARE, KitLayout.hitTest(snare.x - snare.r * 1.1f, snare.y))
        assertNull(KitLayout.hitTest(KitLayout.WIDTH - 1f, KitLayout.HEIGHT - 1f))
    }

    @Test
    fun theKitLightsWhatTheAppPlays() {
        val ex = grooves.first { it.id == "ind_kick_quarters" }
        val p = RoundPlanner.preview(ex, 100, 1, 0, sr)
        val kick = p.notes.first { it.pad == Pad.KICK }
        val at = appLights(p.clicks, kick.frame + 10, sr)
        assertTrue(at.getValue(Pad.KICK.id) > 0.8f)
        // Metronome clicks light nothing, and a note fades out after its light time.
        assertTrue(appLights(p.clicks, kick.frame + sr * 0.3, sr)[Pad.KICK.id] == null)
        assertTrue(p.clicks.filter { ClickType.padOf(it.type) == null }.all { appLights(listOf(it), it.frame + 10.0, sr).isEmpty() })
    }

    @Test
    fun rudimentsLightTheHandThatPlays() {
        val ex = rudiments.first { it.id == "single_paradiddle" }
        val p = RoundPlanner.preview(ex, 80, 1, 0, sr)
        val first = p.notes.first()
        val lit = appLights(p.clicks, first.frame + 10, sr, byHand = true)
        assertEquals(setOf(first.hand.toString()), lit.keys)
    }

    @Test
    fun handPadsJudgeTheHand() {
        val ex = rudiments.first { it.id == "single_paradiddle" }  // R L R R  L R L L
        val p = RoundPlanner.forExercise(ex, 80, 1, 0, sr)
        val notes = Scoring.notes(p, padAware = false)
        assertTrue(Scoring.handAware(notes))
        // Every stroke on time, but the third one with the wrong hand.
        val taps = notes.mapIndexed { i, n ->
            val hand = if (i == 2) (if (n.hand == 'R') 'L' else 'R') else n.hand
            Onset(n.frame + 48.0, 1f, hand = hand)
        }
        val m = Scoring.match(notes, taps, p, padAware = false, byHand = true)
        assertEquals(notes.size - 1, m.notes.count { it.hit })
        assertTrue(!m.notes[2].hit)
        assertEquals(1, m.wrongDrum.size)
        // Without hand judging the same taps are all hits.
        assertEquals(notes.size, Scoring.match(notes, taps, p, padAware = false).notes.count { it.hit })
    }

    @Test
    fun kitTapsKnowTheirDrum() {
        val ex = grooves.first { it.id == "ind_kick_quarters" }
        val p = RoundPlanner.forExercise(ex, 90, 1, 0, sr)
        val notes = Scoring.notes(p, padAware = true)
        val taps = notes.map { Onset(it.frame, 1f, pads = listOf(it.pad!!)) }
        val m = Scoring.match(notes, taps, p, padAware = true)
        assertEquals(notes.size, m.notes.count { it.hit })
        assertTrue(m.wrongDrum.isEmpty() && m.extras.isEmpty())
    }
}
