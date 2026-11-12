package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.domain.CollectionAction
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.SwipeAction
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val imageRepository: ImageRepository = mockk(relaxed = true)

    // Flow mocks
    private val phoneSettingsFlow = MutableStateFlow(
        PhoneSettings(
            collectionAction = CollectionAction.COPY,
            directDeleteConfirmEnabled = true,
            sortByOrientation = false,
            randomizeOrder = false,
            fileTypeFilter = FileTypeFilter.ALL,
            leftSwipeAction = SwipeAction.DELETE,
            showExifOverlay = false,
            moveRelatedFiles = false,
            recentPathsEnabled = true,
            recentPathsCount = 3,
        )
    )
    private val selectionFolderNameFlow = MutableStateFlow("Selection")
    private val sortingEnabledFlow = MutableStateFlow(true)
    private val phoneCollectionUriFlow = MutableStateFlow<String?>(null)
    private val phoneLeftSwipeUriFlow = MutableStateFlow<String?>(null)
    private val lastFolderUriFlow = MutableStateFlow<String?>(null)

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock settings flows (one typed flow for the simple settings)
        every { settingsRepository.phoneSettings } returns phoneSettingsFlow
        every { settingsRepository.selectionFolderName } returns selectionFolderNameFlow
        every { settingsRepository.sortingEnabled } returns sortingEnabledFlow
        every { settingsRepository.phoneCollectionUri } returns phoneCollectionUriFlow
        every { settingsRepository.phoneLeftSwipeUri } returns phoneLeftSwipeUriFlow
        every { settingsRepository.lastFolderUri } returns lastFolderUriFlow

        viewModel = SettingsViewModel(settingsRepository, imageRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects settings repository defaults`() = runTest {
        val state = viewModel.uiState.value

        assertEquals("Selection", state.selectionFolderName)
        assertTrue(state.sortingEnabled)
        assertEquals(CollectionAction.COPY, state.collectionAction)
        assertTrue(state.directDeleteConfirmEnabled)
        assertFalse(state.sortByOrientation)
        assertFalse(state.randomizeOrder)
        assertEquals(FileTypeFilter.ALL, state.fileTypeFilter)
        assertFalse(state.showExifOverlay)
        assertFalse(state.moveRelatedFiles)
        assertTrue(state.recentPathsEnabled)
        assertEquals(3, state.recentPathsCount)
        assertEquals(SwipeAction.DELETE, state.leftSwipeAction)
        assertEquals(null, state.leftSwipeUri)
    }

    @Test
    fun `updating settings calls repository`() = runTest {
        coEvery { settingsRepository.setPhoneMoveRelatedFiles(any()) } just Runs
        coEvery { settingsRepository.setPhoneRecentPathsEnabled(any()) } just Runs
        coEvery { settingsRepository.setPhoneRecentPathsCount(any()) } just Runs
        coEvery { settingsRepository.setSourceDirectDeleteConfirmEnabled(any(), any()) } just Runs

        viewModel.updateMoveRelatedFiles(true)
        coVerify { settingsRepository.setPhoneMoveRelatedFiles(true) }

        viewModel.updateRecentPathsEnabled(false)
        coVerify { settingsRepository.setPhoneRecentPathsEnabled(false) }

        viewModel.updateRecentPathsCount(5)
        coVerify { settingsRepository.setPhoneRecentPathsCount(5) }

        viewModel.updateDirectDeleteConfirm(false)
        coVerify { settingsRepository.setSourceDirectDeleteConfirmEnabled(SourceKind.PHONE_LIBRARY, false) }

        viewModel.updateLeftSwipeAction(SwipeAction.COPY)
        coVerify { settingsRepository.setPhoneLeftSwipeAction(SwipeAction.COPY) }

        viewModel.updateLeftSwipeUri("content://left")
        coVerify { settingsRepository.setPhoneLeftSwipeUri("content://left") }

        viewModel.updateSortingEnabled(false)
        coVerify { settingsRepository.setSortingEnabled(false) }

        viewModel.updateCollectionAction(CollectionAction.MOVE)
        coVerify { settingsRepository.setPhoneCollectionAction(CollectionAction.MOVE) }

        viewModel.updateSortByOrientation(true)
        coVerify { settingsRepository.setPhoneSortByOrientation(true) }

        viewModel.updateRandomizeOrder(true)
        coVerify { settingsRepository.setPhoneRandomizeOrder(true) }

        viewModel.updateCollectionUri("content://coll")
        coVerify { settingsRepository.setPhoneCollectionUri("content://coll") }

        viewModel.updateFileTypeFilter(FileTypeFilter.RAW)
        coVerify { settingsRepository.setPhoneFileTypeFilter(FileTypeFilter.RAW) }

        viewModel.updateShowExifOverlay(true)
        coVerify { settingsRepository.setPhoneShowExifOverlay(true) }
    }

    @Test
    fun `updateSelectionFolder trims whitespace and ignores empty input`() = runTest {
        coEvery { settingsRepository.setSelectionFolderName(any()) } just Runs

        viewModel.updateSelectionFolder("  CustomFolder  ")
        coVerify(exactly = 1) { settingsRepository.setSelectionFolderName("CustomFolder") }

        viewModel.updateSelectionFolder("   ")
        coVerify(exactly = 1) { settingsRepository.setSelectionFolderName(any()) }

        viewModel.updateSelectionFolder("")
        coVerify(exactly = 1) { settingsRepository.setSelectionFolderName(any()) }
    }

    @Test
    fun `resetTutorials calls repository and invokes onDone callback`() = runTest {
        coEvery { settingsRepository.resetFirstRunHints() } just Runs
        var callbackInvoked = false

        viewModel.resetTutorials {
            callbackInvoked = true
        }

        coVerify(exactly = 1) { settingsRepository.resetFirstRunHints() }
        assertTrue(callbackInvoked)
    }

    @Test
    fun `clearCache calls repository and invokes onDone callback`() = runTest {
        coEvery { settingsRepository.clearCache() } just Runs
        var callbackInvoked = false

        viewModel.clearCache {
            callbackInvoked = true
        }

        coVerify(exactly = 1) { settingsRepository.clearCache() }
        assertTrue(callbackInvoked)
    }

    @Test
    fun `state updates when selectionFolderName and sortingEnabled flows emit`() = runTest {
        assertEquals("Selection", viewModel.uiState.value.selectionFolderName)
        assertTrue(viewModel.uiState.value.sortingEnabled)

        selectionFolderNameFlow.value = "NewFolder"
        sortingEnabledFlow.value = false

        assertEquals("NewFolder", viewModel.uiState.value.selectionFolderName)
        assertFalse(viewModel.uiState.value.sortingEnabled)
    }

    @Test
    fun `state updates when collectionUri and lastFolderUri emit`() = runTest {
        coEvery { imageRepository.resolveFolderName(any()) } returns "Vacation2026"

        phoneCollectionUriFlow.value = "content://collection"
        lastFolderUriFlow.value = "content://photos"

        assertEquals("content://collection", viewModel.uiState.value.collectionUri)
        assertEquals("content://photos", viewModel.uiState.value.sourceFolderUri)
        assertEquals("Vacation2026", viewModel.uiState.value.sourceFolderName)
    }

    @Test
    fun `state updates when the typed settings flow emits new values`() = runTest {
        assertFalse(viewModel.uiState.value.moveRelatedFiles)

        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(moveRelatedFiles = true)

        assertTrue(viewModel.uiState.value.moveRelatedFiles)
    }

    @Test
    fun `state updates when the direct delete confirmation changes`() = runTest {
        assertTrue(viewModel.uiState.value.directDeleteConfirmEnabled)

        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(
            directDeleteConfirmEnabled = false,
        )

        assertFalse(viewModel.uiState.value.directDeleteConfirmEnabled)
    }

    @Test
    fun `state updates when left swipe settings change`() = runTest {
        assertEquals(SwipeAction.DELETE, viewModel.uiState.value.leftSwipeAction)
        assertEquals(null, viewModel.uiState.value.leftSwipeUri)

        phoneSettingsFlow.value = phoneSettingsFlow.value.copy(leftSwipeAction = SwipeAction.MOVE)
        phoneLeftSwipeUriFlow.value = "content://left"

        assertEquals(SwipeAction.MOVE, viewModel.uiState.value.leftSwipeAction)
        assertEquals("content://left", viewModel.uiState.value.leftSwipeUri)
    }

    @Test
    fun `clearCache delegates to settingsRepository and invokes callback`() = runTest {
        coEvery { settingsRepository.clearCache() } just Runs
        var called = false

        viewModel.clearCache {
            called = true
        }
        advanceUntilIdle()

        coVerify { settingsRepository.clearCache() }
        assertTrue(called)
    }

    /** JP-UI-2: clearing a card's custom folder used to keep showing the old one ("Reset" did nothing visible). */
    @Test
    fun `clearing a source's custom folders shows Default again, not the previous folders`() = runTest {
        val removableFlow = MutableStateFlow(
            SourceSettings.defaultFor(SourceKind.REMOVABLE).copy(collectionUri = CARD_KEEPERS, leftSwipeUri = CARD_REJECTS),
        )
        every { settingsRepository.sourceSettingsFlow(SourceKind.REMOVABLE) } returns removableFlow
        viewModel = SettingsViewModel(settingsRepository, imageRepository)
        viewModel.selectSourceKindTab(SourceKind.REMOVABLE)
        assertEquals(CARD_KEEPERS, viewModel.uiState.value.collectionUri)
        assertEquals(CARD_REJECTS, viewModel.uiState.value.leftSwipeUri)

        removableFlow.value = removableFlow.value.copy(collectionUri = null, leftSwipeUri = null)
        advanceUntilIdle()

        assertNull("Selection location cleared", viewModel.uiState.value.collectionUri)
        assertNull("Left swipe folder cleared", viewModel.uiState.value.leftSwipeUri)
    }

    @Test
    fun `a custom folder's provider name is resolved for its path, and follows the tab`() = runTest {
        val cloud = "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3Dxyz"
        coEvery { imageRepository.resolveFolderName(Uri.parse(cloud)) } returns "Keepers"
        coEvery { imageRepository.resolveFolderName(Uri.parse(CARD_REJECTS)) } returns "Rejects"
        every { settingsRepository.sourceSettingsFlow(SourceKind.REMOVABLE) } returns MutableStateFlow(
            SourceSettings.defaultFor(SourceKind.REMOVABLE).copy(collectionUri = cloud, leftSwipeUri = CARD_REJECTS),
        )
        viewModel = SettingsViewModel(settingsRepository, imageRepository)

        viewModel.selectSourceKindTab(SourceKind.REMOVABLE)
        advanceUntilIdle()
        assertEquals("Keepers", viewModel.uiState.value.collectionFolderName)
        assertEquals("Rejects", viewModel.uiState.value.leftSwipeFolderName)

        viewModel.selectSourceKindTab(SourceKind.CAMERA_MTP)
        advanceUntilIdle()
        assertNull("no name of another tab's folder", viewModel.uiState.value.collectionFolderName)
        assertNull(viewModel.uiState.value.leftSwipeFolderName)
    }

    private companion object {
        const val CARD_KEEPERS = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AKeepers"
        const val CARD_REJECTS = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ARejects"
    }
}
