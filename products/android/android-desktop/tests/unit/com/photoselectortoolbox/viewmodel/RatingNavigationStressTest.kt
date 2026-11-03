package com.photoselectortoolbox.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.metadata.MetadataWriter
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RatingNavigationStressTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(testDispatcher)

    private val groupingEnabledFlow = MutableStateFlow(false)
    private val selectorSortOrderFlow = MutableStateFlow(SelectorSortOrder.OLDEST_FIRST)
    private val groupingLevelFlow = MutableStateFlow(GroupingLevel.TIME_FILENAME)
    private val sortingEnabledFlow = MutableStateFlow(true)
    private val lastFolderUriFlow = MutableStateFlow<String?>(null)
    private val filingActionFlow = MutableStateFlow(FilingAction.COPY)
    private val fullscreenButtonsFlow = MutableStateFlow(true)
    private val overlayValuesFlow = MutableStateFlow(true)
    private val filmstripVisibleFlow = MutableStateFlow(true)
    private val detailsVisibleFlow = MutableStateFlow(true)
    private val seenFullscreenHintFlow = MutableStateFlow(true)
    private val seenNavHintFlow = MutableStateFlow(true)
    private val selectionFolderNameFlow = MutableStateFlow(FilingAction.DEFAULT_SELECTION_FOLDER)

    private val discovery = MutableSharedFlow<List<ImageItem>>(replay = 1)

    private companion object {
        const val FOLDER_URI = "content://test/folder"
    }

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { groupingEnabled } returns groupingEnabledFlow
        every { selectorSortOrder } returns selectorSortOrderFlow
        every { groupingLevel } returns groupingLevelFlow
        every { sortingEnabled } returns sortingEnabledFlow
        every { lastFolderUri } returns lastFolderUriFlow
        every { filingAction } returns filingActionFlow
        every { fullscreenButtonsEnabled } returns fullscreenButtonsFlow
        every { overlayValuesVisible } returns overlayValuesFlow
        every { filmstripVisible } returns filmstripVisibleFlow
        every { detailsVisible } returns detailsVisibleFlow
        every { hasSeenFullscreenGestureHint } returns seenFullscreenHintFlow
        every { hasSeenNavHint } returns seenNavHintFlow
        every { selectionFolderName } returns selectionFolderNameFlow
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { openFolder(any(), any()) } returns "Stress Shoot"
        every { discoverImages(any()) } returns discovery
        every { canTrash(any()) } returns false
        coEvery { getExifData(any(), any()) } returns null
        coEvery { resolveDimensions(any(), any()) } returns emptyMap<String, ImageDimensions>()
    }

    private val scanImagesUseCase: ScanImagesUseCase = mockk(relaxed = true)
    private val cacheRepository: CacheRepository = mockk(relaxed = true)
    private val scoreDao: ScoreDao = mockk(relaxed = true) {
        coEvery { getScore(any()) } returns null
    }
    private val metadataWriter: MetadataWriter = mockk(relaxed = true) {
        coEvery { writeRating(any(), any()) } returns Result.success(Unit)
        coEvery { recoverInterruptedWrites(any(), any()) } returns com.photoselectortoolbox.data.metadata.RatingRecovery.NONE
    }

    /**
     * Fixtures keep the order they are listed in: every photo is a moment later than
     * the one built before it, and these suites run on the oldest-first order. The
     * default order (newest first) is pinned in [SelectorSortJumpViewModelTest].
     */
    private var clock = 0L

    private fun nextModified(): Long = ++clock

    private fun image(name: String, rating: Int = 0) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1024L,
        lastModified = nextModified(),
        mimeType = "image/jpeg",
        rating = rating,
    )

    private val viewModels = mutableListOf<SelectorViewModel>()

    private fun buildViewModel(): SelectorViewModel {
        val viewModel = SelectorViewModel(
            imageRepository = imageRepository,
            scanImagesUseCase = scanImagesUseCase,
            cacheRepository = cacheRepository,
            settingsRepository = settingsRepository,
            scoreDao = scoreDao,
            appScope = appScope,
            context = ApplicationProvider.getApplicationContext(),
            metadataWriter = metadataWriter,
        )
        viewModel.ioDispatcher = testDispatcher
        viewModels.add(viewModel)
        return viewModel
    }

    private suspend fun loadFolder(vararg images: ImageItem): SelectorViewModel {
        val viewModel = buildViewModel()
        viewModel.selectFolder(Uri.parse(FOLDER_URI))
        discovery.emit(images.toList())
        return viewModel
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
        discovery.resetReplayCache()
    }

    // ── Edge Case 1: Empty folder handling ───────────────────────────────────

    @Test
    fun `empty folder does not crash on filter changes or navigation calls`() = runTest {
        val viewModel = loadFolder() // no images

        val state = viewModel.uiState.value
        assertTrue(state.images.isEmpty())
        assertTrue(state.filteredImages.isEmpty())
        assertEquals(0, state.filteredPosition)
        assertEquals(0, state.position)
        assertNull(state.currentImage)

        // Exercise all filters on empty state
        for (preset in RatingFilter.PRESETS) {
            viewModel.setRatingFilter(preset)
            assertEquals(preset, viewModel.uiState.value.ratingFilter)
            assertTrue(viewModel.uiState.value.filteredImages.isEmpty())
            assertEquals(0, viewModel.uiState.value.filteredPosition)
        }

        // Navigation should be no-ops
        viewModel.navigateNext()
        viewModel.navigatePrevious()
        viewModel.navigateToImage(0)
        viewModel.navigateToImage(5)

        // Rating operations should be safe no-ops
        viewModel.setRatingForCurrent(5)
        viewModel.setRatingForMaximised(3)
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    // ── Edge Case 2: All items filtered out ──────────────────────────────────

    @Test
    fun `all items filtered out behaves safely and recovers when rating matches`() = runTest {
        val viewModel = loadFolder(
            image("unrated1.jpg", rating = 0),
            image("unrated2.jpg", rating = 0),
            image("unrated3.jpg", rating = 0),
        )

        // Filter to 5 stars only -> 0 matches
        viewModel.setRatingFilter(RatingFilter.ExactRating(5))
        val state = viewModel.uiState.value
        assertEquals(0, state.filteredImages.size)
        assertEquals(0, state.filteredPosition)
        // Current index stays at 0
        assertEquals(0, state.currentIndex)

        // Navigating when no matches exist should be a safe no-op
        viewModel.navigateNext()
        assertEquals(0, viewModel.uiState.value.currentIndex)
        viewModel.navigatePrevious()
        assertEquals(0, viewModel.uiState.value.currentIndex)

        // User rates the current image to 5 stars
        viewModel.setRatingForCurrent(5)

        val updatedState = viewModel.uiState.value
        assertEquals(1, updatedState.filteredImages.size)
        assertEquals("unrated1.jpg", updatedState.filteredImages[0].fileName)
        assertEquals(1, updatedState.filteredPosition)
        assertEquals(5, updatedState.currentImage?.rating)
    }

    // ── Edge Case 3: Rapid filter switching across large photo set ───────────

    @Test
    fun `rapid filter switching maintains invariant that current matches if matching items exist`() = runTest {
        // Create 60 images with varied ratings (-1..5)
        val imageList = (0 until 60).map { i ->
            val rating = when (i % 7) {
                0 -> -1
                1 -> 0
                2 -> 1
                3 -> 2
                4 -> 3
                5 -> 4
                else -> 5
            }
            image("img_$i.jpg", rating = rating)
        }.toTypedArray()

        val viewModel = loadFolder(*imageList)

        val filtersToTest = listOf(
            RatingFilter.All,
            RatingFilter.MinRating(3),
            RatingFilter.ExactRating(5),
            RatingFilter.Rejected,
            RatingFilter.Unrated,
            RatingFilter.MinRating(1),
            RatingFilter.MinRating(4),
            RatingFilter.ExactRating(2),
            RatingFilter.All,
        )

        for (filter in filtersToTest) {
            viewModel.setRatingFilter(filter)
            val state = viewModel.uiState.value
            val matching = state.images.filter { filter.matches(it.rating) }
            assertEquals(matching.size, state.filteredImages.size)

            if (matching.isNotEmpty()) {
                val current = state.currentImage
                assertNotNull(current)
                assertTrue("Current image should match filter $filter", filter.matches(current!!.rating))
                assertTrue("filteredPosition should be >= 1", state.filteredPosition >= 1)
                assertTrue("filteredPosition <= size", state.filteredPosition <= state.filteredImages.size)
            } else {
                assertEquals(0, state.filteredPosition)
            }
        }
    }

    // ── Edge Case 4: Sparse matching clusters & leapfrog navigation ──────────

    @Test
    fun `sparse matching navigation jumps correctly over non-matching clusters`() = runTest {
        // Layout: [Match(0), Skip(1), Skip(2), Skip(3), Match(4), Skip(5), Match(6)]
        val viewModel = loadFolder(
            image("m0.jpg", rating = 5),
            image("s1.jpg", rating = 1),
            image("s2.jpg", rating = 2),
            image("s3.jpg", rating = 1),
            image("m4.jpg", rating = 5),
            image("s5.jpg", rating = 3),
            image("m6.jpg", rating = 5),
        )

        viewModel.setRatingFilter(RatingFilter.ExactRating(5))
        assertEquals(3, viewModel.uiState.value.filteredImages.size)
        assertEquals(0, viewModel.uiState.value.currentIndex) // m0.jpg
        assertEquals(1, viewModel.uiState.value.filteredPosition)

        // Leap to m4.jpg
        viewModel.navigateNext()
        assertEquals(4, viewModel.uiState.value.currentIndex)
        assertEquals("m4.jpg", viewModel.uiState.value.currentImage?.fileName)
        assertEquals(2, viewModel.uiState.value.filteredPosition)

        // Leap to m6.jpg
        viewModel.navigateNext()
        assertEquals(6, viewModel.uiState.value.currentIndex)
        assertEquals("m6.jpg", viewModel.uiState.value.currentImage?.fileName)
        assertEquals(3, viewModel.uiState.value.filteredPosition)

        // End of list: navigateNext stays at m6.jpg
        viewModel.navigateNext()
        assertEquals(6, viewModel.uiState.value.currentIndex)
        assertEquals(3, viewModel.uiState.value.filteredPosition)

        // Leap back to m4.jpg
        viewModel.navigatePrevious()
        assertEquals(4, viewModel.uiState.value.currentIndex)
        assertEquals("m4.jpg", viewModel.uiState.value.currentImage?.fileName)
        assertEquals(2, viewModel.uiState.value.filteredPosition)

        // Leap back to m0.jpg
        viewModel.navigatePrevious()
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals("m0.jpg", viewModel.uiState.value.currentImage?.fileName)
        assertEquals(1, viewModel.uiState.value.filteredPosition)

        // Start of list: navigatePrevious stays at m0.jpg
        viewModel.navigatePrevious()
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(1, viewModel.uiState.value.filteredPosition)
    }

    // ── Edge Case 5: Rapid rating mutation cycle ─────────────────────────────

    @Test
    fun `rapid rating mutations on single photo update cache and disk correctly`() = runTest {
        val viewModel = loadFolder(image("test.jpg", rating = 0))

        val ratingSequence = listOf(1, 5, 0, -1, 3, 0, 2, 4, 0, -1, 5)
        for (rating in ratingSequence) {
            viewModel.setRatingForCurrent(rating)
            assertEquals(rating, viewModel.uiState.value.currentImage?.rating)
            coVerify { cacheRepository.updateRating("content://test/folder/test.jpg", rating) }
            testScheduler.advanceUntilIdle()
            coVerify { metadataWriter.writeRating(any(), rating) }
        }
    }

    // ── Edge Case 6: Rating on PREVIOUS, NEXT, CURRENT in maximised frame ─────

    @Test
    fun `setRatingForMaximised targets the exact maximised frame`() = runTest {
        val viewModel = loadFolder(
            image("prev.jpg", rating = 0),
            image("curr.jpg", rating = 0),
            image("next.jpg", rating = 0),
        )

        // Step to index 1 so PREVIOUS (prev.jpg), CURRENT (curr.jpg), NEXT (next.jpg) all exist
        viewModel.navigateToImage(1)
        assertEquals("curr.jpg", viewModel.uiState.value.currentImage?.fileName)
        assertEquals("prev.jpg", viewModel.uiState.value.previousImage?.fileName)
        assertEquals("next.jpg", viewModel.uiState.value.nextImage?.fileName)

        // 1. Maximise PREVIOUS
        viewModel.toggleMaximised(SelectorFrame.PREVIOUS)
        viewModel.setRatingForMaximised(2)
        assertEquals(2, viewModel.uiState.value.images[0].rating)
        assertEquals(0, viewModel.uiState.value.images[1].rating)
        assertEquals(0, viewModel.uiState.value.images[2].rating)

        // 2. Maximise NEXT
        viewModel.toggleMaximised(SelectorFrame.NEXT)
        viewModel.setRatingForMaximised(4)
        assertEquals(2, viewModel.uiState.value.images[0].rating)
        assertEquals(0, viewModel.uiState.value.images[1].rating)
        assertEquals(4, viewModel.uiState.value.images[2].rating)

        // 3. Maximise CURRENT
        viewModel.toggleMaximised(SelectorFrame.CURRENT)
        viewModel.setRatingForMaximised(5)
        assertEquals(2, viewModel.uiState.value.images[0].rating)
        assertEquals(5, viewModel.uiState.value.images[1].rating)
        assertEquals(4, viewModel.uiState.value.images[2].rating)
    }

    // ── Edge Case 7: Unrate and Reject toggle logic oracle ───────────────────

    @Test
    fun `rating bar toggle and reject state oracle`() {
        // Oracle modeling StarRatingBar's click handler logic:
        // star i click: if (rating == i) 0 else i
        // reject click: if (rating == -1) 0 else -1
        // clear click: 0

        fun onStarClick(current: Int, star: Int): Int = if (current == star) 0 else star
        fun onRejectClick(current: Int): Int = if (current == -1) 0 else -1
        fun onClearClick(): Int = 0

        // Test star toggles
        assertEquals(3, onStarClick(0, 3))
        assertEquals(0, onStarClick(3, 3)) // toggle off (unrate)
        assertEquals(5, onStarClick(3, 5)) // change from 3 to 5
        assertEquals(0, onStarClick(5, 5)) // toggle off (unrate)

        // Test reject toggles
        assertEquals(-1, onRejectClick(0))
        assertEquals(0, onRejectClick(-1)) // toggle reject off
        assertEquals(-1, onRejectClick(4)) // switch from 4 stars to reject
        assertEquals(0, onRejectClick(-1)) // toggle reject off

        // Test clear
        assertEquals(0, onClearClick())
    }
}
