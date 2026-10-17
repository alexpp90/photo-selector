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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import com.photoselectortoolbox.domain.guidance.LegendRow
import com.photoselectortoolbox.domain.guidance.SelectorTour
import com.photoselectortoolbox.ui.selector.ControlBlockFit
import com.photoselectortoolbox.ui.selector.SelectorCoachOverlay
import com.photoselectortoolbox.ui.selector.SelectorControlBlock
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.navigation.Screen
import com.photoselectortoolbox.ui.selector.SelectorActions
import com.photoselectortoolbox.ui.selector.SelectorSidebar
import com.photoselectortoolbox.ui.selector.SelectorSnackbarHost
import com.photoselectortoolbox.ui.selector.SidebarWidth
import com.photoselectortoolbox.ui.selector.ThreeUpSelectorLayout
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selector's chrome at forced window sizes (AD-1, AD-2, AD-4, AD-7).
 *
 * The screen is hosted in a `requiredSize` box, so the window is a function of
 * the test rather than of whichever emulator CI happens to boot (the reference
 * AVD's app area is 1480 x 844 dp, so every size below fits). Geometry is
 * `getUnclippedBoundsInRoot()`: merged accessibility bounds report a clipped
 * node as smaller than it was laid out, which is how a squashed control passes.
 */
@RunWith(AndroidJUnit4::class)
class SelectorForcedSizeTest {

    @get:Rule
    val composeRule = createComposeRule()

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
    ) {
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
                                maximisedFrame = null,
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
        host(width, height)
        assertHostedControlBlockFits(width, height)
    }

    private fun assertHostedControlBlockFits(width: Int, height: Int) {
        val block = bounds("control_block")
        assertTrue("control_block has no height: $block", block.height > 0.dp)
        assertTrue("control_block runs off the window: $block", block.bottom <= height.dp && block.top >= 0.dp)

        val tags = controls + "position_counter"
        tags.forEach { tag ->
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().assertIsDisplayed()
            val b = bounds(tag)
            assertTrue(
                "$tag $b is not inside control_block $block at ${width}x$height",
                b.left >= block.left - 0.5.dp && b.right <= block.right + 0.5.dp &&
                    b.top >= block.top - 0.5.dp && b.bottom <= block.bottom + 0.5.dp,
            )
        }
        assertTrue("position_counter is squashed", bounds("position_counter").height >= 14.dp)

        for (i in controls.indices) for (j in i + 1 until controls.size) {
            assertTrue(
                "${controls[i]} overlaps ${controls[j]} at ${width}x$height",
                !intersects(bounds(controls[i]), bounds(controls[j])),
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
        host(1024, 560)
        assertTrue("expected the compact block at 1024x560", count("control_block_compact") > 0)
        assertHostedControlBlockFits(1024, 560)
    }

    @Test
    fun everyDestinationIsReachableAt1024x480() {
        host(1024, 480)
        Screen.all.forEach { screen ->
            val tag = "nav_${screen.route}"
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().performScrollTo()
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun snackbarIsACard_1024x720() = checkSnackbar(1024, 720)

    @Test
    fun snackbarIsACard_1280x800() = checkSnackbar(1280, 800)

    private fun checkSnackbar(width: Int, height: Int) {
        host(
            width, height,
            snackbar = "Moved to Selection and a deliberately long message that has to wrap " +
                "inside the card instead of spanning the window edge to edge",
        )
        composeRule.onAllNodesWithTag("selector_snackbar", useUnmergedTree = true).onFirst().assertIsDisplayed()
        val card = bounds("selector_snackbar")
        assertTrue("snackbar ${card.width} wide at ${width}x$height", card.width in 340.dp..480.dp)
        assertTrue(
            "snackbar $card intersects the sidebar ${bounds("selector_sidebar")}",
            !intersects(card, bounds("selector_sidebar")),
        )
    }

    @Test
    fun firstRunPillClearsEveryMaximiseBadge_1280x800() = checkPill(1280, 800)

    @Test
    fun firstRunPillClearsEveryMaximiseBadge_1024x720() = checkPill(1024, 720)

    @Test
    fun firstRunPillClearsEveryMaximiseBadge_1024x560() = checkPill(1024, 560)

    private fun checkPill(w: Int, h: Int) {
        host(w, h, showPill = true)
        composeRule.onAllNodesWithTag("first_run_hint", useUnmergedTree = true).onFirst().assertIsDisplayed()
        val pill = bounds("first_run_hint")
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

    private fun hostBlock(availableHeight: Int, fontScale: Float) {
        composeRule.setContent {
            PhotoSelectorToolboxTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Box(Modifier.width(388.dp).requiredHeight(availableHeight.dp)) {
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

    private fun assertPriceMatchesMeasurement(marker: String, price: androidx.compose.ui.unit.Dp, height: Int, fontScale: Float) {
        hostBlock(height, fontScale)
        assertTrue("expected $marker at ${height}dp / scale $fontScale", count(marker) > 0)
        val measured = bounds(marker).let { it.bottom - it.top }
        assertTrue(
            "$marker priced at $price but measured $measured (scale $fontScale): a price below the " +
                "measurement clips a control on a device",
            price >= measured - 0.5.dp,
        )
        assertTrue(
            "$marker priced at $price but measured only $measured (scale $fontScale): " +
                "the price is stale by more than 8dp",
            price - measured <= 8.dp,
        )
    }

    @Test
    fun fullPriceMatchesTheMeasuredBlock_scale1() =
        assertPriceMatchesMeasurement("control_block_full", ControlBlockFit.fullHeight(1f), 1000, 1f)

    @Test
    fun fullPriceMatchesTheMeasuredBlock_scale1_3() =
        assertPriceMatchesMeasurement("control_block_full", ControlBlockFit.fullHeight(1.3f), 1000, 1.3f)

    @Test
    fun compactPriceMatchesTheMeasuredBlock_scale1() =
        assertPriceMatchesMeasurement("control_block_compact", ControlBlockFit.compactHeight(1f), 250, 1f)

    @Test
    fun compactPriceMatchesTheMeasuredBlock_scale1_3() =
        assertPriceMatchesMeasurement("control_block_compact", ControlBlockFit.compactHeight(1.3f), 250, 1.3f)

    // ── AD-3: the paged coach guide ──────────────────────────────────────

    private val frameTags = listOf("selector.frame.current", "selector.frame.previous", "selector.frame.next")

    private fun checkGuide(width: Int, height: Int, fontScale: Float? = null) {
        host(width, height, guide = true, fontScale = fontScale)
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
            assertTrue("footer is ${footer.height} tall", footer.height >= 48.dp)
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
            assertTrue("$primary is ${bounds(primary).height} tall", bounds(primary).height >= 48.dp)
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
            screenLegend = Screen.all.map { LegendRow(it.label, it.meaning) },
        )
        expected.forEach { callout ->
            assertTrue(
                "no page shows the \"${callout.title}\" callout (pages=$pages, saw $seenDescriptions)",
                seenDescriptions.any { it.contains(callout.title) },
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
