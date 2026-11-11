package com.sari.tube.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/** Download arrow above a tray. */
val DownloadArrow: ImageVector by lazy {
    ImageVector.Builder("DownloadArrow", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
        moveTo(11f, 3f); lineTo(13f, 3f); lineTo(13f, 13.17f); lineTo(16.59f, 9.59f); lineTo(18f, 11f)
        lineTo(12f, 17f); lineTo(6f, 11f); lineTo(7.41f, 9.59f); lineTo(11f, 13.17f); close()
        moveTo(4f, 19f); lineTo(20f, 19f); lineTo(20f, 21f); lineTo(4f, 21f); close()
    }.build()
}

/** Four rounded tiles: the "Tools" tab. */
val ToolsGrid: ImageVector by lazy {
    ImageVector.Builder("ToolsGrid", 24.dp, 24.dp, 24f, 24f)
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            roundRect(3f, 3f, 7.5f, 7.5f, 2.2f)
            roundRect(13.5f, 3f, 7.5f, 7.5f, 2.2f)
            roundRect(3f, 13.5f, 7.5f, 7.5f, 2.2f)
            roundRect(13.5f, 13.5f, 7.5f, 7.5f, 2.2f)
        }.build()
}

/** Library tab: a front card with a rounded play mark, and only the top and right edges of a card behind it. */
val PlayLibrary: ImageVector by lazy {
    ImageVector.Builder("PlayLibrary", 24.dp, 24.dp, 24f, 24f)
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            moveTo(7f, 3f); lineTo(19f, 3f); quadTo(21f, 3f, 21f, 5f); lineTo(21f, 13f)
        }
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            roundRect(3f, 7f, 14f, 13f, 2.5f)
        }
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
            roundedPolygon(listOf(Pt(8.5f, 10.5f), Pt(14.5f, 13.5f), Pt(8.5f, 16.5f)), 1.2f)
        }
        .build()
}

private data class Pt(val x: Float, val y: Float)

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

/** A polygon whose corners are rounded by a quadratic curve through each vertex. */
private fun PathBuilder.roundedPolygon(pts: List<Pt>, r: Float) {
    val n = pts.size
    for (i in 0 until n) {
        val prev = pts[(i - 1 + n) % n]
        val cur = pts[i]
        val next = pts[(i + 1) % n]
        val a = towards(cur, prev, r)
        val b = towards(cur, next, r)
        if (i == 0) moveTo(a.x, a.y) else lineTo(a.x, a.y)
        quadTo(cur.x, cur.y, b.x, b.y)
    }
    close()
}

private fun towards(from: Pt, to: Pt, d: Float): Pt {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    val t = if (len == 0f) 0f else minOf(d / len, 0.5f)
    return Pt(from.x + dx * t, from.y + dy * t)
}
