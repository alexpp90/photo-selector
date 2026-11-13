package com.photoselectortoolbox.ui

// Explicit imports only; see SelectorScreenTest for why the wildcard is banned.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import com.photoselectortoolbox.domain.guidance.LegendRow
import com.photoselectortoolbox.domain.guidance.SelectorTour
import com.photoselectortoolbox.ui.selector.CandidateStrip
import com.photoselectortoolbox.ui.selector.ControlBlockFit
import com.photoselectortoolbox.ui.selector.FilmstripWidth
import com.photoselectortoolbox.ui.selector.ThumbnailTouchTarget
import com.photoselectortoolbox.ui.selector.SelectorCoachOverlay
import com.photoselectortoolbox.ui.selector.SelectorControlBlock
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.navigation.NavEntry
import com.photoselectortoolbox.ui.selector.SelectorActions
import com.photoselectortoolbox.ui.selector.SelectorSidebar
import com.photoselectortoolbox.ui.selector.SelectorSnackbarHost
import com.photoselectortoolbox.ui.selector.SidebarWidth
import com.photoselectortoolbox.ui.selector.ThreeUpSelectorLayout
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorFrame
import com.photoselectortoolbox.ui.selector.FrameGeometry
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt
import com.photoselectortoolbox.str
import com.photoselectortoolbox.R

/**
 * The selector's chrome at forced window sizes (AD-1, AD-2, AD-4, AD-7).
 *
 * The screen is hosted in a `requiredSize` box, so the window is a function of
 * the test rather than of whichever emulator CI happens to boot (the reference
 * AVD's app area is 1480 x 844 dp, so every size below fits). Geometry is
 * `getUnclippedBoundsInRoot()`: merged accessibility bounds report a clipped
 * node as smaller than it was laid out, which is how a squashed control passes.
 */
@ExpandedWindow
@RunWith(AndroidJUnit4::class)
class SelectorForcedSizeTest {

    // Outermost: puts the display at the reference geometry, so every size below fits
    // on whatever device runs the suite (FD-T-1: on CI's phone-class emulator these tests
    // used to return early and assert nothing).
    @get:Rule(order = 0)
    val geometryRule = WindowGeometryRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    // Innermost: tells a hang in the Compose rule's teardown from one in the test (M-9, 2026-11-09).
    @get:Rule(order = 2)
    val bodyProbe: TestRule = geometryRule.bodyProbe

