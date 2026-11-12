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
import kotlinx.coroutines.withTimeout
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
 * A relaunch in a warm process (configuration change, DeX window resize, a second activity)
 * builds a [SelectorViewModel] on a DataStore that already holds the last folder. With an
 * immediate main dispatcher the `lastFolderUri` collection in `init` reopens that folder inside
 * the constructor, so every property the reopen path reads must exist by then (backlog
 * 2026-11-11: `resetSession` cleared `loadedExifCache`, declared below `init`, and threw).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectorViewModelInitOrderTest {

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

        val file = File(rootDir, "IMG_0001.JPG").apply { writeBytes(ByteArray(64) { 7 }) }
        listed = listOf(
            ImageItem(
                uri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    "${FakeSafDocumentsProvider.ROOT_ID}/${file.name}",
                ).toString(),
                fileName = file.name,
                fileSize = file.length(),
                lastModified = file.lastModified(),
                mimeType = "image/jpeg",
            ),
        )
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
        runBlocking { settings.setLastFolderUri(null) }
        Dispatchers.resetMain()
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
}
