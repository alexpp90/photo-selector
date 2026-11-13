package com.photoselectortoolbox.data.source

import android.net.Uri
import com.photoselectortoolbox.conformance.FormatManifest
import com.photoselectortoolbox.data.model.ImageDimensions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A TIFF is sized from its header (REQ-AND-PERF.05): the platform has no TIFF codec, so its
 * header-only bounds read finds nothing, and `containerDimensions` used to answer UNKNOWN for
 * every TIFF — a scan's frame had no shape until the photo was decoded (finding FD-C-23,
 * full review 2026-11-09). Driven through the resolver the scan uses, not the header reader alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TiffDimensionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val source = LocalImageSourceImpl(RuntimeEnvironment.getApplication(), Dispatchers.IO)

    /** A minimal TIFF header with IFD0 holding [entries] (tag, type, value). */
    private fun tiff(name: String, order: ByteOrder, vararg entries: Triple<Int, Int, Long>): String {
        val buffer = ByteBuffer.allocate(8 + 2 + entries.size * 12 + 4).order(order)
        buffer.put(if (order == ByteOrder.BIG_ENDIAN) "MM".toByteArray() else "II".toByteArray())
        buffer.putShort(42)
        buffer.putInt(8)
        buffer.putShort(entries.size.toShort())
        for ((tag, type, value) in entries) {
            buffer.putShort(tag.toShort())
            buffer.putShort(type.toShort())
            buffer.putInt(1)
            if (type == SHORT) {
                buffer.putShort(value.toInt().toShort())
                buffer.putShort(0)
            } else {
                buffer.putInt(value.toInt())
            }
        }
        buffer.putInt(0)
        val file = File(tmp.root, name)
        file.writeBytes(buffer.array())
        return Uri.fromFile(file).toString()
    }

    @Test
    fun `the corpus TIFF is sized from its header`() = runBlocking {
        val file = File(tmp.root, "sample.tif")
        File(FormatManifest.repoRoot, "testdata/formats/bitmaps/sample.tif").copyTo(file)
        val uri = Uri.fromFile(file).toString()

        assertEquals(ImageDimensions.of(400, 266), source.resolveDimensions(listOf(uri))[uri])
    }

    @Test
    fun `a little-endian TIFF with LONG sizes and a sideways orientation is sized as shown`() = runBlocking {
        val upright = tiff(
            "upright.tif", ByteOrder.LITTLE_ENDIAN,
            Triple(WIDTH, LONG, 6000L), Triple(LENGTH, LONG, 4000L), Triple(ORIENTATION, SHORT, 1L),
        )
        val turned = tiff(
            "turned.tiff", ByteOrder.BIG_ENDIAN,
            Triple(WIDTH, SHORT, 6000L), Triple(LENGTH, SHORT, 4000L), Triple(ORIENTATION, SHORT, 6L),
        )

        val sizes = source.resolveDimensions(listOf(upright, turned))

        assertEquals(ImageDimensions.of(6000, 4000), sizes[upright])
        assertEquals(ImageDimensions.of(4000, 6000), sizes[turned])
    }

    @Test
    fun `a TIFF without a size, or cut short, stays unknown`() = runBlocking {
        val noSize = tiff("nosize.tif", ByteOrder.LITTLE_ENDIAN, Triple(ORIENTATION, SHORT, 1L))
        val cut = File(tmp.root, "cut.tif").apply { writeBytes(byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08)) }
        val cutUri = Uri.fromFile(cut).toString()

        val sizes = source.resolveDimensions(listOf(noSize, cutUri))

        assertEquals(ImageDimensions.UNKNOWN, sizes[noSize])
        assertEquals(ImageDimensions.UNKNOWN, sizes[cutUri])
    }

    private companion object {
        const val WIDTH = 256
        const val LENGTH = 257
        const val ORIENTATION = 274
        const val SHORT = 3
        const val LONG = 4
    }
}
