package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.source.FileOperationResult
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.PhotoFolders
import com.phototok.domain.ProModePolicy
import com.phototok.domain.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Casual swipe right on an SD card or camera copies into the phone's camera folder, subfolder
 * `Jackpix`; without access to that folder nothing is copied and the UI is asked for the grant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneModeViewModelCasualImportTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val sdFolder = Uri.parse("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM")
    private val cameraTree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera")
    private val pickedTree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")

    private val phoneSettingsFlow = MutableStateFlow(PhoneSettings())
    private val casualTargetFlow = MutableStateFlow<String?>(null)
    private val sourceSettings = MutableStateFlow(ProModePolicy.casualSourceSettings(SourceKind.REMOVABLE))

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } returns phoneSettingsFlow
        every { phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { sortingEnabled } returns MutableStateFlow(false)
        every { phoneGestureTutorialTs } returns MutableStateFlow(System.currentTimeMillis())
        every { lastAppUsedTs } returns MutableStateFlow(System.currentTimeMillis())
        every { casualCameraTargetUri } returns casualTargetFlow
        every { sourceSettingsFlow(any()) } returns sourceSettings
        coEvery { getFolderScanInfo(any()) } returns FolderScanInfo(0L, 0)
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { prepareSourceFolder(any()) } returns "DCIM"
        coEvery { resolveFolderName(any()) } returns null
        coEvery { getExifData(any()) } returns null
        coEvery { getImageDimensions(any()) } returns Pair(0, 0)
        coEvery { copyImageDetailed(any(), any(), any(), any()) } coAnswers {
            FileOperationResult.success(firstArg<Uri>().toString(), null)
        }
        coEvery { transferBundleDetailed(any(), any(), any(), any(), any()) } coAnswers {
            firstArg<List<Uri>>().map { FileOperationResult.success(it.toString(), null) }
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

    private fun loadCard(vararg images: ImageItem): PhoneModeViewModel {
        every { imageRepository.discoverImages(any()) } returns flowOf(images.toList())
        val viewModel = PhoneModeViewModel(
            imageRepository = imageRepository,
            settingsRepository = settingsRepository,
            appScope = CoroutineScope(testDispatcher),
        )
        viewModel.selectSourceFolder(sdFolder)
        return viewModel
    }

    private fun grantAvailableFor(vararg trees: Uri) {
        for (tree in trees) coEvery { imageRepository.resolveFolderName(tree) } returns "Camera"
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
    fun `casual SD swipe right copies into the camera folder under Jackpix`() = runTest {
        grantAvailableFor(cameraTree)
        val viewModel = loadCard(image("a.jpg", 1), image("b.jpg", 2))
        val state = viewModel.uiState.value
        assertTrue(state.importToPhoneCamera)
        assertEquals("Jackpix", state.selectionSubfolderName)
        assertEquals("Jackpix", state.collectionFolderName)
        assertEquals(cameraTree.toString(), state.collectionFolderUri)
        val first = state.images[state.currentIndex]

        viewModel.addToCollection()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            imageRepository.copyImageDetailed(Uri.parse(first.uri), cameraTree, false, "Jackpix")
        }
        val after = viewModel.uiState.value
        assertEquals(
            UiText.of(R.string.snackbar_copied, UiText.raw("Jackpix")),
            after.lastActionFeedback?.message,
        )
        assertEquals(2, after.images.size)
        assertNull(after.cameraFolderGrantRequest)
        // The grant that was found is remembered under a durable purpose.
        coVerify { settingsRepository.setCasualCameraTarget(cameraTree.toString()) }
    }

    @Test
    fun `a stored target is used before the camera folder`() = runTest {
        casualTargetFlow.value = pickedTree.toString()
        grantAvailableFor(pickedTree, cameraTree)
        val viewModel = loadCard(image("a.jpg", 1), image("b.jpg", 2))
        val first = viewModel.uiState.value.let { it.images[it.currentIndex] }

        viewModel.addToCollection()
        advanceUntilIdle()

        coVerify { imageRepository.copyImageDetailed(Uri.parse(first.uri), pickedTree, false, "Jackpix") }
    }

    @Test
    fun `linked companions travel with the copy`() = runTest {
        grantAvailableFor(cameraTree)
        val viewModel = loadCard(image("IMG_1.jpg", 1), image("IMG_1.arw", 1))

        viewModel.addToCollection()
        advanceUntilIdle()

        coVerify {
            imageRepository.transferBundleDetailed(
                match { it.size == 2 },
                cameraTree,
                false,
                "Jackpix",
                false,
            )
        }
    }

    @Test
    fun `without access the first swipe copies nothing and asks for the camera folder`() = runTest {
        val viewModel = loadCard(image("a.jpg", 1), image("b.jpg", 2))
        val before = viewModel.uiState.value

        viewModel.addToCollection()
        advanceUntilIdle()

        coVerify(exactly = 0) { imageRepository.copyImageDetailed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { imageRepository.transferBundleDetailed(any(), any(), any(), any(), any()) }
        val state = viewModel.uiState.value
        val request = state.cameraFolderGrantRequest
        assertNotNull(request)
        // The picker opens inside DCIM/Camera: a document URI, not the bare tree.
        assertEquals("primary:DCIM/Camera", android.provider.DocumentsContract.getDocumentId(request!!.initialUri))
        assertEquals("com.android.externalstorage.documents", request.initialUri.authority)
        // The photo stays where it was.
        assertEquals(before.currentIndex, state.currentIndex)
        assertEquals(before.images, state.images)
    }

    @Test
    fun `a second swipe while the grant is pending asks nothing more`() = runTest {
        val viewModel = loadCard(image("a.jpg", 1), image("b.jpg", 2))
        viewModel.addToCollection()
        val request = viewModel.uiState.value.cameraFolderGrantRequest

        viewModel.addToCollection()
        advanceUntilIdle()

        assertEquals(request, viewModel.uiState.value.cameraFolderGrantRequest)
        coVerify(exactly = 0) { imageRepository.copyImageDetailed(any(), any(), any(), any()) }
    }

    @Test
    fun `granting the folder keeps the grant and copies the waiting photo`() = runTest {
        val viewModel = loadCard(image("a.jpg", 1), image("b.jpg", 2))
        val waiting = viewModel.uiState.value.let { it.images[it.currentIndex] }
        viewModel.addToCollection()
        assertNotNull(viewModel.uiState.value.cameraFolderGrantRequest)

        grantAvailableFor(pickedTree)
        viewModel.onCameraFolderGranted(pickedTree)
        advanceUntilIdle()

        coVerify { settingsRepository.setCasualCameraTarget(pickedTree.toString()) }
        coVerify(exactly = 1) {
            imageRepository.copyImageDetailed(Uri.parse(waiting.uri), pickedTree, false, "Jackpix")
        }
        val state = viewModel.uiState.value
        assertNull(state.cameraFolderGrantRequest)
        assertEquals(pickedTree.toString(), state.collectionFolderUri)
        assertEquals(
            UiText.of(R.string.snackbar_copied, UiText.raw("Jackpix")),
            state.lastActionFeedback?.message,
        )
    }

    @Test
    fun `cancelling the picker copies nothing, keeps the photo and says not copied`() = runTest {
        val viewModel = loadCard(image("a.jpg", 1), image("b.jpg", 2))
        val before = viewModel.uiState.value
        viewModel.addToCollection()

        viewModel.onCameraFolderGrantCancelled()
        advanceUntilIdle()

        coVerify(exactly = 0) { imageRepository.copyImageDetailed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setCasualCameraTarget(any()) }
        val state = viewModel.uiState.value
        assertNull(state.cameraFolderGrantRequest)
        assertEquals(UiText.of(R.string.snackbar_not_copied), state.lastActionFeedback?.message)
        assertEquals(before.currentIndex, state.currentIndex)
        assertEquals(before.images, state.images)
    }

    @Test
    fun `a picked folder that cannot be read is not copied to`() = runTest {
        val viewModel = loadCard(image("a.jpg", 1))
        viewModel.addToCollection()

        viewModel.onCameraFolderGranted(pickedTree)
        advanceUntilIdle()

        coVerify(exactly = 0) { imageRepository.copyImageDetailed(any(), any(), any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setCasualCameraTarget(any()) }
        assertEquals(UiText.of(R.string.snackbar_not_copied), viewModel.uiState.value.lastActionFeedback?.message)
    }

    @Test
    fun `pro mode keeps the card selection folder`() = runTest {
        sourceSettings.value = SourceSettings.defaultFor(SourceKind.REMOVABLE)
        phoneSettingsFlow.value = PhoneSettings(proMode = true)
        coEvery { imageRepository.copyImage(any(), any(), any(), any()) } returns true
        val viewModel = loadCard(image("a.jpg", 1), image("b.jpg", 2))
        val state = viewModel.uiState.value
        assertFalse(state.importToPhoneCamera)
        assertEquals(PhotoFolders.SELECTION, state.selectionSubfolderName)
        val first = state.images[state.currentIndex]

        viewModel.addToCollection()
        advanceUntilIdle()

        coVerify { imageRepository.copyImageDetailed(Uri.parse(first.uri), sdFolder, any(), "Selection") }
        assertNull(viewModel.uiState.value.cameraFolderGrantRequest)
    }

    @Test
    fun `leaving casual brings the phone collection folder back`() = runTest {
        grantAvailableFor(cameraTree)
        val viewModel = loadCard(image("a.jpg", 1))
        assertEquals("Jackpix", viewModel.uiState.value.collectionFolderName)

        sourceSettings.value = SourceSettings.defaultFor(SourceKind.REMOVABLE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.importToPhoneCamera)
        assertNull(state.collectionFolderUri)
        assertEquals("", state.collectionFolderName)
    }

    // ── Pro-only guards ───────────────────────────────────────────────────

    @Test
    fun `casual never suggests RAW and JPEG settings`() = runTest {
        // Even if a filter that would trigger the dialog and the hint reached the view model, the
        // explicit Pro guard keeps both away from a Casual user.
        sourceSettings.value = ProModePolicy.casualSourceSettings(SourceKind.PHONE_LIBRARY)
            .copy(fileTypeFilter = FileTypeFilter.RAW, moveRelatedFiles = false)
        phoneSettingsFlow.value = PhoneSettings(proMode = false)
        val viewModel = loadCard(image("photo1.jpg", 10), image("photo1.raw", 10), image("photo2.jpg", 20), image("photo3.jpg", 30))

        assertFalse(viewModel.uiState.value.showRawJpegSuggestion)
        assertNull(viewModel.uiState.value.firstRunHint)
        viewModel.maybeShowRawJpegSuggestion()
        viewModel.maybeShowFilterMismatchHint()
        assertFalse(viewModel.uiState.value.showRawJpegSuggestion)
        assertNull(viewModel.uiState.value.firstRunHint)
    }

    @Test
    fun `casual logo tap does not toggle camera info`() = runTest {
        val viewModel = loadCard(image("a.jpg", 1))

        viewModel.toggleExifOverlay()
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.setPhoneShowExifOverlay(any()) }
    }

    @Test
    fun `switching pro mode shows the snackbar the repository names`() = runTest {
        val message = UiText.of(R.string.snackbar_pro_paused)
        coEvery { settingsRepository.setProMode(false) } returns message
        val viewModel = loadCard(image("a.jpg", 1))

        viewModel.setProMode(false)
        advanceUntilIdle()

        assertEquals(message, viewModel.uiState.value.lastActionFeedback?.message)
    }

    @Test
    fun `the ui state follows the pro mode of the settings`() = runTest {
        val viewModel = loadCard(image("a.jpg", 1))
        assertFalse(viewModel.uiState.value.proMode)

        phoneSettingsFlow.value = PhoneSettings(proMode = true)

        assertTrue(viewModel.uiState.value.proMode)
    }
}
