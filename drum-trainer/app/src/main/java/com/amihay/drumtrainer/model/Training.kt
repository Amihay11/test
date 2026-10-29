package com.amihay.drumtrainer.model

import kotlin.math.roundToInt

/** What the planner needs to know about one past round. */
data class RunRecord(
    val exerciseId: String,
    val bpm: Int,
    val passed: Boolean,
    val startedAt: Long,
    /** Training session the round belonged to; null for rounds played outside one. */
    val sessionId: Long?,
    val stdMs: Double,
)

const val DAY_MS = 24L * 60 * 60 * 1000

/** Suffix of the run mode for rounds tapped on the screen instead of played on drums. */
const val TOUCH_TAG = "-touch"

/** Gap-metronome rounds are stored as "gap_<on>_<off>". */
data class GapSetup(val onBars: Int, val offBars: Int, val stepsPerBeat: Int = 1) {
    val id: String get() = "gap_${onBars}_$offBars"

    companion object {
        /** Easiest to hardest: longer silences relative to the click. */
        val LADDER = listOf(GapSetup(2, 2), GapSetup(4, 4), GapSetup(1, 3))

        fun fromId(id: String): GapSetup? {
            val parts = id.split('_')
            if (parts.size != 3 || parts[0] != "gap") return null
            return GapSetup(parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
        }
    }
}

object Mastery {
    /** Rounds in the same session (or, outside sessions, on the same day) count as one occasion. */
    fun occasion(r: RunRecord): String = r.sessionId?.let { "s$it" } ?: "d${r.startedAt / DAY_MS}"

    /**
     * Mastered = passed at the target tempo in two different sessions: the second time
     * shows the skill survived the break between practice days.
     */
    fun isMastered(ex: Exercise, runs: List<RunRecord>): Boolean =
        runs.filter { it.exerciseId == ex.id && it.passed && it.bpm >= ex.targetBpm }
            .map(::occasion).distinct().size >= 2

    fun bestPassedBpm(exerciseId: String, runs: List<RunRecord>): Int? =
        runs.filter { it.exerciseId == exerciseId && it.passed }.maxOfOrNull { it.bpm }

    /** Highest passed tempo per day, for the progress graph. */
    fun tempoHistory(exerciseId: String, runs: List<RunRecord>): List<Pair<Long, Int>> =
        runs.filter { it.exerciseId == exerciseId && it.passed }
            .groupBy { it.startedAt / DAY_MS }
            .map { (day, rs) -> day to rs.maxOf { it.bpm } }
            .sortedBy { it.first }
}

enum class PartKind(val he: String, val defaultMinutes: Int) {
    REVIEW("חזרה", 5),
    TECHNIQUE("טכניקה", 12),
    TIMING("שמירת קצב", 8),
    GROOVE("גרוב ומעבר", 15),
    FREE("נגינה חופשית", 5),
}

/** One thing to play: an exercise or a gap-metronome setup, at a tempo, and why. */
data class SessionItem(val exerciseId: String?, val gap: GapSetup?, val bpm: Int, val reason: String)

data class SessionPart(val kind: PartKind, val minutes: Int, val items: List<SessionItem>)

data class SessionPlan(val parts: List<SessionPart>) {
    val minutes: Int get() = parts.sumOf { it.minutes }
}

/**
 * Builds a five-part session from past results (see spec, "מבנה אימון ותוכנית"):
 * review of the previous session at 90 % of the tempo reached, one new and one
 * in-progress rudiment, a gap-metronome step, a groove and a fill, and free play.
 */
object SessionPlanner {
    /** Order in which the styles are taught (spec: rock first, odd meters last). */
    val STYLE_ORDER = listOf("rock", "funk", "jazz", "blues", "latin", "mideast", "electronic")

    fun styleRank(style: String?): Int = STYLE_ORDER.indexOf(style).let { if (it < 0) STYLE_ORDER.size else it }

    const val REVIEW_FACTOR = 0.9
    /** Mastered exercises come back for review when they have rested this long. */
    const val REVIEW_AFTER_DAYS = 14
    /** A gap round this steady moves on to the next, harder setup. */
    const val GAP_STEP_STD_MS = 20.0

