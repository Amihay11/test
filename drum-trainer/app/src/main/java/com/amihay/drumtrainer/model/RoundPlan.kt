package com.amihay.drumtrainer.model

/** Sound types; must match ClickType in DrumEngine.h. */
object ClickType {
    const val NORMAL = 0
    const val ACCENT = 1
    const val BROADBAND = 2
    private const val PAD_BASE = 3
    private const val GUIDE_BASE = 11

    /** Synthesized drum sound for [pad] (see DrumKitSounds.h; follows the Pad order). */
    fun pad(pad: Pad) = PAD_BASE + pad.ordinal

    /**
     * Tonal guide sound for [pad], low to high: kick, snare and toms, hi-hat and ride,
     * crash (see GuideTones.h). The onset detector filters these out of the microphone.
     */
    fun guide(pad: Pad?) = GUIDE_BASE + when (pad) {
        Pad.KICK -> 0
        Pad.HIHAT, Pad.RIDE -> 2
        Pad.CRASH -> 3
        else -> 1
    }

    /** The free-play backing loop (see AudioEngine.setBacking). */
    const val BACKING = 15

    /** The drum a [pad] sound type plays, or null for any other sound. */
    fun padOf(type: Int): Pad? = Pad.entries.getOrNull(type - PAD_BASE)?.takeIf { type < GUIDE_BASE }

    fun isAppSound(type: Int) = type >= PAD_BASE
}

/**
 * A sound the engine plays at [frame]: a metronome click or a drum. [hand] is the hand
 * of a stroke the app plays (to light it on the screen); the engine ignores it.
 */
data class Click(val frame: Long, val type: Int, val gain: Float = 1f, val hand: Char? = null)

data class ExpectedNote(
    val index: Int,
    /** Output-clock frame at which the note should sound. */
    val frame: Double,
    val hand: Char,
    val accent: Boolean,
    val bar: Int,
    val stepInBar: Int,
    /** Whether the metronome was audible during this note's bar. */
    val clickAudible: Boolean = true,
    /** Drum to play; null when any pad will do (rudiments, gap metronome). */
    val pad: Pad? = null,
    val ghost: Boolean = false,
    /** Part of a fill bar. */
    val inFill: Boolean = false,
    /** The downbeat right after a fill: where beginners tend to rush. */
    val landing: Boolean = false,
)

data class RoundPlan(
    val sampleRate: Int,
    val bpm: Double,
    val startFrame: Long,
    val framesPerBeat: Double,
    val framesPerStep: Double,
    val beatsPerBar: Int,
    val stepsPerBar: Int,
    val countInBeats: Int,
    val bars: Int,
    val notes: List<ExpectedNote>,
    val clicks: List<Click>,
    /** For each bar after the count-in: is the metronome audible? */
    val clickBars: List<Boolean>,
    /** "Repeat after me": bars the app plays while the player listens (not scored). */
    val listenBars: Set<Int> = emptySet(),
) {
    val firstNoteFrame: Double get() = startFrame + countInBeats * framesPerBeat
    val endFrame: Double get() = firstNoteFrame + bars * beatsPerBar * framesPerBeat

    /** Acceptance window: a quarter of a grid step on either side (see spec). */
    val windowFrames: Double get() = framesPerStep / 4.0

    fun msToFrames(ms: Double) = ms * sampleRate / 1000.0
    fun framesToMs(frames: Double) = frames * 1000.0 / sampleRate

    /** Position in the round, in beats from the first note (negative during count-in). */
    fun beatAt(frame: Double): Double = (frame - firstNoteFrame) / framesPerBeat

    private val framesPerBar: Double get() = beatsPerBar * framesPerBeat

    /**
     * Whether a hit at [frame] belongs to the player. In "repeat after me" rounds the
     * microphone also hears the app's own drums; those hits fall in listen bars and are
     * dropped. A hit may be up to one window early for a bar, but the first note of the
     * next listen bar (which the app plays) is never counted.
     */
    fun isPlayerTime(frame: Double): Boolean {
        if (listenBars.isEmpty()) return true
        return (0 until bars).any { b ->
            b !in listenBars &&
                frame >= firstNoteFrame + b * framesPerBar - windowFrames &&
                frame < firstNoteFrame + (b + 1) * framesPerBar - windowFrames
        }
    }
}

