package com.phototok.ui.phonemode

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's orientation is a function of the box it is given, not of the device. */
class ControlsGuideGeometryTest {

    private fun geometry(anchors: ControlsGuideAnchors?, width: Float, height: Float) = guideGeometry(
        anchors = anchors,
        origin = Offset.Zero,
        density = 1f,
        width = width,
        height = height,
        topInset = 0f,
        bottomInset = 0f,
    )

    @Test
    fun noAnchors_tallBox_isPortraitWithBars() {
        val g = geometry(null, 320f, 480f)
        assertFalse(g.landscape)
        assertTrue(GuideAnchor.TOP_BAR in g.anchors)
        assertTrue(GuideAnchor.BOTTOM_BAR in g.anchors)
        assertFalse(GuideAnchor.LEFT_PANEL in g.anchors)
    }

    @Test
    fun noAnchors_wideBox_isLandscapeWithSidePanels() {
        val g = geometry(null, 480f, 320f)
        assertTrue(g.landscape)
        assertTrue(GuideAnchor.LEFT_PANEL in g.anchors)
        assertFalse(GuideAnchor.TOP_BAR in g.anchors)
    }

    @Test
    fun measuredTopBar_inWideBox_staysPortrait() {
        val anchors = ControlsGuideAnchors()
        anchors.bounds[GuideAnchor.TOP_BAR] = Rect(0f, 0f, 800f, 64f)
        val g = geometry(anchors, 800f, 400f)
        assertFalse(g.landscape)
        assertTrue(GuideAnchor.TOP_BAR in g.anchors)
    }

    @Test
    fun measuredLeftPanel_inTallBox_isLandscape() {
        val anchors = ControlsGuideAnchors()
        anchors.bounds[GuideAnchor.LEFT_PANEL] = Rect(0f, 0f, 72f, 400f)
        assertTrue(geometry(anchors, 400f, 800f).landscape)
    }
}
