package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.domain.interaction.FilingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * The locator row (`[USER 2026-11-04]`, REQ-AND-WORKFLOW.06 Sort order, .07 Go to photo): the
 * Go chip with the position counter and the Sort chip, last in the control block, right of
 * the current frame. Hosted at fixed sizes with the real block, so each rung of the ladder is
 * reached by the size alone and the tests run under Robolectric and on CI's phone-class
 * emulator alike. Proves the user path: tap a chip, read what opens, pick an entry.
 */
@Requirement("REQ-AND-WORKFLOW.06", "REQ-AND-WORKFLOW.07", "REQ-AND-LAYOUT.07")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp-xhdpi")
class SelectorLocatorRowTest {

    @get:Rule
    val rule = createComposeRule()

    private var opened = 0
    private val picked = mutableListOf<SortChoice>()

    /** The control tags that must never share bounds, the locator chips included. */
    private val controls = listOf(
        "copy_button_expanded", "move_button_expanded", "delete_button_expanded",
        "fullscreen_button", "rail_previous", "rail_next",
        "details_toggle", "filmstrip_toggle", "overlay_values_toggle", "shortcuts_button",
    )

    private fun host(
        width: Dp,
        height: Dp,
        fontScale: Float = 1f,
        menuOpenAtStart: Boolean = false,
        blurriest: Boolean = false,
    ) {
        opened = 0
        picked.clear()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                var selected by remember { mutableStateOf(SortChoice.NEWEST_FIRST) }
                var menuOpen by remember { mutableStateOf(menuOpenAtStart) }
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
                        sort = SortControl(
                            selected = selected,
                            blurriestEnabled = blurriest,
                            menuOpen = menuOpen,
                            onMenuOpenChange = { menuOpen = it },
                            onSelect = { picked += it; selected = it },
                        ),
                    )
                }
            }
        }
    }

    private fun tagged(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(tag: String): DpRect = tagged(tag).getUnclippedBoundsInRoot()

    private fun px(dp: Dp): Int = with(rule.density) { dp.roundToPx() }

    private fun px(dp: Float): Int = (dp * rule.density.density).roundToInt()

    private fun intersects(a: DpRect, b: DpRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun assertTarget48(tag: String) {
        val size = tagged(tag).fetchSemanticsNode().size
        assertTrue(
            "$tag is ${size.width} x ${size.height} px, needs ${px(48.dp)}",
            size.width >= px(48.dp) && size.height >= px(48.dp),
        )
    }

    private fun assertNoControlOverlap() {
        val tags = controls + listOf("go_chip", "sort_chip")
        for (i in tags.indices) for (j in i + 1 until tags.size) {
            assertFalse(
                "${tags[i]} ${bounds(tags[i])} overlaps ${tags[j]} ${bounds(tags[j])}",
                intersects(bounds(tags[i]), bounds(tags[j])),
            )
        }
    }

    // ── FULL + locator ───────────────────────────────────────────────────

    @Test
    fun `the full block ends in the locator row with a go chip and a sort chip`() {
        host(width = 388.dp, height = 420.dp)

        tagged("control_block_full").assertIsDisplayed()
        tagged("locator_row").assertIsDisplayed()
        tagged("go_chip").assertIsDisplayed()
        tagged("sort_chip").assertIsDisplayed()
        // Last child: nothing of the block lies below the row.
        assertTrue(bounds("locator_row").bottom >= bounds("details_toggle").bottom)
        assertEquals(bounds("control_block").bottom.value, bounds("locator_row").bottom.value, 0.6f)
        assertEquals("3 / 12", tagged("position_counter").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
    }

    @Test
    fun `tapping the go chip opens Go to photo`() {
        host(width = 388.dp, height = 420.dp)

        tagged("go_chip").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `tapping the counter itself reaches the chip, so the old target still works`() {
        host(width = 388.dp, height = 420.dp)

        tagged("position_counter").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the chips speak their action and state`() {
        host(width = 388.dp, height = 420.dp)

        assertEquals(
            listOf("Go to photo, 3 of 12"),
            tagged("go_chip").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
        assertEquals(
            listOf("Sort order: Newest first. Change"),
            tagged("sort_chip").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }

    @Test
    fun `each chip is a 48 dp target and no control shares bounds with another, full block`() {
        host(width = 388.dp, height = 420.dp)

        assertTarget48("go_chip")
        assertTarget48("sort_chip")
        assertNoControlOverlap()
    }

    @Test
    fun `the two chips abut and the row is exactly 48 dp tall`() {
        host(width = 388.dp, height = 420.dp)

        assertEquals(px(48.dp), tagged("locator_row").fetchSemanticsNode().size.height)
        val go = bounds("go_chip")
        val sort = bounds("sort_chip")
        assertTrue("chips overlap: $go $sort", go.right <= sort.left + 0.01.dp)
    }

    // ── COMPACT + locator ────────────────────────────────────────────────

    @Test
    fun `where only the compact block fits it still carries the row`() {
        host(width = 300.dp, height = 300.dp)

        tagged("control_block_compact").assertIsDisplayed()
        tagged("locator_row").assertIsDisplayed()
        tagged("go_chip").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `each chip is a 48 dp target and no control shares bounds with another, compact block`() {
        host(width = 300.dp, height = 300.dp)

        assertTarget48("go_chip")
        assertTarget48("sort_chip")
        assertNoControlOverlap()
    }

    @Test
    fun `at the narrowest row width the chips are still 48 dp targets and the counter is whole`() {
        // 192 dp inside the 4 dp padding of a block under 240 dp: the row's minimum.
        host(width = 200.dp, height = 300.dp)

        tagged("locator_row").assertIsDisplayed()
        assertTarget48("go_chip")
        assertTarget48("sort_chip")
        tagged("position_counter").assertIsDisplayed()
        assertNoControlOverlap()
    }

    @Test
    fun `font scale 1_3 keeps the row, its targets and the separation`() {
        host(width = 300.dp, height = 330.dp, fontScale = 1.3f)

        tagged("locator_row").assertIsDisplayed()
        assertTarget48("go_chip")
        assertTarget48("sort_chip")
        assertNoControlOverlap()
    }

    // ── The dropped-row rung ─────────────────────────────────────────────

    @Test
    fun `a block too short for the row keeps the counter as the go control`() {
        host(width = 300.dp, height = 230.dp)

        tagged("control_block_compact").assertIsDisplayed()
        assertFalse("no locator row at this height", exists("locator_row"))
        assertFalse(exists("sort_chip"))
        tagged("position_counter").performClick()
        assertEquals(1, opened)
        // Beside the toggles the counter is a real 48 dp slot.
        assertTarget48("position_counter")
    }

    @Test
    fun `a block too narrow for the row keeps the counter as the go control`() {
        host(width = 150.dp, height = 300.dp)

        assertFalse("no locator row at 142 dp inside", exists("locator_row"))
        tagged("position_counter").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the sort menu still opens where the row is dropped`() {
        host(width = 300.dp, height = 230.dp, menuOpenAtStart = true)

        assertFalse(exists("sort_chip"))
        tagged("sort_oldest_first").assertIsDisplayed()
        tagged("sort_oldest_first").performClick()
        assertEquals(listOf(SortChoice.OLDEST_FIRST), picked)
    }

    // ── The Sort chip and its menu ───────────────────────────────────────

    @Test
    fun `the sort chip names the order and opens the radio group`() {
        host(width = 388.dp, height = 420.dp)
        assertFalse("closed until tapped", exists("sort_oldest_first"))

        tagged("sort_chip").performClick()

        SortChoice.entries.forEach { tagged(it.tag).assertIsDisplayed() }
        val newest = tagged("sort_newest_first").fetchSemanticsNode().config
        assertEquals(true, newest.getOrNull(SemanticsProperties.Selected))
        assertEquals(androidx.compose.ui.semantics.Role.RadioButton, newest.getOrNull(SemanticsProperties.Role))
        assertEquals(
            false,
            tagged("sort_oldest_first").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected),
        )
    }

    @Test
    fun `picking an entry changes the order, closes the menu and the chip says so`() {
        host(width = 388.dp, height = 420.dp)
        tagged("sort_chip").performClick()

        tagged("sort_file_name").performClick()

        assertEquals(listOf(SortChoice.FILE_NAME), picked)
        assertFalse("the menu closes on a pick", exists("sort_file_name"))
        assertEquals(
            listOf("Sort order: File name. Change"),
            tagged("sort_chip").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }

    @Test
    fun `Blurriest first is disabled until scores exist and enabled after`() {
        host(width = 388.dp, height = 420.dp, blurriest = false)
        tagged("sort_chip").performClick()
        assertTrue(tagged("sort_blurriest_first").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
    }

    @Test
    fun `Blurriest first is chosen once scores exist`() {
        host(width = 388.dp, height = 420.dp, blurriest = true)
        tagged("sort_chip").performClick()
        assertFalse(tagged("sort_blurriest_first").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))

        tagged("sort_blurriest_first").performClick()

        assertEquals(listOf(SortChoice.BLURRIEST_FIRST), picked)
    }

    @Test
    fun `the sort chip is one word on a mid cell`() {
        // Inner 269 dp (1280 x 800): two 134 dp cells.
        host(width = 289.dp, height = 420.dp)

        assertEquals("Newest", text("sort_chip_word"))
        assertFalse("no key cap on a mid cell", exists("sort_chip_key"))
    }

    @Test
    fun `the sort chip is the phrase with the S key cap on a wide cell`() {
        // Inner 330 dp: two 165 dp cells.
        host(width = 350.dp, height = 420.dp)

        assertEquals("Newest first", text("sort_chip_word"))
        tagged("sort_chip_key").assertIsDisplayed()
        assertEquals("[S]", text("sort_chip_key"))
        tagged("go_chip_key").assertIsDisplayed()
        assertEquals("[G]", text("go_chip_key"))
    }

    @Test
    fun `the sort chip is the word and its caret alone on a narrow cell`() {
        host(width = 200.dp, height = 300.dp)

        assertEquals("Newest", text("sort_chip_word"))
        assertFalse(exists("sort_chip_key"))
        assertFalse(exists("go_chip_key"))
    }

    private fun text(tag: String): String? = tagged(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
}
