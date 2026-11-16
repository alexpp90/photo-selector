package com.photoselectortoolbox.data.source

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.photoselectortoolbox.testing.ScaleConditions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Discovery of the folder layouts of the usage-conditions contract (testdata/conditions/conditions.json:
 * `flat-camera-roll`, `dcf-card`) at the sizes users have, through a fake document provider whose
 * cursors count the rows read per directory.
 *
 * Within one directory the first batch waits for the directory's listing: RAW+JPEG pairs and
 * sidecars are grouped over every name in it, and a frame shown before its RAW was known would be
 * deleted without it (measured 2026-11-15: all 10,000 rows of a flat folder are read before the
 * first batch; 26–49 ms in-process on a ThinkPad T14 JVM). Across directories discovery is
 * progressive: a camera card's first folder is shown before the next one is listed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiscoveryScaleTest {

    /** A tree of [layout] (directory id → JPEG count, the root first); counts rows read per directory. */
    class TreeProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val parent = DocumentsContract.getDocumentId(uri)
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val subdirectories = if (parent == ROOT) layout.keys.filter { it != ROOT } else emptyList()
            val files = layout[parent] ?: 0
            val cursor = object : MatrixCursor(columns, files + subdirectories.size) {
                override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
                    rowsRead.getOrPut(parent) { AtomicInteger() }.accumulateAndGet(newPosition + 1, ::maxOf)
                    return super.onMove(oldPosition, newPosition)
                }
            }
            fun row(id: String, name: String, mime: String, modified: Long) = cursor.addRow(columns.map { column ->
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> mime
                    DocumentsContract.Document.COLUMN_SIZE -> 4_000_000L
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modified
                    else -> null
                }
            })
            subdirectories.forEach { dir -> row(dir, dir.substringAfterLast('/'), DocumentsContract.Document.MIME_TYPE_DIR, 0L) }
            for (i in 1..files) {
                val name = "IMG_%05d.JPG".format(i)
                row("$parent/$name", name, "image/jpeg", 1_700_000_000_000L + i * 1_000L)
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            const val AUTHORITY = "scale.documents"
            const val ROOT = "card"

            @Volatile
            var layout: Map<String, Int> = emptyMap()
            val rowsRead = ConcurrentHashMap<String, AtomicInteger>()
        }
    }

    private val treeUri = DocumentsContract.buildTreeDocumentUri(TreeProvider.AUTHORITY, TreeProvider.ROOT)
    /**
     * The source on [Dispatchers.Default], collected on the same dispatcher: `flowOn` then fuses
     * and hands each emission over before the walk goes on, so the rows read when the first batch
     * arrives are the rows read when it was emitted (on another dispatcher a 64-deep buffer lets
     * the walk run ahead).
     */
    private val source get() = LocalImageSourceImpl(RuntimeEnvironment.getApplication(), Dispatchers.Default)

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(TreeProvider::class.java).create(TreeProvider.AUTHORITY)
    }

    private fun layout(vararg dirs: Pair<String, Int>) {
        TreeProvider.layout = linkedMapOf(*dirs)
        TreeProvider.rowsRead.clear()
    }

    private fun flatFolder(n: Int) = layout(TreeProvider.ROOT to n)

    /** A camera card of [n] photos: DCIM folders of the contract's DCF size, the last one partly full. */
    private fun dcfCard(n: Int) {
        val perDir = ScaleConditions.dcfFilesPerDir
        val dirs = (0 until (n + perDir - 1) / perDir).map { d ->
            "${TreeProvider.ROOT}/${100 + d}CANON" to minOf(perDir, n - d * perDir)
        }
        layout(TreeProvider.ROOT to 0, *dirs.toTypedArray())
    }

    /**
     * REQ-AND-PERF.03, REQ-AND-PERF.10: on a camera card of the PR size the first batch is shown
     * once the first DCIM folder is listed, before the next one is read.
     *
     * Scale: pr
     */
    @Test
    fun `the first batch of a camera card comes before its second folder is listed`() = runBlocking {
        val n = ScaleConditions.prPhotos
        dcfCard(n)
        val firstDir = "${TreeProvider.ROOT}/100CANON"

        val first = runBlocking(Dispatchers.Default) { source.discoverImages(treeUri).first() }

        assertTrue("a first batch", first.isNotEmpty())
        assertTrue("the first batch is from the first folder", first.all { "/100CANON/" in Uri.decode(it.uri) })
        assertEquals(ScaleConditions.dcfFilesPerDir, TreeProvider.rowsRead[firstDir]?.get())
        assertEquals("rows of the other folders read before the first batch", setOf(TreeProvider.ROOT, firstDir), TreeProvider.rowsRead.keys)
    }

    /**
     * REQ-AND-PERF.03, REQ-AND-PERF.10: in a flat camera roll the time to the first batch is the
     * time to list the folder, and both grow linearly with it; so does the whole of a camera card.
     *
     * Scale: pr
     */
    @Test
    fun `listing a large folder grows linearly, to the first batch and to the end`() {
        ScaleConditions.assertGrowth("discovery, flat folder: first batch") { n ->
            flatFolder(n);
            { runBlocking(Dispatchers.Default) { check(source.discoverImages(treeUri).first().isNotEmpty()) } }
        }
        ScaleConditions.assertGrowth("discovery, flat folder: whole folder") { n ->
            flatFolder(n);
            { runBlocking(Dispatchers.Default) { check(source.discoverImages(treeUri).last().size == n) } }
        }
        ScaleConditions.assertGrowth("discovery, camera card: whole card") { n ->
            dcfCard(n);
            { runBlocking(Dispatchers.Default) { check(source.discoverImages(treeUri).last().size == n) } }
        }
    }

    /**
     * REQ-AND-PERF.03: within one directory nothing is emitted before the directory is listed to
     * the end, so no frame is shown (and filed or deleted) before its RAW and sidecars are known.
     *
     * Scale: pr
     */
    @Test
    fun `a flat folder's first batch follows its whole listing, companions known`() = runBlocking {
        val n = ScaleConditions.prPhotos
        flatFolder(n)

        val first = runBlocking(Dispatchers.Default) { source.discoverImages(treeUri).first() }

        assertTrue("a first batch", first.isNotEmpty())
        assertEquals(n, TreeProvider.rowsRead[TreeProvider.ROOT]?.get())
    }

    // REQ-AND-PERF.03
    @Test
    fun `every photo of a camera card is discovered once`() = runBlocking {
        val n = ScaleConditions.prPhotos
        dcfCard(n)

        val all = source.discoverImages(treeUri).last()

        assertEquals(n, all.size)
        assertEquals(n, all.map { it.uri }.toSet().size)
    }
}
