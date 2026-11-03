package com.phototok.viewmodel

import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.ImageRepository
import com.phototok.data.source.SelectionListing
import com.phototok.domain.FavouritesSnapshot
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.phototok.data.source.FileOperationResult
import android.net.Uri
import com.phototok.domain.FavouriteSyncLogic
import io.mockk.slot
import io.mockk.every
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
        viewModel = SelectionViewerViewModel(imageRepository, CoroutineScope(testDispatcher))
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

    // ── Which list opens first, and whether there is anything to switch (REQ-TOK-FEATURE.17) ──

    private fun source(vararg names: String) = names.map { image(it) }.map { it.copy(uri = "content://source/${it.fileName}") }

    private fun openWith(
        favourited: Int,
        selection: Int,
        preferred: SelectionViewerTab,
    ) {
        val sourceImages = source("a.jpg", "b.jpg", "c.jpg", "d.jpg")
        val favs = sourceImages.take(favourited).map { it.uri }.toSet()
        coEvery { imageRepository.listSelectionImages(any()) } returns
            if (selection > 0) {
                SelectionListing.Available((1..selection).map { image("sel$it.jpg") }, "Keepers")
            } else {
                SelectionListing.Missing
            }
        viewModel.openWithFavourites(
            targetFolderUri = "content://tree/photos",
            sourceImages = sourceImages,
            favourites = FavouritesSnapshot(favourites = favs),
            preferredTab = preferred,
        )
    }

    @Test
    fun `preferred tab follows the swipe-right verb`() {
        assertEquals(SelectionViewerTab.FAVOURITES, SelectionViewerLogic.preferredTab(SwipeRightVerb.FAVOURITE))
        assertEquals(SelectionViewerTab.SELECTION, SelectionViewerLogic.preferredTab(SwipeRightVerb.COPY))
        assertEquals(SelectionViewerTab.SELECTION, SelectionViewerLogic.preferredTab(SwipeRightVerb.MOVE))
    }

    @Test
    fun `initial tab is the preferred one unless its list is empty`() {
        val fav = SelectionViewerTab.FAVOURITES
        val sel = SelectionViewerTab.SELECTION
        assertEquals(fav, SelectionViewerLogic.initialTab(fav, 3, 5))
        assertEquals(sel, SelectionViewerLogic.initialTab(sel, 3, 5))
        assertEquals(sel, SelectionViewerLogic.initialTab(fav, 0, 5))
        assertEquals(fav, SelectionViewerLogic.initialTab(sel, 3, 0))
        assertEquals(fav, SelectionViewerLogic.initialTab(fav, 3, 0))
        assertEquals(sel, SelectionViewerLogic.initialTab(sel, 0, 5))
    }

    @Test
    fun `tabs are shown only when both lists have photos`() {
        assertTrue(SelectionViewerLogic.showTabs(2, 3))
        assertFalse(SelectionViewerLogic.showTabs(0, 3))
        assertFalse(SelectionViewerLogic.showTabs(2, 0))
        assertFalse(SelectionViewerLogic.showTabs(0, 0))
    }

    @Test
    fun `a copying swipe opens on the Selection tab even though favourites exist`() = runTest {
        openWith(favourited = 2, selection = 3, preferred = SelectionViewerTab.SELECTION)

        val state = viewModel.uiState.value
        assertEquals(SelectionViewerTab.SELECTION, state.activeTab)
        assertEquals(3, state.images.size)
        assertEquals("Keepers", state.folderName)
        assertTrue(state.showTabs)
        assertEquals(2, state.favouriteImages.size)
        assertEquals(3, state.selectionImages.size)
    }

    @Test
    fun `a favouriting swipe opens on the Favourites tab even though the folder has photos`() = runTest {
        openWith(favourited = 2, selection = 3, preferred = SelectionViewerTab.FAVOURITES)

        val state = viewModel.uiState.value
        assertEquals(SelectionViewerTab.FAVOURITES, state.activeTab)
        assertEquals(2, state.images.size)
        assertTrue(state.showTabs)
    }

    @Test
    fun `with only favourites the viewer opens on them whatever the verb and shows a title, not tabs`() = runTest {
        openWith(favourited = 2, selection = 0, preferred = SelectionViewerTab.SELECTION)

        val state = viewModel.uiState.value
        assertTrue(state.isOpen)
        assertEquals(SelectionViewerTab.FAVOURITES, state.activeTab)
        assertEquals(2, state.images.size)
        assertFalse(state.showTabs)
    }

    @Test
    fun `with only the Selection folder the viewer opens on it whatever the verb and shows a title, not tabs`() = runTest {
        openWith(favourited = 0, selection = 3, preferred = SelectionViewerTab.FAVOURITES)

        val state = viewModel.uiState.value
        assertTrue(state.isOpen)
        assertEquals(SelectionViewerTab.SELECTION, state.activeTab)
        assertEquals(3, state.images.size)
        assertEquals("Keepers", state.selectionFolderName)
        assertFalse(state.showTabs)
    }

    @Test
    fun `selecting a tab swaps the list and the share and copy source follows it`() = runTest {
        openWith(favourited = 2, selection = 3, preferred = SelectionViewerTab.SELECTION)

        viewModel.selectTab(SelectionViewerTab.FAVOURITES)

        val state = viewModel.uiState.value
        assertEquals(SelectionViewerTab.FAVOURITES, state.activeTab)
        assertEquals(state.favouriteImages, state.images)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `a casual card import opens the Jackpix folder of the camera target`() = runTest {
        val images = listOf(image("a.jpg", 1))
        coEvery { imageRepository.listSelectionImages(any(), "Jackpix") } returns
            SelectionListing.Available(images, "Jackpix")

        viewModel.openWithFavourites(
            targetFolderUri = "content://tree/camera",
            sourceImages = emptyList(),
            favourites = FavouritesSnapshot(favourites = emptySet()),
            preferredTab = SelectionViewerTab.SELECTION,
            selectionSubfolder = "Jackpix",
        )

        val state = viewModel.uiState.value
        assertTrue(state.isOpen)
        assertEquals(images, state.images)
        assertEquals("Jackpix", state.selectionFolderName)
        coVerify { imageRepository.listSelectionImages(any(), "Jackpix") }
        coVerify(exactly = 0) { imageRepository.listSelectionImages(any(), "Selection") }
    }

    @Test
    fun `close returns to the feed and a recreated viewmodel starts closed`() = runTest {
        coEvery { imageRepository.listSelectionImages(any(), any()) } returns
            SelectionListing.Available(listOf(image("a.jpg", 1), image("b.jpg", 2)), "Selection")
        viewModel.open("content://tree/photos")
        viewModel.navigateTo(1)
        assertTrue(viewModel.uiState.value.isOpen)

        viewModel.close()

        assertFalse(viewModel.uiState.value.isOpen)
        // Process recreation builds a new ViewModel: the app starts on the feed, never the viewer.
        assertFalse(SelectionViewerViewModel(imageRepository, CoroutineScope(testDispatcher)).uiState.value.isOpen)
    }

    private val dest = Uri.parse("content://tree/dest")
    private val p = "content://tree/photos"

    private fun shot(name: String, vararg sidecars: String) = image(name).copy(
        uri = "$p/$name",
        parentUri = p,
        sidecars = sidecars.map { image(it).copy(uri = "$p/$it", parentUri = p, mimeType = null) },
    )

    private fun openWithFavourites(favs: List<ImageItem>, all: List<ImageItem>) {
        val snapshot = FavouriteSyncLogic.setFavourite(FavouritesSnapshot(), favs.map { it.uri }, favourite = true)
        viewModel.openWithFavourites(targetFolderUri = null, sourceImages = favs, favourites = snapshot)
        assertEquals(favs, viewModel.uiState.value.favouriteImages)
    }

    @Test
    fun `copying favourites takes the RAW and the sidecar of a favourite JPEG in one bundle`() = runTest {
        val jpg = shot("IMG_1.JPG", "IMG_1.xmp")
        val raw = shot("IMG_1.CR3", "IMG_1.xmp")
        val sent = slot<List<Uri>>()
        coEvery {
            imageRepository.transferBundleDetailed(capture(sent), any(), any(), any(), any())
        } coAnswers { sent.captured.map { FileOperationResult.success(it.toString(), "d") } }
        openWithFavourites(listOf(jpg), all = listOf(jpg, raw))

        viewModel.copyFavouritesToFolder(dest, allSourceImages = listOf(jpg, raw))

        assertEquals(
            listOf("$p/IMG_1.JPG", "$p/IMG_1.CR3", "$p/IMG_1.xmp").map(Uri::parse).toSet(),
            sent.captured.toSet(),
        )
        coVerify(exactly = 1) { imageRepository.transferBundleDetailed(any(), dest, false, "Favourites", false) }
        assertEquals(UiText.of(R.string.snackbar_favourites_copied, 1, "Favourites"), viewModel.uiState.value.feedback?.message)
    }

    @Test
    fun `favourites that are twins of each other are copied once`() = runTest {
        val jpg = shot("IMG_1.JPG")
        val raw = shot("IMG_1.CR3")
        coEvery { imageRepository.transferBundleDetailed(any(), any(), any(), any(), any()) } coAnswers {
            firstArg<List<Uri>>().map { FileOperationResult.success(it.toString(), "d") }
        }
        openWithFavourites(listOf(jpg, raw), all = listOf(jpg, raw))

        viewModel.copyFavouritesToFolder(dest, allSourceImages = listOf(jpg, raw))

        coVerify(exactly = 1) { imageRepository.transferBundleDetailed(any(), any(), any(), any(), any()) }
        assertEquals(UiText.of(R.string.snackbar_favourites_copied, 1, "Favourites"), viewModel.uiState.value.feedback?.message)
    }

    @Test
    fun `a bundle with a failed member is reported as not copied`() = runTest {
        val jpg = shot("IMG_1.JPG", "IMG_1.xmp")
        coEvery { imageRepository.transferBundleDetailed(any(), any(), any(), any(), any()) } returns listOf(
            FileOperationResult.success("$p/IMG_1.JPG", "d"),
            FileOperationResult.failure("$p/IMG_1.xmp", "no"),
        )
        openWithFavourites(listOf(jpg), all = listOf(jpg))

        viewModel.copyFavouritesToFolder(dest, allSourceImages = listOf(jpg))

        assertEquals(UiText.of(R.string.error_favourites_partial, 0, 1), viewModel.uiState.value.feedback?.message)
        assertTrue(viewModel.uiState.value.feedback?.isError == true)
    }

    @Test
    fun `closing the viewer does not cut a favourites copy short`() = runTest {
        val a = shot("IMG_1.JPG")
        val b = shot("IMG_2.JPG")
        val gate = CompletableDeferred<Unit>()
        coEvery { imageRepository.copyImageDetailed(any(), any(), any(), any()) } coAnswers {
            gate.await()
            yield() // a cancellation point, like the real file I/O
            FileOperationResult.success(firstArg<Uri>().toString(), "d")
        }
        // The viewer's view model is cleared (the user leaves the Selection viewer) while the copy runs.
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                SelectionViewerViewModel(imageRepository, CoroutineScope(testDispatcher)) as T
        })[SelectionViewerViewModel::class.java]
        vm.openWithFavourites(
            targetFolderUri = null,
            sourceImages = listOf(a, b),
            favourites = FavouriteSyncLogic.setFavourite(FavouritesSnapshot(), listOf(a.uri, b.uri), favourite = true),
        )
        vm.copyFavouritesToFolder(dest)
        store.clear()

        gate.complete(Unit)

        coVerify(exactly = 1) { imageRepository.copyImageDetailed(Uri.parse(a.uri), dest, false, "Favourites") }
        coVerify(exactly = 1) { imageRepository.copyImageDetailed(Uri.parse(b.uri), dest, false, "Favourites") }
        assertEquals(UiText.of(R.string.snackbar_favourites_copied, 2, "Favourites"), vm.uiState.value.feedback?.message)
    }
}
