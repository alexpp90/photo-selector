package com.photoselectortoolbox.ui.selector

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.format.ExifFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The fullscreen info overlay (REQ-AND-THEME.04): close button and a compact
 * panel carrying name, position, exposure and lens, over a photograph that owns
 * the whole window.
 *
 * `GraphicsMode.NATIVE`, because under LEGACY Robolectric measures text at about
 * one pixel a character and every width assertion passes or fails for no reason.
 * The window is the Robolectric display, so each test pins its own qualifiers.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w600dp-h480dp-xhdpi")
class FullscreenInfoOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    private fun photo(
        name: String,
        lens: String = "FE 85mm F1.4 GM",
    ) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = 1920,
        imageHeight = 1080,
        exifData = ExifData(
            shutterSpeed = 0.0000625, // the longest realistic line: 1/16000s
            aperture = 22.0,
            iso = 102400,
            focalLength = 600.0,
            focalLength35mm = 600.0,
            lens = lens,
            isFallback = false,
        ),
        scanResult = ScanResult(
            filePath = "content://test/folder/$name",
            sharpnessScore = 64.0,
            noiseLevel = 1.2,
            highlightClipping = 0.4,
            shadowClipping = 0.2,
        ),
    )

    private fun open(
        widthDp: Int,
        heightDp: Int,
        image: ImageItem,
        pinned: Boolean = false,
        hint: Boolean = false,
    ) {
        rule.setContent {
            FullscreenViewer(
                images = listOf(image),
                initialIndex = 0,
                onDismiss = {},
                onDelete = {},
                onMoveToSelection = {},
                onCopyToSelection = {},
                windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(widthDp.dp, heightDp.dp)),
                infoPinned = pinned,
                showGestureHint = hint,
            )
        }
        rule.waitForIdle()
    }

    private fun layoutOf(tag: String, index: Int = 0): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread {
            rule.onAllNodesWithTag(tag, useUnmergedTree = true)[index].fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        }
        return results.first()
    }

    private fun boundsOf(tag: String): DpRect =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()

    private fun intersects(a: DpRect, b: DpRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun tagCount(tag: String) =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /** Whether the node is as wide as its text wants to be (`hasVisualOverflow` lies for `softWrap = false`). */
    private fun assertNotCut(tag: String, index: Int = 0) {
        val wanted = kotlin.math.ceil(layoutOf(tag, index).multiParagraph.maxIntrinsicWidth).toInt()
        val actual = rule.onAllNodesWithTag(tag, useUnmergedTree = true)[index].fetchSemanticsNode().size.width
        assertTrue("$tag[$index] is $actual px wide, its text wants $wanted", actual >= wanted)
    }

    /** All four tokens (`1/16000s`, `f/22.0`, `600mm`, `ISO 102400`) present and whole. */
    private fun assertExposureWhole() {
        assertEquals("a token was dropped", 4, tagCount("exposure_token"))
        repeat(4) { assertNotCut("exposure_token", it) }
    }

    // ── EXIF and lens ────────────────────────────────────────────────────

    @Test
    @Requirement("REQ-AND-THEME.04")
    fun `at 600 dp name and position stay on one line, the lens is shown and the exposure is whole`() {
        open(600, 480, photo("IMG_20260101_123456_HDR.jpg"))

        listOf("fullscreen_file_name", "fullscreen_position", "fullscreen_lens").forEach {
            assertEquals("$it wraps", 1, layoutOf(it).lineCount)
        }
        assertNotCut("fullscreen_position")
        assertEquals("the lens is shown at 600 dp", 1, tagCount("fullscreen_lens"))
        assertEquals("FE 85mm F1.4 GM", layoutOf("fullscreen_lens").layoutInput.text.text)
        assertFalse("a short lens is not cut", layoutOf("fullscreen_lens").hasVisualOverflow)
        assertExposureWhole()
        // A long name gives way (ellipsis); it is not squeezed to nothing either.
        val nameWidth = rule.onNodeWithTag("fullscreen_file_name", useUnmergedTree = true)
            .fetchSemanticsNode().size.width
        assertTrue("name is $nameWidth px wide", nameWidth >= with(rule.density) { 96.dp.roundToPx() })
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Requirement("REQ-AND-THEME.04")
    fun `at 1280 dp the lens is there as well and nothing wraps`() {
        open(1280, 800, photo("IMG_20260101_123456_HDR.jpg"))

        assertEquals(1, tagCount("fullscreen_lens"))
        listOf("fullscreen_file_name", "fullscreen_position", "fullscreen_lens").forEach {
            assertEquals("$it wraps", 1, layoutOf(it).lineCount)
        }
        assertNotCut("fullscreen_position")
        assertNotCut("fullscreen_lens")
        assertExposureWhole()
    }

    @Test
    @Requirement("REQ-AND-THEME.04")
    fun `a long lens name ellipsises on one line and stays inside the panel`() {
        open(600, 480, photo("a.jpg", lens = "Sigma 100-400mm F5-6.3 DG DN OS | Contemporary 020"))

        assertEquals(1, layoutOf("fullscreen_lens").lineCount)
        assertTrue(layoutOf("fullscreen_lens").hasVisualOverflow)
        val panel = boundsOf("fullscreen_info_overlay")
        val lens = boundsOf("fullscreen_lens")
        assertTrue(lens.right <= panel.right && lens.left >= panel.left)
    }

    @Test
    @Requirement("REQ-AND-THEME.04")
    fun `an unknown lens draws no lens line`() {
        open(1280, 800, photo("a.jpg", lens = ExifFormatter.UNKNOWN))
        assertEquals(0, tagCount("fullscreen_lens"))
        assertEquals(4, tagCount("exposure_token"))
    }

    // ── It is a panel, not a bar ─────────────────────────────────────────

    private fun assertPanelIsNotABar(widthDp: Int, heightDp: Int) {
        open(widthDp, heightDp, photo("IMG_20260101_123456_HDR.jpg"))
        val panel = boundsOf("fullscreen_info_overlay")
        val width = (panel.right - panel.left).value
        assertTrue("panel is $width wide in a ${widthDp}dp window", width <= widthDp * 0.35f + 0.5f)
        assertTrue("panel is $width wide", width <= 420.5f)
        assertTrue("panel is $width wide", width >= minOf(280f, widthDp * 0.35f) - 0.5f)
        // At the top-left, beside the close button.
        val close = boundsOf("fullscreen_close")
        assertTrue(close.right <= panel.left)
        assertFalse(intersects(close, panel))
        assertTrue("the panel starts in the top-left corner", panel.top.value < 24f)
    }

    @Test
    @Requirement("REQ-AND-THEME.04")
    fun `the panel is capped at 35 percent of a 600 dp window`() = assertPanelIsNotABar(600, 480)

    @Test
    @Config(qualifiers = "w1024dp-h720dp-xhdpi")
    @Requirement("REQ-AND-THEME.04")
    fun `the panel is capped at 35 percent of a 1024 dp window`() = assertPanelIsNotABar(1024, 720)

    @Test
    @Config(qualifiers = "w1480dp-h924dp-xhdpi")
    @Requirement("REQ-AND-THEME.04")
    fun `the panel is capped at 420 dp in a 1480 dp window`() = assertPanelIsNotABar(1480, 924)

    // ── The photograph owns the window ───────────────────────────────────

    private fun assertImageFillsTheWindow() {
        val root = boundsOf("fullscreen_root")
        assertEquals("pager vs root", root, boundsOf("fullscreen_pager"))
        assertEquals("image vs root", root, boundsOf("fullscreen_image"))
    }

    @Test
    @Requirement("REQ-AND-THEME.04")
    fun `the image fills the root while the chrome is visible`() {
        open(600, 480, photo("a.jpg"))
        assertEquals("chrome is shown", 1, tagCount("fullscreen_info_overlay"))
        assertImageFillsTheWindow()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Requirement("REQ-AND-THEME.04")
    fun `the image fills the root while the chrome is pinned, long after the auto-hide time`() {
        open(1280, 800, photo("a.jpg"), pinned = true)
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitForIdle()
        assertEquals(1, tagCount("fullscreen_info_overlay"))
        assertImageFillsTheWindow()
    }

    @Test
    @Requirement("REQ-AND-THEME.04")
    fun `the image fills the root once the chrome has hidden`() {
        open(600, 480, photo("a.jpg"))
        rule.mainClock.advanceTimeBy(3_600)
        rule.waitForIdle()
        assertEquals(0, tagCount("fullscreen_info_overlay"))
        assertImageFillsTheWindow()
    }

    // ── Controls and guidance do not collide ─────────────────────────────

    @Test
    @Requirement("REQ-AND-THEME.04")
    fun `at 600 by 480 the panel clears the score chips, the action buttons and the gesture card`() {
        open(600, 480, photo("IMG_20260101_123456_HDR.jpg"), hint = true)

        val panel = boundsOf("fullscreen_info_overlay")
        val close = boundsOf("fullscreen_close")
        val pin = boundsOf("fullscreen_info_pin")
        val chips = boundsOf("fullscreen_score_chips")
        val hint = boundsOf("fullscreen_gesture_hint")
        val actions = boundsOf("fullscreen_actions")

        listOf("chips" to chips, "hint" to hint, "actions" to actions).forEach { (name, rect) ->
            assertFalse("panel intersects $name", intersects(panel, rect))
            assertFalse("close intersects $name", intersects(close, rect))
        }
        assertFalse("chips $chips and actions $actions share bounds", intersects(chips, actions))
        assertFalse("hint and chips share bounds", intersects(hint, chips))
        assertFalse("hint and actions share bounds", intersects(hint, actions))
        // The pin is inside the panel, at its right edge, and 48 dp.
        assertEquals(panel.right.value, pin.right.value, 0.5f)
        assertTrue((pin.right - pin.left).value >= 47.9f && (pin.bottom - pin.top).value >= 47.9f)
        assertTrue("card starts at ${hint.left}, panel ends at ${panel.right}", hint.left > panel.right)
    }

    @Test
    @Config(qualifiers = "w1024dp-h720dp-xhdpi")
    @Requirement("REQ-AND-THEME.04")
    fun `at 1024 by 720 the panel clears the gesture card and the chips`() {
        open(1024, 720, photo("a.jpg"), hint = true)
        val panel = boundsOf("fullscreen_info_overlay")
        assertFalse(intersects(panel, boundsOf("fullscreen_gesture_hint")))
        assertFalse(intersects(panel, boundsOf("fullscreen_score_chips")))
    }

    // ── Compact score chips are chips, not bars ──────────────────────────

    private fun chipBounds(): List<DpRect> {
        val chips = rule.onNodeWithTag("fullscreen_score_chips", useUnmergedTree = true).onChildren()
        val n = chips.fetchSemanticsNodes().size
        return (0 until n).map { chips[it].getUnclippedBoundsInRoot() }
    }

    private fun assertChipsAreChips(widthDp: Int, heightDp: Int, oneLine: Boolean) {
        open(widthDp, heightDp, photo("a.jpg"))
        val chips = chipBounds()
        assertEquals("four metrics, four chips", 4, chips.size)
        chips.forEach {
            val w = (it.right - it.left).value
            assertTrue("a chip is $w dp wide in a ${widthDp}dp window", w < 120f)
        }
        if (oneLine) {
            assertEquals("the chips wrapped", 1, chips.map { it.top.value.toInt() }.distinct().size)
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    @Requirement("REQ-AND-THEME.04")
    fun `at 1280 by 800 each score chip is content-sized and the row is one line`() =
        assertChipsAreChips(1280, 800, oneLine = true)

    @Test
    @Requirement("REQ-AND-THEME.04")
    fun `at 600 by 480 each score chip is content-sized`() = assertChipsAreChips(600, 480, oneLine = false)
}
