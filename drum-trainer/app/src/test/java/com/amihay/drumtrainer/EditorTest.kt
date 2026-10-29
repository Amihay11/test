package com.amihay.drumtrainer

import com.amihay.drumtrainer.model.DraftKind
import com.amihay.drumtrainer.model.ExerciseDraft
import com.amihay.drumtrainer.model.ExerciseKind
import com.amihay.drumtrainer.model.ExerciseParser
import com.amihay.drumtrainer.model.Pad
import com.amihay.drumtrainer.model.RoundPlanner
import com.amihay.drumtrainer.model.isCustom
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditorTest {
    private fun parse(d: ExerciseDraft) = ExerciseParser.parse(JSONArray().put(d.toJson()).toString()).single()

    @Test
    fun tappingCyclesRestHitAccentGhost() {
        var d = ExerciseDraft.blank("custom_1")
        val seen = (0 until 4).map { d = d.tap(0, "snare", 2); d.cell(0, "snare", 2) }
        assertEquals(listOf('x', 'X', 'g', '-'), seen)
    }

    @Test
    fun stickingHasOneHandPerStepAndNoGhosts() {
        var d = ExerciseDraft.blank("custom_2").copy(kind = DraftKind.STICKING).normalized()
        d = d.tap(0, "R", 0)
        d = d.tap(0, "L", 0)
        assertEquals('-', d.cell(0, "R", 0))
        assertEquals('x', d.cell(0, "L", 0))
        d = d.tap(0, "L", 0).tap(0, "L", 0)  // accent, then back to rest (no ghost)
        assertEquals('-', d.cell(0, "L", 0))
    }

    @Test
    fun grooveRoundTripsThroughTheExerciseFormat() {
        var d = ExerciseDraft.blank("custom_3").copy(name = "הגרוב שלי", swing = 62)
        for (s in 0 until 8) d = d.tap(0, "hihat", s)
        d = d.tap(0, "snare", 2).tap(0, "snare", 2)  // accent on 2
        d = d.tap(0, "snare", 6).tap(0, "snare", 6).tap(0, "snare", 6)  // ghost on 4
        d = d.tap(0, "kick", 0)
        assertTrue(d.problems().isEmpty())

        val ex = parse(d)
        assertTrue(ex.isCustom)
        assertEquals(ExerciseKind.GROOVE, ex.kind)
        assertEquals("הגרוב שלי", ex.nameHe)
        assertEquals(62, ex.swing)
        assertEquals(setOf(Pad.HIHAT, Pad.SNARE, Pad.KICK), ex.pads)
        assertTrue(ex.steps[2].first { it.pad == Pad.SNARE }.accent)
        assertTrue(ex.steps[6].first { it.pad == Pad.SNARE }.ghost)
        // Editing the saved exercise gives the same grid back.
        assertEquals(d.bars, ExerciseDraft.from(ex).copy(pads = d.pads).normalized().bars)
    }

    @Test
    fun fillBarMakesAFillLoopWithALanding() {
        var d = ExerciseDraft.blank("custom_4").copy(name = "מילוי", bars = List(2) { com.amihay.drumtrainer.model.DraftBar(emptyMap()) })
            .normalized()
        d = d.tap(0, "kick", 0).tap(0, "snare", 4)
        d = d.copy(bars = d.bars.toMutableList().also { it[1] = it[1].copy(fill = true) })
        for (s in 4 until 8) d = d.tap(1, "snare", s)
        val ex = parse(d)
        assertEquals(ExerciseKind.FILL, ex.kind)
        assertEquals(setOf(1), ex.fillBars)
        val plan = RoundPlanner.forExercise(ex, 90, ex.roundBars(2), 0, 48000)
        assertTrue(plan.notes.any { it.landing })
    }

    @Test
    fun stickingBecomesARudimentPattern() {
        var d = ExerciseDraft.blank("custom_5").copy(name = "פרדידל שלי", kind = DraftKind.STICKING, subdivision = 4).normalized()
        "RLRRLRLL".forEachIndexed { i, h -> d = d.tap(0, h.toString(), i) }
        d = d.tap(0, "R", 0)  // accent the first stroke
        val ex = parse(d)
        assertEquals(ExerciseKind.RUDIMENT, ex.kind)
        assertFalse(ex.usesPads)
        assertEquals("RLRRLRLL", ex.steps.take(8).joinToString("") { it.single().hand.toString() })
        assertTrue(ex.steps[0].single().accent)
        assertEquals(d.bars, ExerciseDraft.from(ex).bars)
    }

    @Test
    fun oddMeterWithGroups() {
        val groups = ExerciseDraft.parseGroups("2 + 2 + 3")
        assertEquals(listOf(2, 2, 3), groups)
        assertNull(ExerciseDraft.parseGroups("2+x"))
        var d = ExerciseDraft.blank("custom_6").copy(name = "7/8", beatsPerBar = 7, subdivision = 1, beatUnit = 8, beatGroups = groups)
            .normalized()
        d = d.tap(0, "kick", 0)
        val ex = parse(d)
        assertEquals(setOf(0, 2, 4), ex.accentBeats)
        assertEquals(8, ex.beatUnit)
        assertTrue(d.copy(beatGroups = listOf(3, 3)).problems().any { it.contains("לא מסתכמות") })
    }

    @Test
    fun problemsAreReported() {
        val d = ExerciseDraft.blank("custom_7")
        val p = d.problems()
        assertTrue(p.contains("חסר שם"))
        assertTrue(p.contains("אין אף מכה"))
    }

    @Test
    fun builtInExercisesOpenInTheEditorAndSaveBackTheSame() {
        val all = listOf("rudiments", "grooves", "styles").flatMap { ExerciseParser.parse(File("src/main/assets/exercises/$it.json").readText()) }
        for (ex in all) {
            val again = parse(ExerciseDraft.from(ex))
            assertEquals(ex.id, ex.steps.size.let { n -> (0 until n).map { ex.strokesAt(it).toSet() } },
                (0 until ex.steps.size).map { again.strokesAt(it).toSet() })
            assertEquals(ex.id, ex.fillBars, again.fillBars)
        }
    }
}

class CustomExerciseStoreTest {
    @Test
    fun savesUpdatesAndDeletes() {
        val dir = java.nio.file.Files.createTempDirectory("custom").toFile()
        val store = com.amihay.drumtrainer.data.CustomExerciseStore(File(dir, "custom.json"))
        assertTrue(store.load().isEmpty())

        val a = ExerciseDraft.blank("custom_a").copy(name = "א").tap(0, "kick", 0)
        val b = ExerciseDraft.blank("custom_b").copy(name = "ב").tap(0, "snare", 2)
        store.upsert(a.toJson())
        store.upsert(b.toJson())
        store.upsert(a.copy(name = "א2").toJson())  // update keeps one entry
        assertEquals(listOf("א2", "ב"), store.load().map { it.nameHe })

        store.delete("custom_a")
        assertEquals(listOf("custom_b"), store.load().map { it.id })
    }

    @Test
    fun aBrokenEntryDoesNotHideTheOthers() {
        val dir = java.nio.file.Files.createTempDirectory("custom").toFile()
        val f = File(dir, "custom.json")
        val good = ExerciseDraft.blank("custom_ok").copy(name = "טוב").tap(0, "kick", 0).toJson()
        f.writeText(JSONArray().put(org.json.JSONObject().put("id", "custom_bad")).put(good).toString())
        assertEquals(listOf("custom_ok"), com.amihay.drumtrainer.data.CustomExerciseStore(f).load().map { it.id })
    }
}
