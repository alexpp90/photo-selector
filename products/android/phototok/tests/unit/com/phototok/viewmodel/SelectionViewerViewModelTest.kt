package com.phototok.viewmodel

import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.ImageRepository
import com.phototok.data.source.SelectionListing
import com.phototok.domain.FavouritesSnapshot
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class SelectionViewerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val imageRepository: ImageRepository = mockk()
    private lateinit var viewModel: SelectionViewerViewModel

    private fun image(name: String, modified: Long = 0) = ImageItem(
        uri = "content://selection/$name",
        fileName = name,
        fileSize = 1,
        lastModified = modified,
        mimeType = "image/jpeg",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = SelectionViewerViewModel(imageRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `open with null target shows feedback and stays closed`() = runTest {
        viewModel.open(null)

        val state = viewModel.uiState.value
        assertFalse(state.isOpen)
        assertEquals(UiText.of(R.string.snackbar_selection_none), state.feedback?.message)
        assertFalse(state.feedback?.isError == true)
    }

    @Test
    fun `open with available listing shows images`() = runTest {
        val images = listOf(image("b.jpg", 2), image("a.jpg", 1))
        coEvery { imageRepository.listSelectionImages(any()) } returns
            SelectionListing.Available(images, "PhotoTok_Selection")

        viewModel.open("content://tree/photos")

        val state = viewModel.uiState.value
        assertTrue(state.isOpen)
        assertEquals(images, state.images)
        assertEquals("PhotoTok_Selection", state.folderName)
        assertEquals(0, state.currentIndex)
        assertNull(state.feedback)
    }

    @Test
    fun `open on unsupported source closes with local-only feedback`() = runTest {
        coEvery { imageRepository.listSelectionImages(any()) } returns SelectionListing.NotSupported

        viewModel.open("content://tree/unsupported")

        val state = viewModel.uiState.value
        assertFalse(state.isOpen)
        assertEquals(UiText.of(R.string.error_selection_local), state.feedback?.message)
    }

    @Test
    fun `open on missing folder closes with empty feedback`() = runTest {
        coEvery { imageRepository.listSelectionImages(any()) } returns SelectionListing.Missing

        viewModel.open("content://tree/photos")

        val state = viewModel.uiState.value
        assertFalse(state.isOpen)
        assertEquals(UiText.of(R.string.snackbar_selection_empty), state.feedback?.message)
        assertFalse(state.feedback?.isError == true)
    }

    @Test
    fun `open surfaces repository errors as feedback`() = runTest {
        coEvery { imageRepository.listSelectionImages(any()) } throws IllegalStateException("boom")

        viewModel.open("content://tree/photos")

        val state = viewModel.uiState.value
        assertFalse(state.isOpen)
        assertEquals(UiText.of(R.string.error_selection_open), state.feedback?.message)
    }

    @Test
    fun `navigate and close manage index and images`() = runTest {
        val images = listOf(image("a.jpg"), image("b.jpg"))
        coEvery { imageRepository.listSelectionImages(any()) } returns
            SelectionListing.Available(images, "Selection")
        viewModel.open("content://tree/photos")

        viewModel.navigateTo(1)
        assertEquals(1, viewModel.uiState.value.currentIndex)

        viewModel.navigateTo(99) // out of bounds ignored
        assertEquals(1, viewModel.uiState.value.currentIndex)

        viewModel.close()
        val state = viewModel.uiState.value
        assertFalse(state.isOpen)
        assertTrue(state.images.isEmpty())
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `clearFeedback removes feedback`() = runTest {
        viewModel.open(null)
        assertNotNull(viewModel.uiState.value.feedback)

        viewModel.clearFeedback()
        assertNull(viewModel.uiState.value.feedback)
    }

    // ── Which list opens first, and whether there is anything to switch (REQ-TOK-FEATURE.17) ──

    private fun source(vararg names: String) = names.map { image(it) }.map { it.copy(uri = "content://source/${it.fileName}") }

    private fun openWith(
        favourited: Int,
        selection: Int,
        preferred: SelectionViewerTab,
    ) {
        val sourceImages = source("a.jpg", "b.jpg", "c.jpg", "d.jpg")
        val favs = sourceImages.take(favourited).map { it.uri }.toSet()
        coEvery { imageRepository.listSelectionImages(any()) } returns
            if (selection > 0) {
                SelectionListing.Available((1..selection).map { image("sel$it.jpg") }, "Keepers")
            } else {
                SelectionListing.Missing
            }
        viewModel.openWithFavourites(
            targetFolderUri = "content://tree/photos",
            sourceImages = sourceImages,
            favourites = FavouritesSnapshot(favourites = favs),
            preferredTab = preferred,
        )
    }

    @Test
    fun `preferred tab follows the swipe-right verb`() {
        assertEquals(SelectionViewerTab.FAVOURITES, SelectionViewerLogic.preferredTab(SwipeRightVerb.FAVOURITE))
        assertEquals(SelectionViewerTab.SELECTION, SelectionViewerLogic.preferredTab(SwipeRightVerb.COPY))
        assertEquals(SelectionViewerTab.SELECTION, SelectionViewerLogic.preferredTab(SwipeRightVerb.MOVE))
    }

    @Test
    fun `initial tab is the preferred one unless its list is empty`() {
        val fav = SelectionViewerTab.FAVOURITES
        val sel = SelectionViewerTab.SELECTION
        assertEquals(fav, SelectionViewerLogic.initialTab(fav, 3, 5))
        assertEquals(sel, SelectionViewerLogic.initialTab(sel, 3, 5))
        assertEquals(sel, SelectionViewerLogic.initialTab(fav, 0, 5))
        assertEquals(fav, SelectionViewerLogic.initialTab(sel, 3, 0))
        assertEquals(fav, SelectionViewerLogic.initialTab(fav, 3, 0))
        assertEquals(sel, SelectionViewerLogic.initialTab(sel, 0, 5))
    }

    @Test
    fun `tabs are shown only when both lists have photos`() {
        assertTrue(SelectionViewerLogic.showTabs(2, 3))
        assertFalse(SelectionViewerLogic.showTabs(0, 3))
        assertFalse(SelectionViewerLogic.showTabs(2, 0))
        assertFalse(SelectionViewerLogic.showTabs(0, 0))
    }

    @Test
    fun `a copying swipe opens on the Selection tab even though favourites exist`() = runTest {
        openWith(favourited = 2, selection = 3, preferred = SelectionViewerTab.SELECTION)

        val state = viewModel.uiState.value
        assertEquals(SelectionViewerTab.SELECTION, state.activeTab)
        assertEquals(3, state.images.size)
        assertEquals("Keepers", state.folderName)
        assertTrue(state.showTabs)
        assertEquals(2, state.favouriteImages.size)
        assertEquals(3, state.selectionImages.size)
    }

    @Test
    fun `a favouriting swipe opens on the Favourites tab even though the folder has photos`() = runTest {
        openWith(favourited = 2, selection = 3, preferred = SelectionViewerTab.FAVOURITES)

        val state = viewModel.uiState.value
        assertEquals(SelectionViewerTab.FAVOURITES, state.activeTab)
        assertEquals(2, state.images.size)
        assertTrue(state.showTabs)
    }

    @Test
    fun `with only favourites the viewer opens on them whatever the verb and shows a title, not tabs`() = runTest {
        openWith(favourited = 2, selection = 0, preferred = SelectionViewerTab.SELECTION)

        val state = viewModel.uiState.value
        assertTrue(state.isOpen)
        assertEquals(SelectionViewerTab.FAVOURITES, state.activeTab)
        assertEquals(2, state.images.size)
        assertFalse(state.showTabs)
    }

    @Test
    fun `with only the Selection folder the viewer opens on it whatever the verb and shows a title, not tabs`() = runTest {
        openWith(favourited = 0, selection = 3, preferred = SelectionViewerTab.FAVOURITES)

        val state = viewModel.uiState.value
        assertTrue(state.isOpen)
        assertEquals(SelectionViewerTab.SELECTION, state.activeTab)
        assertEquals(3, state.images.size)
        assertEquals("Keepers", state.selectionFolderName)
        assertFalse(state.showTabs)
    }

    @Test
    fun `selecting a tab swaps the list and the share and copy source follows it`() = runTest {
        openWith(favourited = 2, selection = 3, preferred = SelectionViewerTab.SELECTION)

        viewModel.selectTab(SelectionViewerTab.FAVOURITES)

        val state = viewModel.uiState.value
        assertEquals(SelectionViewerTab.FAVOURITES, state.activeTab)
        assertEquals(state.favouriteImages, state.images)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `a casual card import opens the Jackpix folder of the camera target`() = runTest {
        val images = listOf(image("a.jpg", 1))
        coEvery { imageRepository.listSelectionImages(any(), "Jackpix") } returns
            SelectionListing.Available(images, "Jackpix")

        viewModel.openWithFavourites(
            targetFolderUri = "content://tree/camera",
            sourceImages = emptyList(),
            favourites = FavouritesSnapshot(favourites = emptySet()),
            preferredTab = SelectionViewerTab.SELECTION,
            selectionSubfolder = "Jackpix",
        )

        val state = viewModel.uiState.value
        assertTrue(state.isOpen)
        assertEquals(images, state.images)
        assertEquals("Jackpix", state.selectionFolderName)
        coVerify { imageRepository.listSelectionImages(any(), "Jackpix") }
        coVerify(exactly = 0) { imageRepository.listSelectionImages(any(), "Selection") }
    }
}
