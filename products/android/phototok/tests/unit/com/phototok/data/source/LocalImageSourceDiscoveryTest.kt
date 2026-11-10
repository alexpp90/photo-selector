package com.phototok.data.source

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the progressive, cursor-based folder walk in [LocalImageSourceImpl].
 *
 * Two things are being pinned down here:
 *  1. **Streaming** — the first batch must arrive after
 *     [LocalImageSourceImpl.FIRST_BATCH_SIZE] photos so the user can start
 *     swiping, with the rest appended in chunks. Emissions are cumulative and
 *     append-only, which is what lets the feed grow without reordering.
 *  2. **One query per directory** — the walk reads every attribute it needs from
 *     a single cursor per folder. The `DocumentFile` API it replaced cost roughly
 *     five ContentResolver round-trips *per file*, which is what made large
 *     folders take tens of seconds to open.
 *
 * The real `DocumentsContract` URI builders are used (no static mocking): the
 * fake resolver routes a children query back to a directory by reading the
 * parent document id out of the URI the production code built.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalImageSourceDiscoveryTest {

    private companion object {
        const val DIR = DocumentsContract.Document.MIME_TYPE_DIR
        const val AUTHORITY = "com.phototok.test.documents"
        const val ROOT = "root"
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /** `content://…/tree/root/document/<parentId>/children` → `<parentId>`. */
        fun parentDocumentIdOf(childrenUri: Uri): String? =
            childrenUri.pathSegments.getOrNull(3)
    }

    private class Row(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long = 10L,
        val modified: Long = 5L,
    )

    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

    /** Directory id → children. A missing id models an unreadable directory. */
    private var tree: Map<String, List<Row>> = emptyMap()
    private var queryCount = 0

    private val contentResolver: ContentResolver = mockk {
        every { query(any(), any(), any(), any(), any()) } answers {
            queryCount++
            val parentId = parentDocumentIdOf(firstArg())
            tree[parentId]?.let { rows ->
                MatrixCursor(COLUMNS).apply {
                    rows.forEach { addRow(arrayOf<Any?>(it.id, it.name, it.mime, it.size, it.modified)) }
                }
            }
        }
    }

    private val context: Context = mockk {
        every { this@mockk.contentResolver } returns this@LocalImageSourceDiscoveryTest.contentResolver
    }

    private val source = LocalImageSourceImpl(
        context = context,
        androidExifReader = mockk(relaxed = true),
        mediaStoreReader = mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        queryCount = 0
    }

    private fun photos(count: Int) = (1..count).map {
        Row(id = "f$it", name = "IMG_$it.jpg", mime = "image/jpeg", modified = it.toLong())
    }

    @Test
    fun `a large flat folder is emitted progressively and cumulatively`() = runTest {
        tree = mapOf(ROOT to photos(600))

        val emissions = source.discoverImages(treeUri).toList()

        assertEquals(
            "the user must get a usable feed after the first small batch",
            LocalImageSourceImpl.FIRST_BATCH_SIZE,
            emissions.first().size,
        )
        assertEquals(listOf(24, 274, 524, 600), emissions.map { it.size })
        assertTrue(
            "each emission must extend the previous one, never reorder it",
            emissions.zipWithNext().all { (earlier, later) -> later.take(earlier.size) == earlier },
        )
    }

    @Test
    fun `a folder is read with a single query, not one per file`() = runTest {
        tree = mapOf(ROOT to photos(600))

        source.discoverImages(treeUri).toList()

        assertEquals("600 photos must cost one directory query", 1, queryCount)
    }

    @Test
    fun `a folder smaller than the first batch emits once`() = runTest {
        tree = mapOf(ROOT to photos(5))

        assertEquals(listOf(5), source.discoverImages(treeUri).toList().map { it.size })
    }

    @Test
    fun `an empty folder emits a single empty list`() = runTest {
        tree = mapOf(ROOT to emptyList())

        assertEquals(listOf(0), source.discoverImages(treeUri).toList().map { it.size })
    }

    @Test
    fun `nested folders are walked, excluding app folders, hidden and unsupported files`() = runTest {
        tree = mapOf(
            ROOT to listOf(
                Row("d1", "Sub", DIR),
                Row("d2", "PhotoTok_Selection", DIR), // excluded by name (legacy)
                Row("d4", "Selection", DIR), // excluded by name (standard)
                Row("d5", "Selected", DIR), // excluded by name (cross-product compatibility)
                Row("d6", "PhotoTok_LeftSwipe", DIR), // excluded by name (app-managed)
                Row("a", "a.jpg", "image/jpeg"),
                Row("h", ".hidden.jpg", "image/jpeg"), // hidden
                Row("v", "clip.mp4", "video/mp4"), // unsupported extension
                Row("n", "notes", "text/plain"), // no extension
            ),
            "d1" to listOf(Row("b", "b.ARW", "image/x-sony-arw"), Row("d3", "Deep", DIR)),
            "d2" to listOf(Row("x", "x.jpg", "image/jpeg")),
            "d4" to listOf(Row("s", "s.jpg", "image/jpeg")),
            "d5" to listOf(Row("sel", "sel.jpg", "image/jpeg")),
            "d6" to listOf(Row("left", "left.jpg", "image/jpeg")),
            "d3" to listOf(Row("c", "c.dng", "image/dng")),
        )

        val images = source.discoverImages(treeUri).toList().last()

        assertEquals(listOf("a.jpg", "b.ARW", "c.dng"), images.map { it.fileName }.sorted())
        assertFalse(
            "the app's own selection folder must not be re-ingested",
            images.any { it.fileName in setOf("x.jpg", "s.jpg", "sel.jpg", "left.jpg") },
        )
    }

    @Test
    fun `the Casual card import folder is never re-ingested by the Camera source`() = runTest {
        // DCIM/Camera/Jackpix holds the copies a Casual swipe right made from an SD card.
        tree = mapOf(
            ROOT to listOf(
                Row("j", "Jackpix", DIR),
                Row("jl", "jackpix", DIR), // folder names compare case-insensitively
                Row("a", "a.jpg", "image/jpeg"),
            ),
            "j" to listOf(Row("copy", "copy.jpg", "image/jpeg")),
            "jl" to listOf(Row("copy2", "copy2.jpg", "image/jpeg")),
        )

        val images = source.discoverImages(treeUri).toList().last()

        assertEquals(listOf("a.jpg"), images.map { it.fileName })
    }

    @Test
    fun `an unreadable directory is skipped instead of aborting the walk`() = runTest {
        tree = mapOf(
            // "broken" has no entry, so its query returns null.
            ROOT to listOf(Row("broken", "Broken", DIR), Row("ok", "ok.jpg", "image/jpeg")),
        )

        val images = source.discoverImages(treeUri).toList().last()

        assertEquals(listOf("ok.jpg"), images.map { it.fileName })
    }

    @Test
    fun `image metadata comes from the directory cursor`() = runTest {
        tree = mapOf(ROOT to listOf(Row("m", "M.jpg", "image/jpeg", size = 4242L, modified = 999L)))

        val item = source.discoverImages(treeUri).toList().last().single()

        assertEquals("M.jpg", item.fileName)
        assertEquals(4242L, item.fileSize)
        assertEquals(999L, item.lastModified)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "m").toString(),
            item.uri,
        )
        // Dimensions stay unread here — they are resolved lazily by the ViewModel.
        assertEquals(0, item.imageWidth)
        assertEquals(0, item.imageHeight)
    }

    @Test
    fun `a folder URI that is not a document tree yields an empty feed`() = runTest {
        val notATree = Uri.parse("content://$AUTHORITY/whatever")

        assertEquals(listOf(0), source.discoverImages(notATree).toList().map { it.size })
    }

    @Test
    fun `opening the Selection viewer finds the folder with one query, not a listing of every child`() = runTest {
        tree = mapOf(
            ROOT to photos(3000) + Row("sel", "Selection", DIR),
            "sel" to listOf(
                Row("s1", "keep1.jpg", "image/jpeg", modified = 7L),
                Row("s2", "keep2.jpg", "image/jpeg", modified = 9L),
            ),
        )

        val listing = source.listSelectionImages(treeUri, "Selection")

        listing as SelectionListing.Available
        assertEquals(listOf("keep2.jpg", "keep1.jpg"), listing.images.map { it.fileName })
        assertEquals("Selection", listing.folderName)
        assertEquals("one query for the root, one for the Selection folder", 2, queryCount)
    }

    @Test
    fun `a missing Selection folder, or a file of that name, is reported as missing`() = runTest {
        tree = mapOf(ROOT to listOf(Row("f", "Selection", "image/jpeg"), Row("a", "a.jpg", "image/jpeg")))

        assertEquals(SelectionListing.Missing, source.listSelectionImages(treeUri, "Selection"))
        assertEquals(SelectionListing.Missing, source.listSelectionImages(treeUri, "Keepers"))
    }

    @Test
    fun `a stray Selection marker does not hide the photos of a source folder`() = runTest {
        tree = mapOf(
            ROOT to listOf(Row("t", "Trips", DIR), Row("a", "a.jpg", "image/jpeg")),
            "t" to listOf(
                Row("m", LocalImageSourceImpl.SELECTION_MARKER_FILE, "application/octet-stream"),
                Row("t1", "trip1.jpg", "image/jpeg"),
            ),
        )

        val images = source.discoverImages(treeUri).toList().last()

        assertEquals(listOf("a.jpg", "trip1.jpg"), images.map { it.fileName }.sorted())
    }

    @Test
    fun `the output folders of the app are skipped by name, a copy of favourites included`() = runTest {
        tree = mapOf(
            ROOT to listOf(Row("f", "Favourites", DIR), Row("k", "Keepers", DIR), Row("a", "a.jpg", "image/jpeg")),
            "f" to listOf(Row("f1", "fav.jpg", "image/jpeg")),
            "k" to listOf(Row("k1", "keep.jpg", "image/jpeg")),
        )

        assertEquals(
            "Keepers is a user's folder until the caller names it as output",
            listOf("a.jpg", "keep.jpg"),
            source.discoverImages(treeUri).toList().last().map { it.fileName }.sorted(),
        )
        assertEquals(
            listOf("a.jpg"),
            source.discoverImages(treeUri, setOf("Keepers")).toList().last().map { it.fileName },
        )
    }

    // ── Displayed dimensions (EXIF orientation) ───────────────────────────

    private fun jpegWithOrientation(width: Int, height: Int, orientation: Int): ByteArray {
        val file = java.io.File.createTempFile("orient", ".jpg")
        try {
            java.io.FileOutputStream(file).use {
                android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)
            }
            androidx.exifinterface.media.ExifInterface(file.absolutePath).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> {
        val uri = Uri.parse("content://$AUTHORITY/photo")
        every { contentResolver.openInputStream(uri) } answers { java.io.ByteArrayInputStream(bytes) }
        return kotlinx.coroutines.runBlocking { source.getImageDimensions(uri) }
    }

    @Test
    fun `a photo stored landscape but rotated by EXIF reports portrait dimensions`() {
        assertEquals(Pair(20, 40), dimensionsOf(jpegWithOrientation(40, 20, 6)))
        assertEquals(Pair(20, 40), dimensionsOf(jpegWithOrientation(40, 20, 8)))
    }

    @Test
    fun `an upright or 180 degree photo keeps its stored dimensions`() {
        assertEquals(Pair(40, 20), dimensionsOf(jpegWithOrientation(40, 20, 1)))
        assertEquals(Pair(40, 20), dimensionsOf(jpegWithOrientation(40, 20, 3)))
    }

    @Test
    fun `displayedDimensions swaps exactly for orientations 5 to 8`() {
        for (o in 0..9) {
            val expected = if (o in 5..8) Pair(2, 1) else Pair(1, 2)
            assertEquals("orientation $o", expected, LocalImageSourceImpl.displayedDimensions(1, 2, o))
        }
    }
}
