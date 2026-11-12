package com.photoselectortoolbox.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.viewModelScope
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.SafGrantBackend
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepositoryImpl
import com.photoselectortoolbox.data.repository.SafTransferTest.FakeSafDocumentsProvider
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.data.source.LocalImageSource
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Relaunch paths of the cull (backlog 2026-11-11).
 *
 * A relaunch in a warm process (configuration change, DeX window resize, a second activity)
 * builds a [SelectorViewModel] on a DataStore that already holds the last folder. With an
 * immediate main dispatcher the `lastFolderUri` collection in `init` reopens that folder inside
 * the constructor, so every property the reopen path reads must exist by then (backlog
 * 2026-11-11: `resetSession` cleared `loadedExifCache`, declared below `init`, and threw).
 *
 * A reopened folder resumes at the photo it was left on, by URI (FolderPlace,
 * REQ-AND-WORKFLOW.06), through the shipped reopen: a second view model built on the store the
 * first one wrote, which reopens `lastFolderUri` itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectorViewModelRelaunchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(testDispatcher)
    private val viewModels = mutableListOf<SelectorViewModel>()

    private lateinit var rootDir: File
    private lateinit var settings: SettingsRepository
    private lateinit var repository: ImageRepositoryImpl
    private lateinit var listed: List<ImageItem>

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(
        FakeSafDocumentsProvider.AUTHORITY,
        FakeSafDocumentsProvider.ROOT_ID,
    )

    private class NoGrants : SafGrantBackend {
        override fun takePersistableGrant(uri: Uri, modeFlags: Int) = Unit
        override fun releasePersistableGrant(uri: Uri, modeFlags: Int) = Unit
        override fun persistedGrantUris(): Set<String> = emptySet()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        rootDir = tmp.newFolder("shoot")
        FakeSafDocumentsProvider.reset(rootDir)
        Robolectric.buildContentProvider(FakeSafDocumentsProvider::class.java)
            .create(FakeSafDocumentsProvider.AUTHORITY)
        val component = android.content.ComponentName(context.packageName, FakeSafDocumentsProvider::class.java.name)
        val info = android.content.pm.ProviderInfo().apply {
            authority = FakeSafDocumentsProvider.AUTHORITY
            name = component.className
            packageName = component.packageName
            exported = true
            grantUriPermissions = true
            applicationInfo = context.applicationInfo
        }
        val packageManager = org.robolectric.Shadows.shadowOf(context.packageManager)
        packageManager.addOrUpdateProvider(info)
        packageManager.addIntentFilterForProvider(
            component,
            android.content.IntentFilter("android.content.action.DOCUMENTS_PROVIDER"),
        )

        settings = SettingsRepository(context, FolderAccessManager(NoGrants()))
        runBlocking {
            settings.setPendingDeleteJournal(emptySet())
            settings.setLastFolderUri(null)
        }

        listed = listOf(photo(1))
        val source: LocalImageSource = mockk(relaxed = true) {
            every { discoverImages(any()) } answers { flowOf(listed) }
            coEvery { resolveDimensions(any()) } returns emptyMap<String, ImageDimensions>()
        }
        repository = ImageRepositoryImpl(
            localImageSource = source,
            androidExifReader = AndroidExifReader(),
            mediaStoreReader = MediaStoreReader(),
            settingsRepository = settings,
            folderAccessManager = FolderAccessManager(NoGrants()),
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        runBlocking { settings.setLastFolderUri(null) }
        Dispatchers.resetMain()
    }

    /** IMG_000[n].JPG, newer for a higher [n]. */
    private fun photo(n: Int): ImageItem {
        val file = File(rootDir, "IMG_000$n.JPG").apply { writeBytes(ByteArray(64) { 7 }) }
        return ImageItem(
            uri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                "${FakeSafDocumentsProvider.ROOT_ID}/${file.name}",
            ).toString(),
            fileName = file.name,
            fileSize = file.length(),
            lastModified = 1_700_000_000_000L + n * 1_000L,
            mimeType = "image/jpeg",
        )
    }

    private fun newViewModel(): SelectorViewModel = SelectorViewModel(
        imageRepository = repository,
        scanImagesUseCase = mockk<ScanImagesUseCase>(relaxed = true),
        cacheRepository = mockk<CacheRepository>(relaxed = true),
        settingsRepository = settings,
        scoreDao = mockk<ScoreDao>(relaxed = true) { coEvery { getScore(any()) } returns null },
        appScope = appScope,
        context = context,
    ).also { viewModels += it }

    @Test
    fun `a view model built on a store that holds the last folder reopens it without throwing`() = runTest {
        settings.setLastFolderUri(treeUri.toString())
        // The store is warm: the value is already there when the constructor subscribes.
        assertEquals(treeUri.toString(), settings.lastFolderUri.first())

        val viewModel = newViewModel()

        val opened = withTimeout(10_000) { viewModel.uiState.first { it.images.isNotEmpty() } }
        assertEquals(treeUri.toString(), opened.folderUri)
        assertEquals(listOf("IMG_0001.JPG"), opened.images.map { it.fileName })
    }

    /**
     * [block] under a real-time bound: the reopen waits on DataStore and provider I/O on real
     * threads, and a virtual-time bound would fire while the scheduler idles for them.
     */
    private suspend fun <T> inRealTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { block() } }

    /** Wait, in real time, for the DataStore write the debounced save makes. */
    private suspend fun storedPhoto(expected: String?): String? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(10_000) {
            while (settings.getFolderLastPhoto(treeUri.toString()) != expected) delay(20)
        }
        settings.getFolderLastPhoto(treeUri.toString())
    }

    /** Session one: open the folder, go to [name], let the save land, then the process dies. */
    private suspend fun TestScope.leaveAt(name: String) {
        val first = newViewModel()
        first.selectFolder(treeUri)
        val opened = inRealTime { first.uiState.first { it.images.isNotEmpty() && !it.isEnumerating } }
        first.navigateToImage(opened.images.indexOfFirst { it.fileName == name })
        assertEquals(name, first.uiState.value.currentImage?.fileName)
        testScheduler.advanceTimeBy(FolderPlace.SAVE_DEBOUNCE_MILLIS + 100)
        testScheduler.runCurrent()
        assertEquals(listed.first { it.fileName == name }.uri, storedPhoto(listed.first { it.fileName == name }.uri))
        first.viewModelScope.cancel()
    }

    @Test
    fun `a folder reopened after the process died resumes at the photo it was left on`() = runTest(testDispatcher) {
        listed = (1..5).map(::photo)
        leaveAt("IMG_0002.JPG")

        // Session two: a new view model reopens the last folder by itself.
        val second = newViewModel()
        val settled = inRealTime { second.uiState.first { it.images.size == 5 && !it.isEnumerating } }

        // Newest first: IMG_0005 is frame 1, IMG_0002 frame 4.
        assertEquals("IMG_0005.JPG", settled.images.first().fileName)
        assertEquals("IMG_0002.JPG", second.uiState.value.currentImage?.fileName)
        assertEquals(3, second.uiState.value.currentIndex)
    }

    @Test
    fun `a photo gone since leaves the reopened folder on frame 1 and says nothing`() = runTest(testDispatcher) {
        listed = (1..5).map(::photo)
        leaveAt("IMG_0002.JPG")
        listed = listed.filterNot { it.fileName == "IMG_0002.JPG" }

        val second = newViewModel()
        inRealTime { second.uiState.first { it.images.size == 4 && !it.isEnumerating } }

        assertEquals(0, second.uiState.value.currentIndex)
        assertEquals("IMG_0005.JPG", second.uiState.value.currentImage?.fileName)
        assertEquals(null, second.uiState.value.snackbarMessage)
    }
}
