package com.amihay.drumtrainer.model

import org.json.JSONArray
import org.json.JSONObject

/** Exercises written in the editor have ids with this prefix. */
const val CUSTOM_PREFIX = "custom_"

val Exercise.isCustom: Boolean get() = id.startsWith(CUSTOM_PREFIX)

enum class DraftKind { GROOVE, STICKING }

/**
 * One bar in the editor: for each row (a drum id, or "R"/"L" for sticking) one token
 * per step: '-' rest, 'x' hit, 'X' accent, 'g' ghost note.
 */
data class DraftBar(val rows: Map<String, List<Char>>, val fill: Boolean = false)

/** An exercise being edited. Saved in the same JSON format as the built-in ones. */
data class ExerciseDraft(
    val id: String,
    val name: String,
    val kind: DraftKind,
    val beatsPerBar: Int,
    val subdivision: Int,
    /** 4 = the beat is a quarter; 8 = an eighth (x/8 meters). */
    val beatUnit: Int,
    val beatGroups: List<Int>?,
    /** Drums in the grid (GROOVE), top to bottom. */
    val pads: List<Pad>,
    val bars: List<DraftBar>,
    val startBpm: Int,
    val targetBpm: Int,
    val swing: Int?,
) {
    val stepsPerBar: Int get() = beatsPerBar * subdivision

    /** Row keys shown in the grid. */
    val rowKeys: List<String> get() = if (kind == DraftKind.STICKING) listOf("R", "L") else pads.map { it.id }

    fun cell(bar: Int, row: String, step: Int): Char = bars.getOrNull(bar)?.rows?.get(row)?.getOrNull(step) ?: '-'

    /**
     * Tapping a cell cycles it: rest → hit → accent → ghost → rest. In a sticking grid
     * a step has one hand only, so setting R clears L (and the other way round), and
     * there are no ghost notes.
     */
    fun tap(bar: Int, row: String, step: Int): ExerciseDraft {
        val next = when (cell(bar, row, step)) {
            '-' -> 'x'
            'x' -> 'X'
            'X' -> if (kind == DraftKind.STICKING) '-' else 'g'
            else -> '-'
        }
        return set(bar, row, step, next)
    }

    fun set(bar: Int, row: String, step: Int, value: Char): ExerciseDraft {
        val b = bars[bar]
        val rows = rowKeys.associateWith { key -> (b.rows[key] ?: emptyList()).padEnd(stepsPerBar).toMutableList() }
        rows.getValue(row)[step] = value
        if (kind == DraftKind.STICKING && value != '-') rows.getValue(if (row == "R") "L" else "R")[step] = '-'
        return copy(bars = bars.toMutableList().also { it[bar] = b.copy(rows = rows) })
    }

    /** Re-shapes every bar after a change of meter, rows or bar count, keeping what fits. */
    fun normalized(): ExerciseDraft {
        val shaped = List(bars.size.coerceAtLeast(1)) { i ->
            val b = bars.getOrNull(i) ?: DraftBar(emptyMap())
            DraftBar(rowKeys.associateWith { key -> (b.rows[key] ?: emptyList()).padEnd(stepsPerBar) }, b.fill)
        }
        return copy(bars = shaped)
    }

    private fun List<Char>.padEnd(n: Int): List<Char> = List(n) { getOrNull(it) ?: '-' }

    /** What is wrong with the draft, in Hebrew; empty when it can be saved. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (name.isBlank()) out += "חסר שם"
        if (bars.none { b -> b.rows.values.any { r -> r.any { it != '-' } } }) out += "אין אף מכה"
        if (beatGroups != null && beatGroups.sum() != beatsPerBar) out += "הקבוצות (${beatGroups.joinToString("+")}) לא מסתכמות ב-$beatsPerBar"
        if (kind == DraftKind.GROOVE && pads.isEmpty()) out += "לא נבחרו תופים"
        if (startBpm > targetBpm) out += "טמפו ההתחלה גבוה מהיעד"
        if (bars.all { it.fill }) out += "צריך לפחות תיבה אחת שאינה מילוי"
        return out
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("nameHe", name)
            .put("subdivision", subdivision)
            .put("beatsPerBar", beatsPerBar)
            .put("startBpm", startBpm)
            .put("targetBpm", targetBpm)
        if (beatUnit != 4) o.put("beatUnit", beatUnit)
        beatGroups?.let { o.put("beatGroups", JSONArray(it)) }
        swing?.let { o.put("swing", it) }
        if (kind == DraftKind.STICKING) {
            o.put("kind", "rudiment")
            val tokens = bars.flatMap { b ->
                (0 until stepsPerBar).map { s ->
                    val r = b.rows["R"]?.getOrNull(s) ?: '-'
                    val l = b.rows["L"]?.getOrNull(s) ?: '-'
                    when {
                        r == 'X' -> "^R"
                        r != '-' -> "R"
                        l == 'X' -> "^L"
                        l != '-' -> "L"
                        else -> "-"
                    }
                }
            }
            o.put("pattern", tokens.joinToString(" "))
        } else {
            o.put("kind", if (bars.any { it.fill }) "fill" else "groove")
            val arr = JSONArray()
            for (b in bars) {
                val bo = JSONObject()
                if (b.fill) bo.put("fill", true)
                for (pad in pads) {
                    val row = b.rows[pad.id] ?: continue
                    if (row.all { it == '-' }) continue
                    bo.put(pad.id, row.joinToString(" "))
                }
                arr.put(bo)
            }
            o.put("bars", arr)
        }
        return o
    }

    companion object {
        fun blank(id: String) = ExerciseDraft(
            id = id, name = "", kind = DraftKind.GROOVE, beatsPerBar = 4, subdivision = 2, beatUnit = 4,
            beatGroups = null, pads = listOf(Pad.HIHAT, Pad.SNARE, Pad.KICK), bars = listOf(DraftBar(emptyMap())),
            startBpm = 70, targetBpm = 110, swing = null,
        ).normalized()

        /** Opens a saved exercise for editing. */
        fun from(ex: Exercise): ExerciseDraft {
            val sticking = !ex.usesPads
            val barCount = ex.cycleBars
            val pads = ex.pads.sortedBy { it.ordinal }
            val bars = (0 until barCount).map { b ->
                val rows = HashMap<String, List<Char>>()
                val keys = if (sticking) listOf("R", "L") else pads.map { it.id }
                for (key in keys) {
                    rows[key] = (0 until ex.stepsPerBar).map { s ->
                        val step = b * ex.stepsPerBar + s
                        val stroke = if (step < ex.steps.size) {
                            ex.steps[step].firstOrNull { if (sticking) it.hand.toString() == key else it.pad?.id == key }
                        } else null
                        when {
                            stroke == null -> '-'
                            stroke.accent -> 'X'
                            stroke.ghost -> 'g'
                            else -> 'x'
                        }
                    }
                }
                DraftBar(rows, ex.isFillBar(b))
            }
            return ExerciseDraft(
                id = ex.id, name = ex.nameHe, kind = if (sticking) DraftKind.STICKING else DraftKind.GROOVE,
                beatsPerBar = ex.beatsPerBar, subdivision = ex.subdivision, beatUnit = ex.beatUnit,
                beatGroups = ex.beatGroups, pads = pads, bars = bars,
                startBpm = ex.startBpm, targetBpm = ex.targetBpm, swing = ex.swing,
            )
        }

        /** "2+2+3" → [2, 2, 3]; null for blank or malformed text. */
        fun parseGroups(text: String): List<Int>? =
            text.split('+').map { it.trim() }.takeIf { it.all { p -> p.toIntOrNull()?.let { n -> n > 0 } == true } }
                ?.map { it.toInt() }?.takeIf { it.size > 1 }
    }
}
