package com.phototok.ui.phonemode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phototok.ui.components.ViewerBottomBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TOK-1: composes the REAL top bar, bottom bar and guide sharing one anchors object and checks
 * the callouts against the semantics bounds of the real controls.
 */
@RunWith(AndroidJUnit4::class)
class ControlsGuideAlignmentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val topControls = mapOf(
        GuideAnchor.RELOAD to "reload_folder_button",
        GuideAnchor.HELP to "help_button",
        GuideAnchor.INFO to "info_exif_button",
        GuideAnchor.SETTINGS to "settings_button",
        GuideAnchor.DONE_WITH_CARD to "done_with_card_button",
        GuideAnchor.LOGO to "top_bar_logo",
    )
    private val bottomControls = mapOf(
        GuideAnchor.SOURCES to "viewer_sources_button",
        GuideAnchor.SELECTION to "viewer_selection_button",
        GuideAnchor.REVERT to "viewer_revert_button",
    )

    private fun host(widthDp: Int, heightDp: Int, sd: Boolean) {
        composeTestRule.setContent {
            val anchors = remember { ControlsGuideAnchors() }
            Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                GestureTutorialOverlay(visible = true, onDismiss = {}, anchors = anchors)
                PhoneModeTopBar(
                    showControls = true,
                    showDoneWithCard = sd,
                    guideOpen = true,
                    exifOverlayOn = false,
                    onToggleExif = {},
                    onDoneWithCard = {},
                    onReload = {},
                    onHelp = {},
                    onSettings = {},
                    modifier = Modifier.align(Alignment.TopCenter),
                    anchors = anchors,
                )
                ViewerBottomBar(
                    canRevert = true,
                    onRevert = {},
                    onJumpToSelection = {},
                    onGoToLanding = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                    anchors = anchors,
                )
            }
        }
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodes(hasTestTag("guide_callout_RELOAD"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Anchors are measured after the first layout; the callouts follow one pass later.
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        composeTestRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().boundsInRoot

    private fun exists(tag: String) =
        composeTestRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun check(widthDp: Int, heightDp: Int, sd: Boolean) {
        host(widthDp, heightDp, sd)
        val density = composeTestRule.density.density
        val tolerance = 12f * density

        val topBar = bounds("phone_top_bar")
        val bottomBar = bounds("viewer_bottom_bar")
        val controls = (topControls + bottomControls).filterValues { exists(it) }
            .mapValues { bounds(it.value) }
        assertEquals("SD-card button present only when requested", sd, exists("done_with_card_button"))

        val callouts = mutableMapOf<GuideAnchor, Rect>()
        for (id in topControls.keys + bottomControls.keys) {
            val present = exists("guide_callout_${id.name}")
            assertEquals("callout for $id exists exactly when the control does", id in controls, present)
            if (present) callouts[id] = bounds("guide_callout_${id.name}")
        }

        for ((id, c) in callouts) {
            val control = controls.getValue(id)
            if (id in bottomControls) {
                assertTrue("$id callout ends above the bottom bar", c.bottom <= bottomBar.top + 1f)
                assertEquals("$id centred on its button", control.center.x, c.center.x, tolerance)
            } else {
                assertTrue("$id callout starts below the top bar", c.top >= topBar.bottom - 1f)
                if (id != GuideAnchor.LOGO) {
                    assertEquals("$id centred on its icon", control.center.x, c.center.x, tolerance)
                } else {
                    assertEquals("logo callout left-aligned", control.left, c.left, tolerance)
                }
            }
            controls.forEach { (other, r) ->
                assertFalse("$id callout covers the $other control", c.overlaps(r))
            }
            assertTrue("$id callout on screen horizontally", c.left >= -1f && c.right <= widthDp * density + 1f)
        }
        val list = callouts.entries.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            assertFalse("${list[i].key} and ${list[j].key} callouts overlap", list[i].value.overlaps(list[j].value))
        }
    }

    @Test
    fun portrait393x851_calloutsLabelTheRealControls() = check(393, 851, sd = false)

    @Test
    fun portrait393x851_withSdCardButton() = check(393, 851, sd = true)

    @Test
    fun compact320x480_calloutsLabelTheRealControls() = check(320, 480, sd = false)

    @Test
    fun compact320x480_withSdCardButton() = check(320, 480, sd = true)

    @Test
    fun infoCalloutIsOverTheInfoButtonNotTheHelpButton() {
        host(393, 851, sd = false)
        val info = bounds("guide_callout_INFO").center.x
        assertTrue(abs(info - bounds("info_exif_button").center.x) < abs(info - bounds("help_button").center.x))
    }

    @Test
    fun logoAndInfoButtonHaveDistinctDescriptions_andHelpDescriptionReflectsState() {
        var open by mutableStateOf(false)
        composeTestRule.setContent {
            PhoneModeTopBar(
                showControls = true,
                showDoneWithCard = false,
                guideOpen = open,
                exifOverlayOn = false,
                onToggleExif = {},
                onDoneWithCard = {},
                onReload = {},
                onHelp = {},
                onSettings = {},
            )
        }
        composeTestRule.onAllNodes(
            hasTestTag("top_bar_logo") and hasContentDescription(PhoneModeTopBarDescriptions.LOGO),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().also { assertEquals(1, it.size) }
        composeTestRule.onAllNodes(hasContentDescription("Toggle EXIF stats"), useUnmergedTree = true)
            .fetchSemanticsNodes().also { assertEquals("only the Info button", 1, it.size) }
        composeTestRule.onAllNodes(hasContentDescription("Show controls guide"), useUnmergedTree = true)
            .fetchSemanticsNodes().also { assertEquals(1, it.size) }
        composeTestRule.runOnIdle { open = true }
        composeTestRule.onAllNodes(hasContentDescription("Hide controls guide"), useUnmergedTree = true)
            .fetchSemanticsNodes().also { assertEquals(1, it.size) }
        composeTestRule.onAllNodes(hasContentDescription("Show controls guide"), useUnmergedTree = true)
            .fetchSemanticsNodes().also { assertEquals(0, it.size) }
    }

    @Test
    fun topBarButtonsAre48dp() {
        composeTestRule.setContent {
            Box(Modifier.size(320.dp, 200.dp)) {
                PhoneModeTopBar(
                    showControls = true, showDoneWithCard = true, guideOpen = false, exifOverlayOn = false,
                    onToggleExif = {}, onDoneWithCard = {}, onReload = {}, onHelp = {}, onSettings = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        val density = composeTestRule.density.density
        listOf("done_with_card_button", "reload_folder_button", "help_button", "info_exif_button", "settings_button")
            .forEach { tag ->
                assertEquals(tag, 48f * density, bounds(tag).width, 1f)
                assertEquals(tag, 48f * density, bounds(tag).height, 1f)
            }
        // Logo + five 48dp buttons + 32dp padding still fit a 320dp phone.
        assertTrue(bounds("done_with_card_button").left > bounds("top_bar_logo").right)
    }

    private fun Rect.overlaps(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    private fun abs(v: Float) = if (v < 0) -v else v
}
