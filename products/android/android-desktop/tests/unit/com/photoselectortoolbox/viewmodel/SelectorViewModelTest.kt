package com.photoselectortoolbox.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.photoselectortoolbox.data.cache.ScoreDao
import kotlinx.coroutines.yield
import com.photoselectortoolbox.domain.usecase.ScanProgress
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.FileOperationResult
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import androidx.compose.ui.input.key.Key
import com.photoselectortoolbox.domain.curation.UndoableOperation
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import com.photoselectortoolbox.ui.selector.SelectorActions
import com.photoselectortoolbox.ui.selector.handleSelectorKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * The selector's session behaviour: progressive loading, optimistic filing,
 * deferred deletion and the undo that follows from them.
 *
 * Robolectric rather than an emulator because everything under test here is
 * state transition — the Android surface actually needed is `Uri.parse` and a
 * `Context`. Folders are opened through [ImageRepository.openFolder], which is
 * the seam that keeps SAF's `DocumentFile` dance behind the repository — so a
 * stubbed folder name is all it takes to exercise the real discovery, merge and
 * filing code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectorViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    /**
     * The application scope the ViewModel files and deletes on.
     *
     * Held by the test rather than created per ViewModel so [tearDown] can cancel
     * it. It outlives `viewModelScope` by design — that is what lets a filing
     * operation survive the screen going away — so work left running on it would
     * otherwise still be dispatching while `resetMain` swaps the dispatcher out.
     */
    private val appScope = CoroutineScope(testDispatcher)

    private val groupingEnabledFlow = MutableStateFlow(false)
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
    private val pendingDeleteJournalFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Discovery batches, pushed by the test to imitate progressive enumeration. */
    private val discovery = MutableSharedFlow<List<ImageItem>>(replay = 1)

    private companion object {
        const val FOLDER_URI = "content://test/folder"
        const val OTHER_FOLDER_URI = "content://test/other"
    }

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { groupingEnabled } returns groupingEnabledFlow
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
        every { pendingDeleteJournal } returns pendingDeleteJournalFlow
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { openFolder(any(), any()) } returns "Test Shoot"
        every { discoverImages(any()) } returns discovery
        every { canTrash(any()) } returns false
        coEvery { getExifData(any(), any()) } returns null
        coEvery { resolveDimensions(any(), any()) } returns emptyMap<String, ImageDimensions>()
        coEvery { deleteImage(any(), any()) } returns true
        coEvery { moveBundle(any(), any(), any(), any()) } coAnswers {
            val ctx = firstArg<android.content.Context>()
            val item = secondArg<ImageItem>()
            val dest = thirdArg<Uri>()
            val sort = arg<Boolean>(3)
            moveImage(ctx, Uri.parse(item.uri), dest, sort).let { res ->
                if (item.parentDocumentId != null && res.parentDocumentId == null) {
                    res.copy(parentDocumentId = item.parentDocumentId)
                } else {
                    res
                }
            }
        }
        coEvery { copyBundle(any(), any(), any(), any()) } coAnswers {
            val ctx = firstArg<android.content.Context>()
            val item = secondArg<ImageItem>()
            val dest = thirdArg<Uri>()
            val sort = arg<Boolean>(3)
            copyImage(ctx, Uri.parse(item.uri), dest, sort).let { res ->
                if (item.parentDocumentId != null && res.parentDocumentId == null) {
                    res.copy(parentDocumentId = item.parentDocumentId)
                } else {
                    res
                }
            }
        }
        coEvery { undoMoveBundle(any(), any(), any(), any()) } coAnswers {
            val ctx = firstArg<android.content.Context>()
            val uris = secondArg<List<String>>()
            val root = thirdArg<Uri>()
            undoMove(ctx, Uri.parse(uris.first()), root)
        }
    }

    private val scanImagesUseCase: ScanImagesUseCase = mockk(relaxed = true)

    private val cacheRepository: CacheRepository = mockk(relaxed = true)

    private val scoreDao: ScoreDao = mockk(relaxed = true) {
        coEvery { getScore(any()) } returns null
    }

    private fun image(name: String) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
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
        )
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

    // ── Progressive loading ──────────────────────────────────────────────

    @Test
    fun `a later batch appends without moving the photographer`() = runTest {
        val viewModel = loadFolder(image("a"), image("b"), image("c"))
        viewModel.navigateToImage(2)

        // Cumulative emission: the first three are in it again.
        discovery.emit(listOf(image("a"), image("b"), image("c"), image("d"), image("e")))

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b", "c", "d", "e"), state.images.map { it.fileName })
        assertEquals("c", state.currentImage?.fileName)
    }

    @Test
    fun `a frame filed away is not resurrected by the next batch`() = runTest {
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/folder/a", "content://test/selection/a")

        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.moveToSelection()
        assertEquals(listOf("b"), viewModel.uiState.value.images.map { it.fileName })

        discovery.emit(listOf(image("a"), image("b"), image("c")))

        assertEquals(listOf("b", "c"), viewModel.uiState.value.images.map { it.fileName })
    }

    @Test
    fun `enumeration is flagged while the folder is still growing`() = runTest {
        // What puts the `+` on `127 / 842+`. The total is a running total until
        // the discovery flow completes.
        val streaming = loadFolder(image("a"), image("b"))
        assertTrue(streaming.uiState.value.isEnumerating)
        assertFalse(streaming.uiState.value.isLoading)
    }

    @Test
    fun `enumeration stops being flagged once discovery completes`() = runTest {
        every { imageRepository.discoverImages(any()) } returns
            flowOf(listOf(image("a"), image("b")))

        val settled = buildViewModel()
        settled.selectFolder(Uri.parse(FOLDER_URI))

        assertFalse(settled.uiState.value.isEnumerating)
        assertEquals(2, settled.uiState.value.images.size)
    }

    @Test
    fun `selecting a new folder clears the published set so the folder is not empty`() = runTest {
        val viewModel = loadFolder(image("a"), image("b"))
        assertEquals(2, viewModel.uiState.value.images.size)

        // Same URIs, different folder. A surviving published set would
        // de-duplicate them all away and leave a silently empty screen.
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))
        discovery.emit(listOf(image("a"), image("b")))

        assertEquals(2, viewModel.uiState.value.images.size)
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    // ── Optimistic filing ────────────────────────────────────────────────

    @Test
    fun `a move leaves the list before the transfer completes`() = runTest {
        // The point of the change: gate the repository on a deferred and assert
        // the list has already advanced. A test that only checks the settled
        // state cannot tell optimistic from blocking.
        val transfer = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } coAnswers {
            transfer.await()
        }

        val viewModel = loadFolder(image("a"), image("b"), image("c"))
        viewModel.moveToSelection()

        assertFalse(transfer.isCompleted)
        assertEquals(listOf("b", "c"), viewModel.uiState.value.images.map { it.fileName })
        assertEquals("b", viewModel.uiState.value.currentImage?.fileName)
        assertFalse("no spinner for a filing action", viewModel.uiState.value.isLoading)
        assertEquals("Moved to Selection", viewModel.uiState.value.snackbarMessage)

        transfer.complete(FileOperationResult.success("content://test/folder/a", "content://test/sel/a"))
        assertEquals(listOf("b", "c"), viewModel.uiState.value.images.map { it.fileName })
    }

    @Test
    fun `the confirmation names the folder the photographer configured`() = runTest {
        // Guards the wiring, not the wording: SelectionActionLabels is already
        // unit-tested with a renamed folder, but every other assertion in this
        // file uses the default name, so passing a constant instead of the
        // configured value would satisfy all of them. Only a renamed folder
        // reaching the snackbar proves the ViewModel actually threads it.
        selectionFolderNameFlow.value = "Picks"
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/folder/a", "content://test/sel/a")

        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.moveToSelection()

        assertEquals("Moved to Picks", viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `a copy keeps the frame on screen and confirms it`() = runTest {
        val transfer = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } coAnswers {
            transfer.await()
        }

        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.copyToSelection()

        assertEquals(listOf("a", "b"), viewModel.uiState.value.images.map { it.fileName })
        assertEquals("Copied to Selection", viewModel.uiState.value.snackbarMessage)
        transfer.complete(FileOperationResult.success("content://test/folder/a", "content://test/sel/a"))
    }

    @Test
    fun `a failed move puts the frame back where it was`() = runTest {
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns
            FileOperationResult.failure("content://test/folder/b", "disk full")

        val viewModel = loadFolder(image("a"), image("b"), image("c"))
        viewModel.navigateToImage(1)
        viewModel.moveToSelection()

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b", "c"), state.images.map { it.fileName })
        assertNotNull(state.error)
        assertNull(state.undoOperation)
    }

    @Test
    fun `a partial move keeps the frame in the list and surfaces the partial error`() = runTest {
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns
            FileOperationResult.partial(
                sourceUri = "content://test/folder/b",
                destinationUri = "content://test/sel/b",
                error = "Copied to Selection — original could not be removed",
            )

        val viewModel = loadFolder(image("a"), image("b"), image("c"))
        viewModel.navigateToImage(1)
        viewModel.moveToSelection()

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b", "c"), state.images.map { it.fileName })
        assertEquals("Copied to Selection — original could not be removed", state.error)
        assertNull(state.undoOperation)
    }

    @Test
    fun `a failed copy rewinds nothing, because the source never left`() = runTest {
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } returns
            FileOperationResult.failure("content://test/folder/a", "disk full")

        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.copyToSelection()

        assertEquals(listOf("a", "b"), viewModel.uiState.value.images.map { it.fileName })
    }

    // ── Folder switch while a transfer is in flight (Q-7) ────────────────

    private fun otherImage(name: String) = ImageItem(
        uri = "content://test/other/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
    )

    private fun names(viewModel: SelectorViewModel) = viewModel.uiState.value.images.map { it.fileName }

    @Test
    fun `a move that finishes after a folder switch is undoable in its own folder only`() = runTest {
        val transfer = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } coAnswers { transfer.await() }
        coEvery { imageRepository.undoMoveBundle(any(), any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/sel/a", "content://test/folder/a")
        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.moveToSelection()

        // The shared test flow replays its last batch to a new collector; start B clean.
        discovery.resetReplayCache()
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))
        discovery.emit(listOf(otherImage("x"), otherImage("y")))
        transfer.complete(FileOperationResult.success("content://test/folder/a", "content://test/sel/a"))

        assertTrue(viewModel.uiState.value.undoHistory.isEmpty())
        assertEquals(listOf("x", "y"), names(viewModel))
        assertEquals(FOLDER_URI, viewModel.uiState.value.parkedUndo.folders.single())

        // Ctrl+Z in B has nothing to reverse.
        viewModel.undoLastOperation()
        assertEquals(listOf("x", "y"), names(viewModel))
        coVerify(exactly = 0) { imageRepository.undoMoveBundle(any(), any(), any(), any(), any()) }

        discovery.resetReplayCache()
        viewModel.selectFolder(Uri.parse(FOLDER_URI))
        discovery.emit(listOf(image("b")))
        assertTrue(viewModel.uiState.value.undoHistory.single() is UndoableOperation.ReverseMove)
        viewModel.undoLastOperation()

        assertEquals(listOf("a", "b"), names(viewModel))
        coVerify(exactly = 1) {
            imageRepository.undoMoveBundle(any(), listOf("content://test/sel/a"), Uri.parse(FOLDER_URI), any(), any())
        }
    }

    @Test
    fun `a failed or partial result after a folder switch leaves the new folder's list and messages alone`() = runTest {
        val transfer = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } coAnswers { transfer.await() }
        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.moveToSelection()
        // The shared test flow replays its last batch to a new collector; start B clean.
        discovery.resetReplayCache()
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))
        discovery.emit(listOf(otherImage("x"), otherImage("y")))

        transfer.complete(
            FileOperationResult.partial(
                sourceUri = "content://test/folder/a",
                destinationUri = "content://test/sel/a",
                error = "partial",
            ),
        )

        assertEquals(listOf("x", "y"), names(viewModel))
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.undoHistory.isEmpty())
        // The verified copies are still the photographer's data: undoable back in folder A.
        assertEquals(1, viewModel.uiState.value.parkedUndo.historyOf(FOLDER_URI).size)
    }

    @Test
    fun `a failed move after a folder switch changes nothing in the new folder`() = runTest {
        val transfer = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } coAnswers { transfer.await() }
        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.moveToSelection()
        // The shared test flow replays its last batch to a new collector; start B clean.
        discovery.resetReplayCache()
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))
        discovery.emit(listOf(otherImage("x")))

        transfer.complete(FileOperationResult.failure("content://test/folder/a", "disk full"))

        assertEquals(listOf("x"), names(viewModel))
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.parkedUndo.folders.isEmpty())
    }

    @Test
    fun `the rating still follows a file whose move finished after a folder switch`() = runTest {
        val transfer = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } coAnswers { transfer.await() }
        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.moveToSelection()
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))

        transfer.complete(FileOperationResult.success("content://test/folder/a", "content://test/sel/a"))

        coVerify(exactly = 1) { cacheRepository.moveRating("content://test/folder/a", "content://test/sel/a") }
    }

    @Test
    fun `leaving a folder keeps its finished moves, commits its pending deletes, and pins its grant`() = runTest {
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/folder/a", "content://test/sel/a")
        val viewModel = loadFolder(image("a"), image("b"), image("c"))
        viewModel.moveToSelection()
        viewModel.deleteCurrentImage()
        assertEquals(2, viewModel.uiState.value.undoHistory.size)

        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))

        coVerify(exactly = 1) { imageRepository.deleteImage(any(), Uri.parse("content://test/folder/b")) }
        val parked = viewModel.uiState.value.parkedUndo.historyOf(FOLDER_URI)
        assertTrue(parked.single() is UndoableOperation.ReverseMove)
        assertTrue(viewModel.uiState.value.undoHistory.isEmpty())
        io.mockk.verify { settingsRepository.pinUndoHistoryFolders(setOf(FOLDER_URI)) }
    }

    // ── Undo availability ────────────────────────────────────────────────

    @Test
    fun `a completed move offers an undo, a copy never does`() = runTest {
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/folder/a", "content://test/sel/a")
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/folder/a", "content://test/sel/a")

        val moved = loadFolder(image("a"), image("b"))
        moved.moveToSelection()
        assertTrue(moved.uiState.value.undoOperation is UndoableOperation.ReverseMove)

        val copied = loadFolder(image("a"), image("b"))
        copied.copyToSelection()
        // Undoing a copy could only mean deleting the file just created.
        assertNull(copied.uiState.value.undoOperation)
    }

    @Test
    fun `a move with no destination URI offers no undo`() = runTest {
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/folder/a", null)

        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.moveToSelection()

        assertNull(viewModel.uiState.value.undoOperation)
    }

    // ── Filing hint wording (AD-5) ───────────────────────────────────────

    @Test
    fun `the filing hint names the action performed, not the configured one`() = runTest {
        // Copy is configured (the fixture default) and the user presses M.
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/folder/a", "content://test/sel/a")

        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.moveToSelection()

        val hint = viewModel.uiState.value.pendingHintText
        assertNotNull(hint)
        assertTrue(hint!!.title, hint.title.startsWith("Moved to"))
        assertTrue(hint.message, hint.message.contains("C copies instead"))
        // And the snackbar beside it says the same verb.
        assertTrue(viewModel.uiState.value.snackbarMessage!!.startsWith("Moved to"))
    }

    @Test
    fun `the filing hint names Copied when C is pressed with Move configured`() = runTest {
        filingActionFlow.value = FilingAction.MOVE
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } returns
            FileOperationResult.success("content://test/folder/a", "content://test/sel/a")

        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.copyToSelection()

        val hint = viewModel.uiState.value.pendingHintText
        assertNotNull(hint)
        assertTrue(hint!!.title, hint.title.startsWith("Copied to"))
        assertTrue(hint.message, hint.message.contains("M moves instead"))
    }

    // ── Deferred deletion ────────────────────────────────────────────────

    @Test
    fun `a delete leaves the list at once and touches no file yet`() = runTest {
        val viewModel = loadFolder(image("a"), image("b"), image("c"))
        viewModel.deleteCurrentImage()

        val state = viewModel.uiState.value
        assertEquals(listOf("b", "c"), state.images.map { it.fileName })
        assertEquals("1 image deleted", state.snackbarMessage)
        assertNotNull(state.pendingDeletion)
        assertTrue(state.undoOperation is UndoableOperation.RevertPendingDelete)
        coVerify(exactly = 0) { imageRepository.deleteImage(any(), any()) }
    }

    @Test
    fun `undoing a pending delete restores the frame and never deletes it`() = runTest {
        val viewModel = loadFolder(image("a"), image("b"), image("c"))
        viewModel.navigateToImage(1)
        viewModel.deleteCurrentImage()
        viewModel.undoLastOperation()

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b", "c"), state.images.map { it.fileName })
        assertNull(state.pendingDeletion)
        assertNull(state.undoOperation)
        assertNull(state.snackbarMessage)
        coVerify(exactly = 0) { imageRepository.deleteImage(any(), any()) }
    }

    @Test
    fun `a second destructive action keeps both deletions in the 20-step undo stack`() = runTest {
        val viewModel = loadFolder(image("a"), image("b"), image("c"))
        viewModel.deleteCurrentImage()
        viewModel.deleteCurrentImage()

        coVerify(exactly = 0) {
            imageRepository.deleteImage(any(), any())
        }
        assertEquals(2, viewModel.uiState.value.undoHistory.size)
        assertEquals(listOf("c"), viewModel.uiState.value.images.map { it.fileName })
    }

    @Test
    fun `leaving the screen commits the deletion rather than dropping it`() = runTest {
        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.deleteCurrentImage()

        // Reaching onCleared through a folder change would also do it; this is
        // the path where viewModelScope dies, which is why the commit is on the
        // application scope.
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))

        coVerify(exactly = 1) {
            imageRepository.deleteImage(any(), match { it.toString().endsWith("/a") })
        }
    }

    @Test
    fun `delete is confirmed by dialog only where the file cannot be trashed`() = runTest {
        every { imageRepository.canTrash(any()) } returns false
        val saf = loadFolder(image("a"), image("b"))
        saf.requestDelete()
        assertTrue(saf.uiState.value.showDeleteConfirmation)
        assertEquals(2, saf.uiState.value.images.size)

        every { imageRepository.canTrash(any()) } returns true
        val trashing = loadFolder(image("a"), image("b"))
        trashing.requestDelete()
        assertFalse(trashing.uiState.value.showDeleteConfirmation)
        assertEquals(1, trashing.uiState.value.images.size)
    }

    // ── Snackbar ownership ───────────────────────────────────────────────

    @Test
    fun `dismissing the snackbar takes its undo with it`() = runTest {
        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.deleteCurrentImage()
        assertNotNull(viewModel.uiState.value.undoOperation)

        viewModel.dismissSnackbar()

        assertNull(viewModel.uiState.value.snackbarMessage)
        assertNull(viewModel.uiState.value.undoOperation)
    }

    @Test
    fun `an error reaches the photographer through the snackbar with no undo`() = runTest {
        val viewModel = loadFolder(image("a"))
        viewModel.setError("Could not read the folder")

        assertEquals("Could not read the folder", viewModel.uiState.value.snackbarMessage)
        assertNull(viewModel.uiState.value.undoOperation)
    }

    // ── Background merges must not roll back what landed while they ran ──

    @Test
    fun `a scan does not roll back the EXIF already on screen`() = runTest {
        val exif = ExifData(iso = 800, aperture = 4.0)
        coEvery { imageRepository.getExifData(any(), any()) } returns exif
        val progress = MutableSharedFlow<ScanProgress>(replay = 1)
        every { scanImagesUseCase(any()) } returns progress

        val viewModel = loadFolder(image("a"))
        assertEquals(exif, viewModel.uiState.value.currentImage?.exifData)

        viewModel.startScan()
        progress.emit(
            ScanProgress(
                processed = 1,
                total = 1,
                currentFile = "a",
                results = mapOf(
                    "content://test/folder/a" to ScanResult(
                        filePath = "content://test/folder/a",
                        sharpnessScore = 70.0,
                    )
                ),
            )
        )

        val current = viewModel.uiState.value.currentImage
        assertEquals("the scan rolled EXIF back", exif, current?.exifData)
        assertEquals(70.0, current?.scanResult?.sharpnessScore!!, 0.01)
    }

    // ── Persisted SAF grant lifecycle ────────────────────────────────────

    @Test
    fun `startup reconciles persisted SAF grants`() = runTest {
        buildViewModel()
        coVerify(exactly = 1) { settingsRepository.reconcilePersistedGrants() }
    }

    @Test
    fun `revoked or missing folder grant sets missingGrantPrompt`() = runTest {
        coEvery { imageRepository.openFolder(any(), any()) } returns null
        val viewModel = buildViewModel()

        viewModel.selectFolder(Uri.parse(FOLDER_URI))

        val state = viewModel.uiState.value
        assertTrue(state.missingGrantPrompt)
        assertFalse(state.isLoading)
        assertNotNull(state.snackbarMessage)
    }

    // ── U-04b: Bundle filing, undo to parentDocumentId, and bundle delete ─

    @Test
    fun `a move that had to add a suffix tells the user which name the file got`() = runTest {
        val item = image("IMG_0001.jpg")
        val copy = "content://test/folder/Selection/JPEG/IMG_0001-2.jpg"
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.success(item.uri, copy, listOf(copy), null)

        val viewModel = loadFolder(item, image("b.jpg"))
        viewModel.moveToSelection()

        assertEquals("Moved to Selection as IMG_0001-2.jpg", viewModel.uiState.value.snackbarMessage)
    }

    // ── B-9 / D-13: a rating follows its file ─────────────────────────────

    @Test
    fun `a rated photo keeps its rating when it is moved into the Selection and when the move is undone`() = runTest {
        val item = image("IMG_0010.jpg")
        val moved = "content://test/folder/Selection/JPEG/IMG_0010.jpg"
        val restored = "content://test/folder/IMG_0010-2.jpg"
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.success(item.uri, moved, listOf(moved), null)
        coEvery { imageRepository.undoMoveBundle(any(), listOf(moved), Uri.parse(FOLDER_URI), any(), any()) } returns
            FileOperationResult.success(moved, restored, listOf(restored), null)

        val viewModel = loadFolder(item, image("b.jpg"))
        viewModel.moveToSelection()
        coVerify { cacheRepository.moveRating(item.uri, moved) }

        viewModel.undoLastOperation()
        coVerify { cacheRepository.moveRating(moved, restored) }
    }

    @Test
    fun `a copy gets the rating of its original`() = runTest {
        val item = image("IMG_0011.jpg")
        val copy = "content://test/folder/Selection/JPEG/IMG_0011.jpg"
        coEvery { imageRepository.copyBundle(any(), item, any(), any()) } returns
            FileOperationResult.success(item.uri, copy, listOf(copy), null)

        val viewModel = loadFolder(item, image("b.jpg"))
        viewModel.copyToSelection()

        coVerify { cacheRepository.copyRating(item.uri, copy) }
        coVerify(exactly = 0) { cacheRepository.moveRating(any(), any()) }
    }

    @Test
    fun `a move that kept its name keeps the plain message`() = runTest {
        val item = image("IMG_0002.jpg")
        val copy = "content://test/folder/Selection/JPEG/IMG_0002.jpg"
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.success(item.uri, copy, listOf(copy), null)

        val viewModel = loadFolder(item, image("b.jpg"))
        viewModel.moveToSelection()

        assertEquals("Moved to Selection", viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `an undo that restored under a suffix says so`() = runTest {
        val item = image("IMG_0003.jpg")
        val copy = "content://test/folder/Selection/JPEG/IMG_0003.jpg"
        val restored = "content://test/folder/IMG_0003-2.jpg"
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.success(item.uri, copy, listOf(copy), null)
        coEvery { imageRepository.undoMoveBundle(any(), listOf(copy), Uri.parse(FOLDER_URI), any(), any()) } returns
            FileOperationResult.success(copy, restored, listOf(restored), null)

        val viewModel = loadFolder(item, image("b.jpg"))
        viewModel.moveToSelection()
        viewModel.undoLastOperation()

        assertEquals(
            "Restored as IMG_0003-2.jpg (original name was in use)",
            viewModel.uiState.value.snackbarMessage,
        )
    }

    @Test
    fun `an undo that fell back to the opened folder tells the user`() = runTest {
        val item = image("a")
        val copy = "content://test/folder/Selection/JPEG/a.jpg"
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.success(item.uri, copy, listOf(copy), "msf:gone")
        coEvery { imageRepository.undoMoveBundle(any(), listOf(copy), Uri.parse(FOLDER_URI), "msf:gone") } returns
            FileOperationResult.success(copy, item.uri, listOf(item.uri), "msf:gone").copy(
                notice = "Restored to the opened folder — the original folder no longer exists",
            )

        val viewModel = loadFolder(item, image("b"))
        viewModel.moveToSelection()
        viewModel.undoLastOperation()

        assertEquals(
            "Restored to the opened folder — the original folder no longer exists",
            viewModel.uiState.value.snackbarMessage,
        )
    }

    @Test
    fun `a partial move keeps the photo, reports it and stays undoable so undo deletes only the surviving-original copies`() = runTest {
        val item = image("a")
        val copy = "content://test/folder/Selection/JPEG/a.jpg"
        val copies = mapOf(copy to item.uri)
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.partial(
                sourceUri = item.uri,
                destinationUri = copy,
                error = "Copied to Selection — original could not be removed",
                copiesOfSurvivingOriginals = copies,
            )
        coEvery {
            imageRepository.undoMoveBundle(any(), listOf(copy), Uri.parse(FOLDER_URI), any(), copies)
        } returns FileOperationResult.success(copy, null, emptyList())

        val viewModel = loadFolder(item, image("b"))
        viewModel.moveToSelection()

        val state = viewModel.uiState.value
        assertTrue(state.images.any { it.uri == item.uri })
        assertEquals("Copied to Selection — original could not be removed", state.snackbarMessage)
        val undoOp = state.undoHistory.single() as UndoableOperation.ReverseMove
        assertEquals(copies, undoOp.copiesOfSurvivingOriginals)
        assertTrue(undoOp.slots.isEmpty())

        viewModel.undoLastOperation()

        coVerify(exactly = 1) {
            imageRepository.undoMoveBundle(any(), listOf(copy), Uri.parse(FOLDER_URI), any(), copies)
        }
        assertTrue(viewModel.uiState.value.undoHistory.isEmpty())
        assertTrue(viewModel.uiState.value.images.any { it.uri == item.uri })
    }

    // ── D-13: a verified copy of a partial move carries the rating ────────

    @Test
    fun `a partial move gives the verified copy the rating and leaves the original's alone`() = runTest {
        val item = image("IMG_0020.jpg")
        val copy = "content://test/folder/Selection/JPEG/IMG_0020.jpg"
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.partial(item.uri, copy, copiesOfSurvivingOriginals = mapOf(copy to item.uri))

        val viewModel = loadFolder(item, image("b"))
        viewModel.moveToSelection()

        coVerify(exactly = 1) { cacheRepository.copyRating(item.uri, copy) }
        coVerify(exactly = 0) { cacheRepository.moveRating(any(), any()) }
        coVerify(exactly = 0) { cacheRepository.deleteRating(any()) }
    }

    @Test
    fun `a partial bundle move whose primary did move re-keys the rating instead of copying it`() = runTest {
        val item = image("IMG_0021.jpg")
        val primary = "content://test/folder/Selection/JPEG/IMG_0021.jpg"
        val member = "content://test/folder/Selection/RAW/IMG_0021.cr3"
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.partial(
                sourceUri = item.uri,
                destinationUri = primary,
                destinationUris = listOf(primary, member),
                copiesOfSurvivingOriginals = mapOf(member to "content://test/folder/IMG_0021.cr3"),
            )

        loadFolder(item, image("b")).moveToSelection()

        coVerify(exactly = 1) { cacheRepository.moveRating(item.uri, primary) }
        coVerify(exactly = 0) { cacheRepository.copyRating(any(), any()) }
    }

    @Test
    fun `a partial move that finishes after a folder switch still carries the rating`() = runTest {
        val item = image("a")
        val copy = "content://test/sel/a"
        val transfer = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } coAnswers { transfer.await() }
        val viewModel = loadFolder(item, image("b"))
        viewModel.moveToSelection()
        discovery.resetReplayCache()
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))
        discovery.emit(listOf(otherImage("x")))

        transfer.complete(FileOperationResult.partial(item.uri, copy))

        coVerify(exactly = 1) { cacheRepository.copyRating(item.uri, copy) }
        assertEquals(listOf("x"), names(viewModel))
    }

    @Test
    fun `undoing a partial move drops the deleted copy's rating row and never re-keys onto the original`() = runTest {
        val item = image("IMG_0022.jpg")
        val copy = "content://test/folder/Selection/JPEG/IMG_0022.jpg"
        val copies = mapOf(copy to item.uri)
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.partial(item.uri, copy, copiesOfSurvivingOriginals = copies)
        coEvery {
            imageRepository.undoMoveBundle(any(), listOf(copy), Uri.parse(FOLDER_URI), any(), copies)
        } returns FileOperationResult.success(copy, null, emptyList()).copy(deletedCopies = listOf(copy))

        val viewModel = loadFolder(item, image("b"))
        viewModel.moveToSelection()
        viewModel.undoLastOperation()

        coVerify(exactly = 1) { cacheRepository.deleteRating(copy) }
        coVerify(exactly = 0) { cacheRepository.moveRating(copy, any()) }
        coVerify(exactly = 0) { cacheRepository.deleteRating(item.uri) }
    }

    @Test
    fun `undoing a mixed partial move deletes the copy's row and still re-keys the restored primary`() = runTest {
        val item = image("IMG_0023.jpg")
        val primary = "content://test/folder/Selection/JPEG/IMG_0023.jpg"
        val member = "content://test/folder/Selection/RAW/IMG_0023.cr3"
        val restored = "content://test/folder/IMG_0023-2.jpg"
        val copies = mapOf(member to "content://test/folder/IMG_0023.cr3")
        coEvery { imageRepository.moveBundle(any(), item, any(), any()) } returns
            FileOperationResult.partial(item.uri, primary, destinationUris = listOf(primary, member), copiesOfSurvivingOriginals = copies)
        coEvery {
            imageRepository.undoMoveBundle(any(), listOf(primary, member), Uri.parse(FOLDER_URI), any(), copies)
        } returns FileOperationResult.success(primary, restored, listOf(restored)).copy(deletedCopies = listOf(member))

        val viewModel = loadFolder(item, image("b"))
        viewModel.moveToSelection()
        viewModel.undoLastOperation()

        coVerify(exactly = 1) { cacheRepository.deleteRating(member) }
        coVerify(exactly = 1) { cacheRepository.moveRating(primary, restored) }
    }

    @Test
    fun `moving a bundle records all destinationUris and parentDocumentId and undoLastOperation passes them to undoMoveBundle`() = runTest {
        val bundleItem = ImageItem(
            uri = "content://test/folder/sub/day1/IMG_0001.JPG",
            fileName = "IMG_0001.JPG",
            fileSize = 100L,
            lastModified = 1L,
            mimeType = "image/jpeg",
            companions = listOf(
                com.photoselectortoolbox.data.model.CompanionFile(
                    uri = "content://test/folder/sub/day1/IMG_0001.CR3",
                    fileName = "IMG_0001.CR3",
                ),
                com.photoselectortoolbox.data.model.CompanionFile(
                    uri = "content://test/folder/sub/day1/IMG_0001.CR3.xmp",
                    fileName = "IMG_0001.CR3.xmp",
                ),
            ),
            parentDocumentId = "sub/day1",
        )
        val destUris = listOf(
            "content://test/folder/Selection/JPEG/IMG_0001.JPG",
            "content://test/folder/Selection/RAW/IMG_0001.CR3",
            "content://test/folder/Selection/RAW/IMG_0001.CR3.xmp",
        )
        coEvery { imageRepository.moveBundle(any(), bundleItem, any(), any()) } returns
            FileOperationResult.success(
                sourceUri = bundleItem.uri,
                destinationUri = destUris.first(),
                destinationUris = destUris,
                parentDocumentId = "sub/day1",
            )
        coEvery { imageRepository.undoMoveBundle(any(), destUris, Uri.parse(FOLDER_URI), "sub/day1") } returns
            FileOperationResult.success(destUris.first(), bundleItem.uri, listOf(bundleItem.uri), "sub/day1")

        val viewModel = loadFolder(bundleItem, image("b"))
        viewModel.moveToSelection()

        val undoOp = viewModel.uiState.value.undoOperation as? UndoableOperation.ReverseMove
        assertNotNull(undoOp)
        assertEquals(destUris, undoOp?.destinationUris)
        assertEquals("sub/day1", undoOp?.parentDocumentId)

        viewModel.undoLastOperation()

        assertEquals(listOf("IMG_0001.JPG", "b"), viewModel.uiState.value.images.map { it.fileName })
        coVerify(exactly = 1) {
            imageRepository.undoMoveBundle(any(), destUris, Uri.parse(FOLDER_URI), "sub/day1")
        }
    }

    @Test
    fun `committing a pending deletion deletes both primary and companion URIs`() = runTest {
        val bundleItem = ImageItem(
            uri = "content://test/folder/IMG_0002.JPG",
            fileName = "IMG_0002.JPG",
            fileSize = 100L,
            lastModified = 1L,
            mimeType = "image/jpeg",
            companions = listOf(
                com.photoselectortoolbox.data.model.CompanionFile(
                    uri = "content://test/folder/IMG_0002.CR3",
                    fileName = "IMG_0002.CR3",
                ),
                com.photoselectortoolbox.data.model.CompanionFile(
                    uri = "content://test/folder/IMG_0002.CR3.xmp",
                    fileName = "IMG_0002.CR3.xmp",
                ),
            ),
        )

        val viewModel = loadFolder(bundleItem, image("b"))
        viewModel.deleteCurrentImage()
        // Trigger commit via folder switch.
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))

        coVerify(exactly = 1) {
            imageRepository.deleteImage(any(), Uri.parse("content://test/folder/IMG_0002.JPG"))
        }
        coVerify(exactly = 1) {
            imageRepository.deleteImage(any(), Uri.parse("content://test/folder/IMG_0002.CR3"))
        }
        coVerify(exactly = 1) {
            imageRepository.deleteImage(any(), Uri.parse("content://test/folder/IMG_0002.CR3.xmp"))
        }
    }

    // ── U-05: 20-step undo stack, one-time per-folder confirmation, journal & Ctrl+Z ─

    @Test
    fun `confirming delete once per folder skips dialog for subsequent deletes and resets on new folder`() = runTest {
        every { imageRepository.canTrash(any()) } returns false
        val viewModel = loadFolder(image("a"), image("b"), image("c"))

        // First delete in FOLDER_URI shows confirmation dialog.
        viewModel.requestDelete()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
        assertEquals(3, viewModel.uiState.value.images.size)

        // User confirms the dialog.
        viewModel.deleteCurrentImage()
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
        assertEquals(listOf("b", "c"), viewModel.uiState.value.images.map { it.fileName })

        // Second delete in the same folder executes immediately without showing the dialog.
        viewModel.requestDelete()
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
        assertEquals(listOf("c"), viewModel.uiState.value.images.map { it.fileName })

        // Switching to another folder resets the confirmation gate for that new folder.
        viewModel.selectFolder(Uri.parse(OTHER_FOLDER_URI))
        discovery.emit(listOf(image("d"), image("e")))
        viewModel.requestDelete()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
    }

    @Test
    fun `three consecutive moves and deletes undo in LIFO order to their original slot indices`() = runTest {
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } coAnswers {
            val src = secondArg<Uri>().toString()
            val name = src.substringAfterLast('/')
            FileOperationResult.success(src, "content://test/sel/$name", listOf("content://test/sel/$name"))
        }
        coEvery { imageRepository.undoMove(any(), any(), any()) } coAnswers {
            val dest = secondArg<Uri>().toString()
            val name = dest.substringAfterLast('/')
            FileOperationResult.success(dest, "content://test/folder/$name", listOf("content://test/folder/$name"))
        }

        val viewModel = loadFolder(image("a"), image("b"), image("c"), image("d"))
        // 1. Delete "a" (slot 0) -> remaining: b, c, d
        viewModel.deleteCurrentImage()
        // 2. Move "b" (now at slot 0) -> remaining: c, d
        viewModel.moveToSelection()
        // 3. Delete "c" (now at slot 0) -> remaining: d
        viewModel.deleteCurrentImage()

        assertEquals(listOf("d"), viewModel.uiState.value.images.map { it.fileName })
        assertEquals(3, viewModel.uiState.value.undoHistory.size)

        // Undo 3 (Delete "c") -> c, d
        viewModel.undoLastOperation()
        assertEquals(listOf("c", "d"), viewModel.uiState.value.images.map { it.fileName })

        // Undo 2 (Move "b") -> b, c, d
        viewModel.undoLastOperation()
        assertEquals(listOf("b", "c", "d"), viewModel.uiState.value.images.map { it.fileName })

        // Undo 1 (Delete "a") -> a, b, c, d
        viewModel.undoLastOperation()
        assertEquals(listOf("a", "b", "c", "d"), viewModel.uiState.value.images.map { it.fileName })
        assertTrue(viewModel.uiState.value.undoHistory.isEmpty())
    }

    @Test
    fun `pushing a 21st undoable action evicts the oldest entry and commits its pending deletion to disk`() = runTest {
        val items = (1..22).map { i -> image("img_$i.jpg") }
        val viewModel = loadFolder(*items.toTypedArray())

        repeat(20) {
            viewModel.deleteCurrentImage()
        }
        assertEquals(20, viewModel.uiState.value.undoHistory.size)
        coVerify(exactly = 0) { imageRepository.deleteImage(any(), any()) }

        // 21st delete evicts img_1.jpg and commits it to disk.
        viewModel.deleteCurrentImage()
        assertEquals(20, viewModel.uiState.value.undoHistory.size)
        coVerify(exactly = 1) {
            imageRepository.deleteImage(any(), Uri.parse("content://test/folder/img_1.jpg"))
        }
    }

    @Test
    fun `failed pending delete commit restores the photo to images and reports its name in error`() = runTest {
        coEvery {
            imageRepository.deleteImage(any(), Uri.parse("content://test/folder/img_1.jpg"))
        } returns false

        val items = (1..22).map { i -> image("img_$i.jpg") }
        val viewModel = loadFolder(*items.toTypedArray())

        // Push 21 deletes so img_1.jpg is evicted and committed while still in the same folder.
        repeat(21) {
            viewModel.deleteCurrentImage()
        }

        val state = viewModel.uiState.value
        assertTrue(state.images.any { it.fileName == "img_1.jpg" })
        assertEquals("Could not delete img_1.jpg", state.error)
        assertEquals("Could not delete img_1.jpg", state.snackbarMessage)
    }

    @Test
    fun `non-empty pendingDeleteJournal on startup exposes prompt and confirm or keep clears journal`() = runTest {
        val orphanUri = "content://test/folder/orphan.jpg"
        pendingDeleteJournalFlow.value = setOf(orphanUri)

        // Case 1: Confirming deletes the journaled URI and clears the journal.
        val vmConfirm = buildViewModel()
        val prompt1 = vmConfirm.uiState.value.uncompletedDeletionsPrompt
        assertNotNull(prompt1)
        assertEquals(listOf(orphanUri), prompt1?.uris)

        vmConfirm.confirmUncompletedDeletions()
        assertNull(vmConfirm.uiState.value.uncompletedDeletionsPrompt)
        coVerify(exactly = 1) { imageRepository.deleteImage(any(), Uri.parse(orphanUri)) }
        // Only the recovered entries leave the journal; it is never wiped wholesale (D-9).
        coVerify(exactly = 1) { settingsRepository.removePendingDeletes(listOf(orphanUri)) }
        coVerify(exactly = 0) { settingsRepository.clearPendingDeleteJournal() }

        // Case 2: Keeping drops the recovered entries without calling deleteImage.
        val keepUri = "content://test/folder/keep.jpg"
        pendingDeleteJournalFlow.value = setOf(keepUri)
        val vmKeep = buildViewModel()
        assertNotNull(vmKeep.uiState.value.uncompletedDeletionsPrompt)

        vmKeep.keepUncompletedDeletions()
        assertNull(vmKeep.uiState.value.uncompletedDeletionsPrompt)
        coVerify(exactly = 0) { imageRepository.deleteImage(any(), Uri.parse(keepUri)) }
        coVerify(exactly = 1) { settingsRepository.removePendingDeletes(listOf(keepUri)) }
        coVerify(exactly = 0) { settingsRepository.clearPendingDeleteJournal() }
    }

    @Test
    fun `a failed recovery delete is reported to the user and the other entries are still processed`() = runTest {
        val bad = "content://test/folder/bad.jpg"
        val good = "content://test/folder/good.jpg"
        pendingDeleteJournalFlow.value = setOf(bad, good)
        coEvery { imageRepository.deleteImage(any(), Uri.parse(bad)) } returns false

        val viewModel = buildViewModel()
        viewModel.confirmUncompletedDeletions()

        coVerify(exactly = 1) { imageRepository.deleteImage(any(), Uri.parse(good)) }
        val state = viewModel.uiState.value
        assertTrue(state.snackbarMessage?.contains("1 of 2") == true)
        assertTrue(state.snackbarMessage?.contains("bad.jpg") == true)
    }

    @Test
    fun `a throwing recovery delete is reported to the user`() = runTest {
        val bad = "content://test/folder/bad.jpg"
        pendingDeleteJournalFlow.value = setOf(bad)
        coEvery { imageRepository.deleteImage(any(), Uri.parse(bad)) } throws SecurityException("read-only card")

        val viewModel = buildViewModel()
        viewModel.confirmUncompletedDeletions()

        val message = viewModel.uiState.value.snackbarMessage
        assertTrue(message?.contains("bad.jpg") == true)
        assertTrue(message?.contains("read-only card") == true)
    }

    @Test
    fun `Ctrl+Z key event invokes undoLastOperation even after snackbar is dismissed`() = runTest {
        val viewModel = loadFolder(image("a"), image("b"))
        viewModel.deleteCurrentImage()
        assertEquals(listOf("b"), viewModel.uiState.value.images.map { it.fileName })

        // Dismiss the snackbar — snackbar undo is now null, but undoHistory still holds the operation.
        viewModel.dismissSnackbar()
        assertNull(viewModel.uiState.value.undoOperation)
        assertEquals(1, viewModel.uiState.value.undoHistory.size)

        val handled = handleSelectorKey(
            key = Key.Z,
            sheetOpen = false,
            contextMenuOpen = false,
            fullscreenOpen = false,
            maximised = false,
            actions = SelectorActions(
                onMove = {},
                onCopy = {},
                onDelete = {},
                onFullscreen = {},
                onToggleDetails = {},
                onToggleFilmstrip = {},
                onToggleOverlayValues = {},
                onShowShortcuts = {},
            ),
            onPrevious = {},
            onNext = {},
            onMaximise = {},
            onShowShortcuts = {},
            onCloseOverlays = {},
            ctrlOrMetaPressed = true,
            onUndo = viewModel::undoLastOperation,
        )

        assertTrue(handled)
        assertEquals(listOf("a", "b"), viewModel.uiState.value.images.map { it.fileName })
        assertTrue(viewModel.uiState.value.undoHistory.isEmpty())
    }
}

