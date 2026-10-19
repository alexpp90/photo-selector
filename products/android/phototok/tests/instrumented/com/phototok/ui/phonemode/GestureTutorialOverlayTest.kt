package com.phototok.ui.phonemode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phototok.R
import com.phototok.str
import com.phototok.domain.CollectionAction
import com.phototok.domain.SwipeAction
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks

@RunWith(AndroidJUnit4::class)
class GestureTutorialOverlayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun enableAccessibilityChecks() {
        composeTestRule.enableAccessibilityChecks()
    }

    /** Scope assertions to the overlay so app-bar labels behind it cannot match. */
    private fun inGuide(text: String) =
        hasText(text) and hasAnyAncestor(hasTestTag("controls_guide"))

    private val everyControlLabel
        get() = listOf(
            str(R.string.guide_camera_info),
            str(R.string.guide_reload),
            str(R.string.guide_help),
            str(R.string.guide_info),
            str(R.string.guide_settings),
            str(R.string.guide_sources),
            str(R.string.guide_selection),
            str(R.string.guide_revert),
            str(R.string.guide_next),
            str(R.string.guide_previous),
            str(R.string.guide_tap),
            str(R.string.guide_zoom),
        )

    private fun awaitGuide() {
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule
                .onAllNodes(hasTestTag("controls_guide"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun gestureTutorialOverlay_whenVisible_showsAnIconOnlyGotItButtonWithoutATitle() {
        var dismissClicked = false

        composeTestRule.setContent {
            GestureTutorialOverlay(
                visible = true,
                onDismiss = { dismissClicked = true },
            )
        }

        awaitGuide()

        // Icon-only dismiss: named for TalkBack, no visible word and no title line (REQ-TOK-UX.02/.04).
        composeTestRule.onNodeWithContentDescription(str(R.string.cd_got_it)).assertIsDisplayed()
        composeTestRule.onNode(hasTestTag("gesture_tutorial_got_it"), useUnmergedTree = true).performClick()

        assertTrue(dismissClicked)
    }

    @Test
    fun overlay_labelsEveryOnScreenControlWithoutScrolling() {
        // The guide replaced a ten-row scrolling list with coach marks placed at the
        // controls themselves — every label must therefore be on screen at once.
        composeTestRule.setContent {
            GestureTutorialOverlay(visible = true, onDismiss = {})
        }

        awaitGuide()

        everyControlLabel.forEach { label ->
            composeTestRule.onNode(inGuide(label), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun overlay_onCompact320x480Screen_displaysAllControlsAndSwipeCallouts() {
        // Default CI AVDs created without a device profile use a 320x480dp HVGA screen.
        // Every top/bottom coach mark and gesture stage callout must still be displayed.
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 320.dp, height = 480.dp)) {
                GestureTutorialOverlay(
                    visible = true,
                    onDismiss = {},
                    collectionAction = CollectionAction.MOVE,
                    collectionFolderName = "Keepers",
                    leftSwipeAction = SwipeAction.COPY,
                    leftSwipeFolderName = "Maybes",
                )
            }
        }

        awaitGuide()

        (
            everyControlLabel + listOf(
                str(R.string.label_swipe_move).uppercase(),
                "Keepers",
                "Maybes",
            )
            ).forEach { label ->
            composeTestRule.onNode(inGuide(label), useUnmergedTree = true).assertIsDisplayed()
        }
        composeTestRule.onNode(hasTestTag("gesture_tutorial_got_it"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun overlay_hasNoDescriptionLines() {
        // Explain once (REQ-TOK-UX.04): a callout is a one- or two-word name, never a
        // name plus a description line.
        composeTestRule.setContent {
            GestureTutorialOverlay(visible = true, onDismiss = {})
        }

        awaitGuide()

        composeTestRule.onNode(inGuide(str(R.string.guide_camera_info)), useUnmergedTree = true)
            .assertIsDisplayed()
        listOf("Rescan folder", "This guide", "EXIF overlay", "Pick another folder").forEach { old ->
            composeTestRule.onNode(inGuide(old), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun overlay_namesTheConfiguredSwipeActions() {
        composeTestRule.setContent {
            GestureTutorialOverlay(
                visible = true,
                onDismiss = {},
                collectionAction = CollectionAction.MOVE,
                collectionFolderName = "Keepers",
                leftSwipeAction = SwipeAction.DELETE,
            )
        }

        awaitGuide()

        // Swipe right is a MOVE here — it must never be presented as "KEEP".
        composeTestRule.onNode(inGuide(str(R.string.label_swipe_move).uppercase()), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNode(inGuide("Keepers"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNode(inGuide("KEEP"), useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.onNode(inGuide(str(R.string.label_swipe_delete).uppercase()), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun overlay_describesALeftSwipeCopyAsACopyNotADelete() {
        composeTestRule.setContent {
            GestureTutorialOverlay(
                visible = true,
                onDismiss = {},
                collectionAction = CollectionAction.MOVE,
                collectionFolderName = "Keepers",
                leftSwipeAction = SwipeAction.COPY,
                leftSwipeFolderName = "Maybes",
            )
        }

        awaitGuide()

        composeTestRule.onNode(inGuide(str(R.string.label_swipe_copy).uppercase()), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNode(inGuide("Maybes"), useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNode(inGuide(str(R.string.label_swipe_delete).uppercase()), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun phoneModeScreen_whenGuideVisible_appBarsStayVisibleForTheCoachMarksToLabel() {
        // The guide points at the real logo / help / settings / bottom-bar buttons,
        // so unlike the old full-screen card list it must NOT hide the bars — it
        // reserves their height instead. Mirrors the condition in PhoneModeScreen.
        composeTestRule.setContent {
            val isViewingSelection = false
            val isLandscapeViewer = false
            val isViewing = true

            Box(modifier = Modifier.fillMaxSize()) {
                Text("Viewer Content")

                GestureTutorialOverlay(visible = true, onDismiss = {})

                // Bars are drawn after (above) the overlay scrim.
                if (!isViewingSelection && !isLandscapeViewer) {
                    Text("Top App Bar Logo")
                    if (isViewing) Text("Bottom Nav Bar")
                }
            }
        }

        awaitGuide()

        composeTestRule.onNodeWithText("Top App Bar Logo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bottom Nav Bar").assertIsDisplayed()
    }

    @Test
    fun overlay_whenNotVisible_isAbsent() {
        composeTestRule.setContent {
            GestureTutorialOverlay(visible = false, onDismiss = {})
        }

        composeTestRule.onNode(hasTestTag("controls_guide"), useUnmergedTree = true).assertDoesNotExist()
    }
}
