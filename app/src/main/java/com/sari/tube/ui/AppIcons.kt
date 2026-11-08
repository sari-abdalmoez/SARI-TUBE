package com.sari.tube.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Download arrow above a tray. */
val DownloadArrow: ImageVector by lazy {
    ImageVector.Builder("DownloadArrow", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
        moveTo(11f, 3f); lineTo(13f, 3f); lineTo(13f, 13.17f); lineTo(16.59f, 9.59f); lineTo(18f, 11f)
        lineTo(12f, 17f); lineTo(6f, 11f); lineTo(7.41f, 9.59f); lineTo(11f, 13.17f); close()
        moveTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 21f); lineTo(4f, 21f); close()
    }.build()
}

/** Four rounded tiles: the "Tools" tab. Rounded corners come from round line joins. */
val ToolsGrid: ImageVector by lazy {
    ImageVector.Builder("ToolsGrid", 24.dp, 24.dp, 24f, 24f)
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            roundRect(3f, 3f, 7.5f, 7.5f, 2.2f)
            roundRect(13.5f, 3f, 7.5f, 7.5f, 2.2f)
            roundRect(3f, 13.5f, 7.5f, 7.5f, 2.2f)
            roundRect(13.5f, 13.5f, 7.5f, 7.5f, 2.2f)
        }.build()
}

/** Two stacked rounded cards with a play mark: the "Play" tab. */
val PlayLibrary: ImageVector by lazy {
    ImageVector.Builder("PlayLibrary", 24.dp, 24.dp, 24f, 24f)
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            roundRect(7f, 3f, 14f, 11f, 2.5f)
        }
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            roundRect(3f, 8f, 14f, 13f, 2.5f)
        }
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            moveTo(9f, 11.5f); lineTo(13.5f, 14.5f); lineTo(9f, 17.5f); close()
        }
        .build()
}

private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
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
