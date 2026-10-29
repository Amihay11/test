package com.amihay.drumtrainer.model

/** Click sound types; must match ClickType in DrumEngine.h. */
object ClickType {
    const val NORMAL = 0
    const val ACCENT = 1
    const val BROADBAND = 2
}

data class Click(val frame: Long, val type: Int)

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
) {
    val firstNoteFrame: Double get() = startFrame + countInBeats * framesPerBeat
    val endFrame: Double get() = firstNoteFrame + bars * beatsPerBar * framesPerBeat

    /** Acceptance window: a quarter of a grid step on either side (see spec). */
    val windowFrames: Double get() = framesPerStep / 4.0

    fun msToFrames(ms: Double) = ms * sampleRate / 1000.0
    fun framesToMs(frames: Double) = frames * 1000.0 / sampleRate

    /** Position in the round, in beats from the first note (negative during count-in). */
    fun beatAt(frame: Double): Double = (frame - firstNoteFrame) / framesPerBeat
}

object RoundPlanner {

    fun forExercise(ex: Exercise, bpm: Int, bars: Int, startFrame: Long, sampleRate: Int): RoundPlan {
        val framesPerBeat = sampleRate * 60.0 / bpm
        val framesPerStep = framesPerBeat / ex.subdivision
        val countIn = ex.beatsPerBar
        val first = startFrame + countIn * framesPerBeat
        val notes = ArrayList<ExpectedNote>()
        // Fill exercises end on the downbeat after the last fill, so every fill has a landing.
        val endsOnLanding = ex.kind == ExerciseKind.FILL && ex.isFillBar(bars - 1)
        val lastStep = bars * ex.stepsPerBar + (if (endsOnLanding) 1 else 0)
        for (step in 0 until lastStep) {
            val bar = step / ex.stepsPerBar
            val stepInBar = step % ex.stepsPerBar
            val landing = stepInBar == 0 && bar > 0 && ex.isFillBar(bar - 1)
            for (stroke in ex.strokesAt(step)) {
                notes += ExpectedNote(
                    index = notes.size,
                    frame = first + step * framesPerStep,
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
        val clicks = clicks(startFrame, framesPerBeat, ex.beatsPerBar, countIn, clickBars).toMutableList()
        if (endsOnLanding) clicks += Click((first + bars * ex.beatsPerBar * framesPerBeat).toLong(), ClickType.ACCENT)
        return RoundPlan(
            sampleRate, bpm.toDouble(), startFrame, framesPerBeat, framesPerStep, ex.beatsPerBar,
            ex.stepsPerBar, countIn, bars, notes, clicks, clickBars,
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
    ): List<Click> {
        val out = ArrayList<Click>()
        for (b in 0 until countInBeats) {
            out += Click((startFrame + b * framesPerBeat).toLong(), if (b == 0) ClickType.ACCENT else ClickType.NORMAL)
        }
        val first = startFrame + countInBeats * framesPerBeat
        clickBars.forEachIndexed { bar, audible ->
            if (!audible) return@forEachIndexed
            for (b in 0 until beatsPerBar) {
                val frame = first + (bar * beatsPerBar + b) * framesPerBeat
                out += Click(frame.toLong(), if (b == 0) ClickType.ACCENT else ClickType.NORMAL)
            }
        }
        return out
    }
}
