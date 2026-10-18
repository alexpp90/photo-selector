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
 * Unit tests for non-destructive SAF file transfers in [LocalImageSourceImpl]
 * (REQ-TOK-ARCH.15, REQ-TOK-ARCH.16, `docs/shared/FILE_OPERATIONS.md`).
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

    // ── 1. Collision-free bundle naming ──────────────────────────────────

    // REQ-TOK-ARCH.15
    @Test
    fun `collision in Selection JPEG suffixes entire CR3 + JPG + CR3 xmp + -Edit tif bundle with -2`() {
        val bundle = listOf(
            "IMG_0001.CR3",
            "IMG_0001.JPG",
            "IMG_0001.CR3.xmp",
            "IMG_0001-Edit.tif",
        )
        val existingByFolder = mapOf(
            LocalImageSourceImpl.SelectionSubfolder.RAW.name to emptySet(),
            LocalImageSourceImpl.SelectionSubfolder.JPEG.name to setOf("img_0001.jpg"),
        )

        val resolved = LocalImageSourceImpl.resolveCollisionFreeBundleNames(
            fileNames = bundle,
            primaryStem = "IMG_0001",
            existingNamesByFolder = existingByFolder,
            folderForFile = { LocalImageSourceImpl.selectionSubfolder(it).name },
        )

        assertEquals("IMG_0001-2.CR3", resolved["IMG_0001.CR3"])
        assertEquals("IMG_0001-2.JPG", resolved["IMG_0001.JPG"])
        assertEquals("IMG_0001-2.CR3.xmp", resolved["IMG_0001.CR3.xmp"])
        assertEquals("IMG_0001-2-Edit.tif", resolved["IMG_0001-Edit.tif"])
    }

    // REQ-TOK-ARCH.15
    @Test
    fun `collision resolution skips -2 when -2 already exists in any target subfolder`() {
        val bundle = listOf("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001.xmp")
        val existingByFolder = mapOf(
            LocalImageSourceImpl.SelectionSubfolder.RAW.name to setOf("IMG_0001-2.CR3"),
            LocalImageSourceImpl.SelectionSubfolder.JPEG.name to setOf("IMG_0001.JPG"),
            LocalImageSourceImpl.SelectionSubfolder.ROOT.name to emptySet(),
        )

        val resolved = LocalImageSourceImpl.resolveCollisionFreeBundleNames(
            fileNames = bundle,
            primaryStem = "IMG_0001",
            existingNamesByFolder = existingByFolder,
            folderForFile = { LocalImageSourceImpl.selectionSubfolder(it).name },
        )

        assertEquals("IMG_0001-3.CR3", resolved["IMG_0001.CR3"])
        assertEquals("IMG_0001-3.JPG", resolved["IMG_0001.JPG"])
        assertEquals("IMG_0001-3.xmp", resolved["IMG_0001.xmp"])
    }

    // REQ-TOK-ARCH.15
    @Test
    fun `transferBundle moves two identically named CR3+JPG+XMP bundles in sequence and keeps both paired`() = runTest {
        val card1 = File(rootDir, "card1").apply { mkdirs() }
        File(card1, "IMG_0001.CR3").writeText("raw-card-1")
        File(card1, "IMG_0001.JPG").writeText("jpg-card-1")
        File(card1, "IMG_0001.CR3.xmp").writeText("xmp-card-1")

        val card2 = File(rootDir, "card2").apply { mkdirs() }
        File(card2, "IMG_0001.CR3").writeText("raw-card-2-longer")
        File(card2, "IMG_0001.JPG").writeText("jpg-card-2-longer")
        File(card2, "IMG_0001.CR3.xmp").writeText("xmp-card-2-longer")

        val firstBundle = listOf(
            documentUriFor("card1/IMG_0001.CR3"),
            documentUriFor("card1/IMG_0001.JPG"),
            documentUriFor("card1/IMG_0001.CR3.xmp"),
        )
        val firstResults = source.transferBundle(
            sourceUris = firstBundle,
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
            isMove = true,
        )
        assertTrue(firstResults.all { it.success })

        val secondBundle = listOf(
            documentUriFor("card2/IMG_0001.CR3"),
            documentUriFor("card2/IMG_0001.JPG"),
            documentUriFor("card2/IMG_0001.CR3.xmp"),
        )
        val secondResults = source.transferBundle(
            sourceUris = secondBundle,
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
            isMove = true,
        )
        assertTrue(secondResults.all { it.success })

        // First bundle untouched:
        assertEquals("raw-card-1", File(rootDir, "Selection/RAW/IMG_0001.CR3").readText())
        assertEquals("jpg-card-1", File(rootDir, "Selection/JPEG/IMG_0001.JPG").readText())
        assertEquals("xmp-card-1", File(rootDir, "Selection/RAW/IMG_0001.CR3.xmp").readText())

        // Second bundle suffixed with -2 across all three files:
        assertEquals("raw-card-2-longer", File(rootDir, "Selection/RAW/IMG_0001-2.CR3").readText())
        assertEquals("jpg-card-2-longer", File(rootDir, "Selection/JPEG/IMG_0001-2.JPG").readText())
        assertEquals("xmp-card-2-longer", File(rootDir, "Selection/RAW/IMG_0001-2.CR3.xmp").readText())

        // All 6 source files were deleted after verified copies:
        assertFalse(File(card1, "IMG_0001.CR3").exists())
        assertFalse(File(card2, "IMG_0001.CR3").exists())
    }

    // REQ-TOK-ARCH.15 / B-6: derivatives and both sidecar styles keep their photo's name.
    @Test
    fun `a collision on the RAW gives the JPEG, the edit and both sidecars the same suffix`() = runTest {
        val dest = File(rootDir, "Selection/RAW").apply { mkdirs() }
        File(dest, "IMG_0001.CR3").writeText("already-there")
        val src = File(rootDir, "card").apply { mkdirs() }
        val names = listOf(
            "IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001-Edit.tif", "IMG_0001.xmp", "IMG_0001.CR3.xmp",
        )
        names.forEach { File(src, it).writeText("payload-$it") }

        val results = source.transferBundle(
            sourceUris = names.map { documentUriFor("card/$it") },
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
            isMove = true,
        )

        assertTrue(results.all { it.success })
        assertEquals("already-there", File(dest, "IMG_0001.CR3").readText())
        assertEquals("payload-IMG_0001.CR3", File(dest, "IMG_0001-2.CR3").readText())
        assertEquals("payload-IMG_0001.JPG", File(rootDir, "Selection/JPEG/IMG_0001-2.JPG").readText())
        assertEquals("payload-IMG_0001-Edit.tif", File(dest, "IMG_0001-2-Edit.tif").readText())
        assertEquals("payload-IMG_0001.CR3.xmp", File(dest, "IMG_0001-2.CR3.xmp").readText())
        assertEquals("payload-IMG_0001.xmp", File(rootDir, "Selection/IMG_0001-2.xmp").readText())
    }

    // ── 2. Size mismatch keeps source and cleans up destination ──────────

    // REQ-TOK-ARCH.15
    @Test
    fun `size mismatch after copy deletes partial destination and never deletes source on move`() = runTest {
        val srcFile = File(rootDir, "IMG_0002.JPG").apply { writeText("complete-jpeg-payload-12345") }
        FakeSafDocumentsProvider.reportedSizeOverride = { file ->
            if (file.path.contains("Selection")) 5L else null
        }

        val result = source.moveImageWithResult(
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
        )

        assertFalse(result.success)
        assertFalse(result.partial)
        assertNull(result.destinationUri)
        assertEquals("Copied file size mismatch", result.error)
        assertTrue("Original source file must remain intact", srcFile.exists())
        assertEquals("complete-jpeg-payload-12345", srcFile.readText())
        assertFalse(
            "Truncated destination file must be cleaned up",
            File(rootDir, "Selection/JPEG/IMG_0002.JPG").exists(),
        )
    }

    // ── 3. Source delete failure after verified copy → partial ───────────

    // REQ-TOK-ARCH.15, REQ-TOK-ARCH.16
    @Test
    fun `source delete failure after verified copy returns partial result and keeps destination copy`() = runTest {
        val payload = "verified-photo-bytes"
        val srcFile = File(rootDir, "IMG_0003.JPG").apply { writeText(payload) }
        FakeSafDocumentsProvider.failDeletes = true

        val result = source.moveImageWithResult(
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
        )

        assertFalse(result.success)
        assertTrue(result.partial)
        assertNotNull(result.destinationUri)
        assertEquals("Copied to Selection — original could not be removed", result.error)

        // Verified copy is preserved on disk and source is still present:
        val destFile = File(rootDir, "Selection/JPEG/IMG_0003.JPG")
        assertTrue(destFile.exists())
        assertEquals(payload, destFile.readText())
        assertTrue(srcFile.exists())
    }

    // ── 4. Atomic DocumentsContract.moveDocument when supported ──────────

    // REQ-TOK-ARCH.15
    @Test
    fun `moveImage uses DocumentsContract moveDocument when same authority and FLAG_SUPPORTS_MOVE and no rename needed`() = runTest {
        val payload = "atomic-move-payload"
        val srcFile = File(rootDir, "IMG_0004.ARW").apply { writeText(payload) }
        FakeSafDocumentsProvider.advertiseSupportsMove = true

        val ok = source.moveImage(
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
        )

        assertTrue(ok)
        assertEquals(1, FakeSafDocumentsProvider.moveDocumentCalls.get())
        assertFalse(srcFile.exists())
        val destFile = File(rootDir, "Selection/RAW/IMG_0004.ARW")
        assertTrue(destFile.exists())
        assertEquals(payload, destFile.readText())
    }

    // REQ-TOK-ARCH.15
    @Test
    fun `moveImage falls back to verified copy with -2 suffix when collision exists even if FLAG_SUPPORTS_MOVE is set`() = runTest {
        File(rootDir, "Selection/RAW/IMG_0004.ARW").apply {
            parentFile?.mkdirs()
            writeText("existing-raw")
        }
        val srcFile = File(rootDir, "IMG_0004.ARW").apply { writeText("second-raw") }
        FakeSafDocumentsProvider.advertiseSupportsMove = true

        val ok = source.moveImage(
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
        )

        assertTrue(ok)
        assertEquals(0, FakeSafDocumentsProvider.moveDocumentCalls.get())
        assertEquals("existing-raw", File(rootDir, "Selection/RAW/IMG_0004.ARW").readText())
        assertEquals("second-raw", File(rootDir, "Selection/RAW/IMG_0004-2.ARW").readText())
        assertFalse(srcFile.exists())
    }

    // ── 5. Provider silent auto-rename mismatch ──────────────────────────

    // REQ-TOK-ARCH.15
    @Test
    fun `provider auto-rename on createDocument cleans up renamed file and fails without deleting source`() = runTest {
        val srcFile = File(rootDir, "IMG_0005.CR3").apply { writeText("raw-content") }
        FakeSafDocumentsProvider.autoRenameOnCreate = { requested ->
            if (requested == "IMG_0005.CR3") "IMG_0005 (1).CR3" else requested
        }

        val result = source.moveImageWithResult(
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
            subfolderName = PhotoFolders.SELECTION,
        )

        assertFalse(result.success)
        assertNull(result.destinationUri)
        assertTrue(result.error.orEmpty().contains("IMG_0005 (1).CR3"))
        assertFalse(File(rootDir, "Selection/RAW/IMG_0005 (1).CR3").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0005.CR3").exists())
        assertTrue(srcFile.exists())
    }

    // ── 6. 2-permit Semaphore & CancellationException hygiene ────────────

    // REQ-TOK-ARCH.15
    @Test
    fun `transferSemaphore bounds concurrent transfers to at most 2 in flight`() = runTest {
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        val inFlight = AtomicInteger(0)
        val maxObservedInFlight = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val gatedSource = LocalImageSourceImpl(
            context = context,
            androidExifReader = AndroidExifReader(),
            mediaStoreReader = MediaStoreReader(),
        )
        // Enter the semaphore on `standardDispatcher`, then suspend inside a custom
        // CoroutineDispatcher interceptor before running the transfer body.
        val gatingDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                standardDispatcher.dispatch(
                    context,
                    Runnable {
                        val current = inFlight.incrementAndGet()
                        maxObservedInFlight.updateAndGet { prev -> maxOf(prev, current) }
                        if (!gate.isCompleted) {
                            gate.invokeOnCompletion {
                                inFlight.decrementAndGet()
                                block.run()
                            }
                        } else {
                            inFlight.decrementAndGet()
                            block.run()
                        }
                    },
                )
            }
        }
        gatedSource.ioDispatcher = gatingDispatcher

        // Transfers into the same destination are serialised per destination (D-5), so each file
        // goes to its own destination folder: only the semaphore then bounds the parallelism.
        val files = (1..5).map { idx ->
            File(rootDir, "CONC_$idx.JPG").apply { writeText("photo-$idx") }
        }
        val destinations = (1..5).map { idx ->
            File(rootDir, "dest$idx").mkdirs()
            DocumentsContract.buildTreeDocumentUri(
                FakeSafDocumentsProvider.AUTHORITY,
                "${FakeSafDocumentsProvider.ROOT_ID}/dest$idx",
            )
        }

        val deferreds = files.mapIndexed { idx, f ->
            async(standardDispatcher) {
                gatedSource.copyImage(documentUriFor(f.name), destinations[idx], sorting = true, subfolderName = PhotoFolders.SELECTION)
            }
        }

        testScheduler.runCurrent()
        assertEquals(2, inFlight.get())
        assertEquals(2, maxObservedInFlight.get())

        gate.complete(Unit)
        advanceUntilIdle()

        val results = deferreds.map { it.await() }
        assertTrue(results.all { it })
        assertEquals(2, maxObservedInFlight.get())
    }

    // REQ-TOK-ARCH.15
    @Test
    fun `CancellationException is rethrown and never swallowed into false or failure`() = runTest {
        val cancellingDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                throw CancellationException("Transfer cancelled by scope")
            }
        }
        source.ioDispatcher = cancellingDispatcher
        val srcFile = File(rootDir, "CANCEL_ME.JPG").apply { writeText("bytes") }

        try {
            source.moveImage(documentUriFor(srcFile.name), treeUri, sorting = true, subfolderName = PhotoFolders.SELECTION)
            fail("Expected CancellationException to be rethrown from moveImage")
        } catch (expected: CancellationException) {
            assertEquals("Transfer cancelled by scope", expected.message)
        }

        try {
            source.copyImage(documentUriFor(srcFile.name), treeUri, sorting = true, subfolderName = PhotoFolders.SELECTION)
            fail("Expected CancellationException to be rethrown from copyImage")
        } catch (expected: CancellationException) {
            assertEquals("Transfer cancelled by scope", expected.message)
        }
    }

    // ── B-5 / D-5: concurrent transfers into the same destination ────────

    // REQ-TOK-ARCH.15
    @Test
    fun `two concurrent copies of same-named files into one Selection both succeed with a suffix and one Selection folder`() {
        source.ioDispatcher = Dispatchers.IO
        FakeSafDocumentsProvider.createDelayMs = 150L
        val fileA = File(rootDir, "dayA/IMG_0100.JPG").apply { parentFile!!.mkdirs(); writeText("payload-A") }
        val fileB = File(rootDir, "dayB/IMG_0100.JPG").apply { parentFile!!.mkdirs(); writeText("payload-B") }

        val results = kotlinx.coroutines.runBlocking {
            val a = async(Dispatchers.IO) {
                source.copyImageWithResult(documentUriFor("dayA/${fileA.name}"), treeUri, true, PhotoFolders.SELECTION)
            }
            val b = async(Dispatchers.IO) {
                source.copyImageWithResult(documentUriFor("dayB/${fileB.name}"), treeUri, true, PhotoFolders.SELECTION)
            }
            listOf(a.await(), b.await())
        }

        assertTrue(results.all { it.success })
        val jpeg = File(rootDir, "Selection/JPEG").list()!!.sorted()
        assertEquals(listOf("IMG_0100-2.JPG", "IMG_0100.JPG"), jpeg)
        assertEquals(
            setOf("payload-A", "payload-B"),
            jpeg.map { File(rootDir, "Selection/JPEG/$it").readText() }.toSet(),
        )
        assertEquals(listOf("Selection"), rootDir.list()!!.filter { it.startsWith("Selection") }.sorted())
    }
}
