package com.phototok.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * The Selection button's glyph: a stack of photos with a star on the front one — the place the
 * photos you keep collect in ([USER 2026-10-24], usability panel: no persona recognised the
 * bookmark stack, three reached for the Favourite chip). Composed from two Material paths,
 * `filter_none` (the stack) and `star`, as no stock glyph shows a starred stack.
 */
val SelectionStackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SelectionStack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(STACK).toNodes(),
            fill = SolidColor(Color.Black),
        )
        // The star, half size, centred in the front frame (7..21 x 3..17).
        group(scaleX = 0.5f, scaleY = 0.5f, translationX = 8f, translationY = 4.25f) {
            addPath(
                pathData = PathParser().parsePathString(STAR).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()
}

// Material Icons `filter_none` and `star` (Apache License 2.0), 24 x 24 viewport.
private const val STACK =
    "M3,5H1v16c0,1.1 0.9,2 2,2h16v-2H3V5z" +
        "M21,1H7C5.9,1 5,1.9 5,3v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V3C23,1.9 22.1,1 21,1z" +
        "M21,17H7V3h14V17z"
private const val STAR =
    "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"
