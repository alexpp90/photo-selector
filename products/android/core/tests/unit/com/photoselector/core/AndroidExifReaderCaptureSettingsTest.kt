package com.photoselector.core

import androidx.exifinterface.media.ExifInterface
import com.photoselector.core.reader.AndroidExifReader
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The capture settings `:core` keeps raw for the products (shoot-debrief SD-2a). Absent is not
 * zero: a flash that did not fire, auto white balance, auto exposure and 0 EV are values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidExifReaderCaptureSettingsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val reader = AndroidExifReader()

    private fun jpeg(name: String, edit: (ExifInterface) -> Unit = {}): File {
        val file = tempFolder.newFile(name)
        val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        val exif = ExifInterface(file.absolutePath)
        edit(exif)
        exif.saveAttributes()
        return file
    }

    /** Puts an XMP packet into a JPEG as an APP1 segment right after SOI. */
    private fun withXmp(file: File, xmp: String): File {
        val bytes = file.readBytes()
        val payload = ("http://ns.adobe.com/xap/1.0/\u0000" + xmp).toByteArray(Charsets.ISO_8859_1)
        val len = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
        file.writeBytes(bytes.copyOfRange(0, 2) + segment + bytes.copyOfRange(2, bytes.size))
        return file
    }

    @Test
    fun `every capture setting is read when the file carries it`() = runTest {
        val file = jpeg("all.jpg") {
            it.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, "3")
            it.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, "1")
            it.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, "-2/3")
            it.setAttribute(ExifInterface.TAG_METERING_MODE, "5")
            it.setAttribute(ExifInterface.TAG_FLASH, "25")
            it.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "1")
            it.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, "2/1")
            it.setAttribute(ExifInterface.TAG_LENS_SPECIFICATION, "24/1,70/1,28/10,40/10")
            it.setAttribute(ExifInterface.TAG_CUSTOM_RENDERED, "1")
            it.setAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE, "2")
        }
        val r = reader.readExif(file)!!
        assertEquals(3, r.exposureProgram)
        assertEquals(1, r.exposureMode)
        assertEquals(-2.0 / 3.0, r.exposureCompensationEv!!, 1e-6)
        assertEquals(5, r.meteringMode)
        assertEquals(25, r.flash)
        assertEquals(1, r.whiteBalance)
        assertEquals(2.0, r.digitalZoomRatio!!, 1e-6)
        val lens = r.lensSpecification!!
        assertEquals(24.0, lens.shortestFocalLength!!, 1e-6)
        assertEquals(70.0, lens.longestFocalLength!!, 1e-6)
        assertEquals(2.8, lens.widestApertureAtShortest!!, 1e-6)
        assertEquals(4.0, lens.widestApertureAtLongest!!, 1e-6)
        assertEquals(1, r.customRendered)
        assertEquals(2, r.sceneCaptureType)
    }

    @Test
    fun `a file without them leaves every capture setting null`() = runTest {
        val r = reader.readExif(jpeg("none.jpg"))!!
        assertNull(r.exposureProgram)
        assertNull(r.exposureMode)
        assertNull(r.exposureCompensationEv)
        assertNull(r.meteringMode)
        assertNull(r.flash)
        assertNull(r.whiteBalance)
        assertNull(r.digitalZoomRatio)
        assertNull(r.lensSpecification)
        assertNull(r.customRendered)
        assertNull(r.sceneCaptureType)
        assertNull(r.gimbalPitchDegrees)
    }

    @Test
    fun `a setting written as 0 is 0, not absent`() = runTest {
        val file = jpeg("zeros.jpg") {
            it.setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, "0")
            it.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, "0")
            it.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, "0/1")
            it.setAttribute(ExifInterface.TAG_METERING_MODE, "0")
            it.setAttribute(ExifInterface.TAG_FLASH, "0")
            it.setAttribute(ExifInterface.TAG_WHITE_BALANCE, "0")
            it.setAttribute(ExifInterface.TAG_CUSTOM_RENDERED, "0")
            it.setAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE, "0")
        }
        val r = reader.readExif(file)!!
        assertEquals(0, r.exposureProgram)
        assertEquals(0, r.exposureMode)
        assertEquals(0.0, r.exposureCompensationEv!!, 0.0)
        assertEquals(0, r.meteringMode)
        assertEquals(0, r.flash)
        assertEquals(0, r.whiteBalance)
        assertEquals(0, r.customRendered)
        assertEquals(0, r.sceneCaptureType)
    }

    @Test
    fun `an unknown lens specification entry is null, the others stay`() = runTest {
        val file = jpeg("lens0.jpg") {
            it.setAttribute(ExifInterface.TAG_LENS_SPECIFICATION, "24/1,70/1,0/0,0/0")
        }
        val lens = reader.readExif(file)!!.lensSpecification!!
        assertEquals(24.0, lens.shortestFocalLength!!, 1e-6)
        assertNull(lens.widestApertureAtShortest)
        assertNull(lens.widestApertureAtLongest)
    }

    @Test
    fun `the drone gimbal pitch comes from the XMP packet`() = runTest {
        val file = withXmp(
            jpeg("drone.jpg"),
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description xmlns:drone-dji=\"http://www.dji.com/drone-dji/1.0/\" " +
                "drone-dji:GimbalPitchDegree=\"-90.00\" xmp:Rating=\"3\"/></rdf:RDF></x:xmpmeta>",
        )
        val r = reader.readExif(file)!!
        assertEquals(-90.0, r.gimbalPitchDegrees!!, 1e-6)
        assertEquals(3, r.rating)
    }

    @Test
    fun `a content Uri read opens the document once and takes no stream`() = runTest {
        val file = jpeg("once.jpg") { it.setAttribute(ExifInterface.TAG_FLASH, "0") }
        val resolver = mockk<android.content.ContentResolver>()
        val context = mockk<android.content.Context>()
        val uri = android.net.Uri.parse("content://com.photoselector.test/images/1")
        every { context.contentResolver } returns resolver
        every { resolver.openFileDescriptor(uri, "r") } answers {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        every { resolver.openInputStream(uri) } answers { file.inputStream() }

        val r = reader.readExif(context, uri)
        assertNotNull(r)
        assertEquals(0, r!!.flash)
        verify(exactly = 1) { resolver.openFileDescriptor(uri, "r") }
        verify(exactly = 0) { resolver.openInputStream(uri) }
    }
}
