package com.amihay.drumtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.changedToDown
import android.os.SystemClock
import com.amihay.drumtrainer.model.ClickType
import com.amihay.drumtrainer.model.KitLayout
import com.amihay.drumtrainer.model.LIGHT_MS
import com.amihay.drumtrainer.model.Pad

private val cymbalColor = Color(0xFFC9A227)
private val shellColor = Color(0xFF3B4252)
private val headColor = Color(0xFFD9D4C7)
private val lightColor = Palette.accent

/**
 * Remembers when each drum or hand was last tapped, and redraws every frame so the
 * lights fade out.
 */
class TapLights {
    private val tappedAt = mutableStateMapOf<String, Long>()
    var now by mutableLongStateOf(0L)

    fun tapped(key: String, uptimeMs: Long) {
        tappedAt[key] = uptimeMs
    }

    fun level(key: String): Float {
        val t = tappedAt[key] ?: return 0f
        return (1 - (now - t) / LIGHT_MS).toFloat().coerceIn(0f, 1f)
    }
}

@Composable
fun rememberTapLights(): TapLights {
    val lights = remember { TapLights() }
    LaunchedEffect(lights) {
        while (true) withFrameMillis { lights.now = SystemClock.uptimeMillis() }
    }
    return lights
}

/**
 * A drum kit seen from above. Tapping a piece plays its sound; [appLights] lights the
 * pieces the app is playing (by pad id), and the player's own taps light up too.
 */
@Composable
fun DrumKitView(
    app: AppState,
    modifier: Modifier = Modifier,
    appLights: Map<String, Float> = emptyMap(),
    onTap: (Pad, Long) -> Unit = { _, _ -> },
) {
    val taps = rememberTapLights()
    val measurer = rememberTextMeasurer()
    val tap by rememberUpdatedState(onTap)
    Canvas(
        modifier.fillMaxWidth().aspectRatio(KitLayout.WIDTH / KitLayout.HEIGHT)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (!change.changedToDown()) continue
                            change.consume()
                            val scale = size.width / KitLayout.WIDTH
                            val pad = KitLayout.hitTest(change.position.x / scale, change.position.y / scale) ?: continue
                            app.engine.trigger(ClickType.pad(pad), 0.8f)
                            taps.tapped(pad.id, change.uptimeMillis)
                            tap(pad, change.uptimeMillis)
                        }
                    }
                }
            },
    ) {
        val scale = size.width / KitLayout.WIDTH
        for (piece in KitLayout.pieces) {
            val c = Offset(piece.x * scale, piece.y * scale)
            val r = piece.r * scale
            val lit = maxOf(taps.level(piece.pad.id), appLights[piece.pad.id] ?: 0f)
            if (piece.cymbal) {
                drawCircle(lerp(cymbalColor, lightColor, lit), r, c)
                for (k in 1..3) drawCircle(Color(0x33000000), r * k / 4f, c, style = Stroke(1.5f))
                drawCircle(Color(0xFF6B5A1A), r * 0.12f, c)
            } else {
                drawCircle(shellColor, r, c)
                drawCircle(lerp(headColor, lightColor, lit), r * 0.86f, c)
                drawCircle(Color(0x22000000), r * 0.86f, c, style = Stroke(2f))
            }
            if (lit > 0f) drawCircle(lightColor.copy(alpha = 0.5f * lit), r * (1.05f + 0.15f * lit), c, style = Stroke(6f))
            // The name fits inside the piece: smaller type for the small toms. On a cymbal it
            // sits below the bell.
            val style = TextStyle(color = if (piece.cymbal) Color(0xFF2B2410) else Color(0xFF1B1F27), fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
            var label = measurer.measure(piece.pad.he, style)
            val maxW = r * 1.5f
            if (label.size.width > maxW) {
                label = measurer.measure(piece.pad.he, style.copy(fontSize = 13.sp * (maxW / label.size.width)))
            }
            val y = if (piece.cymbal) c.y + r * 0.45f - label.size.height / 2f else c.y - label.size.height / 2f
            drawText(label, topLeft = Offset(c.x - label.size.width / 2f, y))
        }
    }
}

/**
 * Two big pads for the hands, left on the left. Each tap plays a snare sound;
 * [appLights] lights the hand the app is playing ("R"/"L").
 */
@Composable
fun HandPads(
    app: AppState,
    modifier: Modifier = Modifier,
    appLights: Map<String, Float> = emptyMap(),
    onTap: (Char, Long) -> Unit = { _, _ -> },
) {
    val taps = rememberTapLights()
    val tap by rememberUpdatedState(onTap)
    // Physical left and right, whatever the language.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier.fillMaxWidth().height(200.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                if (!change.changedToDown()) continue
                                change.consume()
                                val hand = if (change.position.x < size.width / 2) 'L' else 'R'
                                app.engine.trigger(ClickType.pad(Pad.SNARE), 0.8f)
                                taps.tapped(hand.toString(), change.uptimeMillis)
                                tap(hand, change.uptimeMillis)
                            }
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((hand, name) in listOf('L' to "שמאל", 'R' to "ימין")) {
                val lit = maxOf(taps.level(hand.toString()), appLights[hand.toString()] ?: 0f)
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(20.dp))
                        .background(lerp(Color(0xFF2A2F3A), lightColor, lit)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$name\n$hand", style = MaterialTheme.typography.headlineMedium,
                        color = if (lit > 0.5f) Color(0xFF0B1220) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/**
 * The screen input for a round: the kit for patterns on drums, hand pads otherwise.
 * Taps go to [runner] (scored while a round runs) and light up.
 */
@Composable
fun TouchInput(app: AppState, runner: RoundRunner, kit: Boolean, appLights: Map<String, Float> = emptyMap()) {
    if (kit) DrumKitView(app, appLights = appLights, onTap = { pad, t -> runner.tap(t, pad = pad) })
    else HandPads(app, appLights = appLights, onTap = { hand, t -> runner.tap(t, hand = hand) })
}