    private fun image(n: Int) = ImageItem(
        uri = "content://test/forced/image$n.jpg",
        fileName = "image$n.jpg",
        fileSize = 1024L,
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
            lens = "FE 50mm",
            isFallback = false,
        ),
    )

    private val noActions = SelectorActions({}, {}, {}, {}, {}, {}, {}, {})

    private fun host(
        width: Int,
        height: Int,
        showPill: Boolean = false,
        snackbar: String? = null,
        guide: Boolean = false,
        fontScale: Float? = null,
        maximised: SelectorFrame? = null,
    ): Boolean {
        // The process learns of the display change a moment after the rule has made it; wait for
        // that (state, not time: the size either arrives or the test fails with it).
        val deadline = System.currentTimeMillis() + 10_000
        while (!windowHolds(width, height) && System.currentTimeMillis() < deadline) Thread.sleep(100)
        if (!windowHolds(width, height)) {
            val seen = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
            // The process's own view is not the display's: the report says which of the two is wrong.
            throw AssertionError(
                "the window holds a $width x $height dp host (the process sees " +
                    "${seen.widthPixels / seen.density} x ${seen.heightPixels / seen.density} dp; " +
                    geometryRule.displayReport() + ")",
            )
        }
        composeRule.setContent {
            PhotoSelectorToolboxTheme {
              val base = LocalDensity.current
              CompositionLocalProvider(
                LocalDensity provides if (fontScale == null) base else Density(base.density, fontScale),
              ) {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar()
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            ThreeUpSelectorLayout(
                                current = image(2),
                                previous = image(1),
                                next = image(3),
                                currentIndex = 1,
                                total = 3,
                                filingAction = FilingAction.COPY,
                                folderName = "Forced",
                                burstLabel = null,
                                detailsVisible = true,
                                filmstripVisible = false,
                                overlayValuesVisible = true,
                                maximisedFrame = maximised,
                                actions = noActions,
                                onNavigatePrevious = {},
                                onNavigateNext = {},
                                onMaximise = {},
                                onLongPressFrame = {},
                                showFirstRunHint = showPill,
                                onDismissFirstRunHint = {},
                            )
                        }
                    }
                    SelectorSnackbarHost(
                        message = snackbar,
                        onUndo = {},
                        onDismiss = {},
                        sidebarInset = SidebarWidth,
                    )
                    var open by remember { mutableStateOf(guide) }
                    SelectorCoachOverlay(
                        visible = open,
                        filingAction = FilingAction.COPY,
                        detailsVisible = true,
                        filmstripVisible = false,
                        aspect = 1.5f,
                        onDismiss = { open = false },
                    )
                }
              }
            }
        }
        composeRule.waitForIdle()
        return true
    }

    /**
     * Whether the display can hold a [width] x [height] dp host at all.
     *
     * A `requiredSize` box larger than the window is laid out off-screen. The class is
     * [ExpandedWindow], so [WindowGeometryRule] has put the display at the reference
     * geometry and [host] fails, rather than returns, when a size does not fit.
     */
    private fun windowHolds(width: Int, height: Int): Boolean {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        return metrics.widthPixels / metrics.density >= width && metrics.heightPixels / metrics.density >= height
    }

    @Composable
    private fun Sidebar() = SelectorSidebar(
        currentRoute = null,
        groupingEnabled = false,
        hasImages = true,
        isScanning = false,
        scanStatusText = "",
        onOpenFolder = {},
        onScan = {},
        onCancelScan = {},
        onToggleGrouping = {},
        onShowLegend = {},
        onShowMenu = {},
        onNavigate = {},
    )

    private fun bounds(tag: String, index: Int = 0): DpRect =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)[index].getUnclippedBoundsInRoot()

    private fun count(tag: String): Int =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun intersects(a: DpRect, b: DpRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private val controls = listOf(
        "copy_button_expanded", "move_button_expanded", "delete_button_expanded",
        "fullscreen_button", "rail_previous", "rail_next",
        "details_toggle", "filmstrip_toggle", "overlay_values_toggle", "shortcuts_button",
    )

    // `setContent` may be called once per test, so every size is its own test.
    private fun assertControlBlockFits(width: Int, height: Int) {
        if (!host(width, height)) return
        assertHostedControlBlockFits(width, height)
    }

    private fun assertHostedControlBlockFits(width: Int, height: Int) {
        val block = bounds("control_block")
        assertTrue("control_block has no height: $block", block.height > 0.dp)
        assertTrue("control_block runs off the window: $block", block.bottom <= height.dp && block.top >= 0.dp)

        // The locator row (REQ-AND-WORKFLOW.06/.07) is the block's last child wherever the ladder
        // grants it: its two chips are controls like the rest, and are in the block, 48 dp targets
        // and clear of every other control. Where it is dropped the counter stands alone.
        val hasLocator = count("locator_row") > 0
        val chips = if (hasLocator) listOf("go_chip", "sort_chip") else emptyList()
        val tags = controls + chips + "position_counter"
        tags.forEach { tag ->
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().assertIsDisplayed()
            val b = bounds(tag)
            assertTrue(
                "$tag $b is not inside control_block $block at ${width}x$height",
                b.left >= block.left - 0.5.dp && b.right <= block.right + 0.5.dp &&
                    b.top >= block.top - 0.5.dp && b.bottom <= block.bottom + 0.5.dp,
            )
        }
        assertTrue("position_counter is squashed", bounds("position_counter").height >= 11.5.dp)

        val all = controls + chips
        for (i in all.indices) for (j in i + 1 until all.size) {
            assertTrue(
                "${all[i]} overlaps ${all[j]} at ${width}x$height",
                !intersects(bounds(all[i]), bounds(all[j])),
            )
        }
        if (hasLocator) {
            val density = displayDensity
            chips.forEach { chip ->
                val b = bounds(chip)
                // Whole pixels, as layout draws them (a 48 dp slot reads 47.999985 dp at 2.4375x).
                assertTrue(
                    "$chip is ${(b.width.value * density).roundToInt()} x ${(b.height.value * density).roundToInt()} px, " +
                        "below ${(48 * density).roundToInt()} px at ${width}x$height",
                    (b.width.value * density).roundToInt() >= (48 * density).roundToInt() &&
                        (b.height.value * density).roundToInt() >= (48 * density).roundToInt(),
                )
            }
            // The row is the last child: nothing of the block lies below it.
            assertTrue(
                "locator_row ${bounds("locator_row")} is not the last child of $block",
                bounds("locator_row").bottom.value >= block.bottom.value - 1f,
            )
        }
        // Whichever variant was chosen, exactly one marker says so.
        val markers = listOf("control_block_full", "control_block_compact", "control_block_scrolling")
            .count { count(it) > 0 }
        assertTrue("expected exactly one control_block_* marker, got $markers", markers == 1)
    }

    @Test
    fun controlBlockFits_1280x800() = assertControlBlockFits(1280, 800)

    @Test
    fun controlBlockFits_1024x720() = assertControlBlockFits(1024, 720)

    @Test
    fun controlBlockIsCompactWhereTheRowIsShort() {
        if (!host(1024, 560)) return
        assertTrue("expected the compact block at 1024x560", count("control_block_compact") > 0)
        assertHostedControlBlockFits(1024, 560)
    }

    @Test
    fun everyDestinationIsReachableAt1024x480() {
        if (!host(1024, 480)) return
        NavEntry.all.forEach { entry ->
            val tag = entry.testTag
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().performScrollTo()
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun snackbarIsACard_1024x720() = checkSnackbar(1024, 720)

    @Test
    fun snackbarIsACard_1280x800() = checkSnackbar(1280, 800)

    private fun checkSnackbar(width: Int, height: Int) {
        if (!host(
            width, height,
            snackbar = "Moved to Selection and a deliberately long message that has to wrap " +
                "inside the card instead of spanning the window edge to edge",
        )) return
        composeRule.onAllNodesWithTag("selector_snackbar", useUnmergedTree = true).onFirst().assertIsDisplayed()
        val card = bounds("selector_snackbar")
        assertTrue("snackbar ${card.width} wide at ${width}x$height", card.width in 340.dp..(480f + 1f / displayDensity).dp)
        assertTrue(
            "snackbar $card intersects the sidebar ${bounds("selector_sidebar")}",
            !intersects(card, bounds("selector_sidebar")),
        )
    }

    /**
     * The maximised frame takes the whole image region with the details on (the readout is
     * an overlay on the photograph, not a column beside it), and its overlay and switch lie
     * on the frame without sharing bounds. 1280 x 800 and 1480 x 844 dp are the two lab
     * windows; both fit the reference AVD, and on a phone-class emulator `host` returns
     * false and the test returns (no `assumeTrue`: this runner reports it as a failure).
     */
    @Test
    fun maximisedFrame_fillsTheRegionHeightWithDetailsVisible_1280x800() = checkMaximised(1280, 800)

    @Test
    fun maximisedFrame_fillsTheRegionHeightWithDetailsVisible_1480x844() = checkMaximised(1480, 844)

    private fun checkMaximised(w: Int, h: Int) {
        if (!host(w, h, maximised = SelectorFrame.CURRENT)) return
        val frame = bounds("maximised_frame")
        // The host is the window less the sidebar; the layout pads 8 dp all round.
        val region = DpRect(0.dp, 0.dp, (w - 88 - 16).dp, (h - 16).dp)
        val expected = FrameGeometry.maximisedFrameSize(region.width, region.height, 1.5f)
        // Two pixels, or 1 dp where that is less: CI's scaled-down window draws 0.69 px per dp, and
        // the frame and the 8 dp paddings each round to a whole pixel.
        val tolerance = maxOf(1f, 2f / displayDensity)
        assertTrue(
            "maximised frame ${frame.width} x ${frame.height} is not the region's ${expected.width} x ${expected.height} at ${w}x$h",
            abs(frame.width.value - expected.width.value) <= tolerance && abs(frame.height.value - expected.height.value) <= tolerance,
        )
        assertTrue(
            "maximised frame height ${frame.height} is not the region height ${region.height} at ${w}x$h",
            frame.height.value >= region.height.value - maxOf(2f, tolerance),
        )
        val panel = bounds("maximised_info_overlay")
        val toggle = bounds("maximised_info_toggle")
        val badge = bounds("maximise_badge")
        assertTrue("panel $panel is not on the frame $frame", panel.left >= frame.left && panel.top >= frame.top && panel.right <= frame.right)
        assertTrue("panel $panel shares bounds with the toggle $toggle", !intersects(panel, toggle))
        assertTrue("toggle $toggle shares bounds with the badge $badge", !intersects(toggle, badge))
        assertTrue("panel $panel shares bounds with the badge $badge", !intersects(panel, badge))
        assertTrue("the toggle is under 48 dp", toggle.width.value >= 47.5f && toggle.height.value >= 47.5f)
        assertTrue("no readout column is drawn beside the maximised frame", count("current_readout") == 0)
    }

    @Test
    fun firstRunPillClearsEveryMaximiseBadge_1280x800() = checkPill(1280, 800)

    @Test
    fun firstRunPillClearsEveryMaximiseBadge_1024x720() = checkPill(1024, 720)

    @Test
    fun firstRunPillClearsEveryMaximiseBadge_1024x560() = checkPill(1024, 560)

    private fun checkPill(w: Int, h: Int) {
        if (!host(w, h, showPill = true)) return
        composeRule.onAllNodesWithTag("first_run_hint", useUnmergedTree = true).onFirst().assertIsDisplayed()
        val pill = bounds("first_run_hint")
        assertTouchTarget("first_run_hint_dismiss", w, h)
        val badges = count("maximise_badge")
        assertTrue("no maximise badges at ${w}x$h", badges > 0)
        for (i in 0 until badges) {
            assertTrue(
                "first_run_hint $pill covers maximise_badge ${bounds("maximise_badge", i)} at ${w}x$h",
                !intersects(pill, bounds("maximise_badge", i)),
            )
        }
    }

    // ── The analytic price versus the measured block ─────────────────────

    private fun hostBlock(availableHeight: Int, fontScale: Float, widthDp: Int = 388) {
        composeRule.setContent {
            PhotoSelectorToolboxTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Box(Modifier.width(widthDp.dp).requiredHeight(availableHeight.dp)) {
                        SelectorControlBlock(
                            actions = noActions,
                            filingAction = FilingAction.COPY,
                            position = 127,
                            total = 842,
                            canGoPrevious = true,
                            canGoNext = true,
                            detailsVisible = true,
                            filmstripVisible = false,
                            overlayValuesVisible = true,
                            onNavigatePrevious = {},
                            onNavigateNext = {},
                            stillEnumerating = true,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val displayDensity: Float
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    /**
     * Compares in pixels, because that is what layout draws and what the block
     * is chosen by: each child is rounded on its own, so the dp sum is only the
     * drawn height on a 2× screen (3 px over it at 1.25×, 1.75× and 2.75×, device
     * matrix 2026-10-19). This runs on every device's own density, so the device
     * matrix is what covers the non-integer ones.
     */
    private fun assertPriceMatchesMeasurement(
        marker: String,
        pricePx: (density: Float) -> Int,
        height: Int,
        fontScale: Float,
        widthDp: Int = 388,
    ) {
        hostBlock(height, fontScale, widthDp)
        assertTrue("expected $marker at ${height}dp / scale $fontScale", count(marker) > 0)
        val density = displayDensity
        val measuredPx = ((bounds(marker).let { it.bottom - it.top }).value * density).roundToInt()
        val price = pricePx(density)
        assertTrue(
            "$marker priced at $price px but measured $measuredPx px (density $density, scale $fontScale): " +
                "a price below the measurement clips a control on a device. Parts: " +
                (controls + "position_counter").joinToString { "$it=${bounds(it)}" },
            price >= measuredPx,
        )
        assertTrue(
            "$marker priced at $price px but measured only $measuredPx px (density $density, " +
                "scale $fontScale): the price is stale by more than 8dp",
            price - measuredPx <= (8 * density).roundToInt(),
        )
    }

    @Test
    fun fullPriceMatchesTheMeasuredBlock_scale1() =
        assertPriceMatchesMeasurement("control_block_full", { ControlBlockFit.fullHeightPx(it) }, 1000, 1f)

    @Test
    fun fullPriceMatchesTheMeasuredBlock_scale1_3() =
        assertPriceMatchesMeasurement("control_block_full", { ControlBlockFit.fullHeightPx(it) }, 1000, 1.3f)

    @Test
    fun compactPriceMatchesTheMeasuredBlock_scale1() =
        assertPriceMatchesMeasurement("control_block_compact", { ControlBlockFit.compactHeightPx(1f, it) }, 250, 1f)

    @Test
    fun compactPriceMatchesTheMeasuredBlock_scale1_3() =
        assertPriceMatchesMeasurement("control_block_compact", { ControlBlockFit.compactHeightPx(1.3f, it) }, 250, 1.3f)

    // COMPACT with the locator row: 388 dp wide (inner 368), a row that holds 252 dp but not 362.
    @Test
    fun compactLocatorPriceMatchesTheMeasuredBlock_scale1() = assertPriceMatchesMeasurement(
        "control_block_compact", { ControlBlockFit.compactHeightPx(1f, it, 368.dp, withLocator = true) }, 300, 1f,
    )

    @Test
    fun compactLocatorPriceMatchesTheMeasuredBlock_scale1_3() = assertPriceMatchesMeasurement(
        "control_block_compact", { ControlBlockFit.compactHeightPx(1.3f, it, 368.dp, withLocator = true) }, 300, 1.3f,
    )

    // ── AD-8: filmstrip touch targets ───────────────────────────────────

    /**
     * Hosted on its own, so it runs on any emulator: in the real screen the strip
     * only appears on windows wide enough for it (the Pixel Tablet and the Tab S11
     * Ultra in the device matrix), and CI's phone-class emulator is not one.
     */
    @Test
    fun filmstripThumbnails_are48dpTargetsThatNeverOverlap() {
        // Landscape and portrait mixed: the 40 dp landscape thumbnail is the one
        // that needs a slot taller than itself.
        val images = (1..6).map { n ->
            if (n % 3 == 0) image(n).copy(imageWidth = 1280, imageHeight = 1920) else image(n)
        }
        composeRule.setContent {
            PhotoSelectorToolboxTheme {
                Box(Modifier.width(FilmstripWidth).requiredHeight(640.dp)) {
                    CandidateStrip(images = images, currentIndex = 0, onImageSelected = {})
                }
            }
        }
        composeRule.waitForIdle()

        val n = count("filmstrip_thumbnail")
        assertTrue("only $n thumbnails laid out", n == images.size)
        val targets = (0 until n).map { bounds("filmstrip_thumbnail", it) }
        // In whole pixels, as layout draws it: at 2.4375x a 48 dp slot is 117 px,
        // which reads back as 47.999985 dp once the bounds have been offset.
        val density = displayDensity
        val floorPx = (ThumbnailTouchTarget.value * density).roundToInt()
        fun px(d: androidx.compose.ui.unit.Dp) = (d.value * density).roundToInt()
        targets.forEachIndexed { i, t ->
            assertTrue(
                "thumbnail $i is a ${px(t.width)} x ${px(t.height)} px target, below $floorPx px " +
                    "($ThumbnailTouchTarget at density $density)",
                px(t.height) >= floorPx && px(t.width) >= floorPx,
            )
        }
        targets.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("thumbnail targets $i and ${i + 1} overlap: $a, $b", !intersects(a, b))
        }
        // The picture itself keeps its 3:2 size; only the target grew.
        val landscape = bounds("filmstrip_thumbnail_image", 0)
        assertTrue("landscape thumbnail is ${landscape.height} tall", abs(landscape.height.value - 40f) < maxOf(0.5f, 1f / displayDensity))
    }

    // Narrow flanks: 150 dp (inner 142, 2 x 2 toggles, counter on top) and 260 dp
    // (inner 240, one row of toggles, counter on top). These fit a phone-class window,
    // so they also run at 420 dpi, where 3 dp is 7.875 px.
    @Test
    fun compactNarrowPriceMatchesTheMeasuredBlock_scale1() =
        assertPriceMatchesMeasurement("control_block_compact", { ControlBlockFit.compactHeightPx(1f, it, 142.dp) }, 300, 1f, 150)

    @Test
    fun compactNarrowPriceMatchesTheMeasuredBlock_scale1_3() =
        assertPriceMatchesMeasurement("control_block_compact", { ControlBlockFit.compactHeightPx(1.3f, it, 142.dp) }, 320, 1.3f, 150)

    @Test
    fun compactMidPriceMatchesTheMeasuredBlock_scale1() =
        assertPriceMatchesMeasurement("control_block_compact", { ControlBlockFit.compactHeightPx(1f, it, 240.dp) }, 230, 1f, 260)

    // ── AD-16 / AD-17: touch targets, readable words, whole exposure tokens ──

    private val viewToggles = listOf("details_toggle", "filmstrip_toggle", "overlay_values_toggle", "shortcuts_button")

    private fun assertTouchTarget(tag: String, width: Int, height: Int) {
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().assertExists()
        val b = bounds(tag)
        assertTrue(
            "$tag is ${b.width} x ${b.height} dp at ${width}x$height; a hit target is at least 48 x 48 dp",
            b.width.value >= 47.5f && b.height.value >= 47.5f,
        )
    }

    private fun textLayout(matcher: androidx.compose.ui.test.SemanticsMatcher): List<TextLayoutResult> {
        val nodes = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            composeRule.runOnUiThread {
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            }
            results.firstOrNull()
        }
    }

    /** The word and key cap of [buttonTag] are shown whole: one line, no ellipsis, no overflow. */
    private fun assertButtonTextIsWhole(buttonTag: String, expectedWord: String?, width: Int, height: Int) {
        listOf("action_word", "action_key").forEach { part ->
            val layouts = textLayout(hasTestTag(part) and hasAnyAncestor(hasTestTag(buttonTag)))
            assertTrue("$buttonTag has no $part text at ${width}x$height", layouts.isNotEmpty())
            layouts.forEach { l ->
                val text = l.layoutInput.text.text
                assertTrue("$buttonTag $part '$text' is ellipsized at ${width}x$height", !l.isLineEllipsized(0))
                // `hasVisualOverflow` is not usable here: for wrap-content text it compares the
                // measured width with the paragraph's constraint width and is true for any
                // short word. What matters is that the one line holds every character.
                assertTrue(
                    "$buttonTag $part '$text' is cut at ${width}x$height: line end ${l.getLineEnd(0, visibleEnd = true)}" +
                        " of ${text.length}, line right ${l.getLineRight(0)} of ${l.size.width}",
                    l.getLineEnd(0, visibleEnd = true) >= text.length &&
                        l.getLineRight(0) <= l.size.width + 1f && !l.didOverflowHeight,
                )
                assertTrue("$buttonTag $part '$text' wraps at ${width}x$height", l.lineCount == 1)
                assertTrue("$buttonTag $part '$text' is not a whole word/key cap", text.isNotBlank() && !text.contains('…'))
                if (part == "action_word" && expectedWord != null) {
                    assertTrue("$buttonTag word is '$text', expected '$expectedWord'", text == expectedWord)
                }
                if (part == "action_key") {
                    assertTrue("$buttonTag key cap '$text' is cut", text.startsWith("[") && text.endsWith("]"))
                }
            }
        }
    }

    /** Every line of the exposure text starts after a separator: no token breaks in the middle. */
    private fun assertExposureTokensWhole(width: Int, height: Int) {
        listOf("1/250s", "f/2.8").forEach { token ->
            val layouts = textLayout(
                hasText(token, substring = true) and hasAnyAncestor(hasTestTag("current_readout")),
            )
            assertTrue("the current readout does not show '$token' at ${width}x$height", layouts.isNotEmpty())
            layouts.forEach { l ->
                val text = l.layoutInput.text.text
                for (line in 1 until l.lineCount) {
                    val before = text.substring(0, l.getLineStart(line)).trimEnd { it.isWhitespace() || it == '\u00A0' }
                    assertTrue(
                        "the exposure text '$text' breaks mid-token (line $line starts after '${before.takeLast(4)}') " +
                            "at ${width}x$height",
                        before.endsWith("·"),
                    )
                }
            }
        }
    }

    /** Runs [block], recording an assertion failure instead of stopping, so one run names every defect. */
    private fun soft(failures: MutableList<String>, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            failures += e.message.orEmpty()
        }
    }

    private fun checkLabSize(width: Int, height: Int, expectedMarker: String? = null) {
        if (!host(width, height, showPill = true)) return
        composeRule.onAllNodesWithTag("first_run_hint", useUnmergedTree = true).onFirst().assertIsDisplayed()
        val failures = mutableListOf<String>()
        if (expectedMarker != null) {
            // Below one pixel per dp (CI's emulator draws 0.69) a pixel is 1.45 dp, and the pixel
            // price of the compact block no longer fits the dp window it fits at a real tablet's
            // density: the designed fallback, scrolling, is then the right answer.
            val accepted = if (expectedMarker == "control_block_compact" && displayDensity < 1f) {
                listOf(expectedMarker, "control_block_scrolling")
            } else {
                listOf(expectedMarker)
            }
            soft(failures) {
                assertTrue("expected one of $accepted at ${width}x$height", accepted.any { count(it) > 0 })
            }
        }
        // AD-17: every one of these is a 48 x 48 dp hit target, at every size.
        (viewToggles + "first_run_hint_dismiss").forEach { tag ->
            soft(failures) { assertTouchTarget(tag, width, height) }
        }
        // AD-16: the word and key cap of every worded control are shown whole, in the device's
        // language (the lab phones run German: "Löschen", "Vollbild", "Zurück", "Weiter").
        listOf(
            "copy_button_expanded" to (null as String?),
            "move_button_expanded" to null,
            "delete_button_expanded" to str(R.string.action_delete),
            "fullscreen_button" to str(R.string.action_full),
            "rail_previous" to str(R.string.action_prev),
            "rail_next" to str(R.string.action_next),
        ).forEach { (tag, word) -> soft(failures) { assertButtonTextIsWhole(tag, word, width, height) } }
        soft(failures) { assertExposureTokensWhole(width, height) }
        // REQ-AND-LAYOUT.09: nothing shares bounds.
        for (i in controls.indices) for (j in i + 1 until controls.size) {
            soft(failures) {
                assertTrue(
                    "${controls[i]} overlaps ${controls[j]} at ${width}x$height",
                    !intersects(bounds(controls[i]), bounds(controls[j])),
                )
            }
        }
        // The pill clears every maximise badge and every control; every badge is a 48 dp target.
        val pill = bounds("first_run_hint")
        for (i in 0 until count("maximise_badge")) {
            soft(failures) {
                assertTrue("pill $pill covers badge ${bounds("maximise_badge", i)}", !intersects(pill, bounds("maximise_badge", i)))
                assertTrue("a maximise badge is under 48 dp", bounds("maximise_badge", i).width.value >= 47.5f)
            }
        }
        controls.forEach { tag ->
            soft(failures) {
                val drawn = drawnControlBounds(tag)
                assertTrue("pill $pill covers $tag $drawn at ${width}x$height", drawn == null || !intersects(pill, drawn))
            }
        }
        if (count("control_block_scrolling") == 0) soft(failures) { assertHostedControlBlockFits(width, height) }
        assertTrue("at ${width}x$height:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * The part of control [tag] that is drawn, or null when none is. Where the block scrolls, a
     * control below its viewport is laid out further down (its unclipped bounds say so) but clipped
     * away, and a pill there covers nothing: at 600x480 the German pill, wider than the English one,
     * reached under the flank in row 2 and "covered" the scrolled-away `details_toggle` (Pixel 4a
     * and Pixel Tablet, 2026-11-13). Sizes are still checked on the unclipped bounds.
     */
    private fun drawnControlBounds(tag: String): DpRect? {
        val b = bounds(tag)
        if (count("control_block_scrolling") == 0) return b
        val viewport = bounds("control_block_scrolling")
        val drawn = DpRect(
            left = maxOf(b.left, viewport.left),
            top = maxOf(b.top, viewport.top),
            right = minOf(b.right, viewport.right),
            bottom = minOf(b.bottom, viewport.bottom),
        )
        return drawn.takeIf { it.left < it.right && it.top < it.bottom }
    }

    @Test
    fun labTargetsAndWords_1480x844() = checkLabSize(1480, 844, "control_block_full")

    @Test
    fun labTargetsAndWords_1280x800() = checkLabSize(1280, 800, "control_block_full")

    @Test
    fun labTargetsAndWords_1024x720() = checkLabSize(1024, 720)

    @Test
    fun labTargetsAndWords_720x560() = checkLabSize(720, 560, "control_block_compact")

    @Test
    fun labTargetsAndWords_600x480() = checkLabSize(600, 480, "control_block_scrolling")

    // ── AD-3: the paged coach guide ──────────────────────────────────────

    private val frameTags = listOf("selector.frame.current", "selector.frame.previous", "selector.frame.next")

    /** 48 dp in whole pixels at the window's density, as layout draws it (33 px, not 33.3, at 0.69x). */
    private fun atLeast48DpInPixels(height: androidx.compose.ui.unit.Dp): Boolean =
        (height.value * displayDensity).roundToInt() >= (48 * displayDensity).roundToInt()

    private fun checkGuide(width: Int, height: Int, fontScale: Float? = null) {
        if (!host(width, height, guide = true, fontScale = fontScale)) return
        composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true).onFirst().assertIsDisplayed()

        val forbidden = buildList {
            add("control_block" to bounds("control_block"))
            add("selector_sidebar" to bounds("selector_sidebar"))
            frameTags.forEach { add(it to bounds(it)) }
            for (i in 0 until count("maximise_badge")) add("maximise_badge#$i" to bounds("maximise_badge", i))
        }
        val seenDescriptions = mutableListOf<String>()
        var pages = 0

        while (true) {
            pages++
            composeRule.waitForIdle()
            val drawn = buildList {
                for (i in 0 until count("coach_callout")) add("coach_callout#$i" to bounds("coach_callout", i))
                add("coach_footer" to bounds("coach_footer"))
                listOf("coach_overlay_skip", "coach_overlay_next", "coach_overlay_dismiss", "coach_page_indicator")
                    .filter { count(it) > 0 }.forEach { add(it to bounds(it)) }
            }
            drawn.forEach { (name, rect) ->
                forbidden.forEach { (other, otherRect) ->
                    assertTrue(
                        "$name $rect intersects $other $otherRect on page $pages at ${width}x$height",
                        !intersects(rect, otherRect),
                    )
                }
            }
            val cards = drawn.filter { it.first.startsWith("coach_callout") }
            for (i in cards.indices) for (j in i + 1 until cards.size) {
                assertTrue("coach cards overlap: ${cards[i]} ${cards[j]}", !intersects(cards[i].second, cards[j].second))
            }
            val footer = bounds("coach_footer")
            assertTrue("footer is ${footer.height} tall", atLeast48DpInPixels(footer.height))
            // Every page explains something, and no card runs under the footer —
            // the pager clips, so a card that overhangs it is a card cut off.
            assertTrue("page $pages at ${width}x$height carries no card", cards.isNotEmpty())
            cards.forEach { (name, rect) ->
                assertTrue(
                    "$name $rect overhangs the footer ${footer} on page $pages at ${width}x$height",
                    rect.bottom <= footer.top,
                )
            }
            val primary = if (count("coach_overlay_next") > 0) "coach_overlay_next" else "coach_overlay_dismiss"
            assertTrue("$primary is ${bounds(primary).height} tall", atLeast48DpInPixels(bounds(primary).height))
            composeRule.onAllNodesWithTag("coach_footer", useUnmergedTree = true).onFirst().assertIsDisplayed()

            composeRule.onAllNodesWithTag("coach_callout", useUnmergedTree = true).fetchSemanticsNodes().forEach {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.let { d -> seenDescriptions += d.joinToString() }
            }

            if (count("coach_overlay_next") == 0) break
            composeRule.onAllNodesWithTag("coach_overlay_next", useUnmergedTree = true).onFirst().performClick()
            assertTrue("the guide never reaches its last page", pages < 20)
        }

        val expected = SelectorTour.callouts(
            filingAction = FilingAction.COPY,
            detailsVisible = true,
            filmstripVisible = false,
            screenLegend = NavEntry.all.map { LegendRow(it.label, it.meaning) },
        )
        expected.forEach { callout ->
            assertTrue(
                "no page shows the \"${str(callout.title)}\" callout (pages=$pages, saw $seenDescriptions)",
                seenDescriptions.any { it.contains(str(callout.title)) },
            )
        }

        composeRule.onAllNodesWithTag("coach_overlay_dismiss", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) { count("selector_coach_overlay") == 0 }
    }

    @Test
    fun guideNeverTouchesControlsSidebarOrFrames_1480x844() = checkGuide(1480, 844)

    @Test
    fun guideNeverTouchesControlsSidebarOrFrames_1280x800() = checkGuide(1280, 800)

    @Test
    fun guideNeverTouchesControlsSidebarOrFrames_1024x720() = checkGuide(1024, 720)

    @Test
    fun guideNeverTouchesControlsSidebarOrFrames_1024x560() = checkGuide(1024, 560)

    @Test
    fun guideNeverTouchesControlsSidebarOrFrames_1280x800_fontScale1_3() = checkGuide(1280, 800, 1.3f)
}
