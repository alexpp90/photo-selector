package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SelectionViewerTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The position counter `viewer.counter` in landscape (device lab 2026-11-05: the landscape panel's
 * counter had no tag, so the lab could not read the position). Exactly one node carries the tag per
 * screen, the Surface with the Text as its direct child, and it shows "n / m+" while the folder is
 * still being enumerated, as portrait does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w851dp-h393dp-land-440dpi")
class PageCounterPillTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val photos = (1..3).map {
        ImageItem(
            uri = "content://photos/$it.jpg",
            fileName = "$it.jpg",
            fileSize = 1,
            lastModified = it.toLong(),
            mimeType = "image/jpeg",
            imageWidth = 100,
            imageHeight = 50,
        )
    }

    /** The landscape feed as `PhoneModeScreen` lays it out: the viewer without a counter, then the right panel. */
    @Composable
    private fun LandscapeFeed(isDiscovering: Boolean) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                PhoneModeViewer(
                    images = photos,
                    currentIndex = 1,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = {},
                    onRequestDelete = {},
                    showExifOverlay = false,
                    showPageCounter = false,
                    isDiscovering = isDiscovering,
                )
            }
            LandscapeRightPanel(
                exifOn = false,
                guideOpen = false,
                anchors = null,
                cameraInfoEnabled = true,
                currentImage = photos[1],
                pageIndex = 1,
                total = photos.size,
                isDiscovering = isDiscovering,
                onReload = {},
                onInfo = {},
                onSettings = {},
            )
        }
    }

    private fun counters(): List<SemanticsNode> =
        rule.onAllNodes(hasTestTag("viewer.counter"), useUnmergedTree = true).fetchSemanticsNodes()

    /**
     * The counters on screen. The pager composes the pages next to the current one (off screen,
     * outside the root), each with its own HUD; accessibility and uiautomator report only what
     * intersects the window, so the lab sees the one on screen.
     */
    private fun countersOnScreen(): List<SemanticsNode> {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        return counters().filter { it.boundsInRoot.overlaps(root) }
    }

    /** The text of the counter's direct child: the shape the lab's reader expects. */
    private fun counterText(): String {
        val counter = countersOnScreen().single()
        val child = counter.children.single()
        return child.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("")
    }

    @Test
    fun landscapeFeed_exactlyOneTaggedCounter() {
        rule.setContent { PhotoTokTheme { LandscapeFeed(isDiscovering = false) } }
        rule.waitForIdle()
        assertEquals(1, counters().size)
        assertEquals(rule.activity.getString(R.string.label_page_counter, 2, 3), counterText())
    }

    @Test
    fun landscapeFeed_showsMoreWhileDiscovering() {
        rule.setContent { PhotoTokTheme { LandscapeFeed(isDiscovering = true) } }
        rule.waitForIdle()
        assertEquals(1, counters().size)
        assertEquals(rule.activity.getString(R.string.label_page_counter_more, 2, 3), counterText())
    }

    @Test
    fun landscapeSelectionViewer_exactlyOneTaggedCounter() {
        rule.setContent {
            PhotoTokTheme {
                SelectionFolderViewer(
                    images = photos,
                    currentIndex = 0,
                    folderName = "Selection",
                    activeTab = SelectionViewerTab.SELECTION,
                    showTabs = false,
                    favouriteCount = 0,
                    selectionCount = 3,
                    showExifOverlay = false,
                    onSelectTab = {},
                    onShareBatch = {},
                    onCopyFavouritesToFolder = {},
                    onNavigate = {},
                    onClose = {},
                )
            }
        }
        rule.waitForIdle()
        val all = counters()
        println("selection viewer: ${all.size} tagged counters composed, at ${all.map { it.boundsInRoot }}")
        assertEquals(1, countersOnScreen().size)
        assertEquals(rule.activity.getString(R.string.label_page_counter, 1, 3), counterText())
    }
}
