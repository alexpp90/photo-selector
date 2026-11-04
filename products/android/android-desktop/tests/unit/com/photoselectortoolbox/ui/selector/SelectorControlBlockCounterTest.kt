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
        // Where the locator row is dropped the counter is the control itself.
        host(width = 300.dp, height = 240.dp)

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

    // Heights measured under Robolectric's fonts. The FULL block is now its analytic price
    // (3 x 80 + 50 + the 48 dp locator row + 4 x 6 = 724 px at xhdpi): the counter line it
    // used to end in is inside the Go chip. The two counter-only compact blocks are exactly
    // what they were before the counter became a button, 402 and 540 px.

    @Test
    fun `the full block is exactly its price with the locator row`() {
        host(width = 388.dp, height = 420.dp)

        assertEquals(724, blockHeightPx())
        assertEquals(ControlBlockFit.fullHeightPx(rule.density.density), blockHeightPx())
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
