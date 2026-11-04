package com.phototok.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb

/**
 * The one glyph per swipe action, for the mid-swipe indicators, the flashes, the edge hints and
 * the controls guide alike (code_health 2026-07-31, review JP-Q-5). The guide used to take its
 * swipe-right glyph from the legacy `CollectionAction` while its label came from the
 * [SwipeRightVerb], so the two could disagree.
 */
object SwipeActionIcons {
    fun right(verb: SwipeRightVerb): ImageVector = when (verb) {
        SwipeRightVerb.COPY -> Icons.Default.ContentCopy
        SwipeRightVerb.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
        SwipeRightVerb.FAVOURITE -> Icons.Default.Star
    }

    fun left(action: SwipeAction): ImageVector = when (action) {
        SwipeAction.DELETE -> Icons.Default.Delete
        SwipeAction.COPY -> Icons.Default.ContentCopy
        SwipeAction.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
    }
}
