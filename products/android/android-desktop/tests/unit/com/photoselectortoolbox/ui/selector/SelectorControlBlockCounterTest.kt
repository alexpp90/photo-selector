package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.domain.interaction.FilingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The position counter opens "Go to photo" without costing the block a pixel:
 * the click is a modifier on the existing node, so [ControlBlockFit]'s prices —
 * and with them the chosen variant — are what they were. Hosted at fixed sizes,
 * so it runs on CI's phone-class emulator and under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "xhdpi")
class SelectorControlBlockCounterTest {

    @get:Rule
    val rule = createComposeRule()

    private var opened = 0

    private fun host(width: Dp, height: Dp) {
        opened = 0
        rule.setContent {
            Box(Modifier.width(width).height(height)) {
                SelectorControlBlock(
                    actions = SelectorActions({}, {}, {}, {}, {}, {}, {}, {}, onGoToPhoto = { opened++ }),
                    filingAction = FilingAction.COPY,
                    position = 3,
                    total = 12,
                    canGoPrevious = true,
                    canGoNext = true,
                    detailsVisible = true,
                    filmstripVisible = true,
                    overlayValuesVisible = true,
                    onNavigatePrevious = {},
                    onNavigateNext = {},
                    modifier = Modifier.width(width).height(height),
                )
            }
        }
    }

    private fun px(dp: Dp): Int = with(rule.density) { dp.roundToPx() }

    private fun blockHeightPx(): Int = rule.onNodeWithTag("control_block", useUnmergedTree = true)
        .fetchSemanticsNode().size.height

    @Test
    fun `clicking the counter in the full block opens the dialog`() {
        host(width = 388.dp, height = 420.dp)
        rule.onNodeWithTag("control_block_full", useUnmergedTree = true).assertIsDisplayed()

        rule.onNodeWithTag("position_counter", useUnmergedTree = true).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `clicking the counter in the compact block opens the dialog`() {
        host(width = 300.dp, height = 240.dp)
        rule.onNodeWithTag("control_block_compact", useUnmergedTree = true).assertIsDisplayed()

        rule.onNodeWithTag("position_counter", useUnmergedTree = true).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the counter is a button that still speaks its position`() {
        host(width = 388.dp, height = 420.dp)

        rule.onNode(hasTestTag("position_counter") and hasClickAction(), useUnmergedTree = true)
            .assertIsDisplayed()
        val description = rule.onNodeWithTag("position_counter", useUnmergedTree = true)
            .fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Image 3 of 12"), description)
    }

    @Test
    fun `beside the toggles the counter is a 48 dp slot`() {
        host(width = 300.dp, height = 240.dp)
        val size = rule.onNodeWithTag("position_counter", useUnmergedTree = true).fetchSemanticsNode().size

        assertTrue("${size.width}x${size.height} px, needs ${px(48.dp)}", size.width >= px(48.dp) && size.height >= px(48.dp))
    }

    // Heights measured on the control block as it was before the counter became
    // clickable (HEAD, same Robolectric xhdpi window): FULL 668, COMPACT 402,
    // narrow COMPACT 540 px. Under Robolectric's fonts those sit 4 and 9 px above
    // the analytic price for FULL and narrow COMPACT, which is why they are pinned
    // to the measurement rather than to [ControlBlockFit]'s price; the price itself
    // is asserted against real fonts by `SelectorForcedSizeTest` on a device.

    @Test
    fun `the full block is exactly as tall as before the counter was clickable`() {
        host(width = 388.dp, height = 420.dp)

        assertEquals(668, blockHeightPx())
    }

    @Test
    fun `the compact block with the counter beside the toggles is exactly as tall as before`() {
        host(width = 300.dp, height = 240.dp)

        assertEquals(402, blockHeightPx())
        assertTrue(
            blockHeightPx() <= ControlBlockFit.compactHeightPx(rule.density.fontScale, rule.density.density, 280.dp),
        )
    }

    @Test
    fun `the narrow compact block with the counter on its own line is exactly as tall as before`() {
        host(width = 150.dp, height = 300.dp)

        assertEquals(540, blockHeightPx())
    }

    @Test
    fun `which variant a window gets did not change`() {
        // Pure function of the height, width and scale handed over: the click added
        // no layout, so the thresholds are the ones the fit has always had.
        val density = 2f
        assertEquals(ControlBlockFit.Mode.FULL, ControlBlockFit.mode(420.dp, 1f, density, 368.dp))
        assertEquals(ControlBlockFit.Mode.COMPACT, ControlBlockFit.mode(240.dp, 1f, density, 280.dp))
        assertEquals(ControlBlockFit.Mode.SCROLLING, ControlBlockFit.mode(150.dp, 1f, density, 280.dp))
    }
}
