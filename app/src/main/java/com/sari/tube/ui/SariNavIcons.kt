package com.sari.tube.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun SariToolsIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier) {
        val s = size.minDimension
        val sw = s * 0.075f
        val cell = s * 0.25f
        val x1 = s * 0.10f
        val x2 = s * 0.65f
        val y1 = s * 0.10f
        val y2 = s * 0.65f
        val style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val corner = CornerRadius(s * 0.035f)

        drawRoundRect(tint, Offset(x1, y1), Size(cell, cell), corner, style)
        drawRoundRect(tint, Offset(x1, y2), Size(cell, cell), corner, style)
        drawRoundRect(tint, Offset(x2, y2), Size(cell, cell), corner, style)

        val diamond = Path().apply {
            moveTo(x2 + cell / 2f, y1)
            lineTo(x2 + cell, y1 + cell / 2f)
            lineTo(x2 + cell / 2f, y1 + cell)
            lineTo(x2, y1 + cell / 2f)
            close()
        }
        drawPath(diamond, tint, style)
    }
}

@Composable
fun SariPlayIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier) {
        val s = size.minDimension
        val sw = s * 0.075f
        val style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val back = Path().apply {
            moveTo(s * 0.20f, s * 0.27f)
            lineTo(s * 0.20f, s * 0.76f)
            quadraticTo(s * 0.20f, s * 0.88f, s * 0.32f, s * 0.88f)
            lineTo(s * 0.78f, s * 0.88f)
        }
        drawPath(back, tint, style)

        drawRoundRect(
            color = tint,
            topLeft = Offset(s * 0.39f, s * 0.08f),
            size = Size(s * 0.51f, s * 0.59f),
            cornerRadius = CornerRadius(s * 0.09f),
            style = style
        )

        val play = Path().apply {
            moveTo(s * 0.57f, s * 0.23f)
            lineTo(s * 0.77f, s * 0.37f)
            lineTo(s * 0.57f, s * 0.51f)
            close()
        }
        drawPath(play, tint)
    }
}
