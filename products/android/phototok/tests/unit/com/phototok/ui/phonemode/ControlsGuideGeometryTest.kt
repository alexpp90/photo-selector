package com.phototok.ui.phonemode

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's orientation is a function of the box it is given, not of the device. */
class ControlsGuideGeometryTest {

    private fun geometry(
        anchors: ControlsGuideAnchors?,
        width: Float,
        height: Float,
        cameraInfo: Boolean = true,
    ) = guideGeometry(
        anchors = anchors,
        origin = Offset.Zero,
        density = 1f,
        width = width,
        height = height,
        topInset = 0f,
        bottomInset = 0f,
        cameraInfo = cameraInfo,
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

    @Test
    fun casual_dropsTheLogoAndInfoCallouts_evenFromMeasuredAnchors() {
        val anchors = ControlsGuideAnchors()
        anchors.bounds[GuideAnchor.TOP_BAR] = Rect(0f, 0f, 400f, 64f)
        anchors.bounds[GuideAnchor.LOGO] = Rect(16f, 8f, 52f, 44f)
        anchors.bounds[GuideAnchor.INFO] = Rect(300f, 8f, 348f, 56f)
        anchors.bounds[GuideAnchor.SETTINGS] = Rect(348f, 8f, 396f, 56f)
        val casual = geometry(anchors, 400f, 800f, cameraInfo = false)
        assertFalse(GuideAnchor.LOGO in casual.anchors)
        assertFalse(GuideAnchor.INFO in casual.anchors)
        assertTrue(GuideAnchor.SETTINGS in casual.anchors)
        val pro = geometry(anchors, 400f, 800f, cameraInfo = true)
        assertTrue(GuideAnchor.LOGO in pro.anchors && GuideAnchor.INFO in pro.anchors)
    }

    @Test
    fun casual_nominalGeometryHasNoInfoControls() {
        for ((w, h) in listOf(320f to 480f, 480f to 320f)) {
            val g = geometry(null, w, h, cameraInfo = false)
            assertFalse(GuideAnchor.LOGO in g.anchors)
            assertFalse(GuideAnchor.INFO in g.anchors)
        }
    }
}
