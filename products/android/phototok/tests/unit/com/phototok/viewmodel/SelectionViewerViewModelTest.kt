package com.phototok.viewmodel

import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.ImageRepository
import com.phototok.data.source.SelectionListing
import com.phototok.domain.UiText
import io.mockk.coEvery
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
}
