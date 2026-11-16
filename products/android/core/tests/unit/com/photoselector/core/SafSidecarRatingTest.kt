package com.photoselector.core

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.photoselector.core.reader.AndroidExifReader
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
import java.io.File

/**
 * A folder on disk served as a document tree; ids are paths below the root ("root/dir/IMG.JPG").
 * A plain ContentProvider answering the document queries by URI: Robolectric's resolver shadow
 * cannot reach a real `DocumentsProvider` through the modern query format.
 */
class SidecarTreeProvider : android.content.ContentProvider() {
    companion object {
        const val AUTHORITY = "com.photoselector.core.test.sidecartree"
        @Volatile var rootDir: File = File(".")
    }

    override fun onCreate() = true

    private fun fileFor(id: String): File =
        if (id == "root") rootDir else File(rootDir, id.removePrefix("root/"))

    private fun idFor(file: File): String =
        if (file == rootDir) "root" else "root/" + file.relativeTo(rootDir).invariantSeparatorsPath

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)

    private fun MatrixCursor.addFile(file: File) {
        newRow().add(Document.COLUMN_DOCUMENT_ID, idFor(file)).add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_FLAGS, 0).add(Document.COLUMN_SIZE, file.length())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: columns)
        val segments = uri.pathSegments
        if (segments.lastOrNull() == "children") {
            fileFor(DocumentsContract.getDocumentId(uri)).listFiles()?.forEach { cursor.addFile(it) }
        } else {
            cursor.addFile(fileFor(DocumentsContract.getDocumentId(uri)))
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** The XMP sidecar rating of a photograph opened through SAF (AS-B-21). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafSidecarRatingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val reader = AndroidExifReader()
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.newFolder("tree")
        SidecarTreeProvider.rootDir = root
        val info = android.content.pm.ProviderInfo().apply {
            authority = SidecarTreeProvider.AUTHORITY
            name = SidecarTreeProvider::class.java.name
            packageName = context.packageName
            exported = true
            grantUriPermissions = true
            applicationInfo = context.applicationInfo
        }
        Robolectric.buildContentProvider(SidecarTreeProvider::class.java).create(info)
    }

    private fun photo(name: String, dir: String = "shoot"): Uri {
        File(root, dir).mkdirs()
        File(root, "$dir/$name").writeBytes(ByteArray(64))
        return documentUri("$dir/$name")
    }

    private fun documentUri(relative: String): Uri = DocumentsContract.buildDocumentUriUsingTree(
        DocumentsContract.buildTreeDocumentUri(SidecarTreeProvider.AUTHORITY, "root"),
        "root/$relative",
    )

    private fun sidecar(name: String, rating: Int, dir: String = "shoot") {
        File(root, "$dir/$name").writeText("<xmp:Rating>$rating</xmp:Rating>")
    }

    @Test
    fun `a tree document reads the file-name sidecar`() = runTest {
        val uri = photo("IMG_1.CR3")
        sidecar("IMG_1.CR3.xmp", 4)
        assertEquals(4, reader.readExif(context, uri)?.rating)
    }

    @Test
    fun `a tree document reads the stem sidecar`() = runTest {
        val uri = photo("IMG_2.ARW")
        sidecar("IMG_2.xmp", 3)
        assertEquals(3, reader.readExif(context, uri)?.rating)
    }

    @Test
    fun `the lookup ignores the case of the sidecar name`() = runTest {
        val uri = photo("IMG_3.JPG")
        sidecar("img_3.XMP", 5)
        assertEquals(5, reader.readExif(context, uri)?.rating)
    }

    @Test
    fun `the file-name sidecar wins over the stem sidecar`() = runTest {
        val uri = photo("IMG_4.CR3")
        sidecar("IMG_4.CR3.xmp", 2)
        sidecar("IMG_4.xmp", 5)
        assertEquals(2, reader.readExif(context, uri)?.rating)
    }

    @Test
    fun `a sidecar in another folder is not used`() = runTest {
        val uri = photo("IMG_5.CR3")
        File(root, "other").mkdirs()
        sidecar("IMG_5.CR3.xmp", 5, dir = "other")
        assertEquals(0, reader.readExif(context, uri)?.rating)
    }

    @Test
    fun `a photograph without a sidecar stays unrated`() = runTest {
        assertEquals(0, reader.readExif(context, photo("IMG_6.CR3"))?.rating)
    }
}