object RoundPlanner {

    /**
     * Frames from the first note to grid step [step]. With swing, the second step of each
     * pair is delayed: it starts [swingPct] % of the way through the pair instead of 50 %.
     */
    fun stepOffset(step: Int, framesPerStep: Double, swingPct: Int?): Double {
        if (swingPct == null || swingPct == 50 || step % 2 == 0) return step * framesPerStep
        return (step - 1 + 2 * swingPct / 100.0) * framesPerStep
    }

    fun forExercise(
        ex: Exercise, bpm: Int, bars: Int, startFrame: Long, sampleRate: Int, endOnLanding: Boolean = true,
        swingPct: Int? = ex.swing,
    ): RoundPlan {
        val framesPerBeat = sampleRate * 60.0 / bpm
        val framesPerStep = framesPerBeat / ex.subdivision
        val countIn = ex.beatsPerBar
        val first = startFrame + countIn * framesPerBeat
        val notes = ArrayList<ExpectedNote>()
        // Fill exercises end on the downbeat after the last fill, so every fill has a landing.
        val endsOnLanding = endOnLanding && ex.kind == ExerciseKind.FILL && ex.isFillBar(bars - 1)
        val lastStep = bars * ex.stepsPerBar + (if (endsOnLanding) 1 else 0)
        for (step in 0 until lastStep) {
            val bar = step / ex.stepsPerBar
            val stepInBar = step % ex.stepsPerBar
            val landing = stepInBar == 0 && bar > 0 && ex.isFillBar(bar - 1)
            for (stroke in ex.strokesAt(step)) {
                notes += ExpectedNote(
                    index = notes.size,
                    frame = first + stepOffset(step, framesPerStep, swingPct),
                    hand = stroke.hand,
                    accent = stroke.accent,
                    bar = bar,
                    stepInBar = stepInBar,
                    pad = stroke.pad,
                    ghost = stroke.ghost,
                    inFill = ex.isFillBar(bar) && bar < bars,
                    landing = landing,
                )
            }
        }
        val clickBars = List(bars) { true }
        val clicks = clicks(startFrame, framesPerBeat, ex.beatsPerBar, countIn, clickBars, ex.accentBeats).toMutableList()
        if (endsOnLanding) clicks += Click((first + bars * ex.beatsPerBar * framesPerBeat).toLong(), ClickType.ACCENT)
        return RoundPlan(
            sampleRate, bpm.toDouble(), startFrame, framesPerBeat, framesPerStep, ex.beatsPerBar,
            ex.stepsPerBar, countIn, bars, notes, clicks, clickBars,
        )
    }

    /** How hard the app plays a note back: accents loud, ghost notes soft. */
    private fun noteGain(n: ExpectedNote) = when {
        n.accent -> 1.0f
        n.ghost -> 0.25f
        else -> 0.65f
    }

    /** The app's own drum hits for [notes] (single-pad rudiments are played on the snare). */
    fun drumHits(notes: List<ExpectedNote>): List<Click> =
        notes.map { Click(it.frame.toLong(), ClickType.pad(it.pad ?: Pad.SNARE), noteGain(it), it.hand) }

    /**
     * Playing along: the app plays the whole pattern together with the player, and every
     * bar is scored. Through the phone speaker it plays guide tones, which the microphone
     * filters out, so only the player's drums are measured; with wired headphones the
     * microphone cannot hear it, and it plays real drum sounds. The metronome sounds only
     * the count-in: the pattern itself keeps the time.
     */
    fun forTogether(
        ex: Exercise, bpm: Int, bars: Int, startFrame: Long, sampleRate: Int, realDrums: Boolean, swingPct: Int? = ex.swing,
    ): RoundPlan {
        val p = forExercise(ex, bpm, bars, startFrame, sampleRate, swingPct = swingPct)
        val countIn = p.clicks.filter { it.frame < p.firstNoteFrame - 1 }
        val app = if (realDrums) drumHits(p.notes) else p.notes.map { Click(it.frame.toLong(), ClickType.guide(it.pad), noteGain(it)) }
        return p.copy(clicks = (countIn + app).sortedBy { it.frame })
    }

