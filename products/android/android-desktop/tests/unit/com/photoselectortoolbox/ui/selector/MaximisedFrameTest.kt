package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorFrame
import com.photoselectortoolbox.viewmodel.SelectorHintUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The maximised frame: the whole image region, the readout on the photograph, and a
 * switch that turns the readout off (user report 2026-11-03: "in full-screen mode the
 * exif data should be an overlay that can be turned off. Currently it makes the image
 * much smaller").
 *
 * The layout is hosted directly in a `requiredSize` box, so the region is a function of
 * the test and not of the Robolectric display. The region the frame is offered is the box
 * minus the 8 dp outer padding on each side.
 *
 * Geometry is `getUnclippedBoundsInRoot()`. Window-bounds geometry alone does not prove
 * that nothing covers the photograph (palette 2026-11-03), so what lies on the frame is
 * asserted separately: the panel and chips are inside it, and none of the three
 * controls (toggle, badge) shares bounds with them or with each other.
 */
@Requirement("REQ-AND-LAYOUT.06", "REQ-AND-LAYOUT.19")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1480dp-h900dp-xhdpi")
class MaximisedFrameTest {

    @get:Rule
    val rule = createComposeRule()

    private var detailsVisible by mutableStateOf(true)
    private var details = 0
    private val maximiseCalls = mutableListOf<SelectorFrame>()
    private var previousCalls by mutableIntStateOf(0)
    private var nextCalls by mutableIntStateOf(0)
    private var longPresses by mutableIntStateOf(0)
    private var dismissedHint by mutableIntStateOf(0)

    private fun photo(n: Int, scanned: Boolean = true) = ImageItem(
        uri = "content://test/maximised/image$n.jpg",
        fileName = "image$n.jpg",
        fileSize = 1,
        lastModified = n.toLong(),
        mimeType = "image/jpeg",
        imageWidth = 1920,
        imageHeight = 1280,
        exifData = ExifData(
            shutterSpeed = 0.004,
            aperture = 2.8,
            iso = 400,
            focalLength = 50.0,
            focalLength35mm = 50.0,
            lens = "FE 50mm F1.2 GM",
            isFallback = false,
        ),
        scanResult = if (scanned) {
            ScanResult(
                filePath = "content://test/maximised/image$n.jpg",
                sharpnessScore = 64.0,
                noiseLevel = 1.2,
                highlightClipping = 0.4,
                shadowClipping = 0.2,
            )
        } else {
            null
        },
    )

    private fun host(
        width: Int,
        height: Int,
        pending: SelectorHintUi? = null,
        scanned: Boolean = true,
        frame: SelectorFrame = SelectorFrame.CURRENT,
    ) {
        rule.setContent {
            PhotoSelectorToolboxTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    ThreeUpSelectorLayout(
                        current = photo(2, scanned),
                        previous = photo(1, scanned),
                        next = photo(3, scanned),
                        currentIndex = 1,
                        total = 3,
                        filingAction = FilingAction.COPY,
                        folderName = "Maximised",
                        burstLabel = null,
                        detailsVisible = detailsVisible,
                        filmstripVisible = false,
                        overlayValuesVisible = true,
                        maximisedFrame = frame,
                        actions = SelectorActions(
                            onMove = {},
                            onCopy = {},
                            onDelete = {},
                            onFullscreen = {},
                            onToggleDetails = { detailsVisible = !detailsVisible; details++ },
                            onToggleFilmstrip = {},
                            onToggleOverlayValues = {},
                            onShowShortcuts = {},
                        ),
                        onNavigatePrevious = { previousCalls++ },
                        onNavigateNext = { nextCalls++ },
                        onMaximise = { maximiseCalls += it },
                        onLongPressFrame = { longPresses++ },
                        showFirstRunHint = false,
                        onDismissFirstRunHint = {},
                        pendingHint = pending,
                        onDismissHint = { dismissedHint++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()

    private fun count(tag: String): Int =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun intersects(a: DpRect, b: DpRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun inside(inner: DpRect, outer: DpRect) =
        inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom

    /** The size of the frame the solver gives a 1.5 photograph in the region of a [w] x [h] host. */
    private fun fullRegionFrame(w: Int, h: Int) =
        FrameGeometry.maximisedFrameSize((w - 16).dp, (h - 16).dp, 1.5f)

    // ── The frame takes the whole region, details or not ─────────────────

    private fun checkFrameIsTheWholeRegion(w: Int, h: Int) {
        val expected = fullRegionFrame(w, h)
        host(w, h)
        val withDetails = bounds("maximised_frame")
        assertEquals("width with details at ${w}x$h", expected.width.value, withDetails.width.value, 0.6f)
        assertEquals("height with details at ${w}x$h", expected.height.value, withDetails.height.value, 0.6f)

        rule.runOnUiThread { detailsVisible = false }
        rule.waitForIdle()
        val without = bounds("maximised_frame")
        assertEquals("width without details", withDetails.width.value, without.width.value, 0.6f)
        assertEquals("height without details", withDetails.height.value, without.height.value, 0.6f)

        // What the frame was before: the 388 dp readout column and a gap came off the width.
        val before = FrameGeometry.maximisedFrameSize(
            (w - 16).dp - FrameGeometry.FlankWidth - FrameGeometry.Gap,
            (h - 16).dp,
            1.5f,
        )
        assertTrue(
            "frame ${withDetails.width} x ${withDetails.height} must beat the old ${before.width} x ${before.height}",
            withDetails.width.value > before.width.value + 50f && withDetails.height.value > before.height.value + 50f,
        )
    }

    @Test
    fun `the frame is the whole region at 1280x800 with details on and off`() = checkFrameIsTheWholeRegion(1280, 800)

    @Test
    fun `the frame is the whole region at 1480x844 with details on and off`() = checkFrameIsTheWholeRegion(1480, 844)

    // ── What lies on the frame ───────────────────────────────────────────

    private fun checkOverlayGeometry(w: Int, h: Int) {
        host(w, h)
        val frame = bounds("maximised_frame")
        val panel = bounds("maximised_info_overlay")
        val chips = bounds("maximised_score_chips")
        val toggle = bounds("maximised_info_toggle")
        val badge = bounds("maximise_badge")

        assertTrue("panel $panel outside frame $frame at ${w}x$h", inside(panel, frame))
        assertTrue("chips $chips outside frame $frame at ${w}x$h", inside(chips, frame))
        assertTrue("toggle $toggle outside frame $frame at ${w}x$h", inside(toggle, frame))
        assertTrue("badge $badge outside frame $frame at ${w}x$h", inside(badge, frame))

        val named = listOf("panel" to panel, "chips" to chips, "toggle" to toggle, "badge" to badge)
        for (i in named.indices) for (j in i + 1 until named.size) {
            assertTrue(
                "${named[i].first} ${named[i].second} intersects ${named[j].first} ${named[j].second} at ${w}x$h",
                !intersects(named[i].second, named[j].second),
            )
        }

        // The panel is the compact card of the fullscreen viewer, never a bar.
        assertTrue("panel ${panel.width} wider than 35% of the frame ${frame.width}", panel.width.value <= frame.width.value * 0.35f + 0.6f)
        // Photo-corner placement: panel top-left, chips bottom-left, controls bottom-right.
        assertTrue("panel is not at the top-left", (panel.left - frame.left).value < 16f && (panel.top - frame.top).value < 16f)
        assertTrue("chips are not at the bottom-left", (chips.left - frame.left).value < 16f && (frame.bottom - chips.bottom).value < 16f)
        assertTrue("toggle is left of the badge", toggle.right <= badge.left)

        // 48 dp targets, in whole pixels at the test density.
        val density = rule.density.density
        listOf("maximised_info_toggle", "maximise_badge").forEach { tag ->
            val b = bounds(tag)
            assertTrue("$tag is under 48 dp wide", (b.width.value * density).roundToInt() >= (48 * density).roundToInt())
            assertTrue("$tag is under 48 dp high", (b.height.value * density).roundToInt() >= (48 * density).roundToInt())
        }
    }

    @Test
    fun `panel chips toggle and badge sit on the frame and never overlap at 1280x800`() = checkOverlayGeometry(1280, 800)

    @Test
    fun `panel chips toggle and badge sit on the frame and never overlap at 1480x844`() = checkOverlayGeometry(1480, 844)

    @Test
    fun `panel chips toggle and badge sit on the frame and never overlap at 600x400`() = checkOverlayGeometry(600, 400)

    @Test
    fun `no readout column is drawn beside the frame`() {
        host(1280, 800)
        assertEquals(0, count("current_readout"))
        // And the neighbour frames are not drawn: one frame, whole region.
        assertEquals(0, count("column_previous"))
    }

    // ── The switch ───────────────────────────────────────────────────────

    @Test
    fun `the toggle hides and shows the overlay and changes the same setting as three-up`() {
        host(1280, 800)
        assertEquals(1, count("maximised_info_overlay"))
        rule.onNodeWithTag("maximised_info_toggle").assertIsOn()
        rule.onAllNodes(hasContentDescription("Hide photo info")).onFirst()

        rule.onNodeWithTag("maximised_info_toggle").performClick()
        rule.waitForIdle()
        assertEquals("the toggle calls the details action", 1, details)
        assertEquals(0, count("maximised_info_overlay"))
        assertEquals(0, count("maximised_score_chips"))
        rule.onNodeWithTag("maximised_info_toggle").assertIsOff()
        rule.onAllNodes(hasContentDescription("Show photo info")).onFirst()
        // Off = nothing over the photograph but the restore badge and the toggle.
        assertEquals(1, count("maximise_badge"))
        assertEquals(1, count("maximised_info_toggle"))

        rule.onNodeWithTag("maximised_info_toggle").performClick()
        rule.waitForIdle()
        assertEquals(2, details)
        assertEquals(1, count("maximised_info_overlay"))
        assertEquals(1, count("maximised_score_chips"))
    }

    @Test
    fun `the toggle does not exit the maximised state and the badge still does`() {
        host(1280, 800)
        rule.onNodeWithTag("maximised_info_toggle").performClick()
        assertTrue("toggle must not exit", maximiseCalls.isEmpty())

        rule.onNodeWithTag("maximise_badge").performClick()
        assertEquals(listOf(SelectorFrame.CURRENT), maximiseCalls)
    }

    @Test
    fun `a tap on the photograph exits and a long press opens the menu`() {
        host(1280, 800)
        rule.onNodeWithTag("maximised_frame").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        assertEquals(listOf(SelectorFrame.CURRENT), maximiseCalls)

        rule.onNodeWithTag("maximised_frame").performTouchInput { longClick(Offset(width / 2f, height / 2f)) }
        assertEquals(1, longPresses)
    }

    @Test
    fun `the position counts the maximised frame not the current one`() {
        host(1280, 800, frame = SelectorFrame.NEXT)
        val text = rule.onAllNodesWithTag("maximised_position", useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        assertEquals("3 / 3", text)
    }

    // ── Swipe still works under the overlay ──────────────────────────────

    @Test
    fun `a swipe that starts on the info panel navigates`() {
        host(1280, 800)
        val frame = bounds("maximised_frame")
        val panel = bounds("maximised_info_overlay")
        val density = rule.density.density
        val start = Offset(
            x = (panel.left - frame.left + panel.width / 2).value * density,
            y = (panel.top - frame.top + panel.height / 2).value * density,
        )
        rule.onNodeWithTag("maximised_frame").performTouchInput {
            down(start)
            moveBy(Offset(-300f, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals("swipe left over the panel goes to the next photograph", 1, nextCalls)

        rule.onNodeWithTag("maximised_frame").performTouchInput {
            down(start)
            moveBy(Offset(300f, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals("swipe right over the panel goes to the previous photograph", 1, previousCalls)
    }

    @Test
    fun `a swipe that starts on the score chips navigates`() {
        host(1280, 800)
        val frame = bounds("maximised_frame")
        val chips = bounds("maximised_score_chips")
        val density = rule.density.density
        val start = Offset(
            x = (chips.left - frame.left + chips.width / 2).value * density,
            y = (chips.top - frame.top + chips.height / 2).value * density,
        )
        rule.onNodeWithTag("maximised_frame").performTouchInput {
            down(start)
            moveBy(Offset(-300f, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, nextCalls)
        assertTrue("a swipe is not a tap", maximiseCalls.isEmpty())
    }

    // ── The one-time maximise card lives in the side slack or not at all ──

    private val hint = SelectorHintUi(SelectorHint.MAXIMISE, "One frame, full size", "Esc returns to comparing all three.")

    @Test
    fun `the maximise card is drawn left of the frame where the slack is wide enough`() {
        // 1480 x 400: a 1464 x 384 region holds a 576 x 384 frame, 444 dp of slack a side.
        host(1480, 400, pending = hint)
        val card = bounds("selector_hint_card")
        val frame = bounds("maximised_frame")
        assertTrue("card $card enters frame $frame", !intersects(card, frame))
        assertTrue("card $card is not left of the frame", card.right <= frame.left)
        assertTrue("card narrower than its minimum: ${card.width}", card.width.value >= 240f)
    }

    @Test
    fun `the maximise card is not drawn where there is no slack for it`() {
        // 1280 x 800: a 1176 x 784 frame leaves no slack at all.
        host(1280, 800, pending = hint)
        assertEquals(0, count("selector_hint_card"))
        assertEquals("an undrawn hint is not dismissed, so it is not marked seen", 0, dismissedHint)
    }

    @Test
    fun `the card does not depend on the details switch`() {
        detailsVisible = false
        host(1480, 400, pending = hint)
        assertEquals(1, count("selector_hint_card"))
        val card = bounds("selector_hint_card")
        val frame = bounds("maximised_frame")
        assertTrue("card $card frame $frame", abs(card.right.value - frame.left.value) < 30f)
    }

    // ── Chips are optional ───────────────────────────────────────────────

    @Test
    fun `a photograph with no scores shows the info panel and no chips`() {
        host(1280, 800, scanned = false)
        assertEquals(1, count("maximised_info_overlay"))
        assertEquals(0, count("maximised_score_chips"))
    }
}
