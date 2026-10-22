package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.source.FileOperationResult
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.domain.CollectionAction
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FirstRunHint
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.PhotoFolders
import com.phototok.domain.SwipeAction
import com.phototok.domain.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.Runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import com.photoselector.core.storage.ExternalVolume
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
class PhoneModeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val phoneSettingsFlow = MutableStateFlow(PhoneSettings())
    private val collectionUriFlow = MutableStateFlow<String?>(null)
    private val leftSwipeUriFlow = MutableStateFlow<String?>(null)
    private val lastFolderUriFlow = MutableStateFlow<String?>(null)
    private val sortingEnabledFlow = MutableStateFlow(true)
    // A recent timestamp so the gesture tutorial stays hidden during tests.
    private val gestureTutorialTsFlow = MutableStateFlow(System.currentTimeMillis())
    private val lastAppUsedTsFlow = MutableStateFlow(System.currentTimeMillis())

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } returns phoneSettingsFlow
        every { phoneCollectionUri } returns collectionUriFlow
        every { phoneLeftSwipeUri } returns leftSwipeUriFlow
        every { lastFolderUri } returns lastFolderUriFlow
        every { sortingEnabled } returns sortingEnabledFlow
        every { phoneGestureTutorialTs } returns gestureTutorialTsFlow
        every { lastAppUsedTs } returns lastAppUsedTsFlow
        coEvery { getFolderScanInfo(any()) } returns FolderScanInfo(0L, 0)
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { prepareSourceFolder(any()) } returns "Photos"
        coEvery { resolveFolderName(any()) } returns null
        coEvery { getExifData(any()) } returns null
        coEvery { getImageDimensions(any()) } returns Pair(0, 0)
        // The view model reads the detailed result; existing tests still stub the Boolean API.
        coEvery { copyImageDetailed(any(), any(), any(), any()) } coAnswers {
            val src = firstArg<Uri>()
            if (copyImage(src, secondArg(), thirdArg(), arg(3))) {
                FileOperationResult.success(src.toString(), null)
            } else {
                FileOperationResult.failure(src.toString(), null)
            }
        }
        coEvery { moveImageDetailed(any(), any(), any(), any()) } coAnswers {
            val src = firstArg<Uri>()
            if (moveImage(src, secondArg(), thirdArg(), arg(3))) {
                FileOperationResult.success(src.toString(), null)
            } else {
                FileOperationResult.failure(src.toString(), null)
            }
        }
        // The bundle seam answers member by member from the per-file stubs, unless a test overrides it.
        coEvery { transferBundleDetailed(any(), any(), any(), any(), any()) } coAnswers {
            val sources = firstArg<List<Uri>>()
            val isMove = arg<Boolean>(4)
            sources.map { src ->
                if (isMove) moveImageDetailed(src, secondArg(), thirdArg(), arg(3))
                else copyImageDetailed(src, secondArg(), thirdArg(), arg(3))
            }
        }
    }

    private fun image(name: String, modified: Long) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1,
        lastModified = modified,
        mimeType = "image/jpeg",
        imageWidth = 100,
        imageHeight = 50,
    )

    private fun buildViewModel(): PhoneModeViewModel = PhoneModeViewModel(
        imageRepository = imageRepository,
        settingsRepository = settingsRepository,
        appScope = CoroutineScope(testDispatcher),
    )

    private fun loadFolder(vararg images: ImageItem): PhoneModeViewModel {
        every { imageRepository.discoverImages(any()) } returns flowOf(images.toList())
        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))
        return viewModel
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `typed settings flow propagates into ui state`() = runTest {
        val viewModel = buildViewModel()

        phoneSettingsFlow.value = PhoneSettings(
            collectionAction = CollectionAction.MOVE,
            leftSwipeAction = SwipeAction.COPY,
            moveRelatedFiles = true,
            recentPathsCount = 5,
        )

        val state = viewModel.uiState.value
        assertEquals(CollectionAction.MOVE, state.collectionAction)
        assertEquals(SwipeAction.COPY, state.leftSwipeAction)
        assertTrue(state.moveRelatedFiles)
        assertEquals(5, state.recentPathsCount)
    }

    @Test
    fun `selectSourceFolder loads images and records the folder`() = runTest {
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        val state = viewModel.uiState.value
        assertEquals(2, state.images.size)
        assertEquals("Photos", state.sourceFolderName)
        assertFalse(state.isLoading)
        assertNull(state.error)
        coVerify { settingsRepository.addRecentPath("content://tree/photos", "Photos") }
    }

    @Test
    fun `selectRecentPath re-opens the folder like a fresh selection`() = runTest {
        every { imageRepository.discoverImages(any()) } returns
            flowOf(listOf(image("IMG_001.JPG", 1)))
        val viewModel = buildViewModel()

        viewModel.selectRecentPath(
            com.phototok.data.model.RecentPath("content://tree/recent", "Recent")
        )

        val state = viewModel.uiState.value
        assertEquals("content://tree/recent", state.sourceFolderUri)
        assertEquals(1, state.images.size)
        coVerify { settingsRepository.addRecentPath("content://tree/recent", "Photos") }
    }

    @Test
    fun `selectSourceFolder reports inaccessible folders and sets missingGrantPrompt`() = runTest {
        coEvery { imageRepository.prepareSourceFolder(any()) } returns null
        val viewModel = buildViewModel()

        coVerify(exactly = 1) { settingsRepository.reconcilePersistedGrants() }

        viewModel.selectSourceFolder(Uri.parse("content://tree/gone"))

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertTrue(state.missingGrantPrompt)
        assertNull(state.sourceFolderUri)
    }

    @Test
    fun `move to collection with partial failure keeps failed file and reports counts`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            collectionAction = CollectionAction.MOVE,
            moveRelatedFiles = true,
        )
        // Sibling pair (same stem) plus one unrelated file.
        val jpg = image("IMG_001.jpg", 1)
        val arw = image("IMG_001.arw", 2)
        val other = image("IMG_002.jpg", 3)
        coEvery {
            imageRepository.moveImage(match { it.toString().endsWith(".jpg") }, any(), any(), any())
        } returns true
        coEvery {
            imageRepository.moveImage(match { it.toString().endsWith(".arw") }, any(), any(), any())
        } returns false
        val viewModel = loadFolder(jpg, arw, other)
        val current = viewModel.uiState.value.images[viewModel.uiState.value.currentIndex]
        // Only exercise the sibling-pair case (current must be part of the pair).
        if (!current.fileName.startsWith("IMG_001")) {
            viewModel.navigateToImage(
                viewModel.uiState.value.images.indexOfFirst { it.fileName.startsWith("IMG_001") }
            )
        }

        viewModel.addToCollection()

        val state = viewModel.uiState.value
        val feedback = state.lastActionFeedback
        assertNotNull(feedback)
        assertTrue(feedback!!.isError)
        assertEquals(UiText.of(R.string.error_moved_some, 1, 2, UiText.raw(PhotoFolders.SELECTION)), feedback.message)
        // Only the successfully moved file left the feed.
        assertTrue(state.images.any { it.fileName == "IMG_001.arw" })
        assertFalse(state.images.any { it.fileName == "IMG_001.jpg" })
        assertTrue(state.images.any { it.fileName == "IMG_002.jpg" })
    }

    @Test
    fun `copy to collection reports success without removing files`() = runTest {
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } returns true
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.addToCollection()

        val state = viewModel.uiState.value
        assertEquals(UiText.of(R.string.snackbar_copied, UiText.raw(PhotoFolders.SELECTION)), state.lastActionFeedback?.message)
        assertFalse(state.lastActionFeedback?.isError == true)
        assertEquals(2, state.images.size)
    }

    @Test
    fun `requestDelete is revertable and revert restores the feed`() = runTest {
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.requestDelete()
        assertNotNull(viewModel.uiState.value.pendingDelete)
        assertEquals(1, viewModel.uiState.value.images.size)

        viewModel.revertDelete()
        assertNull(viewModel.uiState.value.pendingDelete)
        assertEquals(2, viewModel.uiState.value.images.size)
        coVerify(exactly = 0) { imageRepository.deleteImage(any()) }
    }

    @Test
    fun `finalizePendingDelete deletes via the repository`() = runTest {
        coEvery { imageRepository.deleteImage(any()) } returns true
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.requestDelete()
        viewModel.finalizePendingDelete()

        assertNull(viewModel.uiState.value.pendingDelete)
        coVerify(exactly = 1) { imageRepository.deleteImage(any()) }
    }

    @Test
    fun `navigateToImage moves to the page`() = runTest {
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.navigateToImage(1)

        assertEquals(1, viewModel.uiState.value.currentIndex)
    }

    // ── One-time action explanations ─────────────────────────────────────

    @Test
    fun `swipes show no first-run hint - the guide explains them once`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.COPY, leftSwipeAction = SwipeAction.DELETE)
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2), image("c.jpg", 3))

        viewModel.addToCollection()
        assertNull(viewModel.uiState.value.firstRunHint)
        viewModel.requestDelete()
        assertNull(viewModel.uiState.value.firstRunHint)

        phoneSettingsFlow.value = PhoneSettings(leftSwipeAction = SwipeAction.MOVE)
        val moving = loadFolder(image("d.jpg", 1), image("e.jpg", 2))
        moving.performLeftSwipeCopyOrMove()
        assertNull(moving.uiState.value.firstRunHint)
        coVerify(exactly = 0) { settingsRepository.markFirstRunHintSeen(FirstRunHint.SWIPE_RIGHT) }
    }

    @Test
    fun `a hint explains the action once and marks it seen`() = runTest {
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.maybeShowFirstRunHint(FirstRunHint.TAP_HUD)

        val hint = viewModel.uiState.value.firstRunHint
        assertNotNull(hint)
        assertEquals(FirstRunHint.TAP_HUD, hint!!.hint)
        assertEquals(UiText.of(R.string.hint_title_tap_hud), hint.title)
        coVerify { settingsRepository.markFirstRunHintSeen(FirstRunHint.TAP_HUD) }
    }

    @Test
    fun `a hint fires only once per action`() = runTest {
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.maybeShowFirstRunHint(FirstRunHint.TAP_HUD)
        assertNotNull(viewModel.uiState.value.firstRunHint)

        viewModel.dismissFirstRunHint()
        viewModel.maybeShowFirstRunHint(FirstRunHint.TAP_HUD)

        assertNull(viewModel.uiState.value.firstRunHint)
    }

    @Test
    fun `hints already persisted as seen never fire`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            seenFirstRunHints = setOf(FirstRunHint.TAP_HUD.key),
        )
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.maybeShowFirstRunHint(FirstRunHint.TAP_HUD)

        assertNull(viewModel.uiState.value.firstRunHint)
        coVerify(exactly = 0) { settingsRepository.markFirstRunHintSeen(any()) }
    }

    @Test
    fun `no hint is shown while the full tutorial is up`() = runTest {
        gestureTutorialTsFlow.value = 0L // forces the tutorial to show
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))
        assertTrue(viewModel.uiState.value.showGestureTutorial)

        viewModel.maybeShowFirstRunHint(FirstRunHint.TAP_HUD)

        assertNull(viewModel.uiState.value.firstRunHint)
    }

    @Test
    fun `controls guide can be opened and closed`() = runTest {
        val viewModel = buildViewModel()

        assertFalse(viewModel.uiState.value.showControlsGuide)
        viewModel.showControlsGuide()
        assertTrue(viewModel.uiState.value.showControlsGuide)
        viewModel.hideControlsGuide()
        assertFalse(viewModel.uiState.value.showControlsGuide)
    }

    // ── Optimistic copy / move (the swipe must not wait for I/O) ─────────

    @Test
    fun `copy advances to the next photo before the copy finishes`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } coAnswers { gate.await() }
        val viewModel = loadFolder(image("a.jpg", 2), image("b.jpg", 1))

        viewModel.addToCollection()

        // The next photo is already on screen while the copy is still in flight.
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertNull(viewModel.uiState.value.lastActionFeedback)

        gate.complete(true)
        advanceUntilIdle()
        assertEquals(UiText.of(R.string.snackbar_copied, UiText.raw(PhotoFolders.SELECTION)), viewModel.uiState.value.lastActionFeedback?.message)
        assertEquals(2, viewModel.uiState.value.images.size)
    }

    @Test
    fun `move removes the photo from the feed before the move finishes`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        val gate = CompletableDeferred<Boolean>()
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } coAnswers { gate.await() }
        val viewModel = loadFolder(image("a.jpg", 2), image("b.jpg", 1))
        val moved = viewModel.uiState.value.images[0].fileName

        viewModel.addToCollection()

        // Gone from the feed immediately; the transfer is still running.
        assertEquals(1, viewModel.uiState.value.images.size)
        assertFalse(viewModel.uiState.value.images.any { it.fileName == moved })
        assertNull(viewModel.uiState.value.lastActionFeedback)

        gate.complete(true)
        advanceUntilIdle()
        assertEquals(UiText.of(R.string.snackbar_moved, UiText.raw(PhotoFolders.SELECTION)), viewModel.uiState.value.lastActionFeedback?.message)
        assertEquals(1, viewModel.uiState.value.images.size)
    }

    @Test
    fun `a failed move puts the photo back into the feed`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns false
        val viewModel = loadFolder(image("a.jpg", 3), image("b.jpg", 2), image("c.jpg", 1))
        val target = viewModel.uiState.value.images[0].fileName

        viewModel.addToCollection()

        val state = viewModel.uiState.value
        assertEquals(3, state.images.size)
        assertTrue("a failed move must not lose the photo", state.images.any { it.fileName == target })
        assertEquals(target, state.images[0].fileName)
        assertTrue(state.lastActionFeedback?.isError == true)
    }

    @Test
    fun `a move that throws puts the photo back into the feed`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        coEvery {
            imageRepository.moveImage(any(), any(), any(), any())
        } throws IllegalStateException("card removed")
        val viewModel = loadFolder(image("a.jpg", 2), image("b.jpg", 1))
        val target = viewModel.uiState.value.images[0].fileName

        viewModel.addToCollection()

        val state = viewModel.uiState.value
        assertEquals(2, state.images.size)
        assertTrue(state.images.any { it.fileName == target })
        assertTrue(state.lastActionFeedback?.isError == true)
    }

    @Test
    fun `a failed copy does not rewind the feed`() = runTest {
        // A copy leaves the source in place, so there is nothing to restore — the
        // user should stay on the photo they advanced to.
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } returns false
        val viewModel = loadFolder(image("a.jpg", 2), image("b.jpg", 1))

        viewModel.addToCollection()

        val state = viewModel.uiState.value
        assertEquals(2, state.images.size)
        assertEquals(1, state.currentIndex)
        assertTrue(state.lastActionFeedback?.isError == true)
    }

    @Test
    fun `copy on the last photo stays put instead of running off the end`() = runTest {
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } returns true
        val viewModel = loadFolder(image("a.jpg", 2), image("b.jpg", 1))
        viewModel.navigateToImage(1)

        viewModel.addToCollection()

        assertEquals(1, viewModel.uiState.value.currentIndex)
    }

    // ── Progressive discovery of large folders ───────────────────────────

    @Test
    fun `the viewer opens on the first batch while discovery continues`() = runTest {
        val discovery = MutableSharedFlow<List<ImageItem>>(extraBufferCapacity = 8)
        every { imageRepository.discoverImages(any()) } returns discovery
        val viewModel = buildViewModel()

        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)

        discovery.emit(listOf(image("a.jpg", 3), image("b.jpg", 2)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.images.size)
        assertFalse("the user must be able to swipe already", state.isLoading)
        assertTrue("the count is still growing", state.isDiscovering)
    }

    @Test
    fun `after the user swiped later batches are appended without reordering the visible feed`() = runTest {
        val discovery = MutableSharedFlow<List<ImageItem>>(extraBufferCapacity = 8)
        every { imageRepository.discoverImages(any()) } returns discovery
        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))

        val first = listOf(image("old_a.jpg", 10), image("old_b.jpg", 20))
        discovery.emit(first)
        advanceUntilIdle()
        viewModel.navigateToImage(1) // the user has taken over
        val orderAfterFirstBatch = viewModel.uiState.value.images.map { it.fileName }

        // Cumulative emission: the same two files plus two *newer* ones. A global
        // re-sort would put the newer files first and yank the feed under the user.
        discovery.emit(first + listOf(image("new_a.jpg", 900), image("new_b.jpg", 800)))
        advanceUntilIdle()

        val names = viewModel.uiState.value.images.map { it.fileName }
        assertEquals(4, names.size)
        assertEquals(orderAfterFirstBatch, names.take(2))
        assertEquals(listOf("new_a.jpg", "new_b.jpg"), names.drop(2))
        assertEquals(1, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun `before any swipe a newer later batch moves the newest photo to index 0`() = runTest {
        val discovery = MutableSharedFlow<List<ImageItem>>(extraBufferCapacity = 8)
        every { imageRepository.discoverImages(any()) } returns discovery
        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))

        val first = listOf(image("old_a.jpg", 10), image("old_b.jpg", 20))
        discovery.emit(first)
        advanceUntilIdle()
        assertEquals("old_b.jpg", viewModel.uiState.value.images[0].fileName)

        discovery.emit(first + listOf(image("new_a.jpg", 900), image("new_b.jpg", 800)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            listOf("new_a.jpg", "new_b.jpg", "old_b.jpg", "old_a.jpg"),
            state.images.map { it.fileName },
        )
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `a newer photo arriving after a swipe is appended and does not move the viewed photo`() = runTest {
        val discovery = MutableSharedFlow<List<ImageItem>>(extraBufferCapacity = 8)
        every { imageRepository.discoverImages(any()) } returns discovery
        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))

        val first = listOf(image("a.jpg", 30), image("b.jpg", 20))
        discovery.emit(first)
        advanceUntilIdle()
        viewModel.navigateToImage(1) // b.jpg
        discovery.emit(first + listOf(image("z_new.jpg", 99)))
        advanceUntilIdle()
        // append-only after a swipe: z_new is at the end, b.jpg still on screen
        assertEquals("z_new.jpg", viewModel.uiState.value.images.last().fileName)
        assertEquals("b.jpg", viewModel.uiState.value.images[viewModel.uiState.value.currentIndex].fileName)
    }

    @Test
    fun `a photo removed during discovery is not resurrected by the next batch`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        coEvery { imageRepository.moveImage(any(), any(), any(), any()) } returns true
        val discovery = MutableSharedFlow<List<ImageItem>>(extraBufferCapacity = 8)
        every { imageRepository.discoverImages(any()) } returns discovery
        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))

        val first = listOf(image("a.jpg", 20), image("b.jpg", 10))
        discovery.emit(first)
        advanceUntilIdle()
        val moved = viewModel.uiState.value.images[0].fileName
        viewModel.addToCollection()
        assertFalse(viewModel.uiState.value.images.any { it.fileName == moved })

        discovery.emit(first + listOf(image("c.jpg", 5)))
        advanceUntilIdle()

        val names = viewModel.uiState.value.images.map { it.fileName }
        assertFalse("the moved photo must stay gone", names.contains(moved))
        assertTrue(names.contains("c.jpg"))
    }

    @Test
    fun `a fresh load stays on the newest photo while batches arrive`() = runTest {
        val discovery = MutableSharedFlow<List<ImageItem>>(extraBufferCapacity = 8)
        every { imageRepository.discoverImages(any()) } returns discovery
        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))

        val first = (1..3).map { image("b1_$it.jpg", it.toLong()) }
        discovery.emit(first)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.currentIndex)

        discovery.emit(first + (4..8).map { image("b2_$it.jpg", it.toLong()) })
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals("b2_8.jpg", viewModel.uiState.value.images[0].fileName)
    }

    @Test
    fun `swiping during discovery keeps batches append-only`() = runTest {
        val discovery = MutableSharedFlow<List<ImageItem>>(extraBufferCapacity = 8)
        every { imageRepository.discoverImages(any()) } returns discovery
        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))

        val first = (1..3).map { image("b1_$it.jpg", it.toLong()) }
        discovery.emit(first)
        advanceUntilIdle()
        viewModel.navigateToImage(1) // the user takes over
        val viewed = viewModel.uiState.value.images[1].fileName

        discovery.emit(first + (4..9).map { image("b2_$it.jpg", it.toLong()) })
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(viewed, viewModel.uiState.value.images[1].fileName)
    }

    @Test
    fun `selecting another folder does not prepend the previous folder's photos`() = runTest {
        val viewModel = loadFolder(image("first_a.jpg", 2), image("first_b.jpg", 1))
        assertEquals(2, viewModel.uiState.value.images.size)

        every { imageRepository.discoverImages(any()) } returns
            flowOf(listOf(image("second_a.jpg", 5)))
        viewModel.selectSourceFolder(Uri.parse("content://tree/other"))

        val names = viewModel.uiState.value.images.map { it.fileName }
        assertEquals(listOf("second_a.jpg"), names)
    }

    @Test
    fun `isDiscovering clears when enumeration completes`() = runTest {
        val viewModel = loadFolder(image("a.jpg", 1))

        assertFalse(viewModel.uiState.value.isDiscovering)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `folder with raw and jpeg pairs triggers raw jpeg suggestion`() = runTest {
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo1.raw", 10),
        )

        assertTrue(viewModel.uiState.value.showRawJpegSuggestion)
    }

    @Test
    fun `dismissing raw jpeg suggestion hides it and marks hint seen`() = runTest {
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo1.raw", 10),
        )

        assertTrue(viewModel.uiState.value.showRawJpegSuggestion)
        viewModel.dismissRawJpegSuggestion()

        assertFalse(viewModel.uiState.value.showRawJpegSuggestion)
        coVerify { settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS) }
    }

    @Test
    fun `dismissing raw jpeg suggestion leaves settings unchanged (don't change settings)`() = runTest {
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo1.raw", 10),
        )

        assertTrue(viewModel.uiState.value.showRawJpegSuggestion)
        viewModel.dismissRawJpegSuggestion()

        assertFalse(viewModel.uiState.value.showRawJpegSuggestion)
        coVerify { settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS) }
        coVerify(exactly = 0) { settingsRepository.setPhoneFileTypeFilter(any()) }
        coVerify(exactly = 0) { settingsRepository.setPhoneMoveRelatedFiles(any()) }
    }

    @Test
    fun `applying raw jpeg filter updates settings and hides suggestion`() = runTest {
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo1.raw", 10),
        )

        viewModel.applyRawJpegFilter(com.phototok.domain.FileTypeFilter.RAW)

        assertFalse(viewModel.uiState.value.showRawJpegSuggestion)
        coVerify { settingsRepository.setPhoneFileTypeFilter(com.phototok.domain.FileTypeFilter.RAW) }
        coVerify { settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS) }
    }

    @Test
    fun `enabling move related files updates settings and hides suggestion`() = runTest {
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo1.raw", 10),
        )

        viewModel.enableMoveRelatedFiles()

        assertFalse(viewModel.uiState.value.showRawJpegSuggestion)
        coVerify { settingsRepository.setPhoneMoveRelatedFiles(true) }
        coVerify { settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS) }
    }

    @Test
    fun `folder with filter mismatch triggers filter mismatch hint`() = runTest {
        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(
            fileTypeFilter = com.phototok.domain.FileTypeFilter.RAW,
        )
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo2.jpg", 20),
            image("photo3.jpg", 30),
            image("photo4.raw", 40),
        )

        val hint = viewModel.uiState.value.firstRunHint
        assertNotNull(hint)
        assertEquals(FirstRunHint.FILTER_MISMATCH, hint!!.hint)
        assertEquals(UiText.of(R.string.hint_title_filter_raw), hint.title)
        assertEquals(UiText.of(R.string.hint_body_filter_mostly_jpeg), hint.message)
        assertEquals(UiText.of(R.string.action_show_all), hint.actionLabel)
        coVerify { settingsRepository.markFirstRunHintSeen(FirstRunHint.FILTER_MISMATCH) }
    }

    @Test
    fun `folder with raw and jpeg pairs does not trigger filter mismatch hint`() = runTest {
        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(
            fileTypeFilter = com.phototok.domain.FileTypeFilter.RAW,
        )
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo1.raw", 10),
            image("photo2.jpg", 20),
            image("photo2.raw", 20),
        )

        val hint = viewModel.uiState.value.firstRunHint
        assertNull(hint)
    }

    @Test
    fun `show all action on filter mismatch hint switches filter to all and marks hint seen`() = runTest {
        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(
            fileTypeFilter = com.phototok.domain.FileTypeFilter.RAW,
        )
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo2.jpg", 20),
            image("photo3.jpg", 30),
        )

        val hint = viewModel.uiState.value.firstRunHint
        assertNotNull(hint)
        assertNotNull(hint!!.onAction)

        hint.onAction!!.invoke()

        assertNull(viewModel.uiState.value.firstRunHint)
        coVerify { settingsRepository.setPhoneFileTypeFilter(com.phototok.domain.FileTypeFilter.ALL) }
        coVerify { settingsRepository.markFirstRunHintSeen(FirstRunHint.FILTER_MISMATCH) }
    }

    @Test
    fun `dismissing filter mismatch hint hides it and marks hint seen`() = runTest {
        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(
            fileTypeFilter = com.phototok.domain.FileTypeFilter.RAW,
        )
        val viewModel = loadFolder(
            image("photo1.jpg", 10),
            image("photo2.jpg", 20),
            image("photo3.jpg", 30),
        )

        assertNotNull(viewModel.uiState.value.firstRunHint)
        viewModel.dismissFirstRunHint()

        assertNull(viewModel.uiState.value.firstRunHint)
        coVerify { settingsRepository.markFirstRunHintSeen(FirstRunHint.FILTER_MISMATCH) }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.09")
    fun `reloadSourceFolder re-scans images and lands on the latest photo`() = runTest {
        val img1 = image("photo1.jpg", 10)
        val img2 = image("photo2.jpg", 20)
        val img3 = image("photo3.jpg", 30)
        val viewModel = loadFolder(img1, img2, img3)
        viewModel.navigateToImage(1) // viewing img2

        // Simulate reload returning updated list including an older photo
        val imgNew = image("photo0.jpg", 5)
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(imgNew, img1, img2, img3))

        viewModel.reloadSourceFolder()
        advanceUntilIdle()

        // Images are newest first: photo3 (30), photo2 (20), photo1 (10), photo0 (5)
        val names = viewModel.uiState.value.images.map { it.fileName }
        assertEquals(listOf("photo3.jpg", "photo2.jpg", "photo1.jpg", "photo0.jpg"), names)
        // Reload always goes to the latest photo, not back to the one that was on screen.
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(img3.uri, viewModel.uiState.value.images[viewModel.uiState.value.currentIndex].uri)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.09")
    fun `reloadSourceFolder with new photos goes straight to the newest without a dialog`() = runTest {
        val img1 = image("photo1.jpg", 10)
        val img2 = image("photo2.jpg", 20)
        val viewModel = loadFolder(img1, img2)
        viewModel.navigateToImage(1) // img1 (oldest)

        val imgNew = image("photo3.jpg", 30)
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(img1, img2, imgNew))

        viewModel.reloadSourceFolder()
        advanceUntilIdle()

        assertNull("an explicit Reload never asks", viewModel.uiState.value.reloadPrompt)
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(imgNew.uri, viewModel.uiState.value.images[0].uri)
        // New photos are not a "nothing new" case: no snackbar.
        assertNull(viewModel.uiState.value.lastActionFeedback)
        // The scan info is persisted for the landing position.
        coVerify {
            settingsRepository.setFolderScanInfo(
                any(),
                match { it.maxLastModified == 30L && it.fileCount == 3 && it.lastViewedUri == imgNew.uri && it.lastViewedIndex == 0 },
            )
        }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.09")
    fun `reloadSourceFolder with no new photos still goes to the latest and confirms in a snackbar`() = runTest {
        val img1 = image("photo1.jpg", 10)
        val img2 = image("photo2.jpg", 20)
        val viewModel = loadFolder(img1, img2)
        viewModel.navigateToImage(1)

        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(img1, img2))

        viewModel.reloadSourceFolder()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.reloadPrompt)
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(img2.uri, viewModel.uiState.value.images[0].uri)
        assertEquals(UiText.of(R.string.snackbar_no_new_photos), viewModel.uiState.value.lastActionFeedback?.message)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.09")
    fun `reloadSourceFolder in shooting order lands on the last photo`() = runTest {
        every { settingsRepository.sourceSettingsFlow(any()) } returns
            flowOf(SourceSettings(feedOrderMode = FeedOrderMode.SHOOTING))
        val img1 = image("photo1.jpg", 10)
        val img2 = image("photo2.jpg", 20)
        val viewModel = loadFolder(img1, img2)
        assertEquals(listOf("photo1.jpg", "photo2.jpg"), viewModel.uiState.value.images.map { it.fileName })

        val imgNew = image("photo3.jpg", 30)
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(img1, img2, imgNew))
        viewModel.navigateToImage(0)

        viewModel.reloadSourceFolder()
        advanceUntilIdle()

        assertEquals(listOf("photo1.jpg", "photo2.jpg", "photo3.jpg"), viewModel.uiState.value.images.map { it.fileName })
        assertEquals(2, viewModel.uiState.value.currentIndex)
        assertNull(viewModel.uiState.value.reloadPrompt)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.09")
    fun `reloadSourceFolder keeps the place of a user who swiped while the rescan was running`() = runTest {
        val img1 = image("photo1.jpg", 10)
        val img2 = image("photo2.jpg", 20)
        val img3 = image("photo3.jpg", 30)
        val viewModel = loadFolder(img1, img2, img3)

        val gate = CompletableDeferred<Unit>()
        every { imageRepository.discoverImages(any()) } returns kotlinx.coroutines.flow.flow {
            emit(listOf(img1, img2, img3))
            gate.await()
            emit(listOf(img1, img2, img3, image("photo4.jpg", 40)))
        }
        viewModel.reloadSourceFolder()
        advanceUntilIdle()
        assertTrue("the rescan is still running", viewModel.uiState.value.isDiscovering)
        viewModel.navigateToImage(2) // the user takes over and looks at photo1
        assertEquals(img1.uri, viewModel.uiState.value.images[2].uri)

        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDiscovering)
        assertNull(state.reloadPrompt)
        assertEquals("the viewed photo stays on screen", img1.uri, state.images[state.currentIndex].uri)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.09")
    fun `reloadSourceFolder records the scan so a later reopen does not announce the photos it showed`() = runTest {
        val img1 = image("photo1.jpg", 10)
        val img2 = image("photo2.jpg", 20)
        val viewModel = loadFolder(img1, img2)
        val imgNew = image("photo3.jpg", 30)
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(img1, img2, imgNew))
        viewModel.reloadSourceFolder()
        advanceUntilIdle()

        // What Reload persisted is what a later reopen compares against.
        coVerify { settingsRepository.setFolderScanInfo(any(), match { it.maxLastModified == 30L && it.fileCount == 3 }) }
    }

    @Test
    fun `selectSourceFolder on previously opened folder with new photos prompts to continue or start with newest`() = runTest {
        val img1 = image("photo1.jpg", 10)
        val img2 = image("photo2.jpg", 20)
        val img3 = image("photo3.jpg", 30)
        val img4 = image("photo4.jpg", 40)

        // Folder previously had max timestamp 20L (img2 & img1), user left off at img2
        coEvery { settingsRepository.getFolderScanInfo("content://tree/photos") } returns FolderScanInfo(
            maxLastModified = 20L,
            fileCount = 2,
            lastViewedUri = img2.uri,
            lastViewedIndex = 0,
        )
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(img1, img2, img3, img4))

        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))
        advanceUntilIdle()

        // Newest-first feed: [img4(40), img3(30), img2(20), img1(10)]
        // User left off at img2 (now index 2), and 2 newer photos (img4, img3) were added
        val prompt = viewModel.uiState.value.reloadPrompt
        assertNotNull(prompt)
        assertEquals(2, prompt?.newCount)
        assertEquals(0, prompt?.firstNewIndex)
        assertEquals(2, prompt?.resumeIndex)
        assertEquals(4, prompt?.totalCount)
        assertEquals(2, viewModel.uiState.value.currentIndex)
        assertEquals(img2.uri, viewModel.uiState.value.images[viewModel.uiState.value.currentIndex].uri)

        // Choosing Start with Newest jumps to index 0 (img4)
        viewModel.confirmReloadJumpToLatest()
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertEquals(img4.uri, viewModel.uiState.value.images[viewModel.uiState.value.currentIndex].uri)
        assertNull(viewModel.uiState.value.reloadPrompt)
    }

    @Test
    fun `selectSourceFolder on a fresh folder starts on the newest photo without prompt`() = runTest {
        val viewModel = loadFolder(image("a.jpg", 10), image("c.jpg", 30), image("b.jpg", 20))

        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), viewModel.uiState.value.images.map { it.fileName })
        assertEquals(0, viewModel.uiState.value.currentIndex)
        assertNull(viewModel.uiState.value.reloadPrompt)
    }

    @Test
    fun `selectSourceFolder on previously opened folder with no new photos resumes silently`() = runTest {
        val img1 = image("photo1.jpg", 10)
        val img2 = image("photo2.jpg", 20)
        val img3 = image("photo3.jpg", 30)

        coEvery { settingsRepository.getFolderScanInfo("content://tree/photos") } returns FolderScanInfo(
            maxLastModified = 30L,
            fileCount = 3,
            lastViewedUri = img2.uri,
            lastViewedIndex = 1,
        )
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(img1, img2, img3))

        val viewModel = buildViewModel()
        viewModel.selectSourceFolder(Uri.parse("content://tree/photos"))
        advanceUntilIdle()

        // No dialog and no snackbar: the user is simply back at img2 (index 1).
        assertNull(viewModel.uiState.value.reloadPrompt)
        assertNull(viewModel.uiState.value.lastActionFeedback)
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(img2.uri, viewModel.uiState.value.images[1].uri)
        coVerify {
            settingsRepository.setFolderScanInfo(
                "content://tree/photos",
                match { it.lastViewedUri == img2.uri && it.lastViewedIndex == 1 },
            )
        }
    }

    @Test
    fun `onCleared finalizes pending delete on appScope`() = runTest {
        coEvery { imageRepository.deleteImage(any()) } returns true
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.requestDelete()
        assertNotNull(viewModel.uiState.value.pendingDelete)

        viewModel.onCleared()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingDelete)
        coVerify(exactly = 1) { imageRepository.deleteImage(Uri.parse("content://photos/b.jpg")) }
    }

    @Test
    fun `performLeftSwipeCopyOrMove routes to default LEFT_SWIPE subfolder when left folder uri is not set`() = runTest {
        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(
            leftSwipeAction = SwipeAction.COPY,
        )
        leftSwipeUriFlow.value = null
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } returns true

        val viewModel = loadFolder(image("a.jpg", 1))
        viewModel.performLeftSwipeCopyOrMove()
        advanceUntilIdle()

        coVerify {
            imageRepository.copyImage(
                sourceUri = Uri.parse("content://photos/a.jpg"),
                destFolderUri = Uri.parse("content://tree/photos"),
                sorting = true,
                subfolderName = com.phototok.domain.PhotoFolders.LEFT_SWIPE,
            )
        }
    }

    @Test
    fun `toggleExifOverlay updates settings repository`() = runTest {
        coEvery { settingsRepository.setPhoneShowExifOverlay(any()) } just Runs
        val viewModel = buildViewModel()

        viewModel.toggleExifOverlay()
        advanceUntilIdle()

        coVerify { settingsRepository.setPhoneShowExifOverlay(true) }
    }

    @Test
    fun `goBackToLanding finalizes delete and clears state`() = runTest {
        coEvery { imageRepository.deleteImage(any()) } returns true
        val viewModel = loadFolder(image("a.jpg", 1), image("b.jpg", 2))

        viewModel.requestDelete()
        viewModel.goBackToLanding()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.images.isEmpty())
        assertNull(viewModel.uiState.value.pendingDelete)
        coVerify(exactly = 1) { imageRepository.deleteImage(Uri.parse("content://photos/b.jpg")) }
    }

    @Test
    fun `dimension loading updates dimensions and regroups by orientation`() = runTest {
        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(
            sortByOrientation = true,
            randomizeOrder = false,
        )
        val img1 = ImageItem("content://photos/p.jpg", "p.jpg", 1, 100, "image/jpeg", 0, 0)
        val img2 = ImageItem("content://photos/l.jpg", "l.jpg", 1, 200, "image/jpeg", 0, 0)
        coEvery { imageRepository.getImageDimensions(Uri.parse(img1.uri)) } returns Pair(3000, 4000) // Portrait
        coEvery { imageRepository.getImageDimensions(Uri.parse(img2.uri)) } returns Pair(4000, 3000) // Landscape

        val viewModel = loadFolder(img1, img2)
        advanceUntilIdle()

        val images = viewModel.uiState.value.images
        assertEquals(2, images.size)
        // Landscape comes first when sortByOrientation is active
        assertEquals("l.jpg", images[0].fileName)
        assertEquals("p.jpg", images[1].fileName)
        assertEquals(4000, images[0].imageWidth)
        assertEquals(3000, images[0].imageHeight)
        assertEquals(1, viewModel.uiState.value.portraitSectionStart)
    }

    @Test
    fun `scrolling through feed does not finalize pending delete`() = runTest {
        val viewModel = loadFolder(image("a.jpg", 3), image("b.jpg", 2), image("c.jpg", 1))

        viewModel.requestDelete()
        assertNotNull(viewModel.uiState.value.pendingDelete)

        // Navigate to remaining photos
        viewModel.navigateToImage(1)
        advanceUntilIdle()

        // Pending delete must remain available across scrolls
        assertNotNull("pending delete must not be finalized by vertical scroll", viewModel.uiState.value.pendingDelete)
    }

    @Test
    fun `empty folder sets emptyFolderMessage and selecting new folder clears it`() = runTest {
        val viewModel = loadFolder()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.images.isEmpty())
        assertNotNull(state.emptyFolderMessage)
        assertTrue(state.emptyFolderMessage is UiText.Res && (state.emptyFolderMessage as UiText.Res).id == R.string.empty_folder)

        // Selecting a folder with photos clears the emptyFolderMessage
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(image("photo.jpg", 1)))
        viewModel.selectSourceFolder(Uri.parse("content://tree/new_photos"))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.emptyFolderMessage)
    }

    @Test
    fun `gesture tutorial fires when app was not used for 7 days`() = runTest {
        val now = System.currentTimeMillis()
        gestureTutorialTsFlow.value = now - 14 * 24 * 60 * 60 * 1000L // dismissed 2 weeks ago
        lastAppUsedTsFlow.value = now - 8 * 24 * 60 * 60 * 1000L // last used 8 days ago (> 7 days)

        val viewModel = loadFolder(image("a.jpg", 1))
        advanceUntilIdle()

        assertTrue("tutorial should fire after 7 days of inactivity", viewModel.uiState.value.showGestureTutorial)
    }

    @Test
    fun `gesture tutorial does not fire when app was used recently`() = runTest {
        val now = System.currentTimeMillis()
        gestureTutorialTsFlow.value = now - 14 * 24 * 60 * 60 * 1000L // dismissed 2 weeks ago
        lastAppUsedTsFlow.value = now - 2 * 24 * 60 * 60 * 1000L // last used 2 days ago (< 7 days)

        val viewModel = loadFolder(image("a.jpg", 1))
        advanceUntilIdle()

        assertFalse("tutorial should NOT fire for active user", viewModel.uiState.value.showGestureTutorial)
    }

    @Test
    fun `selectCameraFolder opens directly when permission exists`() = runTest {
        coEvery { imageRepository.prepareSourceFolder(match { it.toString().contains("DCIM") }) } returns "Camera"
        var pickerLaunched = false
        val viewModel = buildViewModel()

        viewModel.selectCameraFolder { pickerLaunched = true }
        advanceUntilIdle()

        assertFalse(pickerLaunched)
        assertTrue(viewModel.uiState.value.sourceFolderUri?.contains("DCIM") == true)
    }

    @Test
    fun `selectCameraFolder launches fallback picker when permission not yet granted`() = runTest {
        coEvery { imageRepository.prepareSourceFolder(match { it.toString().contains("DCIM") }) } returns null
        var pickerLaunched = false
        var launchedUri: Uri? = null
        val viewModel = buildViewModel()

        viewModel.selectCameraFolder { uri ->
            pickerLaunched = true
            launchedUri = uri
        }
        advanceUntilIdle()

        assertTrue(pickerLaunched)
        assertNotNull(launchedUri)
        assertTrue(launchedUri.toString().contains("DCIM"))
    }

    @Test
    fun `selectCameraFolder opens the picker on the camera folder as a document URI`() = runTest {
        coEvery { imageRepository.prepareSourceFolder(any()) } returns null
        var launchedUri: Uri? = null
        val viewModel = buildViewModel()

        viewModel.selectCameraFolder { launchedUri = it }
        advanceUntilIdle()

        // A bare tree URI is ignored as EXTRA_INITIAL_URI and the picker reopens its last location.
        val uri = requireNotNull(launchedUri)
        assertEquals("com.android.externalstorage.documents", uri.authority)
        assertEquals(listOf("document", "primary:DCIM/Camera"), uri.pathSegments)
    }

    @Test
    fun `selectCameraFolder checks the grant on the camera tree URI`() = runTest {
        val checked = mutableListOf<Uri>()
        coEvery { imageRepository.prepareSourceFolder(capture(checked)) } returns null
        val viewModel = buildViewModel()

        viewModel.selectCameraFolder { }
        advanceUntilIdle()

        assertEquals(listOf("tree", "primary:DCIM/Camera"), checked.single().pathSegments)
    }

    @Test
    fun `selectRemovableVolume opens the picker on the card's DCIM as a document URI`() = runTest {
        coEvery { imageRepository.prepareSourceFolder(any()) } returns null
        var launchedUri: Uri? = null
        val viewModel = buildViewModel()
        val card = ExternalVolume(
            description = "SD card",
            path = "/storage/1234-ABCD",
            isRemovable = true,
            isPrimary = false,
            uuid = "1234-ABCD",
        )

        viewModel.selectRemovableVolume(card) { launchedUri = it }
        advanceUntilIdle()

        assertEquals(listOf("document", "1234-ABCD:DCIM"), requireNotNull(launchedUri).pathSegments)
    }

    @Test
    fun `a partial move keeps the photo in the feed and says the original could not be removed`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        coEvery {
            imageRepository.moveImageDetailed(any(), any(), any(), any())
        } answers {
            FileOperationResult.partial(
                sourceUri = firstArg<Uri>().toString(),
                destinationUri = "content://tree/photos/Selection/a.jpg",
            )
        }
        val viewModel = loadFolder(image("a.jpg", 2), image("b.jpg", 1))

        viewModel.addToCollection()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a.jpg", "b.jpg"), state.images.map { it.fileName })
        assertEquals(
            UiText.of(R.string.error_move_partial, UiText.raw(PhotoFolders.SELECTION)),
            state.lastActionFeedback?.message,
        )
        assertTrue(state.lastActionFeedback?.isError == true)
    }

    @Test
    fun `a bundle move with one partial member restores only the member that stayed behind`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            collectionAction = CollectionAction.MOVE,
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ALL,
        )
        val jpg = image("IMG_001.jpg", 30)
        val arw = image("IMG_001.arw", 20)
        val other = image("IMG_002.jpg", 10)
        coEvery {
            imageRepository.moveImageDetailed(match { it.toString() == jpg.uri }, any(), any(), any())
        } returns FileOperationResult.success(jpg.uri, "content://tree/photos/Selection/IMG_001.jpg")
        coEvery {
            imageRepository.moveImageDetailed(match { it.toString() == arw.uri }, any(), any(), any())
        } returns FileOperationResult.partial(arw.uri, "content://tree/photos/Selection/IMG_001.arw")
        val viewModel = loadFolder(jpg, arw, other)

        viewModel.addToCollection()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("IMG_001.arw", "IMG_002.jpg"), state.images.map { it.fileName })
        assertEquals(listOf("IMG_001.arw", "IMG_002.jpg"), state.allImages.map { it.fileName })
        assertTrue(state.lastActionFeedback?.isError == true)
    }

    @Test
    fun `requestDelete with moveRelatedFiles removes sibling from visible feed under ALL filter and revert restores both`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ALL,
        )
        val jpg = image("IMG_001.jpg", 30)
        val arw = image("IMG_001.arw", 20)
        val other = image("IMG_002.jpg", 10)
        val viewModel = loadFolder(jpg, arw, other)

        assertEquals(listOf("IMG_001.jpg", "IMG_001.arw", "IMG_002.jpg"), viewModel.uiState.value.images.map { it.fileName })

        viewModel.requestDelete()

        assertEquals(listOf("IMG_002.jpg"), viewModel.uiState.value.images.map { it.fileName })
        assertEquals(listOf("IMG_002.jpg"), viewModel.uiState.value.allImages.map { it.fileName })
        assertEquals(0, viewModel.uiState.value.currentIndex)

        viewModel.revertDelete()

        assertEquals(listOf("IMG_001.jpg", "IMG_001.arw", "IMG_002.jpg"), viewModel.uiState.value.images.map { it.fileName })
        assertEquals(listOf("IMG_001.jpg", "IMG_001.arw", "IMG_002.jpg"), viewModel.uiState.value.allImages.map { it.fileName })
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    private fun shot(name: String, parent: String, vararg sidecarNames: String) = image(name, 10).copy(
        uri = "$parent/$name",
        parentUri = parent,
        sidecars = sidecarNames.map { image(it, 0).copy(uri = "$parent/$it", parentUri = parent, mimeType = null) },
    )

    @Test
    fun `a linked delete takes derivatives and sidecars, journals them and revert brings back only photos`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ALL,
        )
        val p = "content://tree/photos"
        val xmps = arrayOf("IMG_1.xmp", "IMG_1.CR3.xmp")
        val raw = shot("IMG_1.CR3", p, *xmps).copy(lastModified = 30)
        val edit = shot("IMG_1-Edit.tif", p, *xmps).copy(lastModified = 20)
        val other = shot("IMG_2.CR3", p).copy(lastModified = 10)
        coEvery { imageRepository.deleteImage(any()) } returns true
        val viewModel = loadFolder(raw, edit, other)

        viewModel.requestDelete()

        assertEquals(listOf("IMG_2.CR3"), viewModel.uiState.value.images.map { it.fileName })
        val journaled = slot<Set<String>>()
        coVerify { settingsRepository.setPersistedPendingDeleteUris(capture(journaled)) }
        assertEquals(
            setOf("IMG_1.CR3", "IMG_1-Edit.tif", "IMG_1.xmp", "IMG_1.CR3.xmp").map { "$p/$it" }.toSet(),
            journaled.captured,
        )

        viewModel.revertDelete()

        val state = viewModel.uiState.value
        assertEquals(listOf("IMG_1.CR3", "IMG_1-Edit.tif", "IMG_2.CR3"), state.images.map { it.fileName })
        assertEquals(listOf("IMG_1.CR3", "IMG_1-Edit.tif", "IMG_2.CR3"), state.allImages.map { it.fileName })
        coVerify(exactly = 0) { imageRepository.deleteImage(any()) }

        viewModel.requestDelete()
        viewModel.finalizePendingDelete()
        advanceUntilIdle()

        for (name in listOf("IMG_1.CR3", "IMG_1-Edit.tif", "IMG_1.xmp", "IMG_1.CR3.xmp")) {
            coVerify(exactly = 1) { imageRepository.deleteImage(Uri.parse("$p/$name")) }
        }
        coVerify(exactly = 0) { imageRepository.deleteImage(Uri.parse("$p/IMG_2.CR3")) }
    }

    @Test
    fun `a linked move takes the sidecars too and sidecars never enter the feed`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            collectionAction = CollectionAction.MOVE,
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ALL,
        )
        val p = "content://tree/photos"
        val raw = shot("IMG_1.CR3", p, "IMG_1.xmp")
        val jpg = shot("IMG_1.JPG", p, "IMG_1.xmp")
        val other = shot("IMG_2.CR3", p)
        coEvery { imageRepository.moveImageDetailed(any(), any(), any(), any()) } coAnswers {
            FileOperationResult.success(firstArg<Uri>().toString(), "content://tree/photos/Selection/x")
        }
        val viewModel = loadFolder(raw, jpg, other)

        viewModel.addToCollection()
        advanceUntilIdle()

        assertEquals(listOf("IMG_2.CR3"), viewModel.uiState.value.images.map { it.fileName })
        assertTrue(viewModel.uiState.value.allImages.none { it.fileName.endsWith(".xmp") })
        for (name in listOf("IMG_1.CR3", "IMG_1.JPG", "IMG_1.xmp")) {
            coVerify(exactly = 1) { imageRepository.moveImageDetailed(Uri.parse("$p/$name"), any(), any(), any()) }
        }
        coVerify(exactly = 0) { imageRepository.moveImageDetailed(Uri.parse("$p/IMG_2.CR3"), any(), any(), any()) }
    }

    @Test
    fun `a multi-file shot moves through the bundle seam and a single file per file`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            collectionAction = CollectionAction.MOVE,
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ALL,
        )
        val p = "content://tree/photos"
        val raw = shot("IMG_1.CR3", p, "IMG_1.xmp").copy(lastModified = 30)
        val lone = shot("IMG_2.CR3", p).copy(lastModified = 10)
        coEvery { imageRepository.moveImageDetailed(any(), any(), any(), any()) } coAnswers {
            FileOperationResult.success(firstArg<Uri>().toString(), "content://tree/photos/Selection/x")
        }
        coEvery { imageRepository.transferBundleDetailed(any(), any(), any(), any(), any()) } coAnswers {
            firstArg<List<Uri>>().map { FileOperationResult.success(it.toString(), "content://tree/photos/Selection/x") }
        }
        val viewModel = loadFolder(raw, lone)

        viewModel.addToCollection()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            imageRepository.transferBundleDetailed(
                listOf(Uri.parse("$p/IMG_1.CR3"), Uri.parse("$p/IMG_1.xmp")), any(), any(), any(), true,
            )
        }
        coVerify(exactly = 0) { imageRepository.moveImageDetailed(Uri.parse("$p/IMG_1.CR3"), any(), any(), any()) }

        viewModel.addToCollection()
        advanceUntilIdle()

        coVerify(exactly = 1) { imageRepository.moveImageDetailed(Uri.parse("$p/IMG_2.CR3"), any(), any(), any()) }
        coVerify(exactly = 1) { imageRepository.transferBundleDetailed(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a partial bundle result puts back only the member still in the source`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            collectionAction = CollectionAction.MOVE,
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ALL,
        )
        val p = "content://tree/photos"
        val raw = shot("IMG_1.CR3", p, "IMG_1.xmp").copy(lastModified = 30)
        val jpg = shot("IMG_1.JPG", p, "IMG_1.xmp").copy(lastModified = 20)
        val other = shot("IMG_2.CR3", p).copy(lastModified = 10)
        coEvery { imageRepository.transferBundleDetailed(any(), any(), any(), any(), any()) } returns listOf(
            FileOperationResult.success(raw.uri, "d1"),
            FileOperationResult.partial(jpg.uri, "d2"),
            FileOperationResult.success("$p/IMG_1.xmp", "d3"),
        )
        val viewModel = loadFolder(raw, jpg, other)

        viewModel.addToCollection()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("IMG_1.JPG", "IMG_2.CR3"), state.images.map { it.fileName })
        assertTrue(state.allImages.none { it.fileName.endsWith(".xmp") })
        assertTrue(state.lastActionFeedback?.isError == true)
    }

    @Test
    fun `a linked delete never reaches into a sibling folder with the same stem`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ALL,
        )
        val a = shot("IMG_1.CR3", "content://tree/photos/a", "IMG_1.xmp").copy(lastModified = 20)
        val b = shot("IMG_1.CR3", "content://tree/photos/b", "IMG_1.xmp").copy(lastModified = 10)
        coEvery { imageRepository.deleteImage(any()) } returns true
        val viewModel = loadFolder(a, b)

        viewModel.requestDelete()
        viewModel.finalizePendingDelete()
        advanceUntilIdle()

        assertEquals(listOf(b.uri), viewModel.uiState.value.images.map { it.uri })
        coVerify(exactly = 1) { imageRepository.deleteImage(Uri.parse(a.uri)) }
        coVerify(exactly = 1) { imageRepository.deleteImage(Uri.parse("content://tree/photos/a/IMG_1.xmp")) }
        coVerify(exactly = 0) { imageRepository.deleteImage(Uri.parse(b.uri)) }
        coVerify(exactly = 0) { imageRepository.deleteImage(Uri.parse("content://tree/photos/b/IMG_1.xmp")) }
    }

    @Test
    fun `finalizePendingDelete restores photo and reports error when deleteImage returns false`() = runTest {
        coEvery { imageRepository.deleteImage(any()) } returns false
        val viewModel = loadFolder(image("a.jpg", 20), image("b.jpg", 10))

        viewModel.requestDelete()
        assertEquals(listOf("b.jpg"), viewModel.uiState.value.images.map { it.fileName })

        viewModel.finalizePendingDelete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a.jpg", "b.jpg"), state.images.map { it.fileName })
        assertEquals(listOf("a.jpg", "b.jpg"), state.allImages.map { it.fileName })
        assertTrue(state.lastActionFeedback?.isError == true)
        assertEquals(UiText.of(R.string.error_delete_one, "a.jpg"), state.lastActionFeedback?.message)
    }

    @Test
    fun `finalizePendingDelete restores photo and reports cause when deleteImage throws`() = runTest {
        coEvery { imageRepository.deleteImage(any()) } throws SecurityException("read-only SD card")
        val viewModel = loadFolder(image("a.jpg", 20), image("b.jpg", 10))

        viewModel.requestDelete()
        viewModel.finalizePendingDelete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a.jpg", "b.jpg"), state.images.map { it.fileName })
        assertTrue(state.lastActionFeedback?.isError == true)
        assertEquals(UiText.of(R.string.error_delete_one, "a.jpg"), state.lastActionFeedback?.message)
    }

    @Test
    fun `finalizePendingDelete on partial linked delete failure restores only failed sibling and reports partial error`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ALL,
        )
        val jpg = image("IMG_001.jpg", 30)
        val arw = image("IMG_001.arw", 20)
        val other = image("IMG_002.jpg", 10)
        coEvery { imageRepository.deleteImage(Uri.parse(jpg.uri)) } returns true
        coEvery { imageRepository.deleteImage(Uri.parse(arw.uri)) } returns false

        val viewModel = loadFolder(jpg, arw, other)
        viewModel.requestDelete()
        viewModel.finalizePendingDelete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("IMG_001.arw", "IMG_002.jpg"), state.images.map { it.fileName })
        assertEquals(listOf("IMG_001.arw", "IMG_002.jpg"), state.allImages.map { it.fileName })
        assertTrue(state.lastActionFeedback?.isError == true)
        assertEquals(UiText.of(R.string.error_delete_some, 1, 2), state.lastActionFeedback?.message)
    }

    @Test
    fun `a failed delete commit after the folder changed reports the error and leaves the new feed alone`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        coEvery { imageRepository.deleteImage(any()) } coAnswers { gate.await() }
        val viewModel = loadFolder(image("a.jpg", 20), image("b.jpg", 10))

        viewModel.requestDelete()
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(image("x.jpg", 5)))
        viewModel.selectSourceFolder(Uri.parse("content://tree/other"))
        advanceUntilIdle()

        gate.complete(false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("x.jpg"), state.images.map { it.fileName })
        assertEquals(listOf("x.jpg"), state.allImages.map { it.fileName })
        assertTrue(state.lastActionFeedback?.isError == true)
        assertEquals(UiText.of(R.string.error_delete_one, "a.jpg"), state.lastActionFeedback?.message)
    }

    @Test
    fun `a move that fails after the folder switched does not put the photo into the new feed`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        val gate = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImageDetailed(any(), any(), any(), any()) } coAnswers { gate.await() }
        val viewModel = loadFolder(image("a.jpg", 20), image("b.jpg", 10))

        viewModel.addToCollection()
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(image("x.jpg", 5)))
        viewModel.selectSourceFolder(Uri.parse("content://tree/other"))
        advanceUntilIdle()

        gate.complete(FileOperationResult.failure("content://photos/a.jpg", null))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("x.jpg"), state.images.map { it.fileName })
        assertEquals(listOf("x.jpg"), state.allImages.map { it.fileName })
        assertTrue("the outcome of the user's action is still reported", state.lastActionFeedback?.isError == true)
    }

    @Test
    fun `a move that throws after the folder switched does not put the photo into the new feed`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        val gate = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImageDetailed(any(), any(), any(), any()) } coAnswers {
            gate.await()
            throw IllegalStateException("boom")
        }
        val viewModel = loadFolder(image("a.jpg", 20), image("b.jpg", 10))

        viewModel.addToCollection()
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(image("x.jpg", 5)))
        viewModel.selectSourceFolder(Uri.parse("content://tree/other"))
        advanceUntilIdle()

        gate.complete(FileOperationResult.failure("content://photos/a.jpg", null))
        advanceUntilIdle()

        assertEquals(listOf("x.jpg"), viewModel.uiState.value.images.map { it.fileName })
        assertTrue(viewModel.uiState.value.lastActionFeedback?.isError == true)
    }

    @Test
    fun `a move that fails after the card was unmounted does not repopulate the feed`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        val gate = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImageDetailed(any(), any(), any(), any()) } coAnswers { gate.await() }
        val monitor = com.phototok.data.source.VolumeMonitor()
        val viewModel = PhoneModeViewModel(
            imageRepository = imageRepository,
            settingsRepository = settingsRepository,
            appScope = CoroutineScope(testDispatcher),
            seenRepository = com.phototok.data.repository.SeenRepository(),
            favouritesRepository = com.phototok.data.repository.FavouritesRepository(),
            statsRepository = com.phototok.data.repository.StatsRepository(),
            volumeMonitor = monitor,
        )
        every { imageRepository.discoverImages(any()) } returns
            flowOf(listOf(image("a.jpg", 20), image("b.jpg", 10)))
        viewModel.selectSourceFolder(Uri.parse("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM"))
        assertEquals(2, viewModel.uiState.value.images.size)

        viewModel.addToCollection()
        monitor.notifyVolumeUnmounted("1A2B-3C4D")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.images.isEmpty())

        gate.complete(FileOperationResult.failure("content://photos/a.jpg", null))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.images.isEmpty())
        assertTrue(viewModel.uiState.value.allImages.isEmpty())
    }

    @Test
    fun `a move that fails in the same folder still puts the photo back after a late result`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        val gate = CompletableDeferred<FileOperationResult>()
        coEvery { imageRepository.moveImageDetailed(any(), any(), any(), any()) } coAnswers { gate.await() }
        val viewModel = loadFolder(image("a.jpg", 20), image("b.jpg", 10))

        viewModel.addToCollection()
        assertEquals(listOf("b.jpg"), viewModel.uiState.value.images.map { it.fileName })

        gate.complete(FileOperationResult.failure("content://photos/a.jpg", null))
        advanceUntilIdle()

        assertEquals(listOf("a.jpg", "b.jpg"), viewModel.uiState.value.images.map { it.fileName })
    }

    @Test
    fun `a card clear finishing after the folder switched leaves the new feed alone but the deletions stand`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        coEvery { imageRepository.deleteImage(any()) } coAnswers { gate.await() }
        val viewModel = loadFolder(image("a.jpg", 20), image("b.jpg", 10))

        viewModel.confirmClearEntireCard()
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(image("x.jpg", 5)))
        viewModel.selectSourceFolder(Uri.parse("content://tree/other"))
        advanceUntilIdle()

        gate.complete(true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("x.jpg"), state.images.map { it.fileName })
        assertEquals(listOf("x.jpg"), state.allImages.map { it.fileName })
        assertFalse(state.cardClearComplete)
        assertNull(state.emptyFolderMessage)
        assertEquals(0, state.cardClearStep)
        coVerify(exactly = 1) { imageRepository.deleteImage(match { it.toString() == "content://photos/a.jpg" }) }
        coVerify(exactly = 1) { imageRepository.deleteImage(match { it.toString() == "content://photos/b.jpg" }) }
    }

    @Test
    fun `a card clear finishing after an unmount does not mark the card as cleared`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        coEvery { imageRepository.deleteImage(any()) } coAnswers { gate.await() }
        val monitor = com.phototok.data.source.VolumeMonitor()
        val viewModel = PhoneModeViewModel(
            imageRepository = imageRepository,
            settingsRepository = settingsRepository,
            appScope = CoroutineScope(testDispatcher),
            seenRepository = com.phototok.data.repository.SeenRepository(),
            favouritesRepository = com.phototok.data.repository.FavouritesRepository(),
            statsRepository = com.phototok.data.repository.StatsRepository(),
            volumeMonitor = monitor,
        )
        every { imageRepository.discoverImages(any()) } returns flowOf(listOf(image("a.jpg", 20)))
        viewModel.selectSourceFolder(Uri.parse("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM"))

        viewModel.confirmClearEntireCard()
        monitor.notifyVolumeUnmounted("1A2B-3C4D")
        advanceUntilIdle()
        gate.complete(true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.cardClearComplete)
        assertNull(state.emptyFolderMessage)
        assertEquals(0, state.cardClearStep)
        coVerify(exactly = 1) { imageRepository.deleteImage(any()) }
    }

    @Test
    fun `a move that had to add a suffix tells the user which name the file got`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        coEvery {
            imageRepository.moveImageDetailed(any(), any(), any(), any())
        } answers {
            FileOperationResult.success(
                firstArg<Uri>().toString(),
                "content://tree/photos/Selection/a-2.jpg",
            )
        }
        val viewModel = loadFolder(image("a.jpg", 2), image("b.jpg", 1))

        viewModel.addToCollection()
        advanceUntilIdle()

        assertEquals(UiText.of(R.string.snackbar_moved_renamed, "a-2.jpg"), viewModel.uiState.value.lastActionFeedback?.message)
    }

    @Test
    fun `a move that kept its name keeps the plain message`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(collectionAction = CollectionAction.MOVE)
        coEvery {
            imageRepository.moveImageDetailed(any(), any(), any(), any())
        } answers {
            FileOperationResult.success(
                firstArg<Uri>().toString(),
                "content://tree/photos/Selection/a.jpg",
            )
        }
        val viewModel = loadFolder(image("a.jpg", 2), image("b.jpg", 1))

        viewModel.addToCollection()
        advanceUntilIdle()

        assertEquals(UiText.of(R.string.snackbar_moved, UiText.raw(PhotoFolders.SELECTION)), viewModel.uiState.value.lastActionFeedback?.message)
    }
}
