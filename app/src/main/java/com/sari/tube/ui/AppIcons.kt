package com.sari.tube.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Download arrow above a tray: drawn as a vector so it matches the app's style. */
val DownloadArrow: ImageVector by lazy {
    ImageVector.Builder(
        name = "DownloadArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(11f, 3f)
        lineTo(13f, 3f)
        lineTo(13f, 13.17f)
        lineTo(16.59f, 9.59f)
        lineTo(18f, 11f)
        lineTo(12f, 17f)
        lineTo(6f, 11f)
        lineTo(7.41f, 9.59f)
        lineTo(11f, 13.17f)
        close()
        moveTo(4f, 19f)
        lineTo(20f, 19f)
        lineTo(20f, 21f)
        lineTo(4f, 21f)
        close()
    }.build()
}

/** Four-tile grid with a diamond: the "Tools" tab icon. */
val ToolsGrid: ImageVector by lazy {
    ImageVector.Builder("ToolsGrid", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 3f); lineTo(10f, 3f); lineTo(10f, 10f); lineTo(3f, 10f); close()
        moveTo(17f, 2.5f); lineTo(21.5f, 7f); lineTo(17f, 11.5f); lineTo(12.5f, 7f); close()
        moveTo(3f, 14f); lineTo(10f, 14f); lineTo(10f, 21f); lineTo(3f, 21f); close()
        moveTo(14f, 14f); lineTo(21f, 14f); lineTo(21f, 21f); lineTo(14f, 21f); close()
    }.build()
}

/** Stacked cards with a play triangle: the "Play" tab icon. */
val PlayLibrary: ImageVector by lazy {
    ImageVector.Builder("PlayLibrary", 24.dp, 24.dp, 24f, 24f)
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(7f, 3f); lineTo(21f, 3f); lineTo(21f, 16f)
        }
        .path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(3f, 7f); lineTo(17f, 7f); lineTo(17f, 21f); lineTo(3f, 21f); close()
        }
        .path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 11f); lineTo(13f, 14f); lineTo(8f, 17f); close()
        }
        .build()
}
