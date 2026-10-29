package com.amihay.drumtrainer.model

import org.json.JSONArray

/** One step of a sticking pattern. [hand] is 'R', 'L' or '-' (any hand). */
data class Stroke(val hand: Char, val accent: Boolean)

data class Exercise(
    val id: String,
    val name: String,
    val nameHe: String,
    val tip: String,
    /** Grid steps per beat (4 = 16ths, 3 = 8th triplets, 6 = 16th triplets). */
    val subdivision: Int,
    val beatsPerBar: Int,
    /** Pattern that loops over the round; null entries are rests. */
    val pattern: List<Stroke?>,
    val startBpm: Int,
    val targetBpm: Int,
    val passStdMs: Double,
    val passHitPct: Double,
) {
    val stepsPerBar: Int get() = subdivision * beatsPerBar

    fun strokeAt(globalStep: Int): Stroke? = pattern[globalStep % pattern.size]

    val hasAccents: Boolean get() = pattern.any { it?.accent == true }
}

object ExerciseParser {
    /**
     * Pattern tokens are separated by spaces: "R", "L", "^R" (accented), "-" (rest).
     * Bar lines "|" are allowed for readability and ignored.
     */
    fun parsePattern(text: String): List<Stroke?> =
        text.trim().split(Regex("\\s+")).filter { it != "|" && it.isNotEmpty() }.map { token ->
            when {
                token == "-" -> null
                token.startsWith("^") -> Stroke(token[1].uppercaseChar(), accent = true)
                else -> Stroke(token[0].uppercaseChar(), accent = false)
            }
        }

    fun parse(json: String): List<Exercise> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val pattern = parsePattern(o.getString("pattern"))
            require(pattern.isNotEmpty()) { "Empty pattern in ${o.getString("id")}" }
            Exercise(
                id = o.getString("id"),
                name = o.getString("name"),
                nameHe = o.getString("nameHe"),
                tip = o.optString("tip", ""),
                subdivision = o.getInt("subdivision"),
                beatsPerBar = o.optInt("beatsPerBar", 4),
                pattern = pattern,
                startBpm = o.getInt("startBpm"),
                targetBpm = o.getInt("targetBpm"),
                passStdMs = o.optDouble("passStdMs", 15.0),
                passHitPct = o.optDouble("passHitPct", 90.0),
            )
        }
    }
}
