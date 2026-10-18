package com.photoselectortoolbox.data.repository

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.source.LocalImageSource
import com.photoselectortoolbox.domain.format.ImageFormats
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
 * Unit tests for non-destructive SAF file transfers in [ImageRepositoryImpl]
 * (REQ-AND-PLATFORM.06, `docs/shared/FILE_OPERATIONS.md`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SafTransferTest {

    /**
     * SAF-shaped [ContentProvider] backed by a temporary directory with hooks to
     * simulate provider auto-renames, truncated writes, delete failures, and
     * [Document.FLAG_SUPPORTS_MOVE].
     */
    class FakeSafDocumentsProvider : android.content.ContentProvider() {
        companion object {
            const val AUTHORITY = "com.photoselectortoolbox.test.saftransfer"
            const val ROOT_ID = "root"

            @Volatile
            var rootDir: File = File(".")

            @Volatile
            var advertiseSupportsMove: Boolean = false

            @Volatile
            var failDeletes: Boolean = false
            /** When set, only documents whose id contains this text refuse to be deleted. */
            var failDeletesMatching: String? = null

            @Volatile
            var autoRenameOnCreate: ((String) -> String)? = null

            @Volatile
            var reportedSizeOverride: ((File) -> Long?)? = null

            /** Milliseconds each file creation takes, to widen race windows in concurrency tests. */
            @Volatile
            var createDelayMs: Long = 0L

            /** When true, sub-folders get provider-opaque ids ("msf:N") instead of path-like ones. */
            @Volatile
            var opaqueFolderIds: Boolean = false
            private val opaqueRegistry = java.util.concurrent.ConcurrentHashMap<String, File>()

            /** The id the provider hands out for [file] (opaque for folders when [opaqueFolderIds]). */
            fun documentIdFor(file: File): String {
                val rel = file.relativeTo(rootDir).invariantSeparatorsPath
                if (rel.isEmpty()) return ROOT_ID
                if (opaqueFolderIds && file.isDirectory) {
                    val id = "msf:" + rel.hashCode().toUInt()
                    opaqueRegistry[id] = file
                    return id
                }
                return "$ROOT_ID/$rel"
            }

            fun opaqueFileFor(id: String): File? = opaqueRegistry[id]

            val moveDocumentCalls = AtomicInteger(0)
            val childrenQueryCalls = AtomicInteger(0)

            fun reset(root: File) {
                rootDir = root
                advertiseSupportsMove = false
                failDeletes = false
                failDeletesMatching = null
                opaqueFolderIds = false
                createDelayMs = 0L
                opaqueRegistry.clear()
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
            opaqueFileFor(documentId)?.let { return it }
            require(documentId == ROOT_ID || documentId.startsWith("$ROOT_ID/")) {
                "Unexpected documentId: $documentId"
            }
            return if (documentId == ROOT_ID) {
                rootDir
            } else {
                File(rootDir, documentId.removePrefix("$ROOT_ID/"))
            }
        }

        private fun docIdForFile(file: File): String = documentIdFor(file)

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
            val id = documentIdOf(uri)
            if (uri.pathSegments.lastOrNull() == "children") {
                childrenQueryCalls.incrementAndGet()
                val dir = fileForDocId(id)
                dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                    addRow(cursor, cols, child)
                }
            } else {
                val file = fileForDocId(id)
                if (file.exists()) {
                    addRow(cursor, cols, file)
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
            if (failDeletes || failDeletesMatching?.let { documentId.contains(it) } == true) {
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
    private lateinit var repository: ImageRepositoryImpl
    private val selectionFolderNameFlow = MutableStateFlow("Selection")

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
        registerAsDocumentsProvider()

        val settingsRepository: SettingsRepository = mockk(relaxed = true) {
            every { selectionFolderName } returns selectionFolderNameFlow
        }
        repository = ImageRepositoryImpl(
            localImageSource = mockk<LocalImageSource>(relaxed = true),
            androidExifReader = AndroidExifReader(),
            mediaStoreReader = MediaStoreReader(),
            settingsRepository = settingsRepository,
        )
        repository.ioDispatcher = UnconfinedTestDispatcher()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * A real DocumentsProvider answers the `android.content.action.DOCUMENTS_PROVIDER` intent;
     * without that, `DocumentsContract.isDocumentUri` is false and `DocumentFile.fromTreeUri`
     * on a document URI silently resolves to the tree root instead of that document.
     */
    private fun registerAsDocumentsProvider() {
        val component = android.content.ComponentName(
            context.packageName,
            FakeSafDocumentsProvider::class.java.name,
        )
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

    private fun documentUriFor(relativePath: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            "${FakeSafDocumentsProvider.ROOT_ID}/$relativePath",
        )

    // ── 1. Collision-free bundle naming ──────────────────────────────────

    // REQ-AND-PLATFORM.06
    @Test
    fun `collision in Selection JPEG suffixes entire CR3 + JPG + CR3 xmp + -Edit tif bundle with -2`() {
        val bundle = listOf(
            "IMG_0001.CR3",
            "IMG_0001.JPG",
            "IMG_0001.CR3.xmp",
            "IMG_0001-Edit.tif",
        )
        val existingByFolder = mapOf(
            ImageFormats.SelectionSubfolder.RAW.name to emptySet(),
            ImageFormats.SelectionSubfolder.JPEG.name to setOf("img_0001.jpg"), // case-insensitive collision
        )

        val resolved = ImageRepositoryImpl.resolveCollisionFreeBundleNames(
            fileNames = bundle,
            primaryStem = "IMG_0001",
            existingNamesByFolder = existingByFolder,
            folderForFile = { ImageFormats.selectionSubfolder(it).name },
        )

        assertEquals("IMG_0001-2.CR3", resolved["IMG_0001.CR3"])
        assertEquals("IMG_0001-2.JPG", resolved["IMG_0001.JPG"])
        assertEquals("IMG_0001-2.CR3.xmp", resolved["IMG_0001.CR3.xmp"])
        assertEquals("IMG_0001-2-Edit.tif", resolved["IMG_0001-Edit.tif"])
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `collision resolution advances to -3 when -2 is already taken in any target subfolder`() {
        val bundle = listOf(
            "IMG_0001.CR3",
            "IMG_0001.JPG",
            "IMG_0001.xmp",
            "IMG_0001_Edit.tif",
        )
        val existingByFolder = mapOf(
            ImageFormats.SelectionSubfolder.RAW.name to setOf("IMG_0001-2.CR3"),
            ImageFormats.SelectionSubfolder.JPEG.name to setOf("IMG_0001.JPG"),
            ImageFormats.SelectionSubfolder.ROOT.name to emptySet(),
        )

        val resolved = ImageRepositoryImpl.resolveCollisionFreeBundleNames(
            fileNames = bundle,
            primaryStem = "IMG_0001",
            existingNamesByFolder = existingByFolder,
            folderForFile = { ImageFormats.selectionSubfolder(it).name },
        )

        assertEquals("IMG_0001-3.CR3", resolved["IMG_0001.CR3"])
        assertEquals("IMG_0001-3.JPG", resolved["IMG_0001.JPG"])
        assertEquals("IMG_0001-3.xmp", resolved["IMG_0001.xmp"])
        assertEquals("IMG_0001-3_Edit.tif", resolved["IMG_0001_Edit.tif"])
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `end-to-end SAF bundle move preserves existing file in Selection JPEG and suffixes entire bundle`() = runTest {
        // Pre-populate Selection/JPEG/IMG_0001.JPG with existing content that must never be overwritten.
        val existingJpeg = File(rootDir, "Selection/JPEG/IMG_0001.JPG").apply {
            parentFile?.mkdirs()
            writeText("pre-existing-jpeg-must-survive")
        }

        val srcCr3 = File(rootDir, "IMG_0001.CR3").apply { writeText("new-raw-bytes-12345") }
        val srcJpg = File(rootDir, "IMG_0001.JPG").apply { writeText("new-jpeg-bytes-67890") }
        val srcXmp = File(rootDir, "IMG_0001.CR3.xmp").apply { writeText("<x:xmpmeta/>") }
        val srcEdit = File(rootDir, "IMG_0001-Edit.tif").apply { writeText("new-edit-tiff-bytes") }

        val results = repository.transferBundle(
            context = context,
            sourceUris = listOf(
                documentUriFor(srcCr3.name),
                documentUriFor(srcJpg.name),
                documentUriFor(srcXmp.name),
                documentUriFor(srcEdit.name),
            ),
            destFolderUri = treeUri,
            sorting = true,
            isMove = true,
        )

        assertTrue(results.all { it.success })
        // Pre-existing file is untouched.
        assertEquals("pre-existing-jpeg-must-survive", existingJpeg.readText())
        // Entire new bundle landed under -2 with pairing intact.
        assertEquals("new-raw-bytes-12345", File(rootDir, "Selection/RAW/IMG_0001-2.CR3").readText())
        assertEquals("new-jpeg-bytes-67890", File(rootDir, "Selection/JPEG/IMG_0001-2.JPG").readText())
        assertEquals("<x:xmpmeta/>", File(rootDir, "Selection/RAW/IMG_0001-2.CR3.xmp").readText())
        assertEquals("new-edit-tiff-bytes", File(rootDir, "Selection/RAW/IMG_0001-2-Edit.tif").readText())
        // Originals were removed after verification.
        assertFalse(srcCr3.exists())
        assertFalse(srcJpg.exists())
        assertFalse(srcXmp.exists())
        assertFalse(srcEdit.exists())
    }

    // ── 2. Byte size mismatch verification ───────────────────────────────

    // REQ-AND-PLATFORM.06
    @Test
    fun `size mismatch after copy cleans up destination and keeps source untouched`() = runTest {
        val srcFile = File(rootDir, "IMG_0002.CR3").apply {
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }
        // Simulate provider truncating / reporting wrong destination byte size.
        FakeSafDocumentsProvider.reportedSizeOverride = { file ->
            if (file.parentFile?.name == "RAW" && file.name == "IMG_0002.CR3") {
                1024L // truncated vs 4096L source
            } else {
                file.length()
            }
        }

        val result = repository.moveImage(
            context = context,
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
        )

        assertFalse(result.success)
        assertFalse(result.partial)
        assertNull(result.destinationUri)
        assertEquals("Copied file size mismatch", result.error)
        // Partial destination replica was cleaned up.
        assertFalse(File(rootDir, "Selection/RAW/IMG_0002.CR3").exists())
        // Original source file was never deleted.
        assertTrue(srcFile.exists())
        assertEquals(4096L, srcFile.length())
    }

    // ── 3. Source-delete failure reported as partial ─────────────────────

    // REQ-AND-PLATFORM.06
    @Test
    fun `source delete failure after verified copy keeps destination and returns partial result`() = runTest {
        val payload = "verified-camera-original-bytes"
        val srcFile = File(rootDir, "IMG_0003.JPG").apply { writeText(payload) }
        FakeSafDocumentsProvider.failDeletes = true

        val result = repository.moveImage(
            context = context,
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
        )

        assertFalse(result.success)
        assertTrue(result.partial)
        assertNotNull(result.destinationUri)
        assertEquals("Copied to Selection — original could not be removed", result.error)
        // Both the verified destination copy and the source remain intact.
        val destFile = File(rootDir, "Selection/JPEG/IMG_0003.JPG")
        assertTrue(destFile.exists())
        assertEquals(payload, destFile.readText())
        assertTrue(srcFile.exists())
    }

    // ── 4. Atomic DocumentsContract.moveDocument when supported ──────────

    // REQ-AND-PLATFORM.06
    @Test
    fun `moveImage uses DocumentsContract moveDocument when same authority and FLAG_SUPPORTS_MOVE and no rename needed`() = runTest {
        val payload = "atomic-move-payload"
        val srcFile = File(rootDir, "IMG_0004.ARW").apply { writeText(payload) }
        FakeSafDocumentsProvider.advertiseSupportsMove = true

        val result = repository.moveImage(
            context = context,
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
        )

        assertTrue(result.success)
        assertFalse(result.partial)
        assertEquals(1, FakeSafDocumentsProvider.moveDocumentCalls.get())
        assertFalse(srcFile.exists())
        val destFile = File(rootDir, "Selection/RAW/IMG_0004.ARW")
        assertTrue(destFile.exists())
        assertEquals(payload, destFile.readText())
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `moveImage falls back to verified copy with -2 suffix when collision exists even if FLAG_SUPPORTS_MOVE is set`() = runTest {
        File(rootDir, "Selection/RAW/IMG_0004.ARW").apply {
            parentFile?.mkdirs()
            writeText("existing-raw")
        }
        val srcFile = File(rootDir, "IMG_0004.ARW").apply { writeText("second-raw") }
        FakeSafDocumentsProvider.advertiseSupportsMove = true

        val result = repository.moveImage(
            context = context,
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
        )

        assertTrue(result.success)
        assertEquals(0, FakeSafDocumentsProvider.moveDocumentCalls.get())
        assertEquals("existing-raw", File(rootDir, "Selection/RAW/IMG_0004.ARW").readText())
        assertEquals("second-raw", File(rootDir, "Selection/RAW/IMG_0004-2.ARW").readText())
        assertFalse(srcFile.exists())
    }

    // ── 5. Provider silent auto-rename mismatch ──────────────────────────

    // REQ-AND-PLATFORM.06
    @Test
    fun `provider auto-rename on createDocument cleans up renamed file and fails without deleting source`() = runTest {
        val srcFile = File(rootDir, "IMG_0005.CR3").apply { writeText("raw-content") }
        FakeSafDocumentsProvider.autoRenameOnCreate = { requested ->
            if (requested == "IMG_0005.CR3") "IMG_0005 (1).CR3" else requested
        }

        val result = repository.moveImage(
            context = context,
            sourceUri = documentUriFor(srcFile.name),
            destFolderUri = treeUri,
            sorting = true,
        )

        assertFalse(result.success)
        assertNull(result.destinationUri)
        assertTrue(result.error.orEmpty().contains("IMG_0005 (1).CR3"))
        // Auto-renamed file was deleted by cleanup.
        assertFalse(File(rootDir, "Selection/RAW/IMG_0005 (1).CR3").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0005.CR3").exists())
        // Source file was never deleted.
        assertTrue(srcFile.exists())
    }

    // ── 6. 2-permit Semaphore & CancellationException hygiene ────────────

    // REQ-AND-PLATFORM.06
    @Test
    fun `transferSemaphore bounds concurrent transfers to at most 2 in flight`() = runTest {
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        repository.ioDispatcher = standardDispatcher

        val inFlight = AtomicInteger(0)
        val maxObservedInFlight = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        repository.documentMover = object : SafDocumentMover {
            override fun canMoveDocument(
                context: Context,
                sourceUri: Uri,
                sourceParentUri: Uri?,
                targetFolderUri: Uri,
            ): Boolean = true

            override fun moveDocument(
                context: Context,
                sourceUri: Uri,
                sourceParentUri: Uri,
                targetFolderUri: Uri,
            ): Uri? {
                val current = inFlight.incrementAndGet()
                maxObservedInFlight.updateAndGet { prev -> maxOf(prev, current) }
                try {
                    // Block conceptually via runBlocking-free check: record concurrency
                    return sourceUri
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }

        // Verify semaphore permits directly by holding 2 transfers suspended inside settingsRepository.
        val slowSettings: SettingsRepository = mockk(relaxed = true) {
            every { selectionFolderName } returns kotlinx.coroutines.flow.flow {
                val current = inFlight.incrementAndGet()
                maxObservedInFlight.updateAndGet { prev -> maxOf(prev, current) }
                try {
                    gate.await()
                    emit("Selection")
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        val gatedRepo = ImageRepositoryImpl(
            localImageSource = mockk<LocalImageSource>(relaxed = true),
            androidExifReader = AndroidExifReader(),
            mediaStoreReader = MediaStoreReader(),
            settingsRepository = slowSettings,
        ).also {
            it.ioDispatcher = standardDispatcher
        }

        val files = (1..5).map { idx ->
            File(rootDir, "CONC_$idx.JPG").apply { writeText("photo-$idx") }
        }

        val deferreds = files.map { f ->
            async(standardDispatcher) {
                gatedRepo.copyImage(context, documentUriFor(f.name), treeUri, sorting = true)
            }
        }

        testScheduler.runCurrent()
        // Only 2 coroutines can enter the critical section while the gate is closed.
        assertEquals(2, inFlight.get())
        assertEquals(2, maxObservedInFlight.get())

        gate.complete(Unit)
        advanceUntilIdle()

        val results = deferreds.map { it.await() }
        assertTrue(results.all { it.success })
        assertEquals(2, maxObservedInFlight.get())
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `CancellationException is rethrown and never swallowed into FileOperationResult failure`() = runTest {
        val cancellingSettings: SettingsRepository = mockk(relaxed = true) {
            every { selectionFolderName } returns kotlinx.coroutines.flow.flow {
                throw CancellationException("Transfer cancelled by scope")
            }
        }
        val cancellingRepo = ImageRepositoryImpl(
            localImageSource = mockk<LocalImageSource>(relaxed = true),
            androidExifReader = AndroidExifReader(),
            mediaStoreReader = MediaStoreReader(),
            settingsRepository = cancellingSettings,
        ).also {
            it.ioDispatcher = UnconfinedTestDispatcher()
        }

        val srcFile = File(rootDir, "CANCEL_ME.JPG").apply { writeText("bytes") }

        try {
            cancellingRepo.moveImage(context, documentUriFor(srcFile.name), treeUri, sorting = true)
            fail("Expected CancellationException to be rethrown from moveImage")
        } catch (expected: CancellationException) {
            assertEquals("Transfer cancelled by scope", expected.message)
        }

        try {
            cancellingRepo.copyImage(context, documentUriFor(srcFile.name), treeUri, sorting = true)
            fail("Expected CancellationException to be rethrown from copyImage")
        } catch (expected: CancellationException) {
            assertEquals("Transfer cancelled by scope", expected.message)
        }
    }

    // ── 7. U-04b: Bundle transfer, rollback, delete & undo to sub-folder ─

    // REQ-AND-PLATFORM.06, REQ-AND-PLATFORM.12
    @Test
    fun `moveBundle moves CR3 + JPG + CR3 xmp from sub-folder and undoMoveBundle restores all 3 into original sub-folder`() = runTest {
        val subDir = File(rootDir, "sub/day1").apply { mkdirs() }
        val srcJpg = File(subDir, "IMG_0010.JPG").apply { writeText("jpeg-bytes-10") }
        val srcCr3 = File(subDir, "IMG_0010.CR3").apply { writeText("raw-cr3-bytes-10") }
        val srcXmp = File(subDir, "IMG_0010.CR3.xmp").apply { writeText("<x:xmpmeta>rating=5</x:xmpmeta>") }

        val item = ImageItem(
            uri = documentUriFor("sub/day1/IMG_0010.JPG").toString(),
            fileName = "IMG_0010.JPG",
            fileSize = srcJpg.length(),
            lastModified = srcJpg.lastModified(),
            mimeType = "image/jpeg",
            companions = listOf(
                CompanionFile(
                    uri = documentUriFor("sub/day1/IMG_0010.CR3").toString(),
                    fileName = "IMG_0010.CR3",
                    fileSize = srcCr3.length(),
                ),
                CompanionFile(
                    uri = documentUriFor("sub/day1/IMG_0010.CR3.xmp").toString(),
                    fileName = "IMG_0010.CR3.xmp",
                    fileSize = srcXmp.length(),
                ),
            ),
            parentDocumentId = "root/sub/day1",
        )

        val moveResult = repository.moveBundle(
            context = context,
            item = item,
            destFolderUri = treeUri,
            sorting = true,
        )

        assertTrue(moveResult.success)
        assertEquals(3, moveResult.destinationUris.size)
        assertEquals("root/sub/day1", moveResult.parentDocumentId)

        // All 3 files moved out of sub/day1 into Selection/JPEG and Selection/RAW.
        assertFalse(srcJpg.exists())
        assertFalse(srcCr3.exists())
        assertFalse(srcXmp.exists())
        assertEquals("jpeg-bytes-10", File(rootDir, "Selection/JPEG/IMG_0010.JPG").readText())
        assertEquals("raw-cr3-bytes-10", File(rootDir, "Selection/RAW/IMG_0010.CR3").readText())
        assertEquals("<x:xmpmeta>rating=5</x:xmpmeta>", File(rootDir, "Selection/RAW/IMG_0010.CR3.xmp").readText())

        // Undo restores all 3 files into sub/day1 (not into rootDir and without creating sub/day1/Selection).
        val undoResult = repository.undoMoveBundle(
            context = context,
            destinationUris = moveResult.destinationUris,
            rootFolderUri = treeUri,
            parentDocumentId = moveResult.parentDocumentId,
        )

        assertTrue(undoResult.success)
        assertEquals(3, undoResult.destinationUris.size)
        assertEquals("jpeg-bytes-10", File(rootDir, "sub/day1/IMG_0010.JPG").readText())
        assertEquals("raw-cr3-bytes-10", File(rootDir, "sub/day1/IMG_0010.CR3").readText())
        assertEquals("<x:xmpmeta>rating=5</x:xmpmeta>", File(rootDir, "sub/day1/IMG_0010.CR3.xmp").readText())
        // Root was not polluted with restored files, and no Selection/ was created in sub/day1.
        assertFalse(File(rootDir, "IMG_0010.JPG").exists())
        assertFalse(File(rootDir, "IMG_0010.CR3").exists())
        assertFalse(File(rootDir, "IMG_0010.CR3.xmp").exists())
        assertFalse(File(rootDir, "sub/day1/Selection").exists())
        // Selection copies were removed on undo.
        assertFalse(File(rootDir, "Selection/JPEG/IMG_0010.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0010.CR3").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0010.CR3.xmp").exists())
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `moveBundle cleans up staged copies and preserves all sources when a companion copy fails`() = runTest {
        val srcJpg = File(rootDir, "IMG_0011.JPG").apply { writeText("primary-jpeg-ok") }
        val srcCr3 = File(rootDir, "IMG_0011.CR3").apply {
            writeBytes(ByteArray(2048) { (it % 127).toByte() })
        }
        // Simulate size mismatch on the second file (the CR3 companion).
        FakeSafDocumentsProvider.reportedSizeOverride = { file ->
            if (file.parentFile?.name == "RAW" && file.name == "IMG_0011.CR3") {
                512L
            } else {
                file.length()
            }
        }

        val item = ImageItem(
            uri = documentUriFor("IMG_0011.JPG").toString(),
            fileName = "IMG_0011.JPG",
            fileSize = srcJpg.length(),
            lastModified = srcJpg.lastModified(),
            mimeType = "image/jpeg",
            companions = listOf(
                CompanionFile(
                    uri = documentUriFor("IMG_0011.CR3").toString(),
                    fileName = "IMG_0011.CR3",
                    fileSize = srcCr3.length(),
                )
            ),
        )

        val result = repository.moveBundle(
            context = context,
            item = item,
            destFolderUri = treeUri,
            sorting = true,
        )

        assertFalse(result.success)
        assertTrue(result.destinationUris.isEmpty())
        // The first file's staged copy in Selection/JPEG was cleaned up when the second failed.
        assertFalse(File(rootDir, "Selection/JPEG/IMG_0011.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0011.CR3").exists())
        // Both source files are untouched.
        assertTrue(srcJpg.exists())
        assertTrue(srcCr3.exists())
    }

    // REQ-AND-PLATFORM.06, REQ-AND-PLATFORM.12
    @Test
    fun `deleteBundle deletes primary and all companion files`() = runTest {
        val srcJpg = File(rootDir, "IMG_0012.JPG").apply { writeText("jpg") }
        val srcCr3 = File(rootDir, "IMG_0012.CR3").apply { writeText("cr3") }
        val srcXmp = File(rootDir, "IMG_0012.CR3.xmp").apply { writeText("xmp") }

        val item = ImageItem(
            uri = documentUriFor("IMG_0012.JPG").toString(),
            fileName = "IMG_0012.JPG",
            fileSize = srcJpg.length(),
            lastModified = srcJpg.lastModified(),
            mimeType = "image/jpeg",
            companions = listOf(
                CompanionFile(uri = documentUriFor("IMG_0012.CR3").toString(), fileName = "IMG_0012.CR3"),
                CompanionFile(uri = documentUriFor("IMG_0012.CR3.xmp").toString(), fileName = "IMG_0012.CR3.xmp"),
            ),
        )

        val deleted = repository.deleteBundle(context, item)

        assertTrue(deleted)
        assertFalse(srcJpg.exists())
        assertFalse(srcCr3.exists())
        assertFalse(srcXmp.exists())
    }

    // ── 8. U-04c: File Sorting off still files into Selection/ ───────────

    // REQ-AND-PLATFORM.06, REQ-AND-PLATFORM.12
    @Test
    fun `sorting false files bundle flat into Selection without RAW or JPEG subfolders and undo restores to original folder`() = runTest {
        val subDir = File(rootDir, "sub/day2").apply { mkdirs() }
        val srcJpg = File(subDir, "IMG_0020.JPG").apply { writeText("flat-jpg") }
        val srcCr3 = File(subDir, "IMG_0020.CR3").apply { writeText("flat-cr3") }
        val srcXmp = File(subDir, "IMG_0020.CR3.xmp").apply { writeText("flat-xmp") }

        val item = ImageItem(
            uri = documentUriFor("sub/day2/IMG_0020.JPG").toString(),
            fileName = "IMG_0020.JPG",
            fileSize = srcJpg.length(),
            lastModified = srcJpg.lastModified(),
            mimeType = "image/jpeg",
            companions = listOf(
                CompanionFile(
                    uri = documentUriFor("sub/day2/IMG_0020.CR3").toString(),
                    fileName = "IMG_0020.CR3",
                ),
                CompanionFile(
                    uri = documentUriFor("sub/day2/IMG_0020.CR3.xmp").toString(),
                    fileName = "IMG_0020.CR3.xmp",
                ),
            ),
            parentDocumentId = "root/sub/day2",
        )

        val moveResult = repository.moveBundle(
            context = context,
            item = item,
            destFolderUri = treeUri,
            sorting = false,
        )

        assertTrue(moveResult.success)
        assertEquals(3, moveResult.destinationUris.size)
        // Files are placed directly inside <root>/Selection/ (flat), never in <root> itself.
        assertEquals("flat-jpg", File(rootDir, "Selection/IMG_0020.JPG").readText())
        assertEquals("flat-cr3", File(rootDir, "Selection/IMG_0020.CR3").readText())
        assertEquals("flat-xmp", File(rootDir, "Selection/IMG_0020.CR3.xmp").readText())
        assertFalse(File(rootDir, "Selection/RAW").exists())
        assertFalse(File(rootDir, "Selection/JPEG").exists())
        assertFalse(File(rootDir, "IMG_0020.JPG").exists())
        assertFalse(File(rootDir, "IMG_0020.CR3").exists())
        assertFalse(File(rootDir, "IMG_0020.CR3.xmp").exists())

        // Also verify copyImage with sorting = false writes into Selection/, not rootDir.
        val rootPhoto = File(rootDir, "IMG_0021.JPG").apply { writeText("root-photo") }
        val copyResult = repository.copyImage(
            context = context,
            sourceUri = documentUriFor("IMG_0021.JPG"),
            destFolderUri = treeUri,
            sorting = false,
        )
        assertTrue(copyResult.success)
        assertEquals("root-photo", File(rootDir, "Selection/IMG_0021.JPG").readText())
        assertTrue(rootPhoto.exists())

        // Undo returns the moved bundle to sub/day2 without creating Selection/ inside sub/day2.
        val undoResult = repository.undoMoveBundle(
            context = context,
            destinationUris = moveResult.destinationUris,
            rootFolderUri = treeUri,
            parentDocumentId = moveResult.parentDocumentId,
        )
        assertTrue(undoResult.success)
        assertEquals("flat-jpg", File(rootDir, "sub/day2/IMG_0020.JPG").readText())
        assertEquals("flat-cr3", File(rootDir, "sub/day2/IMG_0020.CR3").readText())
        assertEquals("flat-xmp", File(rootDir, "sub/day2/IMG_0020.CR3.xmp").readText())
        assertFalse(File(rootDir, "sub/day2/Selection").exists())
    }

    // ── B-3 / D-3: undo of a partial bundle move ─────────────────────────

    private fun bundleItem(dir: String, stem: String, subDir: File): ImageItem {
        val jpg = File(subDir, "$stem.JPG")
        val cr3 = File(subDir, "$stem.CR3")
        return ImageItem(
            uri = documentUriFor("$dir/$stem.JPG").toString(),
            fileName = "$stem.JPG",
            fileSize = jpg.length(),
            lastModified = jpg.lastModified(),
            mimeType = "image/jpeg",
            companions = listOf(
                CompanionFile(
                    uri = documentUriFor("$dir/$stem.CR3").toString(),
                    fileName = "$stem.CR3",
                    fileSize = cr3.length(),
                ),
            ),
            parentDocumentId = "root/$dir",
        )
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `undo of a fully partial bundle move deletes the copies and creates no duplicate next to the originals`() = runTest {
        val subDir = File(rootDir, "sub/day2").apply { mkdirs() }
        File(subDir, "IMG_0020.JPG").writeText("jpeg-20")
        File(subDir, "IMG_0020.CR3").writeText("raw-20")
        FakeSafDocumentsProvider.failDeletes = true

        val moveResult = repository.moveBundle(context, bundleItem("sub/day2", "IMG_0020", subDir), treeUri, sorting = true)

        assertTrue(moveResult.partial)
        assertEquals(2, moveResult.copiesOfSurvivingOriginals.size)
        assertTrue(File(subDir, "IMG_0020.JPG").exists())

        FakeSafDocumentsProvider.failDeletes = false
        val undo = repository.undoMoveBundle(
            context = context,
            destinationUris = moveResult.destinationUris,
            rootFolderUri = treeUri,
            parentDocumentId = moveResult.parentDocumentId,
            copiesOfSurvivingOriginals = moveResult.copiesOfSurvivingOriginals,
        )

        assertTrue(undo.success)
        assertEquals(listOf("IMG_0020.CR3", "IMG_0020.JPG"), subDir.list()!!.sorted())
        assertEquals("jpeg-20", File(subDir, "IMG_0020.JPG").readText())
        assertEquals("raw-20", File(subDir, "IMG_0020.CR3").readText())
        assertFalse(File(rootDir, "Selection/JPEG/IMG_0020.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0020.CR3").exists())
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `undo of a mixed partial bundle move restores the moved member and only deletes the copy of the member that stayed`() = runTest {
        val subDir = File(rootDir, "sub/day3").apply { mkdirs() }
        File(subDir, "IMG_0030.JPG").writeText("jpeg-30")
        File(subDir, "IMG_0030.CR3").writeText("raw-30")
        FakeSafDocumentsProvider.failDeletesMatching = "IMG_0030.JPG"

        val moveResult = repository.moveBundle(context, bundleItem("sub/day3", "IMG_0030", subDir), treeUri, sorting = true)

        assertTrue(moveResult.partial)
        assertEquals(1, moveResult.copiesOfSurvivingOriginals.size)
        assertFalse(File(subDir, "IMG_0030.CR3").exists())
        assertTrue(File(subDir, "IMG_0030.JPG").exists())

        FakeSafDocumentsProvider.failDeletesMatching = null
        val undo = repository.undoMoveBundle(
            context = context,
            destinationUris = moveResult.destinationUris,
            rootFolderUri = treeUri,
            parentDocumentId = moveResult.parentDocumentId,
            copiesOfSurvivingOriginals = moveResult.copiesOfSurvivingOriginals,
        )

        assertTrue(undo.success)
        // D-13: the result names the deleted copy so its rating row can go with it.
        assertEquals(moveResult.copiesOfSurvivingOriginals.keys.toList(), undo.deletedCopies)
        assertEquals(listOf("IMG_0030.CR3", "IMG_0030.JPG"), subDir.list()!!.sorted())
        assertEquals("raw-30", File(subDir, "IMG_0030.CR3").readText())
        assertEquals("jpeg-30", File(subDir, "IMG_0030.JPG").readText())
        assertFalse(File(rootDir, "Selection/JPEG/IMG_0030.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0030.CR3").exists())
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `undo of a partial move never deletes a copy whose original has disappeared meanwhile`() = runTest {
        val subDir = File(rootDir, "sub/day4").apply { mkdirs() }
        File(subDir, "IMG_0040.JPG").writeText("jpeg-40")
        File(subDir, "IMG_0040.CR3").writeText("raw-40")
        FakeSafDocumentsProvider.failDeletes = true

        val moveResult = repository.moveBundle(context, bundleItem("sub/day4", "IMG_0040", subDir), treeUri, sorting = true)
        assertTrue(moveResult.partial)
        FakeSafDocumentsProvider.failDeletes = false
        File(subDir, "IMG_0040.JPG").delete() // the user removed the stuck original later

        val undo = repository.undoMoveBundle(
            context = context,
            destinationUris = moveResult.destinationUris,
            rootFolderUri = treeUri,
            parentDocumentId = moveResult.parentDocumentId,
            copiesOfSurvivingOriginals = moveResult.copiesOfSurvivingOriginals,
        )

        assertTrue(undo.success)
        // Only the CR3 copy (original still there) was deleted; the restored JPG copy is not listed.
        assertEquals(1, undo.deletedCopies.size)
        assertTrue(undo.deletedCopies.single().contains("IMG_0040.CR3"))
        // The JPG copy was the only one left: it is restored, the CR3 copy (original still there) is deleted.
        assertEquals("jpeg-40", File(subDir, "IMG_0040.JPG").readText())
        assertEquals("raw-40", File(subDir, "IMG_0040.CR3").readText())
        assertEquals(listOf("IMG_0040.CR3", "IMG_0040.JPG"), subDir.list()!!.sorted())
    }

    // ── B-4 / D-4: restore-folder resolution with opaque document ids ────

    private fun itemInOpaqueFolder(folder: File, stem: String): ImageItem {
        val jpg = File(folder, "$stem.JPG")
        val rel = jpg.relativeTo(rootDir).invariantSeparatorsPath
        return ImageItem(
            uri = documentUriFor(rel).toString(),
            fileName = jpg.name,
            fileSize = jpg.length(),
            lastModified = jpg.lastModified(),
            mimeType = "image/jpeg",
            parentDocumentId = FakeSafDocumentsProvider.documentIdFor(folder), // "msf:N", not a path
        )
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `undo restores into the original folder when the provider uses opaque document ids and creates no folder`() = runTest {
        FakeSafDocumentsProvider.opaqueFolderIds = true
        val folder = File(rootDir, "Download/trip").apply { mkdirs() }
        File(folder, "IMG_0050.JPG").writeText("jpeg-50")
        val item = itemInOpaqueFolder(folder, "IMG_0050")
        assertTrue(item.parentDocumentId!!.startsWith("msf:"))
        val before = rootDir.walkTopDown().filter { it.isDirectory }.map { it.relativeTo(rootDir).path }.toSet()

        val moved = repository.moveBundle(context, item, treeUri, sorting = false)
        assertTrue(moved.success)
        assertFalse(File(folder, "IMG_0050.JPG").exists())

        val undo = repository.undoMoveBundle(context, moved.destinationUris, treeUri, moved.parentDocumentId)

        assertTrue(undo.success)
        assertNull(undo.notice)
        assertEquals("jpeg-50", File(folder, "IMG_0050.JPG").readText())
        // No directory literally named after the opaque id (or anything else) appeared.
        val after = rootDir.walkTopDown().filter { it.isDirectory }.map { it.relativeTo(rootDir).path }.toSet()
        assertEquals(setOf("Selection"), after - before)
        assertFalse(after.any { it.contains("msf:") })
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `undo restores to the opened folder and says so when the original folder is gone`() = runTest {
        FakeSafDocumentsProvider.opaqueFolderIds = true
        val folder = File(rootDir, "Download/gone").apply { mkdirs() }
        File(folder, "IMG_0051.JPG").writeText("jpeg-51")
        val item = itemInOpaqueFolder(folder, "IMG_0051")
        val moved = repository.moveBundle(context, item, treeUri, sorting = false)
        assertTrue(moved.success)
        folder.deleteRecursively() // the user removed the folder meanwhile

        val undo = repository.undoMoveBundle(context, moved.destinationUris, treeUri, moved.parentDocumentId)

        assertTrue(undo.success)
        assertEquals("jpeg-51", File(rootDir, "IMG_0051.JPG").readText())
        assertNotNull(undo.notice)
        assertFalse(File(rootDir, "Download/gone").exists())
        assertFalse(rootDir.walkTopDown().any { it.name.contains("msf:") })
    }

    // ── B-5 / D-5: concurrent transfers into the same destination ────────

    private fun namedItem(folder: String, stem: String, payload: String): ImageItem {
        val dir = File(rootDir, folder).apply { mkdirs() }
        val file = File(dir, "$stem.JPG").apply { writeText(payload) }
        return ImageItem(
            uri = documentUriFor("$folder/$stem.JPG").toString(),
            fileName = file.name,
            fileSize = file.length(),
            lastModified = file.lastModified(),
            mimeType = "image/jpeg",
            parentDocumentId = "root/$folder",
        )
    }

    // REQ-AND-PLATFORM.06
    @Test
    fun `two concurrent transfers of same-named files into one Selection both succeed with a suffix and one Selection folder`() {
        repository.ioDispatcher = kotlinx.coroutines.Dispatchers.IO
        FakeSafDocumentsProvider.createDelayMs = 150L
        val itemA = namedItem("dayA", "IMG_0100", "payload-A")
        val itemB = namedItem("dayB", "IMG_0100", "payload-B")

        val results = kotlinx.coroutines.runBlocking {
            val a = async(kotlinx.coroutines.Dispatchers.IO) {
                repository.copyBundle(context, itemA, treeUri, sorting = true)
            }
            val b = async(kotlinx.coroutines.Dispatchers.IO) {
                repository.copyBundle(context, itemB, treeUri, sorting = true)
            }
            listOf(a.await(), b.await())
        }

        assertTrue(results.all { it.success })
        val jpeg = File(rootDir, "Selection/JPEG").list()!!.sorted()
        assertEquals(listOf("IMG_0100-2.JPG", "IMG_0100.JPG"), jpeg)
        val contents = jpeg.map { File(rootDir, "Selection/JPEG/$it").readText() }.toSet()
        assertEquals(setOf("payload-A", "payload-B"), contents)
        // Exactly one Selection folder: no "Selection (1)" created by a racing findFile/createDirectory.
        assertEquals(
            listOf("Selection"),
            rootDir.list()!!.filter { it.startsWith("Selection") }.sorted(),
        )
    }
}
