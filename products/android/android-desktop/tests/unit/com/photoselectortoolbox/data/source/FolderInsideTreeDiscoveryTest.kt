package com.photoselectortoolbox.data.source

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.photoselectortoolbox.conformance.DirectoryDocumentsProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
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
 * *Open Selection* (backlog 2026-11-11) opens the Selection folder as a document inside the
 * shoot's granted tree. Discovery must read that folder — not the whole tree it belongs to, which
 * would show the shoot again with the Selection skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderInsideTreeDiscoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val treeUri: Uri =
        DocumentsContract.buildTreeDocumentUri(DirectoryDocumentsProvider.AUTHORITY, DirectoryDocumentsProvider.ROOT_ID)

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(DirectoryDocumentsProvider::class.java)
            .create(DirectoryDocumentsProvider.AUTHORITY)
    }

    @Test
    fun `a folder given as a document inside a tree is read from that folder`() = runTest {
        val rootDir = tmp.newFolder("shoot")
        File(rootDir, "IMG_0001.JPG").writeBytes(byteArrayOf(1, 2))
        File(rootDir, "Selection").mkdirs()
        File(rootDir, "Selection/IMG_0002.JPG").writeBytes(byteArrayOf(3, 4))
        DirectoryDocumentsProvider.root = rootDir
        val source = LocalImageSourceImpl(context, Dispatchers.IO)

        val shoot = source.discoverImages(treeUri).last().map { it.fileName }
        val selectionUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "${DirectoryDocumentsProvider.ROOT_ID}/Selection")
        val selection = source.discoverImages(selectionUri).last().map { it.fileName }

        assertEquals("the shoot skips its Selection", listOf("IMG_0001.JPG"), shoot)
        assertEquals("the Selection opened as a folder shows its own photos", listOf("IMG_0002.JPG"), selection)
    }
}
