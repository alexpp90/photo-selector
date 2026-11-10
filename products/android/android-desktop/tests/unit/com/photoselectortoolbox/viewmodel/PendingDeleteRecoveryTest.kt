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
import com.photoselectortoolbox.domain.curation.FileIdentity
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * The pending-delete journal across a killed process, end to end (REQ-AND-LAYOUT.23, finding
 * 2026-11-05): the shipped [SelectorViewModel], [SettingsRepository] (real DataStore) and
 * [ImageRepositoryImpl] over a SAF-shaped provider backed by a temporary folder. Only discovery
 * is stubbed. On local storage a document id is the path, so after a restart another photo can
 * sit at a journalled URI; "Delete now" must leave it alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingDeleteRecoveryTest {

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
        registerAsDocumentsProvider()

        settings = SettingsRepository(context, FolderAccessManager(NoGrants()))
        // The DataStore is a process-wide singleton: start every test from an empty journal.
        runBlocking {
            settings.setPendingDeleteJournal(emptySet())
            settings.setLastFolderUri(null)
        }

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
        )
        repository.ioDispatcher = testDispatcher
    }

    @After
    fun tearDown() {
        appScope.cancel()
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        runBlocking { settings.setPendingDeleteJournal(emptySet()) }
        Dispatchers.resetMain()
    }

    private fun registerAsDocumentsProvider() {
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
    }

    private fun documentUri(name: String): String =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, "${FakeSafDocumentsProvider.ROOT_ID}/$name").toString()

    private fun photo(name: String, bytes: Int, modified: Long): File =
        File(rootDir, name).apply {
            writeBytes(ByteArray(bytes) { 7 })
            setLastModified(modified)
        }

    private fun listing(file: File) = ImageItem(
        uri = documentUri(file.name),
        fileName = file.name,
        fileSize = file.length(),
        lastModified = file.lastModified(),
        mimeType = "image/jpeg",
    )

    private fun newViewModel(): SelectorViewModel {
        val viewModel = SelectorViewModel(
            imageRepository = repository,
            scanImagesUseCase = mockk<ScanImagesUseCase>(relaxed = true),
            cacheRepository = mockk<CacheRepository>(relaxed = true),
            settingsRepository = settings,
            scoreDao = mockk<ScoreDao>(relaxed = true) { coEvery { getScore(any()) } returns null },
            appScope = appScope,
            context = context,
        )
        viewModels += viewModel
        return viewModel
    }

    /** Session one: open the folder, delete its first photo, then the process dies before the commit. */
    private suspend fun deleteThenDie(file: File) {
        listed = listOf(listing(file))
        val viewModel = newViewModel()
        viewModel.selectFolder(treeUri)
        viewModel.uiState.first { it.images.isNotEmpty() }
        viewModel.deleteCurrentImage()
        settings.pendingDeleteIdentities.first { documentUri(file.name) in it }
        // Killed: no onCleared, so nothing commits the pending delete.
        viewModel.viewModelScope.cancel()
    }

    /**
     * Session two: the recovery prompt is answered with "Delete now"; returns the message shown,
     * waiting for it when [expectMessage].
     */
    private suspend fun deleteNowAfterRestart(expectMessage: Boolean): String? {
        // The relaunch is tested without reopening the last folder: that path is not under test
        // here, and with this warm in-process DataStore it reopens the folder inside the
        // constructor (see the hand-back note on SelectorViewModel's init order).
        settings.setLastFolderUri(null)
        val viewModel = newViewModel()
        viewModel.uiState.first { it.uncompletedDeletionsPrompt != null }
        viewModel.confirmUncompletedDeletions()
        settings.pendingDeleteJournal.first { it.isEmpty() }
        assertNull(viewModel.uiState.value.uncompletedDeletionsPrompt)
        return if (expectMessage) viewModel.uiState.first { it.snackbarMessage != null }.snackbarMessage
        else viewModel.uiState.value.snackbarMessage
    }

    @Test
    fun `the journal records the size and date the provider reports for the deleted file`() = runTest {
        val file = photo("IMG_0001.JPG", bytes = 1200, modified = 1_700_000_000_000L)

        deleteThenDie(file)

        assertEquals(
            mapOf(documentUri("IMG_0001.JPG") to FileIdentity(1200, 1_700_000_000_000L)),
            settings.pendingDeleteIdentities.first(),
        )
    }

    @Test
    fun `Delete now leaves a different photo that took the deleted photo's place and says so`() = runTest {
        val file = photo("IMG_0001.JPG", bytes = 1200, modified = 1_700_000_000_000L)
        deleteThenDie(file)

        // Before the next launch the camera restarted its numbering: a new shot at the same path.
        photo("IMG_0001.JPG", bytes = 3400, modified = 1_700_000_900_000L)

        val message = deleteNowAfterRestart(expectMessage = true)

        assertTrue("the new photo must survive", file.exists())
        assertEquals(3400L, file.length())
        assertNotNull(message)
        assertTrue(message!!, message.startsWith("Kept IMG_0001.JPG"))
    }

    @Test
    fun `Delete now deletes the photo that was deleted when it is still the same file`() = runTest {
        val file = photo("IMG_0002.JPG", bytes = 1500, modified = 1_700_000_000_000L)
        deleteThenDie(file)

        val message = deleteNowAfterRestart(expectMessage = false)

        assertFalse("the deleted photo must go", file.exists())
        assertNull(message)
    }

    @Test
    fun `Delete now keeps a photo journalled by an earlier version without an identity`() = runTest {
        val file = photo("IMG_0003.JPG", bytes = 1500, modified = 1_700_000_000_000L)
        // What an earlier version wrote: the URI alone.
        settings.addPendingDeletes(listOf(documentUri("IMG_0003.JPG")))

        val message = deleteNowAfterRestart(expectMessage = true)

        assertTrue("an unverifiable entry must not delete anything", file.exists())
        assertTrue(message.orEmpty(), message.orEmpty().startsWith("Kept IMG_0003.JPG"))
    }
}
