package com.phototok.domain

import com.phototok.data.model.ImageItem
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Companion grouping against the shared contract `testdata/formats/companions.json`
 * (docs/shared/FILE_OPERATIONS.md, finding D-6): for every case, the files PhotoTok would move
 * or delete together with any photo of a bundle are exactly that bundle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionGroupingTest {

    private fun item(name: String, parent: String = "content://tree/dir") = ImageItem(
        uri = "$parent/$name",
        fileName = name,
        fileSize = 1L,
        lastModified = 1L,
        mimeType = null,
        parentUri = parent,
    )

    /** What discovery does for one directory listing: photos become feed items, `.xmp` ride along. */
    private fun discover(names: List<String>, parent: String = "content://tree/dir"): List<ImageItem> {
        val photos = names.filter { PhotoExtensions.isSupported(it) }.map { item(it, parent) }
        val sidecars = names.filter { CompanionFiles.isSidecar(it) }.map { item(it, parent) }
        return CompanionFiles.attachSidecars(photos, sidecars)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "testdata/formats/companions.json").isFile) return dir
            dir = dir.parentFile
        }
        error("testdata/formats/companions.json not found above ${File("").absolutePath}")
    }

    private fun strings(arr: org.json.JSONArray) = (0 until arr.length()).map { arr.getString(it) }

    @Test
    fun `every case of the shared table travels as the table says`() {
        val cases = JSONObject(File(repoRoot(), "testdata/formats/companions.json").readText())
            .getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val files = strings(case.getJSONArray("files"))
            val bundles = case.getJSONArray("bundles").let { arr -> (0 until arr.length()).map { strings(arr.getJSONArray(it)) } }
            val expected = bundles.map { it.toSet() }.toSet()

            // The pure grouping, including which file is the shot.
            val grouped = CompanionFiles.bundlesIn(files)
            assertEquals(name, expected, grouped.map { it.toSet() }.toSet())
            assertEquals(name, bundles.map { it.first() }.toSet(), grouped.map { it.first() }.toSet())

            // What a linked move/delete takes, starting from each photo the feed shows.
            val feed = discover(files)
            assertTrue(name, feed.none { CompanionFiles.isSidecar(it.fileName) })
            for (target in feed) {
                val taken = (listOf(target) + RelatedFiles.bundleOf(feed, target)).map { it.fileName }
                assertEquals("$name: ${target.fileName} has no duplicates", taken.toSet().size, taken.size)
                assertTrue(
                    "$name: ${target.fileName} takes exactly its bundle, took $taken",
                    taken.toSet() in expected,
                )
            }
        }
    }

    @Test
    fun `the same stem in two sibling folders is two shots`() {
        val a = discover(listOf("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001.xmp"), "content://tree/a")
        val b = discover(listOf("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001.xmp"), "content://tree/b")
        val feed = a + b

        val taken = RelatedFiles.bundleOf(feed, a.first { it.fileName == "IMG_0001.CR3" })

        assertEquals(setOf("IMG_0001.JPG", "IMG_0001.xmp"), taken.map { it.fileName }.toSet())
        assertTrue(taken.all { it.parentUri == "content://tree/a" })
    }

    @Test
    fun `an orphan sidecar attaches to nothing and never becomes a feed item`() {
        val feed = discover(listOf("IMG_0009.xmp", "IMG_0010.JPG"))

        assertEquals(listOf("IMG_0010.JPG"), feed.map { it.fileName })
        assertTrue(feed.single().sidecars.isEmpty())
        assertEquals(emptyList<List<String>>(), CompanionFiles.bundlesIn(listOf("IMG_0009.xmp")))
    }

    @Test
    fun `moving a derivative takes its whole shot`() {
        val feed = discover(listOf("IMG_0002.CR3", "IMG_0002-Edit.tif", "IMG_0002-Edit.xmp"))

        val taken = RelatedFiles.bundleOf(feed, feed.first { it.fileName == "IMG_0002-Edit.tif" })

        assertEquals(setOf("IMG_0002.CR3", "IMG_0002-Edit.xmp"), taken.map { it.fileName }.toSet())
        assertFalse(CompanionFiles.isDerivative("IMG_0002.CR3"))
        assertTrue(CompanionFiles.isDerivative("IMG_0002_Enhanced-NR.dng"))
    }
}
