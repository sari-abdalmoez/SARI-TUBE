package com.sari.tube.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.random.Random

private class Deco(val x: Float, val phase: Float, val sizeDp: Float, val kind: Int, val alpha: Float)

/** Seeded once, so the layout stays the same on every frame and every launch. */
private val decorations: List<Deco> = run {
    val rnd = Random(7)
    List(18) {
        Deco(
            x = rnd.nextFloat(),
            phase = rnd.nextFloat(),
            sizeDp = 12f + rnd.nextFloat() * 22f,
            kind = rnd.nextInt(4),
            alpha = 0.08f + rnd.nextFloat() * 0.10f
        )
    }
}

/**
 * Faint download and play symbols that rise from the bottom and fade out as they approach the search field.
 * Symbols are generic shapes, not brand logos.
 */
@Composable
fun FloatingDecor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "decor")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 16000, easing = LinearEasing), RepeatMode.Restart),
        label = "decorT"
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val base = Color(0xFFFFC53D)
        decorations.forEach { d ->
            val p = (t + d.phase) % 1f
            val yFrac = 1.1f - p * 1.2f                  // 1.1 = below the screen, -0.1 = above it
            // Invisible at the bottom edge, fully visible in the lower part, and gone by the search field (about 50%).
            val fade = ((yFrac - 0.52f) / 0.28f).coerceIn(0f, 1f) * ((1.15f - yFrac) / 0.15f).coerceIn(0f, 1f)
            if (fade <= 0f) return@forEach

            val c = Offset(d.x * w, yFrac * h)
            val s = d.sizeDp.dp.toPx()
            val color = base.copy(alpha = d.alpha * fade)
            val stroke = Stroke(width = s * 0.12f, cap = StrokeCap.Round)

            when (d.kind) {
                0 -> { // download: ring with an arrow
                    drawCircle(color, radius = s / 2f, center = c, style = stroke)
                    drawLine(color, Offset(c.x, c.y - s * 0.25f), Offset(c.x, c.y + s * 0.22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(color, Offset(c.x - s * 0.18f, c.y + s * 0.05f), Offset(c.x, c.y + s * 0.22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(color, Offset(c.x + s * 0.18f, c.y + s * 0.05f), Offset(c.x, c.y + s * 0.22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
                1 -> { // play: ring with a triangle
                    drawCircle(color, radius = s / 2f, center = c, style = stroke)
                    val tri = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x - s * 0.14f, c.y - s * 0.2f)
                        lineTo(c.x + s * 0.24f, c.y)
                        lineTo(c.x - s * 0.14f, c.y + s * 0.2f)
                        close()
                    }
                    drawPath(tri, color)
                }
                2 -> { // music note
                    drawCircle(color, radius = s * 0.16f, center = Offset(c.x - s * 0.12f, c.y + s * 0.2f))
                    drawLine(color, Offset(c.x - s * 0.02f, c.y + s * 0.2f), Offset(c.x - s * 0.02f, c.y - s * 0.25f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(color, Offset(c.x - s * 0.02f, c.y - s * 0.25f), Offset(c.x + s * 0.26f, c.y - s * 0.15f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
                else -> { // three dots: a sharing cluster
                    drawCircle(color, radius = s * 0.09f, center = Offset(c.x - s * 0.2f, c.y))
                    drawCircle(color, radius = s * 0.09f, center = Offset(c.x + s * 0.2f, c.y - s * 0.16f))
                    drawCircle(color, radius = s * 0.09f, center = Offset(c.x + s * 0.2f, c.y + s * 0.16f))
                }
            }
        }
    }
}
