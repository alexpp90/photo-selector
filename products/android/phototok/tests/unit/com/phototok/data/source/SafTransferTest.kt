package com.phototok.data.source

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.phototok.domain.PhotoFolders
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.junit.Assert.fail
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
 * PhotoTok's adapter over the shared SAF engine (`:saf`, REQ-TOK-ARCH.15): the folder it files
 * into (`Selection`, `PhotoTok_LeftSwipe`), single-file copy and move feedback, the marker the
 * feed walk skips, and the per-file bundle policy. The engine's data-safety contract
 * (collision-free naming, size verification, partial moves, D-5 locking, the two-permit
 * semaphore) is tested once in `:saf` (`PerFileSafTransferTest`, `SafTransferContractTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SafTransferTest {

    /**
     * SAF-shaped [android.content.ContentProvider] backed by a temporary directory
     * with hooks to simulate provider auto-renames, truncated writes, delete
     * failures, and [Document.FLAG_SUPPORTS_MOVE].
     */
    class FakeSafDocumentsProvider : android.content.ContentProvider() {
        companion object {
            const val AUTHORITY = "com.phototok.test.saftransfer"
            const val ROOT_ID = "root"

            @Volatile
            var rootDir: File = File(".")

            @Volatile
            var advertiseSupportsMove: Boolean = false

            @Volatile
            var failDeletes: Boolean = false

            /** Milliseconds each file creation takes, to widen race windows in concurrency tests. */
            @Volatile
            var createDelayMs: Long = 0L

            @Volatile
            var autoRenameOnCreate: ((String) -> String)? = null

            @Volatile
            var reportedSizeOverride: ((File) -> Long?)? = null

            val moveDocumentCalls = AtomicInteger(0)
            val childrenQueryCalls = AtomicInteger(0)

            fun reset(root: File) {
                rootDir = root
                advertiseSupportsMove = false
                failDeletes = false
                createDelayMs = 0L
                autoRenameOnCreate = null
                reportedSizeOverride = null
                moveDocumentCalls.set(0)
                childrenQueryCalls.set(0)
            }
        }

        override fun onCreate(): Boolean = true

        private fun documentIdOf(uri: Uri): String {
            val s = uri.pathSegments
            val i = s.indexOf("document")
            return if (i >= 0 && i + 1 < s.size) s[i + 1] else s[1]
        }

        private fun fileForDocId(documentId: String): File {
            require(documentId == ROOT_ID || documentId.startsWith("$ROOT_ID/")) {
                "Unexpected documentId: $documentId"
            }
            return if (documentId == ROOT_ID) {
                rootDir
            } else {
                File(rootDir, documentId.removePrefix("$ROOT_ID/"))
            }
        }

        private fun docIdForFile(file: File): String {
            val rel = file.relativeTo(rootDir).invariantSeparatorsPath
            return if (rel.isEmpty()) ROOT_ID else "$ROOT_ID/$rel"
        }

        private fun mimeForFile(file: File): String = when {
            file.isDirectory -> Document.MIME_TYPE_DIR
            else -> MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        }

        private fun addRow(cursor: MatrixCursor, cols: Array<out String>, file: File) {
            val flags = buildFlags(file)
            val size = reportedSizeOverride?.invoke(file) ?: file.length()
            cursor.addRow(
                cols.map { col ->
                    when (col) {
                        Document.COLUMN_DOCUMENT_ID -> docIdForFile(file)
                        Document.COLUMN_DISPLAY_NAME -> file.name
                        Document.COLUMN_MIME_TYPE -> mimeForFile(file)
                        Document.COLUMN_SIZE -> size
                        Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        Document.COLUMN_FLAGS -> flags
                        else -> null
                    }
                }.toTypedArray<Any?>()
            )
        }

        private fun buildFlags(file: File): Int {
            var flags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
            if (file.isDirectory) {
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            }
            if (advertiseSupportsMove) {
                flags = flags or Document.FLAG_SUPPORTS_MOVE
            }
            return flags
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = query(uri, projection, null, null)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: android.os.Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            val cols = projection ?: arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_FLAGS,
            )
            val cursor = MatrixCursor(cols)
            val segments = uri.pathSegments
            val isChildren = segments.lastOrNull() == "children"
            if (isChildren) {
                childrenQueryCalls.incrementAndGet()
                val parentId = segments[segments.size - 2]
                val dir = fileForDocId(parentId)
                dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                    if (child.exists()) addRow(cursor, cols, child)
                }
            } else {
                val docId = documentIdOf(uri)
                val f = fileForDocId(docId)
                if (f.exists()) {
                    addRow(cursor, cols, f)
                }
            }
            return cursor
        }

        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
            @Suppress("DEPRECATION")
            val uri = extras?.getParcelable<Uri>("uri")
            val out = android.os.Bundle()
            return when (method) {
                "android:createDocument" -> {
                    val parentUri = requireNotNull(uri)
                    val parentDocId = documentIdOf(parentUri)
                    val mimeType = extras.getString(Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
                    val displayName = requireNotNull(extras.getString(Document.COLUMN_DISPLAY_NAME))
                    val createdId = handleCreateDocument(parentDocId, mimeType, displayName)
                    val createdUri = if (DocumentsContract.isTreeUri(parentUri)) {
                        DocumentsContract.buildDocumentUriUsingTree(parentUri, createdId)
                    } else {
                        DocumentsContract.buildDocumentUri(AUTHORITY, createdId)
                    }
                    out.putParcelable("uri", createdUri)
                    out
                }
                "android:deleteDocument" -> {
                    val targetUri = requireNotNull(uri)
                    handleDeleteDocument(documentIdOf(targetUri))
                    out
                }
                "android:moveDocument" -> {
                    val sourceUri = requireNotNull(uri)
                    @Suppress("DEPRECATION")
                    val sourceParentUri = requireNotNull(
                        extras.getParcelable<Uri>("parentUri")
                            ?: extras.getParcelable<Uri>("android.content.extra.PARENT_URI")
                    )
                    @Suppress("DEPRECATION")
                    val targetParentUri = requireNotNull(
                        extras.getParcelable<Uri>("android.content.extra.TARGET_URI")
                            ?: extras.getParcelable<Uri>("targetUri")
                    )
                    val movedId = handleMoveDocument(
                        documentIdOf(sourceUri),
                        documentIdOf(sourceParentUri),
                        documentIdOf(targetParentUri),
                    )
                    val movedUri = if (DocumentsContract.isTreeUri(targetParentUri)) {
                        DocumentsContract.buildDocumentUriUsingTree(targetParentUri, movedId)
                    } else {
                        DocumentsContract.buildDocumentUri(AUTHORITY, movedId)
                    }
                    out.putParcelable("uri", movedUri)
                    out
                }
                else -> super.call(method, arg, extras)
            }
        }

        private fun handleCreateDocument(
            parentDocumentId: String,
            mimeType: String,
            displayName: String,
        ): String {
            val parent = fileForDocId(parentDocumentId)
            val actualName = if (mimeType == Document.MIME_TYPE_DIR) {
                displayName
            } else {
                autoRenameOnCreate?.invoke(displayName) ?: displayName
            }
            val target = File(parent, actualName)
            if (createDelayMs > 0L) Thread.sleep(createDelayMs)
            if (mimeType == Document.MIME_TYPE_DIR) {
                if (!target.exists() && !target.mkdirs()) {
                    throw FileNotFoundException("Could not mkdir $target")
                }
            } else {
                target.parentFile?.mkdirs()
                if (!target.exists() && !target.createNewFile()) {
                    throw FileNotFoundException("Could not create $target")
                }
            }
            return docIdForFile(target)
        }

        private fun handleDeleteDocument(documentId: String) {
            if (failDeletes) {
                throw SecurityException("Simulated read-only delete failure for $documentId")
            }
            val file = fileForDocId(documentId)
            if (!file.delete()) {
                throw FileNotFoundException("Could not delete $documentId")
            }
        }

        private fun handleMoveDocument(
            sourceDocumentId: String,
            sourceParentDocumentId: String,
            targetParentDocumentId: String,
        ): String {
            moveDocumentCalls.incrementAndGet()
            val src = fileForDocId(sourceDocumentId)
            val targetDir = fileForDocId(targetParentDocumentId)
            targetDir.mkdirs()
            val dest = File(targetDir, src.name)
            if (dest.exists() || !src.renameTo(dest)) {
                throw IllegalStateException("Atomic rename failed from $src to $dest")
            }
            return docIdForFile(dest)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = fileForDocId(documentIdOf(uri))
            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(file, pfdMode)
        }

        override fun getType(uri: Uri): String = mimeForFile(fileForDocId(documentIdOf(uri)))
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: android.content.ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var source: LocalImageSourceImpl

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(
        FakeSafDocumentsProvider.AUTHORITY,
        FakeSafDocumentsProvider.ROOT_ID,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        rootDir = tmp.newFolder("shoot")
        FakeSafDocumentsProvider.reset(rootDir)
        Robolectric.buildContentProvider(FakeSafDocumentsProvider::class.java)
            .create(FakeSafDocumentsProvider.AUTHORITY)

        source = LocalImageSourceImpl(
            context = context,
            androidExifReader = AndroidExifReader(),
            mediaStoreReader = MediaStoreReader(),
        )
        source.ioDispatcher = UnconfinedTestDispatcher()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun documentUriFor(relativePath: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            "${FakeSafDocumentsProvider.ROOT_ID}/$relativePath",
        )

    // REQ-TOK-ARCH.15: feedback names (B-7) for a single move whose source cannot be read.
    @Test
    fun `a single move of a missing source reports Copy step failed and touches nothing`() = runTest {
        val moved = source.moveImageWithResult(documentUriFor("NOPE.JPG"), treeUri, true, PhotoFolders.SELECTION)
        val copied = source.copyImageWithResult(documentUriFor("NOPE.JPG"), treeUri, true, PhotoFolders.SELECTION)

        assertFalse(moved.success)
        assertEquals("Copy step failed", moved.error)
        assertEquals("Could not write to the Selection folder", copied.error)
        assertFalse(File(rootDir, "Selection").exists())
    }

    @Test
    fun `a blank subfolder name files into Selection`() = runTest {
        File(rootDir, "IMG_0001.JPG").writeText("payload")

        assertTrue(source.copyImage(documentUriFor("IMG_0001.JPG"), treeUri, sorting = false, subfolderName = "  "))

        assertTrue(File(rootDir, "Selection/IMG_0001.JPG").exists())
    }

    // REQ-TOK-ARCH.15, REQ-TOK-ARCH.16
    @Test
    fun `a move whose original cannot be removed is partial and keeps both files`() = runTest {
        File(rootDir, "IMG_0003.JPG").writeText("payload")
        FakeSafDocumentsProvider.failDeletes = true

        val result = source.moveImageDetailed(documentUriFor("IMG_0003.JPG"), treeUri, true, PhotoFolders.SELECTION)

        assertTrue(result.partial)
        assertEquals("Copied to Selection — original could not be removed", result.error)
        assertTrue(File(rootDir, "IMG_0003.JPG").exists())
        assertTrue(File(rootDir, "Selection/JPEG/IMG_0003.JPG").exists())
    }

    // PhotoTok moves a bundle file by file: a member that fails does not roll back the others.
    @Test
    fun `transferBundleDetailed moves each member on its own under one shared suffix`() = runTest {
        File(rootDir, "Selection/RAW").mkdirs()
        File(rootDir, "Selection/RAW/IMG_0004.CR3").writeText("already-there")
        File(rootDir, "IMG_0004.CR3").writeText("raw")
        File(rootDir, "IMG_0004.JPG").writeText("jpg")

        val results = source.transferBundleDetailed(
            listOf(documentUriFor("IMG_0004.CR3"), documentUriFor("IMG_0004.JPG"), documentUriFor("IMG_0004.CR3.xmp")),
            treeUri, sorting = true, subfolderName = PhotoFolders.SELECTION, isMove = true,
        )

        assertTrue(results[0].success)
        assertTrue(results[1].success)
        assertFalse("the missing sidecar fails alone", results[2].success)
        assertEquals("raw", File(rootDir, "Selection/RAW/IMG_0004-2.CR3").readText())
        assertEquals("jpg", File(rootDir, "Selection/JPEG/IMG_0004-2.JPG").readText())
        assertEquals("already-there", File(rootDir, "Selection/RAW/IMG_0004.CR3").readText())
    }

    @Test
    fun `deleteImage unlinks the document`() = runTest {
        File(rootDir, "IMG_0005.JPG").writeText("payload")

        assertTrue(source.deleteImage(documentUriFor("IMG_0005.JPG")))
        assertFalse(File(rootDir, "IMG_0005.JPG").exists())
    }

    // ── Default destination: always the Selection subfolder (lab 2026-10-18) ──
    //
    // With File Sorting off (the PHONE_LIBRARY / CLOUD_OTHER default) a copy landed in the
    // source folder itself as `DSC_0007-2.JPG` next to the original, and the marker file went
    // into the source folder. Sorting only adds RAW/ and JPEG/ inside Selection.

    private fun seedShot(vararg names: String) = names.forEach { File(rootDir, it).writeText("payload-$it") }

    private fun assertSourceFolderUntouched(vararg originals: String) {
        val expected = (originals.toList() + PhotoFolders.SELECTION).sorted()
        assertEquals(expected, rootDir.list()!!.sorted())
    }

    // REQ-TOK-FEATURE.05
    @Test
    fun `default copy of a single file lands flat in Selection, never next to the original`() = runTest {
        seedShot("DSC_0007.JPG")

        val result = source.copyImageWithResult(documentUriFor("DSC_0007.JPG"), treeUri, false, PhotoFolders.SELECTION)

        assertTrue(result.success)
        assertEquals("payload-DSC_0007.JPG", File(rootDir, "Selection/DSC_0007.JPG").readText())
        assertTrue(File(rootDir, "Selection/${LocalImageSourceImpl.SELECTION_MARKER_FILE}").exists())
        assertSourceFolderUntouched("DSC_0007.JPG")
    }

    // REQ-TOK-FEATURE.05
    @Test
    fun `default move of a single file lands flat in Selection`() = runTest {
        seedShot("DSC_0007.JPG")

        val result = source.moveImageWithResult(documentUriFor("DSC_0007.JPG"), treeUri, false, PhotoFolders.SELECTION)

        assertTrue(result.success)
        assertEquals("payload-DSC_0007.JPG", File(rootDir, "Selection/DSC_0007.JPG").readText())
        assertEquals(listOf(PhotoFolders.SELECTION), rootDir.list()!!.toList())
    }

    // REQ-TOK-FEATURE.05
    @Test
    fun `default copy of a RAW+JPEG bundle lands flat in Selection with original names`() = runTest {
        seedShot("DSC_0004.ARW", "DSC_0004.JPG")

        val results = source.transferBundle(
            sourceUris = listOf(documentUriFor("DSC_0004.ARW"), documentUriFor("DSC_0004.JPG")),
            destFolderUri = treeUri,
            sorting = false,
            subfolderName = PhotoFolders.SELECTION,
            isMove = false,
        )

        assertTrue(results.all { it.success })
        assertEquals("payload-DSC_0004.ARW", File(rootDir, "Selection/DSC_0004.ARW").readText())
        assertEquals("payload-DSC_0004.JPG", File(rootDir, "Selection/DSC_0004.JPG").readText())
        assertSourceFolderUntouched("DSC_0004.ARW", "DSC_0004.JPG")
    }

    // REQ-TOK-FEATURE.05
    @Test
    fun `default move of a RAW+JPEG bundle lands flat in Selection`() = runTest {
        seedShot("DSC_0004.ARW", "DSC_0004.JPG")

        val results = source.transferBundle(
            sourceUris = listOf(documentUriFor("DSC_0004.ARW"), documentUriFor("DSC_0004.JPG")),
            destFolderUri = treeUri,
            sorting = false,
            subfolderName = PhotoFolders.SELECTION,
            isMove = true,
        )

        assertTrue(results.all { it.success })
        assertEquals(
            listOf("DSC_0004.ARW", "DSC_0004.JPG", LocalImageSourceImpl.SELECTION_MARKER_FILE).sorted(),
            File(rootDir, "Selection").list()!!.sorted(),
        )
        assertEquals(listOf(PhotoFolders.SELECTION), rootDir.list()!!.toList())
    }

    // REQ-TOK-FEATURE.05
    @Test
    fun `sorting on puts a single copy and a bundle into the type subfolders of Selection`() = runTest {
        seedShot("DSC_0007.JPG", "DSC_0004.ARW", "DSC_0004.JPG")

        assertTrue(source.copyImageWithResult(documentUriFor("DSC_0007.JPG"), treeUri, true, PhotoFolders.SELECTION).success)
        val bundle = source.transferBundle(
            sourceUris = listOf(documentUriFor("DSC_0004.ARW"), documentUriFor("DSC_0004.JPG")),
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
            isMove = false,
        )

        assertTrue(bundle.all { it.success })
        assertTrue(File(rootDir, "Selection/JPEG/DSC_0007.JPG").exists())
        assertTrue(File(rootDir, "Selection/RAW/DSC_0004.ARW").exists())
        assertTrue(File(rootDir, "Selection/JPEG/DSC_0004.JPG").exists())
        assertSourceFolderUntouched("DSC_0007.JPG", "DSC_0004.ARW", "DSC_0004.JPG")
    }

    // REQ-TOK-FEATURE.05, REQ-TOK-GESTURE.03 (left swipe copy/move to PhotoTok_LeftSwipe)
    @Test
    fun `default left-swipe copy lands flat in PhotoTok_LeftSwipe`() = runTest {
        seedShot("DSC_0007.JPG")

        val result = source.copyImageWithResult(documentUriFor("DSC_0007.JPG"), treeUri, false, PhotoFolders.LEFT_SWIPE)

        assertTrue(result.success)
        assertTrue(File(rootDir, "${PhotoFolders.LEFT_SWIPE}/DSC_0007.JPG").exists())
        assertEquals(listOf("DSC_0007.JPG", PhotoFolders.LEFT_SWIPE).sorted(), rootDir.list()!!.sorted())
    }

    // REQ-TOK-FEATURE.05, REQ-TOK-FEATURE.06
    @Test
    fun `after a default copy the feed shows only the original and the Selection viewer shows the copy`() = runTest {
        seedShot("DSC_0007.JPG")
        assertTrue(source.copyImageWithResult(documentUriFor("DSC_0007.JPG"), treeUri, false, PhotoFolders.SELECTION).success)

        val feed = source.discoverImages(treeUri).toList().last()
        assertEquals(listOf("DSC_0007.JPG"), feed.map { it.fileName })
        assertEquals(documentUriFor("DSC_0007.JPG").toString(), feed.single().uri)

        val listing = source.listSelectionImages(treeUri, PhotoFolders.SELECTION)
        assertTrue(listing is SelectionListing.Available)
        assertEquals(listOf("DSC_0007.JPG"), (listing as SelectionListing.Available).images.map { it.fileName })
    }
}
