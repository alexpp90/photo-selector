package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.interaction.FilingAction
import org.junit.Assert.*
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * Tests for SelectorUiState data class — verifies defaults and
 * state transitions without needing a full ViewModel.
 */
class SelectorUiStateTest {

    @Test
    fun `default state has empty images and no loading`() {
        val state = SelectorUiState()
        assertTrue(state.images.isEmpty())
        assertEquals(0, state.currentIndex)
        assertFalse(state.isLoading)
        assertFalse(state.isScanRunning)
        assertEquals(0f, state.scanProgress, 1e-6f)
        assertNull(state.folderUri)
        assertNull(state.error?.en())
        assertFalse(state.showDeleteConfirmation)
        assertFalse(state.groupingEnabled)
        assertTrue(state.groups.isEmpty())
    }

    @Test
    fun `selector opens in three-up, not maximised`() {
        val state = SelectorUiState()
        // There is one comparison layout now, so there is no layout preference
        // to restore. Maximise is transient view state rather than a setting:
        // it is something you do to look closer at one frame, and it should not
        // survive a relaunch.
        assertNull(state.maximisedFrame)
    }

    @Test
    fun `neighbour value overlays are on by default`() {
        // A photographer who cannot see the numbers cannot cull. The overlay
        // costs about a quarter of each neighbour's width, which is why it can
        // be turned off — but off is not where it starts.
        assertTrue(SelectorUiState().overlayValuesVisible)
    }

    @Test
    fun `filing action defaults to copy, the non-destructive verb`() {
        assertEquals(FilingAction.COPY, SelectorUiState().filingAction)
    }

    @Test
    fun `nav hint is unseen by default so first-run arrows show once`() {
        val state = SelectorUiState()
        assertFalse(state.hasSeenNavHint)
        // After it has been shown, arrows are suppressed.
        val seen = state.copy(hasSeenNavHint = true)
        assertTrue(seen.hasSeenNavHint)
    }

    @Test
    fun `copy with loading flag`() {
        val state = SelectorUiState().copy(isLoading = true)
        assertTrue(state.isLoading)
        assertFalse(state.isScanRunning)
    }

    @Test
    fun `copy with scan running updates progress fields`() {
        val state = SelectorUiState().copy(
            isScanRunning = true,
            scanProgress = 0.5f,
            scanStatusText = "Analyzing IMG_001.jpg (5/10)"
        )
        assertTrue(state.isScanRunning)
        assertEquals(0.5f, state.scanProgress, 1e-6f)
        assertEquals("Analyzing IMG_001.jpg (5/10)", state.scanStatusText)
    }

    @Test
    fun `images can be set and indexed`() {
        val images = listOf(
            ImageItem("uri1", "a.jpg", 100, 1000, "image/jpeg"),
            ImageItem("uri2", "b.jpg", 200, 2000, "image/jpeg")
        )
        val state = SelectorUiState(images = images, currentIndex = 1)
        assertEquals(2, state.images.size)
        assertEquals("b.jpg", state.images[state.currentIndex].fileName)
    }

    @Test
    fun `error can be set and cleared`() {
        val state = SelectorUiState(error = com.photoselectortoolbox.domain.text.raw("Something broke"))
        assertEquals("Something broke", state.error?.en())
        val cleared = state.copy(error = null)
        assertNull(cleared.error?.en())
    }

    @Test
    fun `groups represent index-based grouping`() {
        val groups = listOf(listOf(0, 1, 2), listOf(3, 4))
        val state = SelectorUiState(groups = groups)
        assertEquals(2, state.groups.size)
        assertEquals(3, state.groups[0].size)
        assertEquals(2, state.groups[1].size)
    }

    @Test
    fun `filmstrip and details are shown by default`() {
        val state = SelectorUiState()
        assertTrue(state.filmstripVisible)
        assertTrue(state.detailsVisible)
    }

    @Test
    fun `neighbours resolve around the current frame`() {
        val state = SelectorUiState(images = threeImages, currentIndex = 1)
        assertEquals("b.jpg", state.currentImage?.fileName)
        assertEquals("a.jpg", state.previousImage?.fileName)
        assertEquals("c.jpg", state.nextImage?.fileName)
    }

    @Test
    fun `the folder edges have no neighbour rather than wrapping around`() {
        // Wrapping would silently take the photographer back to the start of
        // the shoot mid-comparison.
        val first = SelectorUiState(images = threeImages, currentIndex = 0)
        assertNull(first.previousImage)
        assertEquals("b.jpg", first.nextImage?.fileName)

        val last = SelectorUiState(images = threeImages, currentIndex = 2)
        assertEquals("b.jpg", last.previousImage?.fileName)
        assertNull(last.nextImage)
    }

    @Test
    fun `position is one-based and zero for an empty folder`() {
        assertEquals(2, SelectorUiState(images = threeImages, currentIndex = 1).position)
        assertEquals(0, SelectorUiState().position)
    }

    @Test
    fun `hasAnyScores gates Clear scores on there being scores to clear`() {
        assertFalse(SelectorUiState(images = threeImages).hasAnyScores)

        val scanned = threeImages.toMutableList().also { list ->
            list[1] = list[1].copy(
                scanResult = ScanResult(filePath = list[1].uri, sharpnessScore = 40.0)
            )
        }
        assertTrue(SelectorUiState(images = scanned).hasAnyScores)
    }

    @Test
    fun `a current image out of range does not crash`() {
        // Deletes shrink the list; the index is coerced afterwards, but the UI
        // may read state in between.
        val state = SelectorUiState(images = threeImages, currentIndex = 99)
        assertNull(state.currentImage)
        assertNull(state.nextImage)
    }

    @Test
    fun `a fresh folder is neither enumerating nor queueing anything`() {
        val state = SelectorUiState()
        assertFalse(state.isEnumerating)
        assertFalse(state.isGroupingRunning)
        assertNull(state.queuedWork)
        assertNull(state.snackbarMessage?.en())
        assertNull(state.undoOperation)
        assertNull(state.pendingDeletion)
    }

    @Test
    fun `listState hands the list and the index to the pure curation logic as one value`() {
        // The index and the list it indexes into must travel together; every bug
        // in this area has been an index that outlived its list.
        val state = SelectorUiState(images = threeImages, currentIndex = 1)
        assertEquals(threeImages, state.listState.images)
        assertEquals(1, state.listState.currentIndex)
        assertEquals("uri2", state.listState.currentUri)
    }

    @Test
    fun `an empty list has no current URI rather than an out-of-range one`() {
        assertNull(SelectorUiState().listState.currentUri)
    }

    private val threeImages = listOf(
        ImageItem("uri1", "a.jpg", 100, 1000, "image/jpeg"),
        ImageItem("uri2", "b.jpg", 200, 2000, "image/jpeg"),
        ImageItem("uri3", "c.jpg", 300, 3000, "image/jpeg"),
    )

    @Test
    fun `filteredIndex is the 0-based twin of the 1-based filteredPosition`() {
        fun img(n: String, rating: Int) = ImageItem(
            uri = "content://t/$n", fileName = n, fileSize = 1, lastModified = 0, mimeType = null, rating = rating,
        )
        val state = SelectorUiState(images = listOf(img("a", 1), img("b", 5), img("c", 5)), currentIndex = 2)

        assertEquals(3, state.filteredPosition)
        assertEquals(2, state.filteredIndex)
        assertEquals(0, SelectorUiState().filteredIndex)
    }
}
