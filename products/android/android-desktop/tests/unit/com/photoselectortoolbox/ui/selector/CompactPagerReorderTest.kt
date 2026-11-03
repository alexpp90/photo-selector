package com.photoselectortoolbox.ui.selector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.viewmodel.SelectorUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The compact pager is keyed by URI, so a re-order of the list moves the pager to
 * the page its current photo now has, with no scroll. That must never read as the
 * photographer swiping: the ViewModel's index is the truth, and a re-order that
 * the ViewModel already applied (by URI) must not be reported back as a new page.
 *
 * The host stands in for the ViewModel exactly as `SelectorScreen` wires it:
 * a reported page becomes `currentIndex`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class CompactPagerReorderTest {

    @get:Rule
    val rule = createComposeRule()

    private fun photo(name: String) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
    )

    private val a = photo("A.jpg")
    private val b = photo("B.jpg")
    private val c = photo("C.jpg")
    private val d = photo("D.jpg")

    private var state by mutableStateOf(SelectorUiState())
    private val reported = mutableListOf<Int>()

    private fun host(images: List<ImageItem>, index: Int) {
        state = SelectorUiState(images = images, currentIndex = index, hasSeenFullscreenHint = true)
        reported.clear()
        rule.setContent {
            CompactSelectorLayout(
                uiState = state,
                onNavigateToImage = {
                    reported += it
                    state = state.copy(currentIndex = it)
                },
                onFullscreen = {},
                onMoveToSelection = {},
                onCopyToSelection = {},
                onDelete = {},
            )
        }
        rule.waitForIdle()
    }

    private fun pageShows(name: String): Boolean =
        rule.onAllNodes(hasContentDescription(name), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun reorder(images: List<ImageItem>, uri: String?) {
        val next = state.listState.copy(images = images).focusedOn(uri)
        state = state.copy(images = next.images, currentIndex = next.currentIndex)
        rule.waitForIdle()
        // The pager's key-follow and the one-frame wait both need frames.
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    @Test
    fun `a re-order that moves the current photo keeps showing it and reports nothing`() {
        host(listOf(a, b, c, d), index = 2)
        assertTrue(pageShows("C.jpg"))

        reorder(listOf(d, c, b, a), uri = c.uri)

        assertEquals(1, state.currentIndex)
        assertTrue("C.jpg is still the page on screen", pageShows("C.jpg"))
        assertTrue("the pager reported $reported for a re-order nobody swiped", reported.isEmpty())
    }

    @Test
    fun `a settle re-order that lands on the new frame 1 is not dragged back to the old photo`() {
        // Untouched session on frame 1 (A); settle puts the newest photo (D) first and
        // the ViewModel lands on it. The pager's key-follow would carry it to A's new
        // page, index 3.
        host(listOf(a, b, c, d), index = 0)
        assertTrue(pageShows("A.jpg"))

        state = state.copy(images = listOf(d, c, b, a), currentIndex = 0)
        rule.waitForIdle()
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()

        assertEquals("the ViewModel's frame is the truth", 0, state.currentIndex)
        assertTrue("D.jpg, the new frame 1, is on screen", pageShows("D.jpg"))
        assertTrue("the pager reported $reported", reported.isEmpty())
    }

    @Test
    fun `an order change at the same index shows the photo now there`() {
        host(listOf(a, b, c, d), index = 1)

        state = state.copy(images = listOf(a, c, b, d), currentIndex = 1)
        rule.waitForIdle()
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()

        assertEquals(1, state.currentIndex)
        assertTrue(pageShows("C.jpg"))
        assertTrue(reported.isEmpty())
    }
}
