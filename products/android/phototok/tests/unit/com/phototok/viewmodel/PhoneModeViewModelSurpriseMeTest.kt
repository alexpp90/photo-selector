package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.FavouritesRepository
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SeenRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.StatsRepository
import com.phototok.data.source.VolumeMonitor
import com.phototok.domain.FirstRunHint
import com.phototok.domain.UiText
import io.mockk.coEvery
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Casual's "surprise me" ([USER 2026-11-12] decision #14): phone and cloud only, unseen photos
 * first and shuffled for this visit, newest first again on the next open, never on a card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Requirement("REQ-TOK-FEATURE.20", "REQ-TOK-FEATURE.08")
class PhoneModeViewModelSurpriseMeTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val phoneSettingsFlow = MutableStateFlow(PhoneSettings(proMode = false))

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } returns phoneSettingsFlow
        every { phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { sortingEnabled } returns MutableStateFlow(true)
        every { phoneGestureTutorialTs } returns MutableStateFlow(System.currentTimeMillis())
        every { lastAppUsedTs } returns MutableStateFlow(System.currentTimeMillis())
        coEvery { getFolderScanInfo(any()) } returns com.phototok.domain.FolderScanInfo(0L, 0)
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { prepareSourceFolder(any()) } returns "Camera"
        coEvery { resolveFolderName(any()) } returns null
        coEvery { getExifData(any()) } returns null
        coEvery { getImageDimensions(any()) } returns Pair(0, 0)
    }

    private val seen = SeenRepository()
    private val phoneTree = "content://tree/photos"
    private val cloudTree = "content://com.google.android.apps.docs.storage/tree/abc"
    private val sdTree = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM"

    private fun image(name: String, modified: Long) = ImageItem(
        uri = "content://photos/$name", fileName = name, fileSize = 1, lastModified = modified,
        mimeType = "image/jpeg", imageWidth = 100, imageHeight = 50,
    )

    /** Newest first: p01 is the newest. */
    private val photos = (1..12).map { image("p%02d.jpg".format(it), 100L - it) }
    private val newestFirst = photos.map { it.fileName }

    private fun open(tree: String, vm: PhoneModeViewModel? = null): PhoneModeViewModel {
        every { imageRepository.discoverImages(any()) } returns flowOf(photos)
        val viewModel = vm ?: PhoneModeViewModel(
            imageRepository, settingsRepository, CoroutineScope(testDispatcher), seen, FavouritesRepository(),
            StatsRepository(), VolumeMonitor(),
        )
        viewModel.selectSourceFolder(Uri.parse(tree))
        return viewModel
    }

    private fun PhoneModeViewModel.names() = uiState.value.images.map { it.fileName }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a Casual phone feed offers it, deals unseen photos first and turns back to newest first`() = runTest {
        val vm = open(phoneTree)
        assertEquals(newestFirst, vm.names())
        assertEquals("offered, off", false, vm.uiState.value.surpriseMeShown)
        val seenNames = setOf("p01.jpg", "p02.jpg", "p03.jpg", "p04.jpg")
        photos.filter { it.fileName in seenNames }.forEach { seen.markSeen(it, phoneTree) }

        vm.toggleSurpriseMe()

        assertEquals(true, vm.uiState.value.surpriseMeShown)
        val dealt = vm.names()
        assertEquals("the same photos", newestFirst.toSet(), dealt.toSet())
        assertTrue("unseen first: $dealt", dealt.take(8).none { it in seenNames })
        assertEquals(0, vm.uiState.value.currentIndex)
        assertEquals("explained once", FirstRunHint.SURPRISE, vm.uiState.value.firstRunHint?.hint)

        vm.navigateToImage(3)
        vm.navigateToImage(4)
        assertEquals("paging never re-deals", dealt, vm.names())

        vm.toggleSurpriseMe()
        assertEquals(false, vm.uiState.value.surpriseMeShown)
        assertEquals(newestFirst, vm.names())
    }

    @Test
    fun `every open starts newest first again`() = runTest {
        val vm = open(phoneTree)
        vm.toggleSurpriseMe()
        assertTrue(vm.uiState.value.surpriseMe)

        open(phoneTree, vm)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.surpriseMe)
        assertEquals(newestFirst, vm.names())
    }

    @Test
    fun `a cloud folder offers it too`() = runTest {
        val vm = open(cloudTree)
        assertEquals(false, vm.uiState.value.surpriseMeShown)
    }

    @Test
    fun `a card never shuffles`() = runTest {
        every { settingsRepository.sourceSettingsFlow(any()) } returns flowOf(SourceSettings())
        val vm = open(sdTree)
        advanceUntilIdle()
        assertNull("no control on an SD card", vm.uiState.value.surpriseMeShown)
        vm.toggleSurpriseMe()
        assertEquals(newestFirst, vm.names())
        assertFalse(vm.uiState.value.surpriseMe)
    }

    @Test
    fun `Pro has Feed order instead`() = runTest {
        phoneSettingsFlow.value = PhoneSettings(proMode = true)
        val vm = open(phoneTree)
        assertNull(vm.uiState.value.surpriseMeShown)
        vm.toggleSurpriseMe()
        assertEquals(newestFirst, vm.names())
    }

    @Test
    fun `with everything seen it says so`() = runTest {
        photos.forEach { seen.markSeen(it, phoneTree) }
        val vm = open(phoneTree)
        vm.toggleSurpriseMe()
        assertEquals(UiText.of(R.string.snackbar_all_seen), vm.uiState.value.lastActionFeedback?.message)
    }
}
