package com.photoselectortoolbox.data.repository

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.SafGrantBackend
import com.photoselectortoolbox.data.repository.SafTransferTest.FakeSafDocumentsProvider
import com.photoselectortoolbox.data.source.LocalImageSource
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Where the kept photos are, for the end of a cull (backlog 2026-11-11, REQ-AND-WORKFLOW.02):
 * `selectionFolder` finds the Selection folder a transfer from the open folder files into — by
 * listing, never by taking a document id apart — and reports its files with their sizes, the
 * `RAW/` and `JPEG/` subfolders included, and whether it is on the source's volume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectionFolderLookupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var repository: ImageRepositoryImpl

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
        rootDir = tmp.newFolder("shoot")
        FakeSafDocumentsProvider.reset(rootDir)
        Robolectric.buildContentProvider(FakeSafDocumentsProvider::class.java)
            .create(FakeSafDocumentsProvider.AUTHORITY)
        val settings = SettingsRepository(context, FolderAccessManager(NoGrants()))
        runBlocking {
            settings.setCustomSelectionDestinationUri(null)
            settings.setSelectionFolderName("Selection")
        }
        repository = ImageRepositoryImpl(
            localImageSource = mockk<LocalImageSource>(relaxed = true),
            androidExifReader = AndroidExifReader(),
            mediaStoreReader = MediaStoreReader(),
            settingsRepository = settings,
            folderAccessManager = FolderAccessManager(NoGrants()),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun file(path: String, bytes: Int) = File(rootDir, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(bytes) { 1 })
    }

    @Test
    fun `the Selection folder is found with every kept file and its size, subfolders included`() = runTest {
        file("IMG_0001.ARW", 500)
        file("Selection/IMG_0002.JPG", 120)
        file("Selection/RAW/IMG_0003.ARW", 300)
        file("Selection/JPEG/IMG_0003.JPG", 90)

        val folder = repository.selectionFolder(context, treeUri)

        assertNotNull(folder)
        assertEquals("Selection", folder!!.name)
        assertEquals(
            mapOf("IMG_0002.JPG" to 120L, "IMG_0003.ARW" to 300L, "IMG_0003.JPG" to 90L),
            folder.filesWithSizes,
        )
        // A document inside the open tree, so it opens as a working folder under the same grant.
        val uri = Uri.parse(folder.uri)
        assertEquals(DocumentsContract.getTreeDocumentId(treeUri), DocumentsContract.getTreeDocumentId(uri))
        assertEquals("${FakeSafDocumentsProvider.ROOT_ID}/Selection", DocumentsContract.getDocumentId(uri))
        assertEquals(true, folder.onSourceVolume)
    }

    @Test
    fun `no Selection folder yet is null`() = runTest {
        file("IMG_0001.JPG", 100)

        assertNull(repository.selectionFolder(context, treeUri))
    }
}
