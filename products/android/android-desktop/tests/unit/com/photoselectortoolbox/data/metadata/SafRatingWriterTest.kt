package com.photoselectortoolbox.data.metadata

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.exifinterface.media.ExifInterface
import com.photoselector.core.Requirement
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Rating write-back over SAF (REQ-AND-RATING.01) against a fake documents provider whose ids are
 * all opaque (`msf:N`, `doc:N`) — nothing here can pass by splitting an id on `/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Requirement("REQ-AND-RATING.01")
class SafRatingWriterTest {

    class RatingProvider : ContentProvider() {
        companion object {
            const val AUTHORITY = "com.photoselectortoolbox.test.safrating"
            const val ROOT_ID = "root"

            @Volatile var rootDir: File = File(".")
            private val ids = ConcurrentHashMap<File, String>()
            private val files = ConcurrentHashMap<String, File>()
            @Volatile var counter = 0

            /** (file name, open mode) of every openFile call. */
            val opens = CopyOnWriteArrayList<Pair<String, String>>()
            @Volatile var failReadOf: String? = null
            /** The first truncating open of this file name truncates, then throws (a write that dies half way). */
            @Volatile var dieAfterTruncateOf: String? = null
            @Volatile var renameCreatedTo: ((String) -> String)? = null
            @Volatile var supportFindDocumentPath = false
            val findPathCalls = java.util.concurrent.atomic.AtomicInteger(0)

            fun reset(root: File) {
                rootDir = root; ids.clear(); files.clear(); counter = 0; opens.clear()
                failReadOf = null; dieAfterTruncateOf = null; renameCreatedTo = null
                supportFindDocumentPath = false; findPathCalls.set(0)
            }

            fun idFor(file: File): String {
                if (file == rootDir) return ROOT_ID
                return ids.getOrPut(file) {
                    val id = (if (file.isDirectory) "msf:" else "doc:") + (++counter)
                    files[id] = file
                    id
                }
            }

            fun fileFor(id: String): File = if (id == ROOT_ID) rootDir else requireNotNull(files[id]) { "unknown $id" }
        }

        override fun onCreate() = true

        private fun docId(uri: Uri): String {
            val s = uri.pathSegments
            val i = s.indexOf("document")
            return if (i >= 0) s[i + 1] else s[1]
        }

        private fun mime(f: File) = if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"

        private fun row(c: MatrixCursor, cols: Array<out String>, f: File) {
            c.addRow(cols.map {
                when (it) {
                    Document.COLUMN_DOCUMENT_ID -> idFor(f)
                    Document.COLUMN_DISPLAY_NAME -> f.name
                    Document.COLUMN_MIME_TYPE -> mime(f)
                    Document.COLUMN_SIZE -> f.length()
                    Document.COLUMN_LAST_MODIFIED -> f.lastModified()
                    Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
                        (if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
                    else -> null
                }
            }.toTypedArray<Any?>())
        }

        override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            val cols = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
            val c = MatrixCursor(cols)
            val f = fileFor(docId(uri))
            if (uri.pathSegments.lastOrNull() == "children") {
                f.listFiles()?.sortedBy { it.name }?.forEach { row(c, cols, it) }
            } else if (f.exists()) row(c, cols, f)
            return c
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            @Suppress("DEPRECATION") val uri = extras?.getParcelable<Uri>("uri")
            return when (method) {
                "android:createDocument" -> {
                    val parent = fileFor(docId(requireNotNull(uri)))
                    val requested = requireNotNull(extras!!.getString(Document.COLUMN_DISPLAY_NAME))
                    val target = File(parent, renameCreatedTo?.invoke(requested) ?: requested)
                    target.createNewFile()
                    Bundle().apply {
                        putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, idFor(target)))
                    }
                }
                "android:deleteDocument" -> { fileFor(docId(requireNotNull(uri))).delete(); Bundle() }
                "android:findDocumentPath" -> {
                    if (!supportFindDocumentPath) return super.call(method, arg, extras)
                    findPathCalls.incrementAndGet()
                    var f: File? = fileFor(docId(requireNotNull(uri)))
                    val path = ArrayDeque<String>()
                    while (f != null) {
                        path.addFirst(idFor(f))
                        if (f == rootDir) break
                        f = f.parentFile
                    }
                    Bundle().apply { putParcelable("result", DocumentsContract.Path(ROOT_ID, path.toList())) }
                }
                else -> super.call(method, arg, extras)
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val f = fileFor(docId(uri))
            opens += f.name to mode
            if (mode.contains('r') && !mode.contains('w') && f.name == failReadOf) {
                throw FileNotFoundException("simulated read failure")
            }
            if (mode.contains('t') && f.name == dieAfterTruncateOf) {
                dieAfterTruncateOf = null
                f.writeBytes(ByteArray(0))
                throw IOException("simulated death after truncate")
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
        }

        override fun getType(uri: Uri) = mime(fileFor(docId(uri)))
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(RatingProvider.AUTHORITY, RatingProvider.ROOT_ID)
    private val writer = MetadataWriterImpl()

    @Before
    fun setUp() {
        root = tmp.newFolder("shoot")
        RatingProvider.reset(root)
        Robolectric.buildContentProvider(RatingProvider::class.java).create(RatingProvider.AUTHORITY)
        val component = ComponentName(context.packageName, RatingProvider::class.java.name)
        val info = ProviderInfo().apply {
            authority = RatingProvider.AUTHORITY; name = component.className; packageName = component.packageName
            exported = true; grantUriPermissions = true; applicationInfo = context.applicationInfo
        }
        val pm = shadowOf(context.packageManager)
        pm.addOrUpdateProvider(info)
        pm.addIntentFilterForProvider(component, IntentFilter("android.content.action.DOCUMENTS_PROVIDER"))
    }

    private fun uriOf(f: File): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, RatingProvider.idFor(f))

    private fun file(rel: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5)): File {
        val f = File(root, rel)
        f.parentFile!!.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    private fun jpeg(rel: String): File {
        val f = file(rel)
        java.io.FileOutputStream(f).use { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    private fun writesTo(name: String) = RatingProvider.opens.filter { it.first == name && it.second.any { c -> c == 'w' || c == 'a' } }

    @Test
    fun `RAW in a folder with an opaque id gets a new file sidecar and is never opened for writing`() = runTest {
        val raw = file("2026/day 1/IMG_1.ARW", byteArrayOf(9, 8, 7))
        val before = raw.readBytes()

        val result = writer.writeRating(context, uriOf(raw), 4)

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val sidecar = File(raw.parentFile, "IMG_1.ARW.xmp")
        assertTrue(sidecar.readText().contains("<xmp:Rating>4</xmp:Rating>"))
        assertTrue(before.contentEquals(raw.readBytes()))
        assertTrue(writesTo("IMG_1.ARW").isEmpty())
        assertFalse(File(root, "IMG_1.ARW.xmp").exists())
        assertEquals(setOf("IMG_1.ARW", "IMG_1.ARW.xmp"), raw.parentFile!!.list()!!.toSet())
    }

    @Test
    fun `existing sidecar keeps all its XMP content when the rating changes`() = runTest {
        val raw = file("a/IMG_2.ARW")
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            |<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="+0.35">
            |<xmp:Rating>2</xmp:Rating><xmp:Label>Red</xmp:Label></rdf:Description></rdf:RDF></x:xmpmeta>""".trimMargin()
        val sidecar = file("a/IMG_2.ARW.xmp", xmp.toByteArray())

        assertTrue(writer.writeRating(context, uriOf(raw), 5).isSuccess)

        assertEquals(xmp.replace("<xmp:Rating>2</xmp:Rating>", "<xmp:Rating>5</xmp:Rating>"), sidecar.readText())
        assertTrue(writesTo("IMG_2.ARW").isEmpty())
    }

    @Test
    fun `file sidecar wins over stem sidecar when both exist`() = runTest {
        val raw = file("IMG_3.CR3")
        val stem = file("IMG_3.xmp", "<x:xmpmeta><rdf:Description><xmp:Rating>1</xmp:Rating></rdf:Description></x:xmpmeta>".toByteArray())
        val stemBefore = stem.readBytes()
        val named = file("IMG_3.CR3.xmp", "<x:xmpmeta><rdf:Description><xmp:Rating>2</xmp:Rating></rdf:Description></x:xmpmeta>".toByteArray())

        assertTrue(writer.writeRating(context, uriOf(raw), 3).isSuccess)

        assertTrue(named.readText().contains("<xmp:Rating>3</xmp:Rating>"))
        assertTrue(stemBefore.contentEquals(stem.readBytes()))
    }

    @Test
    fun `existing stem sidecar is updated and no second sidecar appears`() = runTest {
        val raw = file("IMG_4.NEF")
        val stem = file("IMG_4.xmp", "<x:xmpmeta><rdf:Description><xmp:Rating>1</xmp:Rating></rdf:Description></x:xmpmeta>".toByteArray())

        assertTrue(writer.writeRating(context, uriOf(raw), 5).isSuccess)

        assertTrue(stem.readText().contains("<xmp:Rating>5</xmp:Rating>"))
        assertFalse(File(root, "IMG_4.NEF.xmp").exists())
    }

    @Test
    fun `unreadable existing sidecar fails the write and stays byte-identical`() = runTest {
        val raw = file("IMG_5.ARW")
        val sidecar = file("IMG_5.ARW.xmp", "<x:xmpmeta><rdf:Description crs:Look='keep'/></x:xmpmeta>".toByteArray())
        val before = sidecar.readBytes()
        RatingProvider.failReadOf = "IMG_5.ARW.xmp"

        val result = writer.writeRating(context, uriOf(raw), 4)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RatingWriteException)
        assertTrue(before.contentEquals(sidecar.readBytes()))
        assertTrue(writesTo("IMG_5.ARW.xmp").isEmpty())
        assertTrue(writesTo("IMG_5.ARW").isEmpty())
    }

    @Test
    fun `existing sidecar that is not UTF-8 or not mergeable is left alone`() = runTest {
        val raw = file("IMG_6.ARW")
        val binary = file("IMG_6.ARW.xmp", byteArrayOf(0x3C, 0xC3.toByte(), 0x28, 0x3E))
        val binaryBefore = binary.readBytes()
        assertTrue(writer.writeRating(context, uriOf(raw), 4).isFailure)
        assertTrue(binaryBefore.contentEquals(binary.readBytes()))

        val raw2 = file("IMG_7.ARW")
        val odd = file("IMG_7.ARW.xmp", "<x:xmpmeta><other/></x:xmpmeta>".toByteArray())
        val oddBefore = odd.readBytes()
        assertTrue(writer.writeRating(context, uriOf(raw2), 4).isFailure)
        assertTrue(oddBefore.contentEquals(odd.readBytes()))
        assertTrue(writesTo("IMG_6.ARW.xmp").isEmpty() && writesTo("IMG_7.ARW.xmp").isEmpty())
    }

    @Test
    fun `a write that dies after truncating is rolled back to the original sidecar bytes`() = runTest {
        val raw = file("IMG_8.ARW")
        val sidecar = file("IMG_8.ARW.xmp", "<x:xmpmeta><rdf:Description><xmp:Rating>2</xmp:Rating></rdf:Description></x:xmpmeta>".toByteArray())
        val before = sidecar.readBytes()
        RatingProvider.dieAfterTruncateOf = "IMG_8.ARW.xmp"

        val result = writer.writeRating(context, uriOf(raw), 5)

        assertTrue(result.isFailure)
        assertTrue(before.contentEquals(sidecar.readBytes()))
    }

    @Test
    fun `provider answering findDocumentPath resolves the parent without walking the tree`() = runTest {
        RatingProvider.supportFindDocumentPath = true
        val raw = file("deep/er/IMG_9.DNG")

        assertTrue(writer.writeRating(context, uriOf(raw), 3).isSuccess)

        assertTrue(File(raw.parentFile, "IMG_9.DNG.xmp").exists())
        assertTrue(RatingProvider.findPathCalls.get() > 0)
    }

    @Test
    fun `sidecar the provider renames on create is refused and removed`() = runTest {
        val raw = file("IMG_10.ARW")
        RatingProvider.renameCreatedTo = { "$it (1)" }

        val result = writer.writeRating(context, uriOf(raw), 3)

        assertTrue(result.isFailure)
        assertEquals(listOf("IMG_10.ARW"), root.list()!!.toList())
    }

    @Test
    fun `TIFF gets a sidecar`() = runTest {
        val tif = file("scan.tif")
        assertTrue(writer.writeRating(context, uriOf(tif), 2).isSuccess)
        assertTrue(File(root, "scan.tif.xmp").exists())
        assertTrue(writesTo("scan.tif").isEmpty())
    }

    @Test
    fun `PNG HEIC and WebP fail visibly and leave the folder untouched`() = runTest {
        val files = listOf("a.png", "b.heic", "c.webp").map { file(it) }
        files.forEach { f ->
            val result = writer.writeRating(context, uriOf(f), 4)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is RatingWriteException)
        }
        assertEquals(setOf("a.png", "b.heic", "c.webp"), root.list()!!.toSet())
        assertTrue(RatingProvider.opens.none { it.second.contains('w') })
    }

    @Test
    fun `JPEG gets its rating embedded in the file`() = runTest {
        val jpg = jpeg("p/shot.jpg")
        val result = writer.writeRating(context, uriOf(jpg), 4)
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue(ExifInterface(jpg.absolutePath).getAttribute(ExifInterface.TAG_XMP)!!.contains("<xmp:Rating>4</xmp:Rating>"))
        assertFalse(File(jpg.parentFile, "shot.jpg.xmp").exists())
    }

    @Test
    fun `failed JPEG verification fails the write with no sidecar and untouched bytes`() = runTest {
        val jpg = jpeg("shot2.jpg")
        val before = jpg.readBytes()

        val result = MetadataWriterImpl(validateJpegBounds = { false }).writeRating(context, uriOf(jpg), 4)

        assertTrue(result.isFailure)
        assertTrue(before.contentEquals(jpg.readBytes()))
        assertEquals(listOf("shot2.jpg"), root.list()!!.toList())
        assertTrue(writesTo("shot2.jpg").isEmpty())
    }
}
