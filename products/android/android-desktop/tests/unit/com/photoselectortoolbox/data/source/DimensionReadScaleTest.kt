package com.photoselectortoolbox.data.source

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.testing.ScaleConditions
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dimension backfill reads each photo's header with **one** open (REQ-AND-PERF.05). On a
 * card reader or a camera over MTP a file open is the expensive part (contract `storage`,
 * testdata/conditions/conditions.json), and the backfill opens every photo of the folder: the
 * EXIF orientation read used to open each file a second time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DimensionReadScaleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val opens = ConcurrentHashMap<String, AtomicInteger>()

    /** A 60 × 40 JPEG with EXIF [orientation], as bytes. */
    private fun jpegBytes(orientation: Int): ByteArray {
        val file = File(tmp.root, "sample-$orientation.jpg")
        file.outputStream().use { Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    /** [n] content URIs served from [bytes], each open counted (a fake resolver). */
    private fun photos(n: Int, bytes: ByteArray, prefix: String): List<String> = (1..n).map { i ->
        val uri = "content://scale.photos/document/$prefix-IMG_$i.jpg"
        shadowOf(context.contentResolver).registerInputStreamSupplier(Uri.parse(uri)) {
            opens.getOrPut(uri) { AtomicInteger() }.incrementAndGet()
            ByteArrayInputStream(bytes)
        }
        uri
    }

    /**
     * REQ-AND-PERF.05: backfilling a folder of the PR size opens every photo once, sizes it as it
     * is shown, and a second pass over the same URIs opens nothing.
     *
     * Scale: pr
     */
    @Test
    fun `the backfill of a folder of the PR size opens each photo once`() = runBlocking {
        val n = ScaleConditions.prPhotos
        val turned = photos(n / 2, jpegBytes(ExifInterface.ORIENTATION_ROTATE_90), "turned")
        val upright = photos(n - n / 2, jpegBytes(ExifInterface.ORIENTATION_NORMAL), "upright")
        val source = LocalImageSourceImpl(context, Dispatchers.IO)

        val sizes = source.resolveDimensions(turned + upright)
        source.resolveDimensions(turned + upright)

        assertEquals(n, sizes.size)
        assertEquals(setOf(ImageDimensions.of(40, 60)), turned.map { sizes[it] }.toSet())
        assertEquals(setOf(ImageDimensions.of(60, 40)), upright.map { sizes[it] }.toSet())
        assertEquals("files opened", n, opens.size)
        assertEquals("opens per photo", setOf(1), opens.values.map { it.get() }.toSet())
    }

    /**
     * [jpeg] with ~650 KB of APP15 segments after its EXIF block, so its frame header lies beyond
     * the header read's mark (a JPEG with a large ICC profile or maker data).
     */
    private fun withPaddedHeader(jpeg: ByteArray): ByteArray {
        check(jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE1.toByte()) { "EXIF APP1 right after SOI" }
        val app1End = 4 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        val padding = java.io.ByteArrayOutputStream()
        repeat(10) {
            val length = 65_535
            padding.write(byteArrayOf(0xFF.toByte(), 0xEF.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()))
            padding.write(ByteArray(length - 2))
        }
        return jpeg.copyOfRange(0, app1End) + padding.toByteArray() + jpeg.copyOfRange(app1End, jpeg.size)
    }

    // REQ-AND-PERF.05
    @Test
    fun `a header beyond the shared read still gets its orientation, with a second open`() = runBlocking {
        val padded = photos(1, withPaddedHeader(jpegBytes(ExifInterface.ORIENTATION_ROTATE_270)), "padded").single()

        val sizes = LocalImageSourceImpl(context, Dispatchers.IO).resolveDimensions(listOf(padded))

        assertEquals(ImageDimensions.of(40, 60), sizes[padded])
        assertEquals(2, opens.getValue(padded).get())
    }
}
