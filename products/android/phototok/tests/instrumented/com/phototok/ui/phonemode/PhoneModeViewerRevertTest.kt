package com.phototok.ui.phonemode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phototok.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TOK-2: after Revert the pager must show the restored photo. The pager is keyed by URI, so
 * re-inserting the deleted photo at the current index used to make the pager follow the
 * *previously visible* photo to page k+1 and report that page back to the ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class PhoneModeViewerRevertTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun testImage(name: String) = ImageItem(
        uri = "content://media/external/images/media/$name",
        fileName = name,
        fileSize = 1024L,
        lastModified = 1000L,
        mimeType = "image/jpeg",
        imageWidth = 1920,
        imageHeight = 1080,
    )

    private val original = (0 until 12).map { testImage("DSC_%04d.jpg".format(it + 10)) }

    private var images by mutableStateOf(original)
    private var currentIndex by mutableIntStateOf(1)

    private fun host() {
        composeTestRule.setContent {
            PhoneModeViewer(
                images = images,
                currentIndex = currentIndex,
                portraitSectionStart = -1,
                onNavigate = { currentIndex = it },
                onAddToCollection = {},
                onRequestDelete = {},
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun assertShowing(index: Int, total: Int) {
        composeTestRule.waitForIdle()
        assertEquals("hoisted index", index, currentIndex)
        composeTestRule.onAllNodes(hasText("${index + 1} / $total")).onFirst().assertIsDisplayed()
    }

    @Test
    fun revert_afterDeleteAtIndex1_showsRestoredPhotoAndKeepsIndex() {
        host()
        assertShowing(1, 12)

        // Delete: the VM removes the item and keeps the index (now pointing at the next photo).
        composeTestRule.runOnIdle { images = original.filterIndexed { i, _ -> i != 1 } }
        assertShowing(1, 11)

        // Revert: the VM re-inserts the photo and points the index at it.
        composeTestRule.runOnIdle {
            images = original
            currentIndex = 1
        }
        assertShowing(1, 12)
    }

    @Test
    fun delete_atCurrentIndex_leavesTheNextPhotoShown() {
        host()
        composeTestRule.runOnIdle { images = original.filterIndexed { i, _ -> i != 1 } }
        assertShowing(1, 11)
        composeTestRule.runOnIdle { images = images.filterIndexed { i, _ -> i != 1 } }
        assertShowing(1, 10)
    }

    @Test
    fun appendingDiscoveredBatches_doesNotMoveTheCurrentPage() {
        host()
        composeTestRule.runOnIdle { images = original + testImage("DSC_9999.jpg") }
        assertShowing(1, 13)
    }
}
