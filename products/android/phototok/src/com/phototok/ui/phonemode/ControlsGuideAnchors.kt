package com.phototok.ui.phonemode

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** Every control the controls guide can label, plus the outlines of the chrome holding them. */
enum class GuideAnchor {
    LOGO,
    DONE_WITH_CARD,
    RELOAD,
    HELP,
    INFO,
    SETTINGS,
    SOURCES,
    SELECTION,
    REVERT,

    /** Outline of the portrait top app bar. */
    TOP_BAR,

    /** Outline of the portrait bottom action bar. */
    BOTTOM_BAR,

    /** Outline of the landscape left side panel. */
    LEFT_PANEL,

    /** Outline of the landscape right side panel. */
    RIGHT_PANEL,
}

/**
 * Where the real controls are on screen, measured. The bars register themselves here and the
 * [GestureTutorialOverlay] reads it, so the callouts label the icons where they actually are
 * instead of where constants say they should be (TOK-1).
 */
@Stable
class ControlsGuideAnchors {
    /** Bounds in root coordinates (px) of every anchor currently in composition. */
    val bounds = mutableStateMapOf<GuideAnchor, Rect>()

    fun isEmpty(): Boolean = bounds.isEmpty()
}

/**
 * Registers this element's bounds as [id]; the entry is removed when the element leaves
 * composition (e.g. the SD-card button), so no callout outlives its control.
 */
fun Modifier.guideAnchor(anchors: ControlsGuideAnchors?, id: GuideAnchor): Modifier {
    if (anchors == null) return this
    return composed {
        DisposableEffect(anchors, id) {
            onDispose { anchors.bounds.remove(id) }
        }
        Modifier.onGloballyPositioned { anchors.bounds[id] = it.boundsInRoot() }
    }
}

/**
 * Dimensions shared by the real chrome and the overlay's fallback geometry. The bars read these
 * so the fallback (overlay composed without anchors) can never drift away from the real thing.
 */
object PhoneModeChromeMetrics {
    val TOP_BAR_HORIZONTAL_PADDING = 16.dp
    val TOP_BAR_VERTICAL_PADDING = 8.dp
    val TOP_BAR_LOGO_SIZE = 36.dp
    val TOP_BAR_BUTTON_SIZE = 48.dp

    val BOTTOM_BAR_HORIZONTAL_PADDING = 24.dp
    val BOTTOM_BAR_VERTICAL_PADDING = 6.dp
    val BOTTOM_BAR_BUTTON_WIDTH = 72.dp
    val BOTTOM_BAR_BUTTON_HEIGHT = 48.dp

    val LEFT_PANEL_WIDTH = 72.dp
    val RIGHT_PANEL_WIDTH = 125.dp
    val SIDE_PANEL_BUTTON_WIDTH = 64.dp
    val SIDE_PANEL_BUTTON_HEIGHT = 48.dp
    val SIDE_PANEL_BUTTON_SPACING = 16.dp
    val LEFT_PANEL_LOGO_SIZE = 32.dp
    val LEFT_PANEL_VERTICAL_PADDING = 16.dp
    val RIGHT_PANEL_PADDING = 12.dp

    /** Height of the portrait top bar below the status bar. */
    val TOP_BAR_CONTENT_HEIGHT = TOP_BAR_BUTTON_SIZE + TOP_BAR_VERTICAL_PADDING * 2

    /** Height of the portrait bottom bar above the navigation bar. */
    val BOTTOM_BAR_CONTENT_HEIGHT = BOTTOM_BAR_BUTTON_HEIGHT + BOTTOM_BAR_VERTICAL_PADDING * 2
}
