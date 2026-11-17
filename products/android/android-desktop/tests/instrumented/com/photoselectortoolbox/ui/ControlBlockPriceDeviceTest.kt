package com.photoselectortoolbox.ui

// Explicit imports only; see SelectorScreenTest for why the wildcard is banned.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.selector.CandidateStrip
import com.photoselectortoolbox.ui.selector.ControlBlockFit
import com.photoselectortoolbox.ui.selector.FilmstripWidth
import com.photoselectortoolbox.ui.selector.SelectorActions
import com.photoselectortoolbox.ui.selector.SelectorControlBlock
import com.photoselectortoolbox.ui.selector.ThumbnailTouchTarget
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The part of `SelectorForcedSizeTest` that is about the device, not the window.
 *
 * `SelectorForcedSizeTest` runs on the JVM (Robolectric, at 2x). These checks also run
 * here because what they pin is a device's own density and fonts: the control block's
 * pixel price against the block as the device draws it (1.25x, 1.75x and 2.75x each drew
 * 3 px over the dp sum, device matrix 2026-10-19), and the filmstrip's 48 dp targets in
 * whole pixels (a 48 dp slot read 47.999985 dp at 2.4375x). Every host below fits a
 * phone-class window, so the class needs no forced geometry and runs on every device.
 */
@RunWith(AndroidJUnit4::class)
class ControlBlockPriceDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val noActions = SelectorActions({}, {}, {}, {}, {}, {}, {}, {})

    private val displayDensity: Float
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun bounds(tag: String, index: Int = 0): DpRect =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)[index].getUnclippedBoundsInRoot()

    private fun count(tag: String): Int =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun intersects(a: DpRect, b: DpRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun image(n: Int) = ImageItem(
        uri = "content://test/forced/image$n.jpg",
        fileName = "image$n.jpg",
        fileSize = 1024L,
        lastModified = n.toLong(),
        mimeType = "image/jpeg",
        imageWidth = 1920,
        imageHeight = 1280,
        exifData = ExifData(shutterSpeed = 0.004, aperture = 2.8, iso = 400, isFallback = false),
    )

    private fun hostBlock(availableHeight: Int, fontScale: Float, widthDp: Int) {
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

    /** The price is never below the drawn block, and never stale by more than 8 dp. */
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
                "a price below the measurement clips a control on a device",
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

    @Test
    fun compactLocatorPriceMatchesTheMeasuredBlock_scale1() = assertPriceMatchesMeasurement(
        "control_block_compact", { ControlBlockFit.compactHeightPx(1f, it, 368.dp, withLocator = true) }, 300, 1f,
    )

    @Test
    fun compactLocatorPriceMatchesTheMeasuredBlock_scale1_3() = assertPriceMatchesMeasurement(
        "control_block_compact", { ControlBlockFit.compactHeightPx(1.3f, it, 368.dp, withLocator = true) }, 300, 1.3f,
    )

    @Test
    fun compactNarrowPriceMatchesTheMeasuredBlock_scale1() =
        assertPriceMatchesMeasurement("control_block_compact", { ControlBlockFit.compactHeightPx(1f, it, 142.dp) }, 300, 1f, 150)

    @Test
    fun compactNarrowPriceMatchesTheMeasuredBlock_scale1_3() =
        assertPriceMatchesMeasurement("control_block_compact", { ControlBlockFit.compactHeightPx(1.3f, it, 142.dp) }, 320, 1.3f, 150)

    @Test
    fun compactMidPriceMatchesTheMeasuredBlock_scale1() =
        assertPriceMatchesMeasurement("control_block_compact", { ControlBlockFit.compactHeightPx(1f, it, 240.dp) }, 230, 1f, 260)

    @Test
    fun filmstripThumbnails_are48dpTargetsThatNeverOverlap() {
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
        val density = displayDensity
        val floorPx = (ThumbnailTouchTarget.value * density).roundToInt()
        fun px(d: androidx.compose.ui.unit.Dp) = (d.value * density).roundToInt()
        val targets = (0 until n).map { bounds("filmstrip_thumbnail", it) }
        targets.forEachIndexed { i, t ->
            assertTrue(
                "thumbnail $i is a ${px(t.width)} x ${px(t.height)} px target, below $floorPx px at density $density",
                px(t.height) >= floorPx && px(t.width) >= floorPx,
            )
        }
        targets.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("thumbnail targets $i and ${i + 1} overlap: $a, $b", !intersects(a, b))
        }
        val landscape = bounds("filmstrip_thumbnail_image", 0)
        assertTrue(
            "landscape thumbnail is ${landscape.height} tall",
            abs(landscape.height.value - 40f) < maxOf(0.5f, 1f / displayDensity),
        )
    }

    private val DpRect.width get() = right - left
    private val DpRect.height get() = bottom - top
}
