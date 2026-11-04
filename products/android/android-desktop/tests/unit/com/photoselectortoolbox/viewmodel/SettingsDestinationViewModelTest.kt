package com.photoselectortoolbox.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.model.FolderReachability
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.curation.DestinationPreview
import com.photoselectortoolbox.domain.curation.DestinationProblem
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

/**
 * "Where photos go" in Settings, through [SettingsViewModel] (`[USER 2026-11-04]` "There
 * should be a selection: Custom Path or Working folder with the full transparency what
 * that folder is currently").
 */
@Requirement("REQ-AND-WORKFLOW.02", "REQ-AND-PLATFORM.06")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDestinationViewModelTest {

    private val trip = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTrip"
    private val keepers = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FKeepers"
    private val tripPath = listOf("Internal storage", "DCIM", "Trip")
    private val keepersPath = listOf("Internal storage", "Pictures", "Keepers")

    private val testDispatcher = UnconfinedTestDispatcher()

    // The fresh-install values, read from production code.
    private val customFlow = MutableStateFlow<String?>(null)
    private val lastFolderFlow = MutableStateFlow<String?>(null)
    private val selectionNameFlow = MutableStateFlow(SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME)
    private val secondNameFlow = MutableStateFlow(SettingsRepository.DEFAULT_SECONDARY_SELECTION_FOLDER_NAME)
    private val sortingFlow = MutableStateFlow(SettingsRepository.DEFAULT_SORTING_ENABLED)

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectionFolderName } returns selectionNameFlow
        every { secondarySelectionFolderName } returns secondNameFlow
        every { sortingEnabled } returns sortingFlow
        every { customSelectionDestinationUri } returns customFlow
        every { lastFolderUri } returns lastFolderFlow
        every { groupingEnabled } returns MutableStateFlow(false)
        every { groupingLevel } returns MutableStateFlow(GroupingLevel.TIME_FILENAME)
        every { analysisThreadCount } returns MutableStateFlow(4)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { filingAction } returns MutableStateFlow(FilingAction.DEFAULT)
        every { tvDisplayScaleEnabled } returns MutableStateFlow(false)
        coEvery { setCustomSelectionDestinationUri(any()) } answers { customFlow.value = firstArg() }
    }

    /** What the storage layer says about each folder; a test changes it to model a lost grant. */
    private val folders = mutableMapOf(
        trip to reachable(trip, tripPath),
        keepers to reachable(keepers, keepersPath),
    )

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { describeFolder(any(), any()) } answers {
            val uri = secondArg<Uri>().toString()
            folders[uri] ?: FolderDescription(uri = uri, displayPath = listOf("Folder"))
        }
    }

    private val viewModels = mutableListOf<SettingsViewModel>()

    private fun reachable(uri: String, path: List<String>, marker: Boolean = false) = FolderDescription(
        uri = uri,
        displayPath = path,
        name = path.last(),
        hasPersistedGrant = true,
        exists = true,
        canWrite = true,
        hasSelectionMarker = marker,
    )

    private fun viewModel(): SettingsViewModel = SettingsViewModel(
        settingsRepository = settingsRepository,
        cacheRepository = mockk<CacheRepository>(relaxed = true),
        imageRepository = imageRepository,
        context = ApplicationProvider.getApplicationContext(),
    ).also { viewModels += it }

    /** Poll the state for [predicate] within a time budget, then return the destination. */
    private suspend fun SettingsViewModel.awaitDestination(predicate: (DestinationUiState) -> Boolean): DestinationUiState =
        withTimeout(5_000) { uiState.first { predicate(it.destination) } }.destination

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `a fresh install with no folder open is the working folder, no path`() = runTest {
        val destination = viewModel().awaitDestination { true }

        assertEquals(DestinationMode.WORKING_FOLDER, destination.mode)
        assertEquals(DestinationPreview.DEFAULT, destination.preview)
        assertNull(destination.preview.segments)
        assertNull(destination.openedFolder)
        assertNull(destination.customFolder)
    }

    @Test
    fun `with a folder open the preview is that folder's Selection, sorted`() = runTest {
        lastFolderFlow.value = trip

        val destination = viewModel().awaitDestination { it.openedFolder != null }

        assertEquals(tripPath, destination.openedFolder?.displayPath)
        assertEquals(tripPath + "Selection", destination.preview.segments)
        assertTrue(destination.preview.sortsIntoRawAndJpeg)
    }

    @Test
    fun `renaming the folder, turning sorting off and opening a Selection all show at once`() = runTest {
        lastFolderFlow.value = trip
        val viewModel = viewModel()

        selectionNameFlow.value = "Picks"
        sortingFlow.value = false
        val renamed = viewModel.awaitDestination { it.preview.folderName == "Picks" && !it.preview.sortsIntoRawAndJpeg }
        assertEquals(tripPath + "Picks", renamed.preview.segments)

        val picks = "$trip%2FPicks"
        folders[picks] = reachable(picks, tripPath + "Picks", marker = true)
        lastFolderFlow.value = picks
        val secondPass = viewModel.awaitDestination { it.preview.isSecondPass }
        assertEquals(tripPath + "Picks" + "Final", secondPass.preview.segments)
    }

    @Test
    fun `an upgrade with a custom folder reads as Custom with its path`() = runTest {
        customFlow.value = keepers
        lastFolderFlow.value = trip

        val destination = viewModel().awaitDestination { it.customFolder != null }

        assertEquals(DestinationMode.CUSTOM, destination.mode)
        assertEquals(keepersPath, destination.customFolder?.displayPath)
        assertEquals(keepersPath + "Selection", destination.preview.segments)
        assertFalse(destination.customFolderUnreachable)
    }

    @Test
    fun `choosing a custom folder persists it with its grant and describes it again`() = runTest {
        lastFolderFlow.value = trip
        // Until the grant is taken the folder reads as unreachable; the setter takes it.
        folders[keepers] = folders.getValue(keepers).copy(hasPersistedGrant = false)
        coEvery { settingsRepository.setCustomSelectionDestinationUri(keepers) } answers {
            customFlow.value = keepers
            folders[keepers] = reachable(keepers, keepersPath)
        }
        val viewModel = viewModel()

        viewModel.chooseCustomFolder(keepers)

        val destination = viewModel.awaitDestination { it.mode == DestinationMode.CUSTOM && it.customFolder?.isReachable == true }
        coVerify { settingsRepository.setCustomSelectionDestinationUri(keepers) }
        assertEquals(keepersPath + "Selection", destination.preview.segments)
        assertNull(destination.preview.problem)
    }

    @Test
    fun `a custom folder that lost its permission is a warning, not a destination`() = runTest {
        customFlow.value = keepers
        folders[keepers] = folders.getValue(keepers).copy(hasPersistedGrant = false)

        val destination = viewModel().awaitDestination { it.customFolder != null }

        assertTrue(destination.customFolderUnreachable)
        assertEquals(FolderReachability.NO_PERMISSION, destination.customFolder?.reachability)
        assertEquals(DestinationProblem.CUSTOM_FOLDER_UNREACHABLE, destination.preview.problem)
    }

    @Test
    fun `refresh notices a folder that came back`() = runTest {
        customFlow.value = keepers
        folders[keepers] = folders.getValue(keepers).copy(exists = false)
        val viewModel = viewModel()
        viewModel.awaitDestination { it.customFolderUnreachable }

        folders[keepers] = reachable(keepers, keepersPath)
        viewModel.refreshDestination()

        val destination = viewModel.awaitDestination { it.customFolder?.isReachable == true }
        assertFalse(destination.customFolderUnreachable)
    }

    @Test
    fun `use working folder clears the custom folder and shows the opened folder at once`() = runTest {
        customFlow.value = keepers
        lastFolderFlow.value = trip
        val viewModel = viewModel()
        viewModel.awaitDestination { it.mode == DestinationMode.CUSTOM }

        viewModel.useWorkingFolder()

        val destination = viewModel.awaitDestination { it.mode == DestinationMode.WORKING_FOLDER }
        coVerify { settingsRepository.setCustomSelectionDestinationUri(null) }
        assertNull(destination.customFolder)
        assertEquals(tripPath + "Selection", destination.preview.segments)
    }

    @Test
    fun `a storage failure while describing reads as unreachable, never as valid`() = runTest {
        customFlow.value = keepers
        coEvery { imageRepository.describeFolder(any(), any()) } throws SecurityException("revoked")

        val destination = viewModel().awaitDestination { it.customFolder != null }

        assertTrue(destination.customFolderUnreachable)
        assertEquals(DestinationProblem.CUSTOM_FOLDER_UNREACHABLE, destination.preview.problem)
        assertEquals(keepersPath + "Selection", destination.preview.segments)
    }
}
