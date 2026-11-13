package com.photoselectortoolbox.viewmodel

import android.content.Context
import android.net.Uri
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.HeldDeleteCommitter
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.duplicates.DuplicateDetectionProgress
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.photoselectortoolbox.testing.en

/**
 * A Dupes delete is held like a cull delete (REQ-AND-ALGO.05): the copies leave the list at once
 * and the disk only when the screen or the folder is left, with undo until then. These tests drive
 * the shipped ViewModel and the shared commit path ([HeldDeleteCommitter]) against a recording
 * repository: at least one copy of every photograph survives, a deleted copy takes its sidecars
 * with it (FD-B-5), and the journal holds every file while it is held.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicatesViewModelDeleteTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val repository = mockk<ImageRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val cache = mockk<CacheRepository>(relaxed = true)
    private val useCase = mockk<FindDuplicatesUseCase>()
    private val context = mockk<Context>(relaxed = true)

    private fun photo(name: String, vararg companions: String) = ImageItem(
        uri = "content://test/$name",
        fileName = name,
        fileSize = 10,
        lastModified = 1,
        mimeType = "image/jpeg",
        companions = companions.map { CompanionFile("content://test/$it", it, fileSize = 10, lastModified = 1) },
    )

    private val a = photo("a.jpg", "a.jpg.xmp")
    private val b = photo("b.jpg", "b.jpg.xmp")
    private val c = photo("c.jpg")
    private val bSidecar = "content://test/b.jpg.xmp"
    private val group = DuplicateGroup("h", listOf(a.uri, b.uri, c.uri))

    /** Every URI the repository was asked to delete, in order. */
    private val deletedUris = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.openFolder(any(), any()) } returns "Folder"
        coEvery { repository.discoverAllImages(any()) } returns listOf(a, b, c)
        val uri = slot<Uri>()
        coEvery { repository.deleteImage(any(), capture(uri)) } answers {
            deletedUris += uri.captured.toString()
            true
        }
        coEvery { repository.documentIdentity(any(), any()) } returns FileIdentity(10, 1)
        every { useCase.invoke(any()) } returns flowOf(DuplicateDetectionProgress(3, 3, listOf(group)))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun scanned(): DuplicatesViewModel {
        val viewModel = DuplicatesViewModel(
            useCase,
            repository,
            HeldDeleteCommitter(repository, cache, settings, context),
            appScope,
            context,
            settings,
        )
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
    fun `a delete is held - nothing leaves the disk and the journal holds every file`() {
        val viewModel = scanned()
        viewModel.toggleSelection(b.uri)

        viewModel.deleteSelected()

        assertEquals(emptyList<String>(), deletedUris)
        assertTrue(viewModel.uiState.value.duplicateGroups.single().files.none { it == b.uri })
        assertEquals(1, viewModel.uiState.value.undoableDeleteCount)
        coVerify(exactly = 1) {
            settings.addPendingDeletes(
                match { it.toSet() == setOf(b.uri, bSidecar) },
                match { it.keys == setOf(b.uri, bSidecar) },
            )
        }
    }

    @Test
    fun `undo before leaving the screen puts the copies back and deletes nothing`() = runTest(dispatcher) {
        val viewModel = scanned()
        viewModel.toggleSelection(b.uri)
        viewModel.toggleSelection(c.uri)
        viewModel.deleteSelected()
        assertTrue("a group left with one copy is no duplicate", viewModel.uiState.value.duplicateGroups.isEmpty())

        viewModel.undoDelete()
        viewModel.onScreenLeft()
        viewModel.awaitCommits()

        assertEquals(listOf(group), viewModel.uiState.value.duplicateGroups)
        assertEquals(setOf(b.uri, c.uri), viewModel.uiState.value.selectedForDeletion)
        assertEquals(0, viewModel.uiState.value.undoableDeleteCount)
        assertEquals(emptyList<String>(), deletedUris)
        coVerify(exactly = 1) {
            settings.removePendingDeletes(match { it.toSet() == setOf(b.uri, bSidecar, c.uri) })
        }
    }

    @Test
    fun `leaving the screen deletes the held copies with their sidecars`() = runTest(dispatcher) {
        val viewModel = scanned()
        viewModel.selectAllButFirst(group)
        viewModel.deleteSelected()
        assertEquals(emptyList<String>(), deletedUris)

        viewModel.onScreenLeft()
        viewModel.awaitCommits()

        assertEquals(setOf(b.uri, bSidecar, c.uri), deletedUris.toSet())
        assertTrue("the first copy stays on disk", deletedUris.none { it.startsWith(a.uri) })
        assertEquals(0, viewModel.uiState.value.undoableDeleteCount)
        coVerify(exactly = 1) { cache.forgetDeletedFile(b.uri) }
        coVerify { settings.removePendingDeletes(match { it.toSet() == setOf(b.uri, bSidecar, c.uri) }) }
    }

    @Test
    fun `undo after leaving has nothing left to take back`() = runTest(dispatcher) {
        val viewModel = scanned()
        viewModel.toggleSelection(b.uri)
        viewModel.deleteSelected()
        viewModel.onScreenLeft()
        viewModel.awaitCommits()

        viewModel.undoDelete()

        assertTrue(viewModel.uiState.value.duplicateGroups.single().files.none { it == b.uri })
        assertTrue(b.uri in deletedUris)
    }

    @Test
    fun `a rescan deletes the held copies before it lists the folder`() = runTest(dispatcher) {
        val viewModel = scanned()
        viewModel.toggleSelection(b.uri)
        viewModel.deleteSelected()
        val listedAfterDelete = mutableListOf<Boolean>()
        coEvery { repository.discoverAllImages(any()) } answers {
            listedAfterDelete += b.uri in deletedUris
            listOf(a, c)
        }

        viewModel.startScan()
        viewModel.awaitCommits()

        assertEquals(listOf(true), listedAfterDelete)
    }

    @Test
    fun `another file at a held URI is kept and named`() = runTest(dispatcher) {
        val viewModel = scanned()
        viewModel.toggleSelection(b.uri)
        viewModel.deleteSelected()
        // A camera that restarted its numbering wrote another photo at the same path meanwhile.
        coEvery { repository.documentIdentity(any(), Uri.parse(b.uri)) } returns FileIdentity(99, 7)

        viewModel.onScreenLeft()
        viewModel.awaitCommits()

        assertTrue(b.uri !in deletedUris)
        assertTrue(bSidecar in deletedUris)
        assertTrue(viewModel.uiState.value.error?.en().orEmpty().contains("b.jpg"))
    }
}