    /** The exercise played by the app with the metronome, for listening before playing. */
    fun preview(ex: Exercise, bpm: Int, bars: Int, startFrame: Long, sampleRate: Int, swingPct: Int? = ex.swing): RoundPlan {
        val p = forExercise(ex, bpm, bars, startFrame, sampleRate, swingPct = swingPct)
        return p.copy(clicks = (p.clicks + drumHits(p.notes)).sortedBy { it.frame })
    }

    /**
     * "Repeat after me": the app plays one cycle of the pattern, the player plays it back,
     * [reps] times. Only the player's bars are scored. Fills have no landing here: the bar
     * after the player's fill is the app's turn again.
     */
    fun forCallResponse(
        ex: Exercise, bpm: Int, reps: Int, startFrame: Long, sampleRate: Int, swingPct: Int? = ex.swing,
    ): RoundPlan {
        val cycle = ex.cycleBars
        val bars = reps * 2 * cycle
        val full = forExercise(ex, bpm, bars, startFrame, sampleRate, endOnLanding = false, swingPct = swingPct)
        val listen = (0 until bars).filter { (it / cycle) % 2 == 0 }.toSet()
        val appNotes = full.notes.filter { it.bar in listen }
        val playerNotes = full.notes.filter { it.bar !in listen }
            .mapIndexed { i, n -> n.copy(index = i, landing = n.landing && (n.bar - 1) !in listen) }
        return full.copy(
            notes = playerNotes,
            clicks = (full.clicks + drumHits(appNotes)).sortedBy { it.frame },
            listenBars = listen,
        )
    }

    /**
     * Gap metronome: the click plays for [onBars] bars and is silent for [offBars] bars,
     * repeated [cycles] times. The player keeps playing [stepsPerBeat] notes per beat.
     */
    fun forGap(
        bpm: Int, stepsPerBeat: Int, onBars: Int, offBars: Int, cycles: Int,
        startFrame: Long, sampleRate: Int, beatsPerBar: Int = 4,
    ): RoundPlan {
        val framesPerBeat = sampleRate * 60.0 / bpm
        val framesPerStep = framesPerBeat / stepsPerBeat
        val bars = (onBars + offBars) * cycles
        val clickBars = List(bars) { bar -> bar % (onBars + offBars) < onBars }
        val stepsPerBar = beatsPerBar * stepsPerBeat
        val first = startFrame + beatsPerBar * framesPerBeat
        val notes = (0 until bars * stepsPerBar).map { step ->
            val bar = step / stepsPerBar
            ExpectedNote(
                index = step,
                frame = first + step * framesPerStep,
                hand = '-',
                accent = false,
                bar = bar,
                stepInBar = step % stepsPerBar,
                clickAudible = clickBars[bar],
            )
        }
        return RoundPlan(
            sampleRate, bpm.toDouble(), startFrame, framesPerBeat, framesPerStep, beatsPerBar,
            stepsPerBar, beatsPerBar, bars, notes,
            clicks(startFrame, framesPerBeat, beatsPerBar, beatsPerBar, clickBars), clickBars,
        )
    }

    /** Evenly spaced clicks of one type, used by the calibration steps. */
    fun calibrationClicks(startFrame: Long, intervalFrames: Double, count: Int, type: Int): List<Click> =
        (0 until count).map { Click((startFrame + it * intervalFrames).toLong(), type) }

    private fun clicks(
        startFrame: Long, framesPerBeat: Double, beatsPerBar: Int, countInBeats: Int, clickBars: List<Boolean>,
        accentBeats: Set<Int> = setOf(0),
    ): List<Click> {
        val out = ArrayList<Click>()
        for (b in 0 until countInBeats) {
            out += Click((startFrame + b * framesPerBeat).toLong(), if (b in accentBeats) ClickType.ACCENT else ClickType.NORMAL)
        }
        val first = startFrame + countInBeats * framesPerBeat
        clickBars.forEachIndexed { bar, audible ->
            if (!audible) return@forEachIndexed
            for (b in 0 until beatsPerBar) {
                val frame = first + (bar * beatsPerBar + b) * framesPerBeat
                out += Click(frame.toLong(), if (b in accentBeats) ClickType.ACCENT else ClickType.NORMAL)
            }
        }
        return out
    }
}
