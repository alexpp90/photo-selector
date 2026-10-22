package com.photoselectortoolbox.data.metadata

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.photoselector.core.Requirement
import com.photoselector.core.reader.AndroidExifReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Requirement("REQ-AND-RATING.01")
class MetadataWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val writer = MetadataWriterImpl()
    private val reader = AndroidExifReader()

    @Test
    fun `jpeg XMP packet update preserves capture timestamp`() = runTest {
        val jpegFile = createSampleJpeg("test_photo.jpg", timestamp = "2026:10:11 12:00:00")

        // Initial check
        val exifBefore = ExifInterface(jpegFile.absolutePath)
        assertEquals("2026:10:11 12:00:00", exifBefore.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

        // Write rating 4
        val result = writer.writeRating(jpegFile, 4)
        assertTrue(result.isSuccess)

        // Verify timestamp untouched
        val exifAfter = ExifInterface(jpegFile.absolutePath)
        assertEquals("2026:10:11 12:00:00", exifAfter.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

        // Verify XMP contains rating 4
        val xmp = exifAfter.getAttribute(ExifInterface.TAG_XMP)
        assertNotNull(xmp)
        assertTrue(xmp!!.contains("<xmp:Rating>4</xmp:Rating>"))
    }

    @Test
    fun `raw file creates companion xmp sidecar without modifying raw bytes`() = runTest {
        val rawBytes = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x10, 0x20, 0x30, 0x40)
        val rawFile = tempFolder.newFile("DSC_1234.ARW")
        rawFile.writeBytes(rawBytes)
        val initialLength = rawFile.length()

        // Write rating 5
        val result = writer.writeRating(rawFile, 5)
        assertTrue(result.isSuccess)

        // Raw file binary must be byte-for-byte unchanged
        assertEquals(initialLength, rawFile.length())
        assertTrue(rawBytes.contentEquals(rawFile.readBytes()))

        // Companion sidecar must exist with ISO 16684-1 standard format
        val sidecar = File(tempFolder.root, "DSC_1234.ARW.xmp")
        assertTrue(sidecar.exists())
        val sidecarContent = sidecar.readText()
        assertTrue(sidecarContent.contains("<xmp:Rating>5</xmp:Rating>"))
        assertTrue(sidecarContent.contains("adobe:ns:meta/"))

        // Update rating to 2
        val updateResult = writer.writeRating(rawFile, 2)
        assertTrue(updateResult.isSuccess)

        val updatedContent = sidecar.readText()
        assertTrue(updatedContent.contains("<xmp:Rating>2</xmp:Rating>"))
        // Previous rating 5 should be replaced, not duplicated
        assertTrue(!updatedContent.contains("<xmp:Rating>5</xmp:Rating>"))
    }

    @Test
    fun `dng file creates companion xmp sidecar`() = runTest {
        val dngFile = tempFolder.newFile("sample.dng")
        dngFile.writeBytes(byteArrayOf(0x49, 0x49, 0x2A, 0x00))

        val result = writer.writeRating(dngFile, 3)
        assertTrue(result.isSuccess)

        val sidecar = File(tempFolder.root, "sample.dng.xmp")
        assertTrue(sidecar.exists())
        assertTrue(sidecar.readText().contains("<xmp:Rating>3</xmp:Rating>"))
    }

    @Test
    fun `round-trip rating read back via AndroidExifReader for JPEG`() = runTest {
        val jpegFile = createSampleJpeg("roundtrip.jpg")

        writer.writeRating(jpegFile, 3)
        val exifData3 = reader.readExif(jpegFile)
        assertNotNull(exifData3)
        assertEquals(3, exifData3?.rating)

        // Test Reject (-1)
        writer.writeRating(jpegFile, -1)
        val exifDataReject = reader.readExif(jpegFile)
        assertNotNull(exifDataReject)
        assertEquals(-1, exifDataReject?.rating)

        // Test Unrated (0)
        writer.writeRating(jpegFile, 0)
        val exifDataZero = reader.readExif(jpegFile)
        assertNotNull(exifDataZero)
        assertEquals(0, exifDataZero?.rating)
    }

    @Test
    fun `round-trip rating read back via AndroidExifReader for RAW with sidecar`() = runTest {
        val rawFile = tempFolder.newFile("IMG_5555.CR3")
        rawFile.writeBytes(ByteArray(64))

        writer.writeRating(rawFile, 5)
        val exifData5 = reader.readExif(rawFile)
        assertNotNull(exifData5)
        assertEquals(5, exifData5?.rating)

        // Update to 1 star
        writer.writeRating(rawFile, 1)
        val exifData1 = reader.readExif(rawFile)
        assertNotNull(exifData1)
        assertEquals(1, exifData1?.rating)

        // Reject (-1)
        writer.writeRating(rawFile, -1)
        val exifDataReject = reader.readExif(rawFile)
        assertNotNull(exifDataReject)
        assertEquals(-1, exifDataReject?.rating)
    }

    @Test
    fun `updates existing stem sidecar if already present`() = runTest {
        val rawFile = tempFolder.newFile("photo_abc.nef")
        rawFile.writeBytes(ByteArray(32))

        // Pre-create stem sidecar: photo_abc.xmp
        val stemSidecar = File(tempFolder.root, "photo_abc.xmp")
        stemSidecar.writeText("<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"><xmp:Rating>1</xmp:Rating></rdf:Description></rdf:RDF></x:xmpmeta>")

        writer.writeRating(rawFile, 4)

        // Should update photo_abc.xmp rather than creating duplicate photo_abc.nef.xmp
        assertTrue(stemSidecar.exists())
        assertTrue(stemSidecar.readText().contains("<xmp:Rating>4</xmp:Rating>"))
        val dotExtSidecar = File(tempFolder.root, "photo_abc.nef.xmp")
        assertTrue(!dotExtSidecar.exists())
    }

    @Test
    fun `prefers existing filename sidecar over stem sidecar when both exist`() = runTest {
        val rawFile = tempFolder.newFile("photo_both.nef")
        rawFile.writeBytes(ByteArray(32))

        val filenameSidecar = File(tempFolder.root, "photo_both.nef.xmp")
        filenameSidecar.writeText("<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"><xmp:Rating>1</xmp:Rating></rdf:Description></rdf:RDF></x:xmpmeta>")

        val stemSidecar = File(tempFolder.root, "photo_both.xmp")
        stemSidecar.writeText("<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"><xmp:Rating>2</xmp:Rating></rdf:Description></rdf:RDF></x:xmpmeta>")

        writer.writeRating(rawFile, 5)

        // Must update photo_both.nef.xmp according to priority
        assertTrue(filenameSidecar.readText().contains("<xmp:Rating>5</xmp:Rating>"))
        // Stem sidecar must remain unchanged
        assertTrue(stemSidecar.readText().contains("<xmp:Rating>2</xmp:Rating>"))
    }

    @Test
    fun `XmpSidecarWriter replaces existing decimal real rating cleanly`() {
        val xmpWithDecimal = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
                  <xmp:Rating>3.5</xmp:Rating>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val updated = XmpSidecarWriter.injectOrUpdateRating(xmpWithDecimal, 5)
        assertTrue("Must replace decimal rating with 5", updated.contains("<xmp:Rating>5</xmp:Rating>"))
        assertFalse("Must not contain old decimal 3.5", updated.contains("3.5"))
    }

    @Test
    fun `jpeg write-back leaves original JPEG untouched and deletes temp file when bounds verification fails`() = runTest {
        val jpegFile = createSampleJpeg("protected_photo.jpg", timestamp = "2026:10:17 09:00:00")
        val originalBytes = jpegFile.readBytes()

        val failingValidatorWriter = MetadataWriterImpl(validateJpegBounds = { false })
        val result = failingValidatorWriter.writeRating(jpegFile, 5)
        assertTrue(result.isSuccess)

        // Original JPEG bytes must be 100% untouched because temp file failed verification
        assertTrue(originalBytes.contentEquals(jpegFile.readBytes()))

        // Fallback companion .xmp sidecar carries the rating instead
        val sidecar = File(tempFolder.root, "protected_photo.jpg.xmp")
        assertTrue(sidecar.exists())
        assertTrue(sidecar.readText().contains("<xmp:Rating>5</xmp:Rating>"))

        // No temporary files left behind
        val leftoverTemps = tempFolder.root.listFiles()?.filter { it.name.contains(".tmp.") }.orEmpty()
        assertTrue(leftoverTemps.isEmpty())
    }

    @Test
    fun `concurrent jpeg writes on same file are serialized via per-URI mutex without corruption`() = runTest {
        val jpegFile = createSampleJpeg("concurrent_jpeg.jpg", timestamp = "2026:10:17 10:00:00")

        val jobs = (1..10).map { i ->
            async(Dispatchers.IO) {
                writer.writeRating(jpegFile, (i % 5) + 1)
            }
        }
        val results = jobs.map { it.await() }
        assertTrue(results.all { it.isSuccess })

        // Verify resulting JPEG is still valid and decodable with intact capture timestamp
        assertTrue(MetadataWriterImpl.defaultValidateJpegBounds(jpegFile))
        val exifAfter = ExifInterface(jpegFile.absolutePath)
        assertEquals("2026:10:17 10:00:00", exifAfter.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        val parsed = reader.readExif(jpegFile)
        assertNotNull(parsed)
        assertTrue(parsed!!.rating in 1..5)

        // No temporary files left behind
        val leftoverTemps = tempFolder.root.listFiles()?.filter { it.name.contains(".tmp.") }.orEmpty()
        assertTrue(leftoverTemps.isEmpty())
    }

    private fun createSampleJpeg(filename: String, timestamp: String = "2026:10:11 10:00:00"): File {
        val file = tempFolder.newFile(filename)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, timestamp)
        exif.saveAttributes()
        return file
    }
}
