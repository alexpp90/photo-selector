package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.Requirement
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.ImageItem
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.FakeMediaStoreTrash
import com.phototok.data.repository.FavouritesRepository
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SeenRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.StatsRepository
import com.phototok.data.repository.SystemTrashRepository
import com.phototok.data.source.VolumeMonitor
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.SwipeAction
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
 * Finalised deletes through the system bin ([USER 2026-11-12] decision #13), wired as the app
 * ships them: the real [PhoneModeViewModel] and [SystemTrashRepository] over a fake MediaStore.
 * Phone photos MediaStore indexes go to the bin after one consent per finalisation; SD card, USB
 * and cloud files, and files MediaStore does not index, are deleted permanently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Requirement("REQ-TOK-GESTURE.03", "REQ-TOK-ARCH.08")
class PhoneModeViewModelSystemTrashTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val phoneSettingsFlow = MutableStateFlow(PhoneSettings())

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } returns phoneSettingsFlow
        every { phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { sortingEnabled } returns MutableStateFlow(true)
        every { phoneGestureTutorialTs } returns MutableStateFlow(System.currentTimeMillis())
        every { lastAppUsedTs } returns MutableStateFlow(System.currentTimeMillis())
        coEvery { getFolderScanInfo(any()) } returns FolderScanInfo(0L, 0)
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { prepareSourceFolder(any()) } returns "DCIM"
        coEvery { resolveFolderName(any()) } returns null
        coEvery { getExifData(any()) } returns null
        coEvery { getImageDimensions(any()) } returns Pair(0, 0)
        coEvery { deleteImage(any()) } returns true
    }

    private val phoneTree = "content://tree/photos"
    private val sdTree = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM"

    private fun image(name: String, modified: Long, parent: String = "content://photos") = ImageItem(
        uri = "$parent/$name",
        fileName = name,
        fileSize = 1_000,
        lastModified = modified,
        mimeType = "image/jpeg",
        imageWidth = 100,
        imageHeight = 50,
        parentUri = parent,
    )

    private val a = image("a.jpg", 3)
    private val b = image("b.jpg", 2)
    private val c = image("c.jpg", 1)

    /** MediaStore indexes the phone's JPEGs; sidecars and card files are not in it. */
    private val gateway = FakeMediaStoreTrash(
        index = listOf(a, b, c).withIndex().associate { (i, img) -> img.uri to "content://media/external/images/media/${i + 1}" },
    )
    private val trash = SystemTrashRepository(gateway, StatsRepository(), testDispatcher)

    private fun viewModel() = PhoneModeViewModel(
        imageRepository = imageRepository,
        settingsRepository = settingsRepository,
        appScope = CoroutineScope(testDispatcher),
        seenRepository = SeenRepository(),
        favouritesRepository = FavouritesRepository(),
        statsRepository = StatsRepository(),
        volumeMonitor = VolumeMonitor(),
        systemTrash = trash,
    )

    private fun open(tree: String, vararg images: ImageItem): PhoneModeViewModel {
        every { imageRepository.discoverImages(any()) } returns flowOf(images.toList())
        return viewModel().also { it.selectSourceFolder(Uri.parse(tree)) }
    }

    /** What the Activity does: launch the published request and report the user's answer. */
    private suspend fun answer(accept: Boolean) {
        val request = trash.state.value.request ?: error("no consent request published")
        trash.onLaunched(request)
        if (accept) gateway.acceptLast()
        trash.onResult(accept)
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
    fun `the delete confirmation is on by default`() {
        assertTrue(SourceSettings().directDeleteConfirmEnabled)
        for (kind in SourceKind.values()) assertTrue("$kind", SourceSettings.defaultFor(kind).directDeleteConfirmEnabled)
    }

    @Test
    fun `the dialog of a phone photo says Trash, of an SD card photo permanently`() = runTest {
        val phone = open(phoneTree, a, b)
        phone.onSwipeLeft()
        assertTrue(phone.uiState.value.confirmDeleteRequested)
        assertTrue("phone photo in MediaStore -> bin", phone.uiState.value.confirmDeleteSplit.allToBin)

        every { settingsRepository.sourceSettingsFlow(any()) } returns flowOf(SourceSettings(swipeLeftVerb = SwipeAction.DELETE))
        val card = open(sdTree, image("a.jpg", 3, parent = sdTree))
        advanceUntilIdle()
        assertEquals(SourceKind.REMOVABLE, card.uiState.value.sourceKind)
        card.onSwipeLeft()
        assertTrue(card.uiState.value.confirmDeleteRequested)
        assertEquals(0, card.uiState.value.confirmDeleteSplit.bin)
    }

    @Test
    fun `leaving the folder asks once for every phone delete, and an OK moves them to the bin`() = runTest {
        val vm = open(phoneTree, a, b, c)
        vm.requestDelete()
        vm.requestDelete()
        assertNull("no dialog while swiping", trash.state.value.request)

        vm.finalizePendingDelete()

        assertEquals("one consent for the batch", 1, gateway.requests.size)
        assertEquals(2, gateway.requests.single().size)
        answer(accept = true)
        coVerify(exactly = 0) { imageRepository.deleteImage(any()) }
        assertEquals(listOf("c.jpg"), vm.uiState.value.images.map { it.fileName })
        assertNull(trash.state.value.notice)
    }

    @Test
    fun `a declined consent keeps the photos, brings them back and Retry asks for the same files`() = runTest {
        val vm = open(phoneTree, a, b, c)
        vm.requestDelete()
        vm.finalizePendingDelete()
        assertEquals(listOf("b.jpg", "c.jpg"), vm.uiState.value.images.map { it.fileName })

        answer(accept = false)

        coVerify(exactly = 0) { imageRepository.deleteImage(any()) }
        assertEquals("the photo is back in the feed", listOf("a.jpg", "b.jpg", "c.jpg"), vm.uiState.value.images.map { it.fileName })
        val notice = trash.state.value.notice
        assertNotNull("'1 not deleted' with Retry", notice)
        assertEquals(listOf(a.uri), notice!!.items.map { it.documentUri })

        trash.retry(notice)
        assertEquals(gateway.requests[0], gateway.requests[1])
        answer(accept = true)
        assertEquals("Retry's OK takes it out of the open feed", listOf("b.jpg", "c.jpg"), vm.uiState.value.images.map { it.fileName })
    }

    @Test
    fun `a companion MediaStore has no bin for goes only once the photo is in the bin`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(moveRelatedFiles = true, fileTypeFilter = FileTypeFilter.ALL)
        val shot = a.copy(sidecars = listOf(image("a.xmp", 0).copy(mimeType = null)))
        val vm = open(phoneTree, shot, b)

        vm.onSwipeLeft()
        val split = vm.uiState.value.confirmDeleteSplit
        assertEquals("1 to Trash · 1 permanent", 1 to 1, split.bin to split.permanent)
        vm.confirmDelete(dontAskAgain = false)
        vm.finalizePendingDelete()
        answer(accept = false)
        coVerify(exactly = 0) { imageRepository.deleteImage(any()) }
        assertTrue("the shot is back", vm.uiState.value.images.any { it.uri == a.uri })

        vm.navigateToImage(vm.uiState.value.images.indexOfFirst { it.uri == a.uri })
        vm.requestDelete()
        vm.finalizePendingDelete()
        answer(accept = true)
        coVerify(exactly = 1) { imageRepository.deleteImage(Uri.parse("content://photos/a.xmp")) }
        coVerify(exactly = 0) { imageRepository.deleteImage(Uri.parse(a.uri)) }
    }

    @Test
    fun `an SD card delete stays permanent and asks the system nothing`() = runTest {
        every { settingsRepository.sourceSettingsFlow(any()) } returns flowOf(SourceSettings(swipeLeftVerb = SwipeAction.DELETE))
        val cardPhoto = image("a.jpg", 3, parent = sdTree)
        val vm = open(sdTree, cardPhoto, image("b.jpg", 2, parent = sdTree))
        advanceUntilIdle()
        vm.requestDelete()
        vm.finalizePendingDelete()

        assertTrue(gateway.requests.isEmpty())
        assertNull(trash.state.value.request)
        coVerify(exactly = 1) { imageRepository.deleteImage(Uri.parse(cardPhoto.uri)) }
    }

    @Test
    fun `the 51st delete holds the evicted phone photo for the next finalisation`() = runTest {
        val photos = (1..52).map { image("p$it.jpg", 100L - it) }
        val bigGateway = FakeMediaStoreTrash(photos.associate { it.uri to "content://media/external/images/media/${it.fileName}" })
        val bigTrash = SystemTrashRepository(bigGateway, StatsRepository(), testDispatcher)
        every { imageRepository.discoverImages(any()) } returns flowOf(photos)
        val vm = PhoneModeViewModel(
            imageRepository, settingsRepository, CoroutineScope(testDispatcher), SeenRepository(), FavouritesRepository(),
            StatsRepository(), VolumeMonitor(), systemTrash = bigTrash,
        )
        vm.selectSourceFolder(Uri.parse(phoneTree))
        repeat(51) { vm.requestDelete() }
        assertNull("the swipe that evicted the oldest opens no dialog", bigTrash.state.value.request)

        vm.finalizePendingDelete()
        assertEquals("the evicted one goes with the batch, in one request", 1, bigGateway.requests.size)
        assertEquals(51, bigGateway.requests.single().size)
        assertFalse(vm.uiState.value.images.isEmpty())
    }
}
