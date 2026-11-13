package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every row of the Scan sheet is a 48 dp hit target (AD-17), in the tablet dialog and in the phone
 * bottom sheet. Once the rows lost their method subtitle they were 36 dp tall, and the device run of
 * 2026-11-13 failed `SelectorScreenTest#scanImages_…` on the Xperia XA2 and the Moto G77 with three
 * "This item's height is 40dp" results: the framework widens a small target to 48 dp only as far as
 * its neighbours allow, and the rows sat on a 40 dp pitch. Whole pixels at the test's density.
 * NATIVE graphics, so the text is measured as on a device. No font-scale case: the dialog and the
 * sheet are windows of their own and take their density from the window, not from a provider here.
 */
@Requirement("REQ-AND-ALGO.07")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class ScanSheetTargetsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun Dp.px(): Int = (value * rule.density.density).roundToInt()

    private fun assertRowsAreTargets(isExpanded: Boolean) {
        rule.setContent {
            PhotoSelectorToolboxTheme {
                ScanConfigSheet(onStartScan = {}, onDismiss = {}, isExpanded = isExpanded)
            }
        }
        rule.waitForIdle()
        val rows = rule.onAllNodesWithTag("scan_row", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("the sheet has one row per measurement", ScanSheetText.all.size, rows.size)
        val bounds = rule.onAllNodesWithTag("scan_row", useUnmergedTree = true).let { nodes ->
            rows.indices.map { nodes[it].getUnclippedBoundsInRoot() }
        }
        val where = if (isExpanded) "dialog" else "bottom sheet"
        bounds.forEachIndexed { i, b ->
            val height = b.bottom - b.top
            assertTrue(
                "row $i of the $where is $height tall; a hit target is 48 dp",
                height.px() >= ScanRowMinHeight.px(),
            )
        }
        bounds.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("rows $i and ${i + 1} of the $where overlap", a.bottom <= b.top)
        }
    }

    @Test
    fun `the dialog's rows are 48 dp targets`() = assertRowsAreTargets(isExpanded = true)

    @Test
    fun `the bottom sheet's rows are 48 dp targets`() = assertRowsAreTargets(isExpanded = false)
}
