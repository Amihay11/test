package com.amihay.drumtrainer.model

/** One note of the backing loop; mirrors backing::Note in BackingSynth.h. */
data class BackingNote(val startFrame: Int, val lengthFrames: Int, val midi: Int, val gain: Float, val instrument: Int) {
    companion object {
        const val BASS = 0
        const val PAD = 1
    }
}

data class BackingLoop(val notes: List<BackingNote>, val loopFrames: Int, val bars: Int)

/**
 * Bass and chords to play over in free play, one short progression per style family.
 * Bass positions are in beats within the bar; chords are held for the bar.
 */
object Backing {
    private data class Chord(val root: Int, val tones: List<Int>)

    private fun triad(root: Int, minor: Boolean = false) = Chord(root, listOf(0, if (minor) 3 else 4, 7))
    private fun seventh(root: Int, third: Int, seventh: Int) = Chord(root, listOf(0, third, 7, seventh))

    /** Chords (one per bar) and bass hits (beat position, semitones above the root). */
    private data class Style(val chords: List<Chord>, val bass: List<Pair<Double, Int>>, val walking: Boolean = false)

    private val styles = mapOf(
        // E - B - C#m - A, root eighths with an octave lift.
        "rock" to Style(
            listOf(triad(40), triad(47), triad(49, minor = true), triad(45)),
            (0 until 8).map { it * 0.5 to if (it == 3 || it == 7) 12 else 0 },
        ),
        // One-chord Em7 vamp, syncopated sixteenths.
        "funk" to Style(
            List(4) { seventh(40, 3, 10) },
            listOf(0.0 to 0, 0.75 to 0, 1.5 to 12, 2.0 to 0, 2.5 to 10, 3.5 to 12),
        ),
        // ii - V - I - VI in F, walking quarters.
        "jazz" to Style(
            listOf(seventh(43, 3, 10), seventh(36, 4, 10), seventh(41, 4, 11), seventh(38, 4, 10)),
            emptyList(), walking = true,
        ),
        // E7 - A7 - E7 - B7, shuffled root-fifth-sixth.
        "blues" to Style(
            listOf(seventh(40, 4, 10), seventh(45, 4, 10), seventh(40, 4, 10), seventh(47, 4, 10)),
            listOf(0.0 to 0, 0.667 to 0, 1.0 to 7, 1.667 to 7, 2.0 to 9, 2.667 to 9, 3.0 to 7, 3.667 to 7),
        ),
        // Bossa: Dm7 - G7 - Cmaj7 - A7, root on 1 and 3 with the fifth just before.
        "latin" to Style(
            listOf(seventh(38, 3, 10), seventh(43, 4, 10), seventh(36, 4, 11), seventh(45, 4, 10)),
            listOf(0.0 to 0, 1.5 to 7, 2.0 to 7, 3.5 to 0),
        ),
        // Hijaz on D: D - Eb - D - Cm, the bass following the dum strokes.
        "mideast" to Style(
            listOf(triad(38), triad(39), triad(38), triad(36, minor = true)),
            listOf(0.0 to 0, 0.5 to 0, 2.0 to 0),
        ),
        // Am - F - G - Am, roots on every beat.
        "electronic" to Style(
            listOf(triad(45, minor = true), triad(41), triad(43), triad(45, minor = true)),
            emptyList(),
        ),
    )

    fun styleOf(ex: Exercise): String = ex.style?.takeIf { it in styles } ?: "rock"

    /**
     * Four bars of backing for [ex] at [bpm]. Bass hits on the beat grid are moved by
     * swing exactly like the drum notes (second eighth of each pair delayed).
     */
    fun loop(ex: Exercise, bpm: Int, sampleRate: Int, swingPct: Int? = ex.swing): BackingLoop {
        val style = styles.getValue(styleOf(ex))
        val beat = sampleRate * 60.0 / bpm
        val beatsPerBar = ex.beatsPerBar
        val bar = beat * beatsPerBar
        val bars = style.chords.size
        val notes = ArrayList<BackingNote>()
        // Bass on group starts for odd meters, so it follows the 2+2+3 (or 3+2) feel.
        val groupBeats = ex.accentBeats.toList().sorted()
        val bassHits: List<Pair<Double, Int>> = when {
            style.walking -> emptyList()
            ex.beatGroups != null -> groupBeats.map { it.toDouble() to 0 }
            style.bass.isEmpty() -> (0 until beatsPerBar).map { it.toDouble() to 0 }
            else -> style.bass.filter { it.first < beatsPerBar }
        }
        style.chords.forEachIndexed { b, chord ->
            val barStart = b * bar
            val nextRoot = style.chords[(b + 1) % bars].root
            if (style.walking) {
                // Root, third, fifth, then a half-step approach to the next chord.
                val line = listOf(chord.root, chord.root + chord.tones[1], chord.root + 7, nextRoot - 1)
                for (q in 0 until beatsPerBar) {
                    notes += BackingNote((barStart + q * beat).toInt(), (beat * 0.9).toInt(), line[q % line.size], 0.35f, BackingNote.BASS)
                }
            } else {
                bassHits.forEachIndexed { i, (pos, interval) ->
                    val nextPos = bassHits.getOrNull(i + 1)?.first ?: beatsPerBar.toDouble()
                    val at = swungBeat(pos, swingPct)
                    val len = ((nextPos - pos) * beat * 0.85).coerceAtLeast(beat * 0.2)
                    notes += BackingNote((barStart + at * beat).toInt(), len.toInt(), chord.root + interval, 0.35f, BackingNote.BASS)
                }
            }
            // Chord held for the bar, an octave or two above the bass.
            for (t in chord.tones) {
                notes += BackingNote(barStart.toInt(), (bar * 0.97).toInt(), chord.root + 12 + t, 0.07f, BackingNote.PAD)
            }
        }
        return BackingLoop(notes, (bar * bars).toInt(), bars)
    }

    /** Off-beat eighths (x.5) land later under swing; other positions are unchanged. */
    private fun swungBeat(pos: Double, swingPct: Int?): Double {
        if (swingPct == null || swingPct == 50) return pos
        val frac = pos - Math.floor(pos)
        return if (Math.abs(frac - 0.5) < 1e-6) Math.floor(pos) + swingPct / 100.0 else pos
    }
}
