package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The locator row costs the photographs nothing (`[USER 2026-11-04]`, REQ-AND-LAYOUT.07): at
 * each window the ladder resolves differently, the three frames are the size the solver gives
 * the region *without* any control block in it, the row lies in the flank right of the current
 * frame, and it shares bounds with no frame and no other control. The real
 * [ThreeUpSelectorLayout], hosted at the window sizes the lab uses.
 */
@Requirement("REQ-AND-LAYOUT.07", "REQ-AND-LAYOUT.09", "REQ-AND-WORKFLOW.06", "REQ-AND-WORKFLOW.07")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1480dp-h900dp-xhdpi")
class SelectorLocatorFramesTest {

    @get:Rule
    val rule = createComposeRule()

    private fun photo(n: Int) = ImageItem(
        uri = "content://test/locator/image$n.jpg",
        fileName = "image$n.jpg",
        fileSize = 1,
        lastModified = n.toLong(),
        mimeType = "image/jpeg",
        imageWidth = 1500,
        imageHeight = 1000,
    )

    private val controls = listOf(
        "copy_button_expanded", "move_button_expanded", "delete_button_expanded",
        "fullscreen_button", "rail_previous", "rail_next",
        "details_toggle", "filmstrip_toggle", "overlay_values_toggle", "shortcuts_button",
    )
    private val frames = listOf("selector.frame.current", "selector.frame.previous", "selector.frame.next")

    /** The window less the sidebar, as the screen hands the layout. */
    private fun host(window: Pair<Int, Int>) {
        rule.setContent {
            PhotoSelectorToolboxTheme {
                Box(Modifier.requiredSize(window.first.dp, window.second.dp)) {
                    ThreeUpSelectorLayout(
                        current = photo(2),
                        previous = photo(1),
                        next = photo(3),
                        currentIndex = 1,
                        total = 3,
                        filingAction = FilingAction.COPY,
                        folderName = "Locator",
                        burstLabel = null,
                        detailsVisible = true,
                        filmstripVisible = false,
                        overlayValuesVisible = true,
                        maximisedFrame = null,
                        actions = SelectorActions({}, {}, {}, {}, {}, {}, {}, {}),
                        onNavigatePrevious = {},
                        onNavigateNext = {},
                        onMaximise = {},
                        onLongPressFrame = {},
                        showFirstRunHint = false,
                        onDismissFirstRunHint = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun intersects(a: DpRect, b: DpRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun check(window: Pair<Int, Int>, expectRow: Boolean, marker: String) {
        host(window)
        assertTrue("expected $marker at $window", exists(marker))
        assertEquals("locator row at $window", expectRow, exists("locator_row"))

        // The frames are exactly what the solver gives the region: the row took nothing.
        val region = FrameGeometry.threeUpLayout((window.first - 16).dp, (window.second - 16).dp, 1.5f)
        frames.forEach { tag ->
            val b = bounds(tag)
            assertTrue(
                "$tag is ${b.width} x ${b.height}, the solver's frame is ${region.frame} at $window",
                abs(b.width.value - region.frame.width.value) <= 4.1f &&
                    abs(b.height.value - region.frame.height.value) <= 4.1f,
            )
        }

        if (expectRow) {
            val row = bounds("locator_row")
            val current = bounds("selector.frame.current")
            assertTrue("row $row is not right of the current frame $current", row.left >= current.right)
            frames.forEach { assertFalse("row $row covers $it ${bounds(it)}", intersects(row, bounds(it))) }
            val all = controls + listOf("go_chip", "sort_chip")
            for (i in all.indices) for (j in i + 1 until all.size) {
                assertFalse(
                    "${all[i]} ${bounds(all[i])} overlaps ${all[j]} ${bounds(all[j])} at $window",
                    intersects(bounds(all[i]), bounds(all[j])),
                )
            }
            // Within the window: the control block does not run off row 1.
            assertTrue("row $row runs off the window $window", row.bottom <= window.second.dp && row.right <= window.first.dp)
        }
    }

    @Test
    fun `1280 x 800 gets the full block with the row`() {
        check(1280 to 800, expectRow = true, marker = "control_block_full")
    }

    @Test
    fun `the reference window gets the full block with the row`() {
        check(1480 to 844, expectRow = true, marker = "control_block_full")
    }

    @Test
    fun `1024 x 720 gets the compact block and keeps the row`() {
        // 336 dp of row is under the 363 dp the full block needs with it: compact, row kept.
        check(1024 to 720, expectRow = true, marker = "control_block_compact")
    }

    @Test
    fun `720 x 560 drops the row and keeps the counter`() {
        host(720 to 560)
        assertFalse(exists("locator_row"))
        assertTrue(exists("position_counter"))
    }

    @Test
    fun `600 x 480 drops the row and keeps the counter`() {
        host(600 to 480)
        assertFalse(exists("locator_row"))
        assertTrue(exists("position_counter"))
    }
}
