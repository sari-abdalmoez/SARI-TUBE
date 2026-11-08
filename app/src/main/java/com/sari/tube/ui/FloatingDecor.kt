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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.random.Random

private class Deco(val x: Float, val phase: Float, val sizeDp: Float, val kind: Int, val alpha: Float, val rot: Float)

/** Seeded once, so the layout is the same on every frame and every launch. */
private val decorations: List<Deco> = run {
    val rnd = Random(11)
    List(26) {
        Deco(
            x = rnd.nextFloat(),
            phase = rnd.nextFloat(),
            sizeDp = 14f + rnd.nextFloat() * 26f,
            kind = rnd.nextInt(8),
            alpha = 0.10f + rnd.nextFloat() * 0.12f,
            rot = rnd.nextFloat() * 60f - 30f
        )
    }
}

/**
 * Faint outline symbols of the app's functions (download, play, music, link, camera, heart, share, clock).
 * They rise from the bottom and fade out before reaching the search field. They are not brand logos.
 */
@Composable
fun FloatingDecor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "decor")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 18000, easing = LinearEasing), RepeatMode.Restart),
        label = "decorT"
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        decorations.forEach { d ->
            val p = (t + d.phase) % 1f
            val yFrac = 1.1f - p * 1.2f
            // Invisible below the screen, visible in the lower part, gone by the search field (about half height).
            val fade = ((yFrac - 0.52f) / 0.28f).coerceIn(0f, 1f) * ((1.15f - yFrac) / 0.15f).coerceIn(0f, 1f)
            if (fade <= 0f) return@forEach

            val c = Offset(d.x * w, yFrac * h)
            val s = d.sizeDp.dp.toPx()
            val color = Color(0xFFFFC53D).copy(alpha = d.alpha * fade)
            val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            rotate(d.rot, pivot = c) { drawDoodle(d.kind, c, s, color, stroke) }
        }
    }
}

private fun DrawScope.drawDoodle(kind: Int, c: Offset, s: Float, color: Color, stroke: Stroke) {
    val lw = stroke.width
    when (kind) {
        0 -> { // download: ring with an arrow
            drawCircle(color, radius = s / 2f, center = c, style = stroke)
            drawLine(color, Offset(c.x, c.y - s * 0.24f), Offset(c.x, c.y + s * 0.16f), strokeWidth = lw, cap = StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(c.x - s * 0.16f, c.y + s * 0.02f)
                    lineTo(c.x, c.y + s * 0.18f)
                    lineTo(c.x + s * 0.16f, c.y + s * 0.02f)
                },
                color,
                style = stroke
            )
        }
        1 -> { // play: ring with a rounded triangle
            drawCircle(color, radius = s / 2f, center = c, style = stroke)
            drawPath(
                Path().apply {
                    moveTo(c.x - s * 0.12f, c.y - s * 0.2f)
                    lineTo(c.x + s * 0.2f, c.y)
                    lineTo(c.x - s * 0.12f, c.y + s * 0.2f)
                    close()
                },
                color,
                style = stroke
            )
        }
        2 -> { // music note
            drawCircle(color, radius = s * 0.14f, center = Offset(c.x - s * 0.14f, c.y + s * 0.2f))
            drawLine(color, Offset(c.x + s * 0.02f, c.y + s * 0.2f), Offset(c.x + s * 0.02f, c.y - s * 0.26f), strokeWidth = lw, cap = StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(c.x + s * 0.02f, c.y - s * 0.26f)
                    quadTo(c.x + s * 0.28f, c.y - s * 0.2f, c.x + s * 0.2f, c.y - s * 0.02f)
                },
                color,
                style = stroke
            )
        }
        3 -> { // link: two overlapping rounded tiles
            drawPath(roundRectPath(c.x - s * 0.3f, c.y - s * 0.14f, s * 0.36f, s * 0.26f, s * 0.12f), color, style = stroke)
            drawPath(roundRectPath(c.x - s * 0.06f, c.y - s * 0.12f, s * 0.36f, s * 0.26f, s * 0.12f), color, style = stroke)
        }
        4 -> { // camera
            drawPath(roundRectPath(c.x - s * 0.32f, c.y - s * 0.2f, s * 0.64f, s * 0.42f, s * 0.08f), color, style = stroke)
            drawCircle(color, radius = s * 0.12f, center = c, style = stroke)
        }
        5 -> { // heart
            drawPath(
                Path().apply {
                    moveTo(c.x, c.y + s * 0.3f)
                    cubicTo(c.x - s * 0.5f, c.y + s * 0.02f, c.x - s * 0.3f, c.y - s * 0.4f, c.x, c.y - s * 0.14f)
                    cubicTo(c.x + s * 0.3f, c.y - s * 0.4f, c.x + s * 0.5f, c.y + s * 0.02f, c.x, c.y + s * 0.3f)
                    close()
                },
                color,
                style = stroke
            )
        }
        6 -> { // share: three nodes joined by lines
            val a = Offset(c.x - s * 0.24f, c.y)
            val b = Offset(c.x + s * 0.22f, c.y - s * 0.2f)
            val d = Offset(c.x + s * 0.22f, c.y + s * 0.2f)
            drawLine(color, a, b, strokeWidth = lw, cap = StrokeCap.Round)
            drawLine(color, a, d, strokeWidth = lw, cap = StrokeCap.Round)
            drawCircle(color, radius = s * 0.1f, center = a)
            drawCircle(color, radius = s * 0.1f, center = b)
            drawCircle(color, radius = s * 0.1f, center = d)
        }
        else -> { // clock
            drawCircle(color, radius = s / 2f, center = c, style = stroke)
            drawLine(color, c, Offset(c.x, c.y - s * 0.28f), strokeWidth = lw, cap = StrokeCap.Round)
            drawLine(color, c, Offset(c.x + s * 0.2f, c.y + s * 0.06f), strokeWidth = lw, cap = StrokeCap.Round)
        }
    }
}

private fun roundRectPath(x: Float, y: Float, w: Float, h: Float, r: Float): Path = Path().apply {
    moveTo(x + r, y)
    lineTo(x + w - r, y)
    quadTo(x + w, y, x + w, y + r)
    lineTo(x + w, y + h - r)
    quadTo(x + w, y + h, x + w - r, y + h)
    lineTo(x + r, y + h)
    quadTo(x, y + h, x, y + h - r)
    lineTo(x, y + r)
    quadTo(x, y, x + r, y)
    close()
}
