package com.amihay.drumtrainer.model

import org.json.JSONArray
import org.json.JSONObject

enum class ExerciseKind { RUDIMENT, INDEPENDENCE, GROOVE, FILL }

/**
 * One note of a pattern. [hand] is 'R', 'L' or '-' (any hand); [pad] is null for
 * rudiments, which are played on a single pad.
 */
data class Stroke(val hand: Char, val accent: Boolean, val pad: Pad? = null, val ghost: Boolean = false)

data class Exercise(
    val id: String,
    val name: String,
    val nameHe: String,
    val tip: String,
    val kind: ExerciseKind,
    /** Style family for grooves ("rock", "funk"…); null otherwise. */
    val style: String?,
    val level: Int,
    /** Grid steps per beat (2 = 8ths, 4 = 16ths, 3 = 8th triplets, 6 = 16th triplets). */
    val subdivision: Int,
    val beatsPerBar: Int,
    /** Notes for every step of one cycle (empty list = rest). Loops over the round. */
    val steps: List<List<Stroke>>,
    /** Bars (index within the cycle) that are fills. */
    val fillBars: Set<Int>,
    val startBpm: Int,
    val targetBpm: Int,
    val passStdMs: Double,
    val passHitPct: Double,
) {
    val stepsPerBar: Int get() = subdivision * beatsPerBar

    /** Bars in one cycle of the pattern (a rudiment shorter than a bar still counts as one). */
    val cycleBars: Int get() = (steps.size + stepsPerBar - 1) / stepsPerBar

    fun strokesAt(globalStep: Int): List<Stroke> = steps[globalStep % steps.size]

    fun isFillBar(bar: Int): Boolean = (bar % cycleBars) in fillBars

    /** Drums used by the exercise; empty for single-pad rudiments. */
    val pads: Set<Pad> = steps.flatten().mapNotNull { it.pad }.toSet()

    val usesPads: Boolean get() = pads.isNotEmpty()

    val hasAccents: Boolean get() = steps.any { s -> s.any { it.accent } }

    /** Bars per round: at least the requested number, and always whole cycles for fills. */
    fun roundBars(requested: Int): Int =
        if (kind == ExerciseKind.FILL) maxOf(1, (requested + cycleBars - 1) / cycleBars) * cycleBars else requested
}

object ExerciseParser {
    /**
     * Rudiment sticking: tokens separated by spaces: "R", "L", "^R" (accented), "-" (rest).
     * Bar lines "|" are allowed for readability and ignored.
     */
    fun parsePattern(text: String): List<List<Stroke>> =
        text.trim().split(Regex("\\s+")).filter { it != "|" && it.isNotEmpty() }.map { token ->
            when {
                token == "-" -> emptyList()
                token.startsWith("^") -> listOf(Stroke(token[1].uppercaseChar(), accent = true))
                else -> listOf(Stroke(token[0].uppercaseChar(), accent = false))
            }
        }

    /**
     * One drum's line in a groove bar, one token per step: "x" hit, "X" accent,
     * "g" ghost note, "-" rest.
     */
    fun parseVoice(text: String, pad: Pad): List<Stroke?> =
        text.trim().split(Regex("\\s+")).filter { it != "|" && it.isNotEmpty() }.map { token ->
            when (token) {
                "-" -> null
                "x" -> Stroke('-', accent = false, pad = pad)
                "X" -> Stroke('-', accent = true, pad = pad)
                "g" -> Stroke('-', accent = false, pad = pad, ghost = true)
                else -> throw IllegalArgumentException("Unknown token '$token' for ${pad.id}")
            }
        }

    fun parse(json: String): List<Exercise> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i -> parseOne(arr.getJSONObject(i)) }
    }

    private fun parseOne(o: JSONObject): Exercise {
        val id = o.getString("id")
        val subdivision = o.getInt("subdivision")
        val beatsPerBar = o.optInt("beatsPerBar", 4)
        val stepsPerBar = subdivision * beatsPerBar
        val fillBars = HashSet<Int>()

        val steps: List<List<Stroke>> = if (o.has("pattern")) {
            parsePattern(o.getString("pattern"))
        } else {
            val bars = o.getJSONArray("bars")
            (0 until bars.length()).flatMap { b ->
                val bar = bars.getJSONObject(b)
                if (bar.optBoolean("fill", false)) fillBars += b
                val cells = List(stepsPerBar) { ArrayList<Stroke>() }
                for (key in bar.keys()) {
                    if (key == "fill") continue
                    val pad = Pad.byId(key) ?: throw IllegalArgumentException("Unknown pad '$key' in $id")
                    val voice = parseVoice(bar.getString(key), pad)
                    require(voice.size == stepsPerBar) { "$id bar $b $key: ${voice.size} steps, expected $stepsPerBar" }
                    voice.forEachIndexed { s, stroke -> if (stroke != null) cells[s] += stroke }
                }
                cells
            }
        }
        require(steps.any { it.isNotEmpty() }) { "Empty pattern in $id" }

        val kind = when (o.optString("kind", "rudiment")) {
            "rudiment" -> ExerciseKind.RUDIMENT
            "independence" -> ExerciseKind.INDEPENDENCE
            "groove" -> ExerciseKind.GROOVE
            "fill" -> ExerciseKind.FILL
            else -> throw IllegalArgumentException("Unknown kind in $id")
        }
        val defaultStd = if (kind == ExerciseKind.RUDIMENT) 15.0 else 20.0
        return Exercise(
            id = id,
            name = o.getString("name"),
            nameHe = o.getString("nameHe"),
            tip = o.optString("tip", ""),
            kind = kind,
            style = o.optString("style", "").ifEmpty { null },
            level = o.optInt("level", 1),
            subdivision = subdivision,
            beatsPerBar = beatsPerBar,
            steps = steps,
            fillBars = fillBars,
            startBpm = o.getInt("startBpm"),
            targetBpm = o.getInt("targetBpm"),
            passStdMs = o.optDouble("passStdMs", defaultStd),
            passHitPct = o.optDouble("passHitPct", 90.0),
        )
    }
}
