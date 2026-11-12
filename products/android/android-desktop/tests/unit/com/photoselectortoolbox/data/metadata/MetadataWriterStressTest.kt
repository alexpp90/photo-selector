package com.photoselectortoolbox.data.metadata

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.XmpRatingParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataWriterStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val writer = MetadataWriterImpl(Dispatchers.IO)
    private val reader = AndroidExifReader()

    // -------------------------------------------------------------
    // 1. Rapid Rating Mutations (1 -> 5 -> 0 -> -1 -> 3 -> 0 -> 2)
    // -------------------------------------------------------------

    @Test
    fun `rapid rating mutations on RAW file updates sidecar cleanly at each transition`() = runTest {
        val rawFile = tempFolder.newFile("stress_rapid_mutations.cr3")
        rawFile.writeBytes(ByteArray(128))

        val mutations = listOf(1, 5, 0, -1, 3, 0, 2, 4, 0, -1, 5)
        for (rating in mutations) {
            val res = writer.writeRating(rawFile, rating)
            assertTrue("writeRating($rating) should succeed", res.isSuccess)

            val readBack = reader.readExif(rawFile)
            assertNotNull("readBack should not be null", readBack)
            assertEquals("Rating after mutating to $rating should match", rating, readBack?.rating)

            // Verify XML structure doesn't produce duplicate xmp:Rating tags
            val sidecar = File(tempFolder.root, "stress_rapid_mutations.cr3.xmp")
            assertTrue(sidecar.exists())
            val occurrences = Regex("<xmp:Rating>").findAll(sidecar.readText()).count()
            assertEquals("Sidecar should contain exactly 1 <xmp:Rating> element", 1, occurrences)
        }
    }

    @Test
    fun `rapid rating mutations on JPEG preserves capture timestamp and updates XMP`() = runTest {
        val jpegFile = createSampleJpeg("stress_rapid_mutations.jpg", timestamp = "2026:10:11 15:30:00")

        val mutations = listOf(1, 5, 0, -1, 4, 2, 0)
        for (rating in mutations) {
            val res = writer.writeRating(jpegFile, rating)
            assertTrue("writeRating($rating) should succeed for JPEG", res.isSuccess)

            val exif = ExifInterface(jpegFile.absolutePath)
            assertEquals("2026:10:11 15:30:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

            val readBack = reader.readExif(jpegFile)
            assertNotNull("readBack should not be null", readBack)
            assertEquals("JPEG rating should match $rating", rating, readBack?.rating)
        }
    }

    // -------------------------------------------------------------
    // 2. Special Characters and Edge Case Filenames
    // -------------------------------------------------------------

    @Test
    fun `handles filenames with spaces, brackets, ampersands, and unicode characters`() = runTest {
        val filenames = listOf(
            "Family Photo (2026) [Select] & Review.arw",
            "München_Schloß_Über_Straße.nef",
            "photo.with.many.dots.in.stem.cr2",
            "CJK_東京_京都_写真_2026.dng",
            "dash-and_underscore+plus.raf"
        )

        for (name in filenames) {
            val rawFile = tempFolder.newFile(name)
            rawFile.writeBytes(ByteArray(64))

            val writeResult = writer.writeRating(rawFile, 4)
            assertTrue("Writing rating for '$name' should succeed", writeResult.isSuccess)

            val exif = reader.readExif(rawFile)
            assertNotNull("Reader should read '$name'", exif)
            assertEquals("Rating for '$name' should be 4", 4, exif?.rating)
        }
    }

    // -------------------------------------------------------------
    // 3. Concurrent Sidecar Updates Stress Test
    // -------------------------------------------------------------

    @Test
    fun `concurrent sidecar writes on same raw file remain consistent without corruption`() = runTest {
        val rawFile = tempFolder.newFile("concurrent_target.arw")
        rawFile.writeBytes(ByteArray(256))

        // Launch 25 concurrent rating writes with random ratings between 1 and 5
        val ratings = (1..25).map { (it % 5) + 1 }
        val deferreds = ratings.map { rating ->
            async(Dispatchers.IO) {
                writer.writeRating(rawFile, rating)
            }
        }

        val results = deferreds.awaitAll()
        for (r in results) {
            assertTrue("Concurrent write must succeed", r.isSuccess)
        }

        // Verify sidecar exists and is valid XML
        val sidecar = File(tempFolder.root, "concurrent_target.arw.xmp")
        assertTrue(sidecar.exists())
        val content = sidecar.readText()
        assertTrue("Sidecar should contain valid xmpmeta", content.contains("<x:xmpmeta"))

        val finalExif = reader.readExif(rawFile)
        assertNotNull(finalExif)
        assertTrue("Final rating must be between 1 and 5", finalExif?.rating in 1..5)
    }

    // -------------------------------------------------------------
    // 4. Byte-for-Byte Non-Destructive Integrity on RAW Files
    // -------------------------------------------------------------

    @Test
    fun `raw file bytes are strictly immutable across multiple writes`() = runTest {
        val rawBytes = ByteArray(1024) { (it % 256).toByte() }
        val rawFile = tempFolder.newFile("unaltered_sensor_data.nef")
        rawFile.writeBytes(rawBytes)

        val originalHash = sha256(rawFile.readBytes())

        for (rating in listOf(1, 2, 3, 4, 5, 0, -1)) {
            writer.writeRating(rawFile, rating)
        }

        val finalHash = sha256(rawFile.readBytes())
        assertEquals("RAW file SHA-256 hash must be 100% byte-for-byte identical", originalHash, finalHash)
    }

    // -------------------------------------------------------------
    // 4b. Sidecar Filename Resolution Priority Consistency
    // -------------------------------------------------------------

    @Test
    fun `sidecar filename resolution priority consistency between writer and reader`() = runTest {
        val rawFile = tempFolder.newFile("conflict_test.cr3")
        rawFile.writeBytes(ByteArray(64))

        // Pre-create conflict_test.cr3.xmp with rating 1
        val filenameSidecar = File(tempFolder.root, "conflict_test.cr3.xmp")
        filenameSidecar.writeText("<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"><xmp:Rating>1</xmp:Rating></rdf:Description></rdf:RDF></x:xmpmeta>")

        // Pre-create conflict_test.xmp with rating 2
        val stemSidecar = File(tempFolder.root, "conflict_test.xmp")
        stemSidecar.writeText("<x:xmpmeta><rdf:RDF><rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"><xmp:Rating>2</xmp:Rating></rdf:Description></rdf:RDF></x:xmpmeta>")

        // Writer updates rating to 5
        writer.writeRating(rawFile, 5)

        // Reader reads back rating
        val exif = reader.readExif(rawFile)
        assertEquals("Reader should read the rating written by writer (5)", 5, exif?.rating)
    }

    // -------------------------------------------------------------
    // 5. Non-Existent File Handling
    // -------------------------------------------------------------

    @Test
    fun `writeRating on non-existent file returns failure cleanly`() = runTest {
        val nonExistent = File(tempFolder.root, "ghost_file_does_not_exist.jpg")
        val res = writer.writeRating(nonExistent, 5)
        assertTrue("Result should be failure", res.isFailure)
    }

    // -------------------------------------------------------------
    // 6. Adversarial Edge Case: Embedded Binary Rating vs Sidecar Rating 0
    // -------------------------------------------------------------

    @Test
    fun `empirical check of sidecar rating 0 when raw has embedded binary rating`() = runTest {
        // Construct a synthetic minimal TIFF buffer with binary Rating = 5
        val tiffBytes = createMinimalTiffWithRating(5)
        val rawFile = tempFolder.newFile("has_camera_rating_5.cr2")
        rawFile.writeBytes(tiffBytes)

        // Before sidecar, it should read 5 from the binary tag
        val beforeSidecar = reader.readExif(rawFile)
        assertEquals("Initial rating should be 5 from binary tag", 5, beforeSidecar?.rating)

        // Now user unrates photo: write rating 0 (unrated)
        val writeRes = writer.writeRating(rawFile, 0)
        assertTrue(writeRes.isSuccess)

        // Verify sidecar exists with rating 0
        val sidecar = File(tempFolder.root, "has_camera_rating_5.cr2.xmp")
        assertTrue(sidecar.exists())
        assertTrue("Sidecar should contain 0", sidecar.readText().contains("<xmp:Rating>0</xmp:Rating>"))

        // Read back via AndroidExifReader
        val afterUnrate = reader.readExif(rawFile)
        assertNotNull(afterUnrate)
        // Adversarial assertion: Did setting rating to 0 take effect, or did binary rating 5 override it?
        assertEquals("Sidecar rating 0 must take precedence over embedded binary rating 5", 0, afterUnrate?.rating)
    }

    // -------------------------------------------------------------
    // 7. Adversarial Edge Case: Floating Point Ratings in XMP
    // -------------------------------------------------------------

    @Test
    fun `XmpRatingParser handling of real or floating point ratings`() {
        val xmpFloat = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
                  <xmp:Rating>4.0</xmp:Rating>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val parsed = XmpRatingParser.parseRating(xmpFloat)
        assertEquals("Floating point rating <xmp:Rating>4.0</xmp:Rating> should parse as 4", 4, parsed)
    }

    // -------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------

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

    private fun createMinimalTiffWithRating(rating: Int): ByteArray {
        val buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.putShort(42)
        buffer.putInt(8) // IFD0 offset 8

        // IFD0:
        buffer.putShort(1) // 1 entry
        buffer.putShort(0x4746.toShort()) // TAG_RATING
        buffer.putShort(3) // SHORT
        buffer.putInt(1) // count 1
        buffer.putShort(rating.toShort())
        buffer.putShort(0) // padding

        return buffer.array()
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
