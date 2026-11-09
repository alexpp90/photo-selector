package com.photoselectortoolbox.viewmodel

import android.content.Context
import android.net.Uri
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.domain.duplicates.DuplicateDetectionProgress
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Duplicates screen's delete is permanent, so these tests hold the shipped ViewModel to what the
 * screen promises: at least one copy of every photograph survives, and a deleted copy takes its
 * own sidecars with it instead of leaving them behind (FD-B-5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicatesViewModelDeleteTest {

    private val repository = mockk<ImageRepository>(relaxed = true)
    private val useCase = mockk<FindDuplicatesUseCase>()
    private val context = mockk<Context>(relaxed = true)
    private val cacheRepository = mockk<com.photoselectortoolbox.data.repository.CacheRepository>(relaxed = true)

    private fun photo(name: String, vararg companions: String) = ImageItem(
        uri = "content://test/$name",
        fileName = name,
        fileSize = 10,
        lastModified = 1,
        mimeType = "image/jpeg",
        companions = companions.map { CompanionFile("content://test/$it", it) },
    )

    private val a = photo("a.jpg", "a.jpg.xmp")
    private val b = photo("b.jpg", "b.jpg.xmp")
    private val c = photo("c.jpg")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { repository.openFolder(any(), any()) } returns "Folder"
        coEvery { repository.discoverAllImages(any()) } returns listOf(a, b, c)
        coEvery { repository.deleteBundle(any(), any()) } returns true
        coEvery { repository.deleteImage(any(), any()) } returns true
        every { useCase.invoke(any()) } returns flowOf(
            DuplicateDetectionProgress(3, 3, listOf(DuplicateGroup("h", listOf(a.uri, b.uri, c.uri)))),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun scanned(): DuplicatesViewModel {
        val viewModel = DuplicatesViewModel(useCase, repository, context, cacheRepository)
        viewModel.selectFolder(Uri.parse("content://test/folder"))
        viewModel.startScan()
        return viewModel
    }

    @Test
    fun `ticking every copy of a photograph is refused at the last one`() {
        val viewModel = scanned()

        viewModel.toggleSelection(a.uri)
        viewModel.toggleSelection(b.uri)
        viewModel.toggleSelection(c.uri)

        assertEquals(setOf(a.uri, b.uri), viewModel.uiState.value.selectedForDeletion)
    }

    @Test
    fun `select all but first leaves the first copy on disk`() {
        val viewModel = scanned()
        viewModel.selectAllButFirst(DuplicateGroup("h", listOf(a.uri, b.uri, c.uri)))
        assertEquals(setOf(b.uri, c.uri), viewModel.uiState.value.selectedForDeletion)

        viewModel.deleteSelected()

        coVerify(exactly = 0) { repository.deleteBundle(any(), match { it.uri == a.uri }) }
        coVerify(exactly = 1) { cacheRepository.forgetDeletedFile(b.uri) }
        coVerify(exactly = 1) { cacheRepository.forgetDeletedFile(c.uri) }
        coVerify(exactly = 1) { repository.deleteBundle(any(), match { it.uri == b.uri }) }
        coVerify(exactly = 1) { repository.deleteBundle(any(), match { it.uri == c.uri }) }
    }

    @Test
    fun `a deleted copy takes its sidecars with it`() {
        val viewModel = scanned()
        viewModel.toggleSelection(b.uri)

        viewModel.deleteSelected()

        coVerify(exactly = 1) { repository.deleteBundle(any(), match { it.uri == b.uri && it.companions.size == 1 }) }
        coVerify(exactly = 0) { repository.deleteImage(any(), any()) }
        assertTrue(viewModel.uiState.value.duplicateGroups.single().files.none { it == b.uri })
    }
}
