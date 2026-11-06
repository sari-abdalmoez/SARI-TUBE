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
