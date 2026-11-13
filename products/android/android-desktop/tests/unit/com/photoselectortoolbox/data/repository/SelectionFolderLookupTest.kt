package com.photoselectortoolbox.data.repository

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselector.saf.FolderAccessManager
import com.photoselector.core.storage.SourceKind
import com.photoselector.saf.SafGrantBackend
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.KeptFiles
import com.photoselectortoolbox.data.model.SelectionFolderState
import com.photoselectortoolbox.data.repository.SafTransferTest.FakeSafDocumentsProvider
import com.photoselectortoolbox.data.source.LocalImageSource
import com.photoselectortoolbox.viewmodel.DoneSelection
import com.photoselectortoolbox.viewmodel.SafeToFormatLine
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private fun documentUriFor(relativePath: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, "${FakeSafDocumentsProvider.ROOT_ID}/$relativePath")

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
            mapOf(
                "root/Selection/IMG_0002.JPG" to 120L,
                "root/Selection/RAW/IMG_0003.ARW" to 300L,
                "root/Selection/JPEG/IMG_0003.JPG" to 90L,
            ),
            folder.filesByDocumentId,
        )
        // A document inside the open tree, so it opens as a working folder under the same grant.
        val uri = Uri.parse(folder.uri)
        assertEquals(DocumentsContract.getTreeDocumentId(treeUri), DocumentsContract.getTreeDocumentId(uri))
        assertEquals("${FakeSafDocumentsProvider.ROOT_ID}/Selection", DocumentsContract.getDocumentId(uri))
        assertEquals(true, folder.onSourceVolume)
    }

    // REQ-AND-WORKFLOW.02 (backlog 2026-11-12: safe to format matched kept files by name only)
    @Test
    fun `a same-named file in a Selection subfolder does not stand in for a kept file that is missing`() = runTest {
        // An earlier shoot left RAW/IMG_0003.ARW in the Selection, same name and size.
        file("Selection/RAW/IMG_0003.ARW", 300)
        file("IMG_0003.ARW", 300)
        val item = ImageItem(
            uri = documentUriFor("IMG_0003.ARW").toString(),
            fileName = "IMG_0003.ARW",
            fileSize = 300,
            lastModified = 1L,
            mimeType = "image/x-sony-arw",
        )
        val copied = repository.copyBundle(context, item, treeUri, sorting = false)
        assertTrue(copied.success)
        // The copy the transfer wrote is gone (deleted, or the card was pulled mid-write).
        assertTrue(File(rootDir, "Selection/IMG_0003.ARW").delete())

        // What the selector records for a successful transfer.
        val kept = KeptFiles.of(item.allFiles, copied.destinationUris)
        val folder = repository.selectionFolder(context, treeUri)!!.copy(onSourceVolume = false)

        assertEquals(
            SafeToFormatLine.Missing(1),
            DoneSelection.line(SourceKind.REMOVABLE, kept, checking = false, selection = folder),
        )
    }

    // REQ-AND-WORKFLOW.02: every member of a sorted bundle is checked against its own copy
    @Test
    fun `each member of a filed bundle is verified as the copy written for it`() = runTest {
        file("Selection/JPEG/DSC_0003.JPG", 80) // an earlier shoot's file of the same name
        file("DSC_0003.JPG", 80)
        file("DSC_0003.ARW", 300)
        file("DSC_0003.xmp", 20)
        val item = ImageItem(
            uri = documentUriFor("DSC_0003.JPG").toString(),
            fileName = "DSC_0003.JPG",
            fileSize = 80,
            lastModified = 1L,
            mimeType = "image/jpeg",
            companions = listOf(
                CompanionFile(documentUriFor("DSC_0003.ARW").toString(), "DSC_0003.ARW", fileSize = 300),
                CompanionFile(documentUriFor("DSC_0003.xmp").toString(), "DSC_0003.xmp", fileSize = 20),
            ),
        )
        val copied = repository.copyBundle(context, item, treeUri, sorting = true)
        assertTrue(copied.success)
        val kept = KeptFiles.of(item.allFiles, copied.destinationUris)
        suspend fun verdict() = DoneSelection.line(
            SourceKind.REMOVABLE,
            kept,
            checking = false,
            selection = repository.selectionFolder(context, treeUri)!!.copy(onSourceVolume = false),
        )

        // The JPEG took a "-2" beside the earlier shoot's; all three copies are there.
        assertTrue(File(rootDir, "Selection/JPEG/DSC_0003-2.JPG").exists())
        assertEquals(SafeToFormatLine.Safe, verdict())

        // This session's JPEG copy goes; the earlier shoot's DSC_0003.JPG does not stand in for it.
        assertTrue(File(rootDir, "Selection/JPEG/DSC_0003-2.JPG").delete())
        assertEquals(SafeToFormatLine.Missing(1), verdict())
    }

    @Test
    fun `a transfer whose destinations do not line up with its members never verifies`() {
        val files = listOf(CompanionFile("content://x/a", "A.JPG", 10), CompanionFile("content://x/b", "A.ARW", 20))

        val kept = KeptFiles.of(files, listOf("content://x/only-one"), documentIdOf = { "id-of-$it" })

        assertEquals(2, kept.size)
        assertTrue(kept.keys.all { it.startsWith(KeptFiles.UNRECORDED) })
        assertEquals(
            SafeToFormatLine.Missing(2),
            DoneSelection.line(
                SourceKind.REMOVABLE,
                kept,
                checking = false,
                selection = SelectionFolderState("content://x/sel", "Selection", kept.mapKeys { "id-of-${it.key}" }, onSourceVolume = false),
            ),
        )
    }

    @Test
    fun `no Selection folder yet is null`() = runTest {
        file("IMG_0001.JPG", 100)

        assertNull(repository.selectionFolder(context, treeUri))
    }
}
