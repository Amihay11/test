package com.amihay.drumtrainer.model

import kotlin.math.hypot

/**
 * The on-screen drum kit, seen from the drummer's seat. Positions are in a
 * [WIDTH] x [HEIGHT] box (scaled to the view); every piece is a circle.
 */
object KitLayout {
    const val WIDTH = 100f
    const val HEIGHT = 75f

    data class Piece(val pad: Pad, val x: Float, val y: Float, val r: Float) {
        val cymbal: Boolean get() = pad == Pad.CRASH || pad == Pad.RIDE || pad == Pad.HIHAT
    }

    /** Back to front, so the drums nearer the player are drawn on top. */
    val pieces = listOf(
        Piece(Pad.CRASH, 16f, 13f, 12f),
        Piece(Pad.RIDE, 84f, 16f, 13f),
        Piece(Pad.TOM_HIGH, 38f, 22f, 9f),
        Piece(Pad.TOM_MID, 60f, 22f, 9f),
        Piece(Pad.KICK, 49f, 45f, 14f),
        Piece(Pad.HIHAT, 11f, 40f, 10f),
        Piece(Pad.SNARE, 27f, 56f, 10f),
        Piece(Pad.TOM_FLOOR, 74f, 54f, 12f),
    )

    /**
     * The piece under a tap at ([x], [y]) in layout units. A tap a little outside a piece
     * still counts (fingers are wide); between two pieces the relatively nearer one wins.
     */
    fun hitTest(x: Float, y: Float): Pad? =
        pieces.map { it to hypot(x - it.x, y - it.y) / it.r }
            .filter { it.second <= 1.25f }
            .minByOrNull { it.second }?.first?.pad
}

/** How long a piece stays lit after it is hit. */
const val LIGHT_MS = 160.0

/**
 * What the app is playing at [playhead]: for each drum (by pad id) or hand ("R"/"L")
 * that sounded within the last [LIGHT_MS], how lit it is (1 = just hit, fading to 0).
 * With [byHand], strokes that carry a hand light that hand instead of the drum.
 */
fun appLights(clicks: List<Click>, playhead: Double, sampleRate: Int, byHand: Boolean = false): Map<String, Float> {
    val span = LIGHT_MS * sampleRate / 1000.0
    val out = HashMap<String, Float>()
    for (c in clicks) {
        val age = playhead - c.frame
        if (age < 0 || age > span) continue
        val pad = ClickType.padOf(c.type) ?: continue
        val key = if (byHand && (c.hand == 'R' || c.hand == 'L')) c.hand.toString() else pad.id
        val level = (1 - age / span).toFloat() * (0.5f + 0.5f * c.gain.coerceAtMost(1f))
        out[key] = maxOf(out[key] ?: 0f, level)
    }
    return out
}
