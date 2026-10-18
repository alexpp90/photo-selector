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
 * Android Desktop's adapter over the shared SAF engine (`:saf`, REQ-AND-PLATFORM.06,
 * `docs/shared/FILE_OPERATIONS.md`): what the product decides — Selection folder name, the
 * second pass into `Final`, the custom Selection destination, settings failures, bundles of
 * [ImageItem]s. The engine's data-safety contract (collision-free naming, size verification,
 * partial moves, undo, D-5 locking) is tested once in `:saf`
 * (`AllOrNothingSafTransferTest`, `SafTransferContractTest`).
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

    private fun settingsWith(
        primary: String = "Selection",
        secondary: String? = null,
        customDestination: String? = null,
    ): SettingsRepository = mockk(relaxed = true) {
        every { selectionFolderName } returns MutableStateFlow(primary)
        every { secondarySelectionFolderName } returns MutableStateFlow(secondary ?: "")
        every { customSelectionDestinationUri } returns MutableStateFlow(customDestination ?: "")
    }

    private fun repositoryWith(settings: SettingsRepository) = ImageRepositoryImpl(
        localImageSource = mockk<LocalImageSource>(relaxed = true),
        androidExifReader = AndroidExifReader(),
        mediaStoreReader = MediaStoreReader(),
        settingsRepository = settings,
    ).also { it.ioDispatcher = UnconfinedTestDispatcher() }

    private fun treeUriOf(relativePath: String): Uri = DocumentsContract.buildTreeDocumentUri(
        FakeSafDocumentsProvider.AUTHORITY,
        "${FakeSafDocumentsProvider.ROOT_ID}/$relativePath",
    )

    // ── Product configuration ────────────────────────────────────────────

    // REQ-AND-PLATFORM.06
    @Test
    fun `the configured Selection folder name is used and the marker is written`() = runTest {
        File(rootDir, "IMG_0001.JPG").writeText("payload")
        val repo = repositoryWith(settingsWith(primary = "  Keepers  "))

        val result = repo.copyImage(context, documentUriFor("IMG_0001.JPG"), treeUri, sorting = true)

        assertTrue(result.success)
        assertTrue(File(rootDir, "Keepers/JPEG/IMG_0001.JPG").exists())
        assertTrue(File(rootDir, "Keepers/${ImageRepositoryImpl.SELECTION_MARKER_FILE_NAME}").exists())
    }

    // REQ-AND-PLATFORM.06: second pass — the opened folder already is a Selection folder.
    @Test
    fun `culling inside a Selection folder files into the secondary Final folder`() = runTest {
        File(rootDir, "Selection").mkdirs()
        File(rootDir, "Selection/IMG_0002.JPG").writeText("first-pass")
        val repo = repositoryWith(settingsWith(secondary = ""))

        val result = repo.copyImage(context, documentUriFor("Selection/IMG_0002.JPG"), treeUriOf("Selection"), sorting = false)

        assertTrue(result.success)
        assertEquals("first-pass", File(rootDir, "Selection/Final/IMG_0002.JPG").readText())
        assertFalse(File(rootDir, "Selection/Selection").exists())
    }

    @Test
    fun `a folder holding the Selection marker is a second pass whatever its name`() = runTest {
        File(rootDir, "Culled").mkdirs()
        File(rootDir, "Culled/${ImageRepositoryImpl.SELECTION_MARKER_FILE_NAME}").writeText("")
        File(rootDir, "Culled/IMG_0003.JPG").writeText("payload")
        val repo = repositoryWith(settingsWith(secondary = "Best"))

        val result = repo.copyImage(context, documentUriFor("Culled/IMG_0003.JPG"), treeUriOf("Culled"), sorting = false)

        assertTrue(result.success)
        assertTrue(File(rootDir, "Culled/Best/IMG_0003.JPG").exists())
    }

    // REQ-AND-PLATFORM.06: a custom Selection destination wins and is never a second pass.
    @Test
    fun `a custom Selection destination receives the Selection folder`() = runTest {
        File(rootDir, "card").mkdirs()
        File(rootDir, "card/IMG_0004.JPG").writeText("payload")
        File(rootDir, "Selection").mkdirs()
        val repo = repositoryWith(settingsWith(customDestination = treeUriOf("Selection").toString()))

        val result = repo.moveImage(context, documentUriFor("card/IMG_0004.JPG"), treeUriOf("card"), sorting = true)

        assertTrue(result.success)
        assertEquals("payload", File(rootDir, "Selection/Selection/JPEG/IMG_0004.JPG").readText())
        assertFalse(File(rootDir, "card/IMG_0004.JPG").exists())
        assertFalse(File(rootDir, "card/Selection").exists())
    }

    @Test
    fun `a failing settings read fails every source with its message instead of throwing`() = runTest {
        File(rootDir, "IMG_0005.JPG").writeText("payload")
        val broken: SettingsRepository = mockk(relaxed = true) {
            every { selectionFolderName } returns kotlinx.coroutines.flow.flow { throw IllegalStateException("datastore corrupt") }
        }

        val result = repositoryWith(broken).moveImage(context, documentUriFor("IMG_0005.JPG"), treeUri, sorting = true)

        assertFalse(result.success)
        assertEquals("datastore corrupt", result.error)
        assertTrue(File(rootDir, "IMG_0005.JPG").exists())
    }

    // REQ-AND-PLATFORM.06 (formerly part of the mirrored CancellationException test)
    @Test
    fun `a cancelled settings read is rethrown, never swallowed into a failure`() = runTest {
        val cancelling: SettingsRepository = mockk(relaxed = true) {
            every { selectionFolderName } returns kotlinx.coroutines.flow.flow { throw CancellationException("Transfer cancelled by scope") }
        }
        try {
            repositoryWith(cancelling).copyImage(context, documentUriFor("ANY.JPG"), treeUri, sorting = true)
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("Transfer cancelled by scope", expected.message)
        }
    }

    // ── Bundles of ImageItems ────────────────────────────────────────────

    // REQ-AND-PLATFORM.06, REQ-AND-PLATFORM.12
    @Test
    fun `moveBundle carries the item's parentDocumentId and undoMoveBundle restores into that folder`() = runTest {
        val subDir = File(rootDir, "sub/day1").apply { mkdirs() }
        File(subDir, "IMG_0010.JPG").writeText("jpeg")
        File(subDir, "IMG_0010.CR3").writeText("raw")
        val item = ImageItem(
            uri = documentUriFor("sub/day1/IMG_0010.JPG").toString(),
            fileName = "IMG_0010.JPG",
            fileSize = 4,
            lastModified = 1L,
            mimeType = "image/jpeg",
            companions = listOf(CompanionFile(uri = documentUriFor("sub/day1/IMG_0010.CR3").toString(), fileName = "IMG_0010.CR3")),
            parentDocumentId = "root/sub/day1",
        )

        val moved = repository.moveBundle(context, item, treeUri, sorting = true)
        assertTrue(moved.success)
        assertEquals("root/sub/day1", moved.parentDocumentId)
        assertEquals(2, moved.destinationUris.size)
        assertEquals("raw", File(rootDir, "Selection/RAW/IMG_0010.CR3").readText())

        val undone = repository.undoMoveBundle(context, moved.destinationUris, treeUri, moved.parentDocumentId)

        assertTrue(undone.success)
        assertEquals(listOf("IMG_0010.CR3", "IMG_0010.JPG"), subDir.list()!!.sorted())
    }

    @Test
    fun `undoMove restores a single file into the opened folder`() = runTest {
        File(rootDir, "IMG_0011.JPG").writeText("payload")
        val moved = repository.moveImage(context, documentUriFor("IMG_0011.JPG"), treeUri, sorting = false)

        val undone = repository.undoMove(context, Uri.parse(moved.destinationUri), treeUri)

        assertTrue(undone.success)
        assertEquals("payload", File(rootDir, "IMG_0011.JPG").readText())
    }

    // The sorting rule is Android Desktop's own (ImageFormats.selectionSubfolder).
    @Test
    fun `sorting follows ImageFormats, Lightroom edits go to RAW`() = runTest {
        listOf("IMG_0012.ARW", "IMG_0012.JPG", "IMG_0012-Edit.JPG", "IMG_0012.ARW.xmp").forEach { File(rootDir, it).writeText(it) }

        val results = repository.transferBundle(
            context, listOf("IMG_0012.ARW", "IMG_0012.JPG", "IMG_0012-Edit.JPG", "IMG_0012.ARW.xmp").map(::documentUriFor),
            treeUri, sorting = true, isMove = false,
        )

        assertTrue(results.all { it.success })
        assertTrue(File(rootDir, "Selection/RAW/IMG_0012.ARW").exists())
        assertTrue(File(rootDir, "Selection/JPEG/IMG_0012.JPG").exists())
        assertTrue(File(rootDir, "Selection/RAW/IMG_0012-Edit.JPG").exists())
        assertTrue(File(rootDir, "Selection/RAW/IMG_0012.ARW.xmp").exists())
    }

    // REQ-AND-PLATFORM.06, REQ-AND-PLATFORM.12
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
}