    fun plan(
        exercises: List<Exercise>,
        runs: List<RunRecord>,
        currentBpm: Map<String, Int>,
        now: Long,
        totalMinutes: Int = 45,
    ): SessionPlan {
        val byId = exercises.associateBy { it.id }
        val mastered = exercises.filter { Mastery.isMastered(it, runs) }.map { it.id }.toSet()
        val lastPlayed = runs.groupBy { it.exerciseId }.mapValues { (_, rs) -> rs.maxOf { it.startedAt } }
        fun bpmFor(ex: Exercise) = currentBpm[ex.id] ?: ex.startBpm
        val scale = totalMinutes / 45.0
        fun minutes(kind: PartKind) = maxOf(2, (kind.defaultMinutes * scale).roundToInt())

        // 1. Review: what the last session practised, at 90 % of the tempo reached there,
        //    plus one mastered exercise that has rested long enough to need refreshing.
        val lastSession = runs.mapNotNull { it.sessionId }.maxOrNull()
        val review = ArrayList<SessionItem>()
        if (lastSession != null) {
            runs.filter { it.sessionId == lastSession && it.exerciseId in byId }
                .sortedBy { it.startedAt }
                .groupBy { it.exerciseId }
                .entries.take(2)
                .forEach { (id, rs) ->
                    val reached = rs.filter { it.passed }.maxOfOrNull { it.bpm } ?: rs.maxOf { it.bpm }
                    review += SessionItem(id, null, (reached * REVIEW_FACTOR).roundToInt(), "מהאימון הקודם, ב-90% מהטמפו שהושג")
                }
        }
        exercises.filter { it.id in mastered && it.id !in review.map { r -> r.exerciseId } }
            .filter { now - (lastPlayed[it.id] ?: 0) >= REVIEW_AFTER_DAYS * DAY_MS }
            .minByOrNull { lastPlayed[it.id] ?: 0 }
            ?.let { ex ->
                val best = Mastery.bestPassedBpm(ex.id, runs) ?: ex.targetBpm
                review += SessionItem(ex.id, null, (best * REVIEW_FACTOR).roundToInt(), "חזרה מרווחת: נשלט, לא נוגן ${REVIEW_AFTER_DAYS}+ ימים")
            }
        // 2. Technique: one new rudiment and one in progress (least recently played).
        val rudiments = exercises.filter { it.kind == ExerciseKind.RUDIMENT }
        val newOnes = rudiments.filter { it.id !in lastPlayed }
        val inProgress = rudiments.filter { it.id in lastPlayed && it.id !in mastered }.sortedBy { lastPlayed[it.id] }
        val technique = (inProgress.take(1).map { SessionItem(it.id, null, bpmFor(it), "בתהליך") } +
            newOnes.take(if (inProgress.isEmpty()) 2 else 1).map { SessionItem(it.id, null, bpmFor(it), "רודימנט חדש") })
            .ifEmpty {
                rudiments.minByOrNull { lastPlayed[it.id] ?: 0 }?.let { listOf(SessionItem(it.id, null, bpmFor(it), "שמירה על כושר")) }
                    ?: emptyList()
            }

        // Nothing to review yet: warm up on a rudiment that is not in today's technique part.
        if (review.isEmpty()) {
            val inTechnique = technique.map { it.exerciseId }.toSet()
            (rudiments.firstOrNull { it.id !in inTechnique } ?: rudiments.firstOrNull())?.let {
                review += SessionItem(it.id, null, bpmFor(it), "חימום")
            }
        }

        // 3. Timing: the gap metronome, one setup harder once the silences are steady.
        val gapRuns = runs.filter { GapSetup.fromId(it.exerciseId) != null }.sortedBy { it.startedAt }
        val timing = gapRuns.lastOrNull()?.let { last ->
            val setup = GapSetup.fromId(last.exerciseId)!!
            val idx = GapSetup.LADDER.indexOfFirst { it.id == setup.id }.coerceAtLeast(0)
            when {
                last.stdMs > GAP_STEP_STD_MS -> SessionItem(null, setup, last.bpm, "אותה רמה – לייצב את השקט")
                idx < GapSetup.LADDER.lastIndex -> SessionItem(null, GapSetup.LADDER[idx + 1], last.bpm, "שקט ארוך יותר")
                else -> SessionItem(null, setup, last.bpm + SpeedTrainer.STEP_BPM, "אותו דפוס, טמפו גבוה יותר")
            }
        } ?: SessionItem(null, GapSetup.LADDER.first(), 70, "התחלה: 2 תיבות עם קליק, 2 בלי")

        // 4. Groove and fill: the next unmastered groove, style by style through the
        //    curriculum, with independence exercises first while their level is not above it.
        val grooves = exercises.filter { it.kind == ExerciseKind.GROOVE }
            .sortedWith(compareBy({ styleRank(it.style) }, { it.level }))
        val nextGroove = grooves.firstOrNull { it.id !in mastered }
        val independence = exercises.filter { it.kind == ExerciseKind.INDEPENDENCE }.sortedBy { it.level }
            .firstOrNull { it.id !in mastered && it.level <= (nextGroove?.level ?: Int.MAX_VALUE) }
        val groove = independence ?: nextGroove ?: grooves.lastOrNull()
        val fill = exercises.filter { it.kind == ExerciseKind.FILL }.sortedBy { it.level }
            .let { fills -> fills.firstOrNull { it.id !in mastered } ?: fills.lastOrNull() }
        val grooveItems = listOfNotNull(
            groove?.let { SessionItem(it.id, null, bpmFor(it), "הגרוב הבא") },
            fill?.let { SessionItem(it.id, null, bpmFor(it), "מילוי ונחיתה") },
        )

        // 5. Free play over the current groove, without a score.
        val free = listOfNotNull(groove?.let { SessionItem(it.id, null, bpmFor(it), "לנגן חופשי עם האפליקציה, בלי ציון") })

        return SessionPlan(
            listOf(
                SessionPart(PartKind.REVIEW, minutes(PartKind.REVIEW), review.take(3)),
                SessionPart(PartKind.TECHNIQUE, minutes(PartKind.TECHNIQUE), technique),
                SessionPart(PartKind.TIMING, minutes(PartKind.TIMING), listOf(timing)),
                SessionPart(PartKind.GROOVE, minutes(PartKind.GROOVE), grooveItems),
                SessionPart(PartKind.FREE, minutes(PartKind.FREE), free),
            ),
        )
    }
}

data class SessionSummary(val improved: List<String>, val stuck: List<String>, val mastered: List<String>)

object SessionReview {
    /**
     * What changed in [sessionId] compared with everything before it: new best
     * passed tempos, exercises that did not pass in several tries, and newly mastered ones.
     */
    fun summarize(exercises: List<Exercise>, runs: List<RunRecord>, sessionId: Long): SessionSummary {
        val inSession = runs.filter { it.sessionId == sessionId }
        val start = inSession.minOfOrNull { it.startedAt } ?: return SessionSummary(emptyList(), emptyList(), emptyList())
        val before = runs.filter { it.startedAt < start }
        val names = exercises.associate { it.id to it.nameHe }
        fun name(id: String) = names[id] ?: GapSetup.fromId(id)?.let { "מטרונום עם חורים ${it.onBars}/${it.offBars}" } ?: id

        val improved = ArrayList<String>()
        val stuck = ArrayList<String>()
        for ((id, rs) in inSession.groupBy { it.exerciseId }) {
            val best = rs.filter { it.passed }.maxOfOrNull { it.bpm }
            val prev = Mastery.bestPassedBpm(id, before)
            when {
                best != null && prev == null -> improved += "${name(id)}: עבר לראשונה ב-$best BPM"
                best != null && best > prev!! -> improved += "${name(id)}: $prev → $best BPM"
                best == null && rs.size >= 2 -> stuck += "${name(id)}: ${rs.size} סבבים בלי מעבר ב-${rs.maxOf { it.bpm }} BPM"
            }
        }
        val mastered = exercises.filter { Mastery.isMastered(it, runs) && !Mastery.isMastered(it, before) }.map { it.nameHe }
        return SessionSummary(improved, stuck, mastered)
    }
}

/**
 * How steady free playing was: every hit compared with the nearest grid position,
 * either a straight 16th or an 8th-note triplet (so swing and shuffle count as on time).
 */
object FreePlayStats {
    data class Summary(val hits: Int, val onGridPct: Double, val meanMs: Double, val stdMs: Double)

    fun summarize(onsets: List<Onset>, plan: RoundPlan): Summary {
        val grids = listOf(plan.framesPerBeat / 4, plan.framesPerBeat / 3)
        val window = grids.first() / 4
        val devs = onsets.filter { it.frame >= plan.firstNoteFrame - window && it.frame <= plan.endFrame + window }
            .map { o ->
                grids.map { grid ->
                    val pos = (o.frame - plan.firstNoteFrame) / grid
                    (pos - Math.round(pos)) * grid
                }.minBy { kotlin.math.abs(it) }
            }
        if (devs.isEmpty()) return Summary(0, 0.0, 0.0, 0.0)
        val ms = devs.map { plan.framesToMs(it) }
        val s = Stats.spread(ms)
        return Summary(devs.size, 100.0 * devs.count { kotlin.math.abs(it) <= window } / devs.size, s.meanMs, s.stdMs)
    }
}
