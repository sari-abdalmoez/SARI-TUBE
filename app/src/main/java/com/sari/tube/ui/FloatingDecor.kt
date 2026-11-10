package com.sari.tube.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.random.Random

private class Deco(val x: Float, val phase: Float, val sizeDp: Float, val icon: ImageVector, val alpha: Float, val rot: Float)

private val decorations: List<Deco> by lazy {
    val icons = listOf(
        Icons.Outlined.Download, Icons.Outlined.PlayCircleOutline, Icons.Outlined.MusicNote,
        Icons.Outlined.Link, Icons.Outlined.Star, Icons.Outlined.Favorite,
        Icons.Outlined.Share, Icons.Outlined.Schedule
    )
    val rnd = Random(11)
    List(22) { i ->
        Deco(
            x = rnd.nextFloat(),
            phase = rnd.nextFloat(),
            sizeDp = 16f + rnd.nextFloat() * 22f,
            icon = icons[i % icons.size],
            alpha = 0.12f + rnd.nextFloat() * 0.14f,
            rot = rnd.nextFloat() * 50f - 25f
        )
    }
}

/**
 * Faint outlined symbols of the app's functions. They rise from the bottom and fade out before the search field.
 * Positions and opacity are read in the layout and draw phases, so the screen does not recompose every frame.
 */
@Composable
fun FloatingDecor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "decor")
    val t = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 18000, easing = LinearEasing), RepeatMode.Restart),
        label = "decorT"
    )

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        decorations.forEach { d ->
            val sizePx = with(density) { d.sizeDp.dp.toPx() }
            Icon(
                imageVector = d.icon,
                contentDescription = null,
                tint = Color(0xFFFFC53D),
                modifier = Modifier
                    .size(d.sizeDp.dp)
                    .offset {
                        val p = (t.value + d.phase) % 1f
                        val yFrac = 1.1f - p * 1.2f
                        IntOffset(
                            (d.x * wPx - sizePx / 2f).roundToInt(),
                            (yFrac * hPx - sizePx / 2f).roundToInt()
                        )
                    }
                    .graphicsLayer {
                        val p = (t.value + d.phase) % 1f
                        val yFrac = 1.1f - p * 1.2f
                        // Hidden below the screen, visible in the lower part, gone before the search field.
                        val fade = ((yFrac - 0.52f) / 0.28f).coerceIn(0f, 1f) * ((1.15f - yFrac) / 0.15f).coerceIn(0f, 1f)
                        alpha = d.alpha * fade
                        rotationZ = d.rot
                    }
            )
        }
    }
}
