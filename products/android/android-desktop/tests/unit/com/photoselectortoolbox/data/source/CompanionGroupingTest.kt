package com.photoselectortoolbox.data.source

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.photoselectortoolbox.conformance.DirectoryDocumentsProvider
import com.photoselectortoolbox.conformance.FormatManifest
import com.photoselectortoolbox.domain.format.ImageFormats
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import kotlinx.coroutines.Dispatchers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionGroupingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val treeUri: Uri =
        DocumentsContract.buildTreeDocumentUri(
            DirectoryDocumentsProvider.AUTHORITY,
            DirectoryDocumentsProvider.ROOT_ID
        )

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(DirectoryDocumentsProvider::class.java)
            .create(DirectoryDocumentsProvider.AUTHORITY)
    }

    @Test
    fun `RAW + JPEG + XMP sidecar + Edit derivative in one directory become one ImageItem with JPEG as primary`() {
        val entries = listOf(
            RawDirectoryEntry(
                uri = "content://test/doc/IMG_0001.CR3",
                fileName = "IMG_0001.CR3",
                fileSize = 30_000_000L,
                lastModified = 1000L,
                mimeType = "image/x-canon-cr3",
            ),
            RawDirectoryEntry(
                uri = "content://test/doc/IMG_0001.JPG",
                fileName = "IMG_0001.JPG",
                fileSize = 8_000_000L,
                lastModified = 1001L,
                mimeType = "image/jpeg",
            ),
            RawDirectoryEntry(
                uri = "content://test/doc/IMG_0001.CR3.xmp",
                fileName = "IMG_0001.CR3.xmp",
                fileSize = 2_048L,
                lastModified = 1002L,
                mimeType = "application/rdf+xml",
            ),
            RawDirectoryEntry(
                uri = "content://test/doc/IMG_0001-Edit.tif",
                fileName = "IMG_0001-Edit.tif",
                fileSize = 60_000_000L,
                lastModified = 1003L,
                mimeType = "image/tiff",
            ),
        )

        val grouped = groupCompanionsInDirectory(entries, parentDocumentId = "doc:shoot_01")

        assertEquals(1, grouped.size)
        val item = grouped.single()
        assertEquals("IMG_0001.JPG", item.fileName)
        assertEquals("content://test/doc/IMG_0001.JPG", item.uri)
        assertEquals(8_000_000L, item.fileSize)
        assertEquals("doc:shoot_01", item.parentDocumentId)

        assertEquals(
            listOf("IMG_0001-Edit.tif", "IMG_0001.CR3", "IMG_0001.CR3.xmp"),
            item.companions.map { it.fileName }
        )
        assertEquals(
            listOf("IMG_0001.JPG", "IMG_0001-Edit.tif", "IMG_0001.CR3", "IMG_0001.CR3.xmp"),
            item.allFiles.map { it.fileName }
        )
    }

    @Test
    fun `lone RAW with stem xmp sidecar becomes one ImageItem with RAW as primary and xmp in companions`() {
        val entries = listOf(
            RawDirectoryEntry(
                uri = "content://test/doc/DSC0001.xmp",
                fileName = "DSC0001.xmp",
                fileSize = 1024L,
                lastModified = 2001L,
            ),
            RawDirectoryEntry(
                uri = "content://test/doc/DSC0001.ARW",
                fileName = "DSC0001.ARW",
                fileSize = 42_000_000L,
                lastModified = 2000L,
            ),
        )

        val grouped = groupCompanionsInDirectory(entries, parentDocumentId = "doc:sony_folder")

        assertEquals(1, grouped.size)
        val item = grouped.single()
        assertEquals("DSC0001.ARW", item.fileName)
        assertEquals("content://test/doc/DSC0001.ARW", item.uri)
        assertEquals("doc:sony_folder", item.parentDocumentId)
        assertEquals(listOf("DSC0001.xmp"), item.companions.map { it.fileName })
    }

    @Test
    fun `orphan xmp sidecar with no matching image is ignored`() {
        val entries = listOf(
            RawDirectoryEntry(
                uri = "content://test/doc/orphan_1.xmp",
                fileName = "orphan_1.xmp",
            ),
            RawDirectoryEntry(
                uri = "content://test/doc/orphan_2.CR3.xmp",
                fileName = "orphan_2.CR3.xmp",
            ),
        )

        val grouped = groupCompanionsInDirectory(entries, parentDocumentId = "doc:orphans")
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun `groups across all supported RAW and processed extensions from manifest`() {
        val rawExtensions = FormatManifest.formats.filter { it.kind == "raw" }.flatMap { it.extensions }
        val bitmapExtensions = FormatManifest.formats.filter { it.kind != "raw" }.flatMap { it.extensions }

        assertTrue(rawExtensions.isNotEmpty())
        assertTrue(bitmapExtensions.isNotEmpty())

        for (rawExt in rawExtensions) {
            for (bmpExt in bitmapExtensions) {
                val rawName = "SHOT_100.${rawExt.uppercase()}"
                val bmpName = "shot_100.${bmpExt.lowercase()}"
                val xmpName = "SHOT_100.${rawExt.uppercase()}.xmp"

                val grouped = groupCompanionsInDirectory(
                    entries = listOf(
                        RawDirectoryEntry(uri = "u://$rawName", fileName = rawName),
                        RawDirectoryEntry(uri = "u://$bmpName", fileName = bmpName),
                        RawDirectoryEntry(uri = "u://$xmpName", fileName = xmpName),
                    ),
                    parentDocumentId = "doc:test",
                )

                assertEquals("Failed for $rawName + $bmpName", 1, grouped.size)
                val item = grouped.single()
                assertEquals("Processed bitmap $bmpName must be primary over $rawName", bmpName, item.fileName)
                assertEquals(2, item.companions.size)
                assertTrue(item.companions.any { it.fileName == rawName })
                assertTrue(item.companions.any { it.fileName == xmpName })
            }
        }
    }

    @Test
    fun `same-named files in different directories remain separate ImageItems with distinct parentDocumentIds`() = runTest {
        val rootDir = tmp.newFolder("multi_day_shoot")
        val day1 = File(rootDir, "Day1").apply { mkdirs() }
        val day2 = File(rootDir, "Day2").apply { mkdirs() }

        // Day1 has IMG_0001.CR3, Day2 has IMG_0001.JPG
        File(day1, "IMG_0001.CR3").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(day2, "IMG_0001.JPG").writeBytes(byteArrayOf(5, 6, 7, 8))

        // Day1 also has a real pair IMG_0002.NEF + IMG_0002.JPG + IMG_0002.NEF.xmp
        File(day1, "IMG_0002.NEF").writeBytes(byteArrayOf(9, 10))
        File(day1, "IMG_0002.JPG").writeBytes(byteArrayOf(11, 12))
        File(day1, "IMG_0002.NEF.xmp").writeText("<x:xmpmeta/>")

        DirectoryDocumentsProvider.root = rootDir

        val discovered = LocalImageSourceImpl(context, Dispatchers.IO).discoverImages(treeUri).last()

        // Must discover 3 ImageItems:
        // 1. IMG_0001.CR3 (from Day1)
        // 2. IMG_0001.JPG (from Day2)
        // 3. IMG_0002.JPG (from Day1, with IMG_0002.NEF and IMG_0002.NEF.xmp in companions)
        assertEquals(3, discovered.size)

        val day1Cr3 = discovered.single { it.fileName == "IMG_0001.CR3" }
        val day2Jpg = discovered.single { it.fileName == "IMG_0001.JPG" }
        val day1Paired = discovered.single { it.fileName == "IMG_0002.JPG" }

        assertTrue(day1Cr3.companions.isEmpty())
        assertTrue(day2Jpg.companions.isEmpty())
        // Their parentDocumentIds must differ because they live in Day1 vs Day2
        assertTrue(day1Cr3.parentDocumentId != null)
        assertTrue(day2Jpg.parentDocumentId != null)
        assertTrue(day1Cr3.parentDocumentId != day2Jpg.parentDocumentId)
        assertEquals(day1Cr3.parentDocumentId, day1Paired.parentDocumentId)

        assertEquals(
            listOf("IMG_0002.NEF", "IMG_0002.NEF.xmp"),
            day1Paired.companions.map { it.fileName }
        )
    }

    @Test
    fun `grouping follows the shared companion table`() {
        val table = org.json.JSONObject(
            File(FormatManifest.repoRoot, "testdata/formats/companions.json").readText()
        ).getJSONArray("cases")
        for (c in 0 until table.length()) {
            val case = table.getJSONObject(c)
            val files = (0 until case.getJSONArray("files").length()).map { case.getJSONArray("files").getString(it) }
            val expected = (0 until case.getJSONArray("bundles").length()).map { b ->
                val bundle = case.getJSONArray("bundles").getJSONArray(b)
                (0 until bundle.length()).map { bundle.getString(it).lowercase() }.toSet()
            }.toSet()

            val items = groupCompanionsInDirectory(
                files.mapIndexed { i, name ->
                    RawDirectoryEntry(
                        uri = "content://test/doc/$name",
                        fileName = name,
                        fileSize = 1_000L,
                        lastModified = 1000L + i,
                        mimeType = "application/octet-stream",
                    )
                },
                parentDocumentId = "dir",
            )
            val actual = items.map { item ->
                (listOf(item.fileName) + item.companions.map { it.fileName }).map { it.lowercase() }.toSet()
            }.toSet()

            assertEquals(case.getString("name"), expected, actual)
        }
    }
}
