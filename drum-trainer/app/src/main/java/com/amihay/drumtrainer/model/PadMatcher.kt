package com.amihay.drumtrainer.model

import kotlin.math.abs

/**
 * Matching for multi-drum patterns. Each onset carries the drums the classifier heard
 * ([Onset.pads]); it can only fill notes of those drums, so a simultaneous kick + hi-hat
 * closes both notes, and a hit on the wrong drum leaves the note missed.
 */
object PadMatcher {

    fun match(
        notes: List<ExpectedNote>,
        onsets: List<Onset>,
        windowFrames: Double,
        sampleRate: Int,
        upToFrame: Double = Double.MAX_VALUE,
        byHand: Boolean = false,
    ): MatchResult {
        if (notes.isEmpty()) return MatchResult(emptyList(), emptyList(), 0)
        val assigned = arrayOfNulls<Onset>(notes.size)
        val extras = ArrayList<Onset>()
        val wrong = ArrayList<Onset>()
        val lo = notes.first().frame - windowFrames
        val hi = notes.last().frame + windowFrames
        // Notes are filled by drum, or (taps on the hand pads) by hand.
        val byKey: Map<Any?, List<Int>> = notes.indices.groupBy { if (byHand) notes[it].hand else notes[it].pad }

        fun nearestIn(candidates: List<Int>?, frame: Double): Int? =
            candidates?.filter { abs(notes[it].frame - frame) <= windowFrames }
                ?.minByOrNull { abs(notes[it].frame - frame) }

        fun claim(i: Int, onset: Onset) {
            val existing = assigned[i]
            if (existing == null) {
                assigned[i] = onset
            } else if (abs(onset.frame - notes[i].frame) < abs(existing.frame - notes[i].frame)) {
                assigned[i] = onset
                if (assigned.none { it === existing }) extras += existing
            } else if (assigned.none { it === onset }) {
                extras += onset
            }
        }

        for (onset in onsets.sortedBy { it.frame }) {
            if (onset.frame < lo || onset.frame > hi) continue  // count-in or after the round
            val keys: List<Any> = if (byHand) listOfNotNull(onset.hand) else onset.pads.orEmpty()
            if (keys.isEmpty()) {
                // Drum not recognised: judge the timing against the nearest open note.
                val i = notes.indices.filter { assigned[it] == null && abs(notes[it].frame - onset.frame) <= windowFrames }
                    .minByOrNull { abs(notes[it].frame - onset.frame) }
                if (i != null) assigned[i] = onset else extras += onset
                continue
            }
            var matched = false
            for (key in keys) {
                val i = nearestIn(byKey[key], onset.frame) ?: continue
                claim(i, onset)
                matched = true
            }
            if (!matched) {
                val nearSomeNote = notes.any { abs(it.frame - onset.frame) <= windowFrames }
                if (nearSomeNote) wrong += onset else extras += onset
            }
        }

        var resolved = 0
        val results = notes.mapIndexed { i, note ->
            val o = assigned[i]
            if (o != null || note.frame + windowFrames <= upToFrame) resolved++
            NoteResult(note, o, o?.let { (it.frame - note.frame) * 1000.0 / sampleRate })
        }
        return MatchResult(results, extras.sortedBy { it.frame }, resolved, wrong.sortedBy { it.frame })
    }

    /**
     * Timing-only view of a multi-drum pattern: notes that sound together become one
     * expected hit (used when the drums in the pattern have not all been learned).
     */
    fun timingNotes(notes: List<ExpectedNote>): List<ExpectedNote> =
        notes.groupBy { it.frame }.values.mapIndexed { i, group ->
            group.first().copy(
                index = i,
                pad = null,
                accent = group.any { it.accent },
                ghost = group.all { it.ghost },
            )
        }
}

/** Chooses between drum-aware and timing-only scoring for a round. */
object Scoring {
    /**
     * Drum-aware only when the exercise names drums and every one of them has been
     * learned; otherwise only the timing is judged.
     */
    fun padAware(ex: Exercise, learned: Set<Pad>): Boolean = ex.usesPads && learned.containsAll(ex.pads)

    /** The expected hits to score against: one per note, or one per grid position. */
    fun notes(plan: RoundPlan, padAware: Boolean): List<ExpectedNote> =
        if (padAware || plan.notes.none { it.pad != null }) plan.notes else PadMatcher.timingNotes(plan.notes)

    /** With [byHand] (taps on the hand pads), each note must be played with its own hand. */
    fun match(
        notes: List<ExpectedNote>, onsets: List<Onset>, plan: RoundPlan, padAware: Boolean,
        upToFrame: Double = Double.MAX_VALUE, byHand: Boolean = false,
    ): MatchResult {
        val mine = onsets.filter { plan.isPlayerTime(it.frame) }
        return when {
            byHand -> PadMatcher.match(notes, mine, plan.windowFrames, plan.sampleRate, upToFrame, byHand = true)
            padAware -> PadMatcher.match(notes, mine, plan.windowFrames, plan.sampleRate, upToFrame)
            else -> Matcher.match(notes, mine, plan.windowFrames, plan.sampleRate, upToFrame)
        }
    }

    /** Hand-aware scoring works when every note names a hand (rudiments). */
    fun handAware(notes: List<ExpectedNote>): Boolean = notes.isNotEmpty() && notes.all { it.hand == 'R' || it.hand == 'L' }
}
