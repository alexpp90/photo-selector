package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.photoselector.core.Requirement
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SelectionViewerTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * REQ-TOK-FEATURE.17: the Selection viewer offers two tabs only when both lists have photos;
 * with one list it shows a single non-clickable title, never a switch to an empty tab. Each list
 * carries its name and its photo count.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SelectionViewerTabsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun host(
        showTabs: Boolean,
        activeTab: SelectionViewerTab,
        favourites: Int,
        selection: Int,
        onSelectTab: (SelectionViewerTab) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PhotoTokTheme {
                SelectionViewerTabs(
                    activeTab = activeTab,
                    showTabs = showTabs,
                    favouriteCount = favourites,
                    selectionCount = selection,
                    folderName = "Keepers",
                    onSelectTab = onSelectTab,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun count(tag: String) =
        composeTestRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun bothListsFilled_showTwoTabsWithNameAndCount() {
        host(showTabs = true, activeTab = SelectionViewerTab.SELECTION, favourites = 3, selection = 12)

        assertEquals(1, count("selection_tab_favourites"))
        assertEquals(1, count("selection_tab_selection"))
        assertEquals("no title next to the tabs", 0, count("selection_title"))
        composeTestRule.onAllNodes(
            hasText("Favourites 3") and hasAnyAncestor(hasTestTag("selection_tab_favourites")),
            useUnmergedTree = true,
        ).onFirst().assertIsDisplayed()
        composeTestRule.onAllNodes(
            hasText("Keepers 12") and hasAnyAncestor(hasTestTag("selection_tab_selection")),
            useUnmergedTree = true,
        ).onFirst().assertIsDisplayed()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun tapsOnTabsSelectThem() {
        val selected = mutableListOf<SelectionViewerTab>()
        host(
            showTabs = true, activeTab = SelectionViewerTab.SELECTION, favourites = 3, selection = 12,
            onSelectTab = { selected += it },
        )

        composeTestRule.onAllNodes(hasTestTag("selection_tab_favourites"), useUnmergedTree = true)
            .onFirst().performClick()
        composeTestRule.onAllNodes(hasTestTag("selection_tab_selection"), useUnmergedTree = true)
            .onFirst().performClick()

        assertEquals(listOf(SelectionViewerTab.FAVOURITES, SelectionViewerTab.SELECTION), selected)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun onlyFavourites_showAnInertTitleWithCount() {
        host(showTabs = false, activeTab = SelectionViewerTab.FAVOURITES, favourites = 3, selection = 0)

        assertEquals(0, count("selection_tab_favourites"))
        assertEquals(0, count("selection_tab_selection"))
        assertEquals(1, count("selection_title"))
        composeTestRule.onAllNodes(
            hasText("Favourites 3") and hasAnyAncestor(hasTestTag("selection_title")),
            useUnmergedTree = true,
        ).onFirst().assertIsDisplayed()
        // Nothing clickable: a title is not a switch.
        val node = composeTestRule.onAllNodes(hasTestTag("selection_title"), useUnmergedTree = true)
            .onFirst().fetchSemanticsNode()
        assertTrue(!node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun onlySelectionFolder_showsItsConfiguredNameAndCountAsTitle() {
        host(showTabs = false, activeTab = SelectionViewerTab.SELECTION, favourites = 0, selection = 12)

        assertEquals(1, count("selection_title"))
        composeTestRule.onAllNodes(
            hasText("Keepers 12") and hasAnyAncestor(hasTestTag("selection_title")),
            useUnmergedTree = true,
        ).onFirst().assertIsDisplayed()
    }
}
