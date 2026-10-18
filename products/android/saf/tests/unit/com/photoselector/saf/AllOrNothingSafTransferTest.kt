package com.photoselector.saf

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SafTransferContractTest] under [BundleAtomicity.ALL_OR_NOTHING] with
 * [MarkerTiming.AFTER_FIRST_TRANSFER] (Android Desktop's policy), plus what only that policy
 * does: roll a bundle back when one copy fails, report a partial bundle, and undo into the
 * folder the photos came from. "AD" names the former Android Desktop test each case replaces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AllOrNothingSafTransferTest : SafTransferContractTest(ALL_OR_NOTHING_POLICY) {

    private fun seed(dir: String, vararg names: String): File {
        val folder = File(rootDir, dir).apply { mkdirs() }
        names.forEach { File(folder, it).writeText("payload-$it") }
        return folder
    }

    private suspend fun moveBundle(dir: String, names: List<String>, sorting: Boolean = true) =
        engine.aggregateBundleResults(
            primarySourceUri = documentUriFor("$dir/${names.first()}").toString(),
            results = engine.transferBundle(
                storage, names.map { documentUriFor("$dir/$it") }, selection(), sorting, isMove = true,
                parentDocumentId = FakeSafDocumentsProvider.documentIdFor(File(rootDir, dir)),
            ),
            parentDocumentId = FakeSafDocumentsProvider.documentIdFor(File(rootDir, dir)),
        )

    private suspend fun undo(moved: FileOperationResult, withCopies: Boolean = true) = engine.undoMoveBundle(
        storage = storage,
        destinationUris = moved.destinationUris,
        rootFolderUri = treeUri,
        parentDocumentId = moved.parentDocumentId,
        copiesOfSurvivingOriginals = if (withCopies) moved.copiesOfSurvivingOriginals else emptyMap(),
    )

    // ── Rollback ─────────────────────────────────────────────────────────

    // AD: moveBundle cleans up staged copies and preserves all sources when a companion copy fails
    @Test
    fun `a failed companion copy removes the staged copies and keeps every source`() = runTest {
        File(rootDir, "IMG_0011.JPG").writeText("primary-jpeg-ok")
        File(rootDir, "IMG_0011.CR3").writeBytes(ByteArray(2048) { (it % 127).toByte() })
        FakeSafDocumentsProvider.reportedSizeOverride = { file ->
            if (file.parentFile?.name == "RAW" && file.name == "IMG_0011.CR3") 512L else file.length()
        }

        val results = engine.transferBundle(
            storage, listOf(documentUriFor("IMG_0011.JPG"), documentUriFor("IMG_0011.CR3")), selection(), true, true,
        )

        assertTrue(results.none { it.success || it.partial })
        assertTrue(results.all { it.destinationUris.isEmpty() })
        assertFalse(File(rootDir, "Selection/JPEG/IMG_0011.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0011.CR3").exists())
        assertTrue(File(rootDir, "IMG_0011.JPG").exists())
        assertTrue(File(rootDir, "IMG_0011.CR3").exists())
        // Nothing landed, so no marker was written.
        assertFalse(File(rootDir, "Selection/${SafTransferEngine.SELECTION_MARKER_FILE_NAME}").exists())
    }

    @Test
    fun `an unresolvable member fails the whole bundle and moves nothing`() = runTest {
        File(rootDir, "IMG_0014.JPG").writeText("payload")

        val results = engine.transferBundle(
            storage, listOf(documentUriFor("IMG_0014.JPG"), documentUriFor("IMG_0014.CR3")), selection(), true, true,
        )

        assertEquals(2, results.size)
        assertTrue(results.none { it.success })
        assertTrue(File(rootDir, "IMG_0014.JPG").exists())
    }

    @Test
    fun `every member of a successful bundle reports all destination uris`() = runTest {
        seed("", "IMG_0015.JPG", "IMG_0015.CR3")

        val results = engine.transferBundle(
            storage, listOf(documentUriFor("IMG_0015.JPG"), documentUriFor("IMG_0015.CR3")), selection(), true, false,
        )

        assertTrue(results.all { it.success && it.destinationUris.size == 2 })
        assertTrue(File(rootDir, "Selection/${SafTransferEngine.SELECTION_MARKER_FILE_NAME}").exists())
    }

    // ── U-04b: move a bundle from a sub-folder and undo it ───────────────

    // AD: moveBundle moves CR3 + JPG + CR3 xmp from sub-folder and undoMoveBundle restores all 3 into original sub-folder
    @Test
    fun `a bundle moved from a sub-folder is restored into that sub-folder by undo`() = runTest {
        val names = listOf("IMG_0010.JPG", "IMG_0010.CR3", "IMG_0010.CR3.xmp")
        val subDir = seed("sub/day1", *names.toTypedArray())

        val moved = moveBundle("sub/day1", names)

        assertTrue(moved.success)
        assertEquals(3, moved.destinationUris.size)
        assertEquals("root/sub/day1", moved.parentDocumentId)
        names.forEach { assertFalse(File(subDir, it).exists()) }
        assertEquals("payload-IMG_0010.JPG", File(rootDir, "Selection/JPEG/IMG_0010.JPG").readText())
        assertEquals("payload-IMG_0010.CR3", File(rootDir, "Selection/RAW/IMG_0010.CR3").readText())
        assertEquals("payload-IMG_0010.CR3.xmp", File(rootDir, "Selection/RAW/IMG_0010.CR3.xmp").readText())

        val undone = undo(moved)

        assertTrue(undone.success)
        assertEquals(3, undone.destinationUris.size)
        names.forEach {
            assertEquals("payload-$it", File(subDir, it).readText())
            assertFalse("root must not receive $it", File(rootDir, it).exists())
        }
        assertFalse(File(subDir, "Selection").exists())
        assertFalse(File(rootDir, "Selection/JPEG/IMG_0010.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0010.CR3").exists())
    }

    // AD: sorting false files bundle flat into Selection without RAW or JPEG subfolders and undo restores to original folder (the undo half)
    @Test
    fun `a flat bundle is restored to its original folder without creating a Selection there`() = runTest {
        val names = listOf("IMG_0020.JPG", "IMG_0020.CR3", "IMG_0020.CR3.xmp")
        val subDir = seed("sub/day2", *names.toTypedArray())

        val moved = moveBundle("sub/day2", names, sorting = false)
        assertTrue(moved.success)
        val undone = undo(moved)

        assertTrue(undone.success)
        names.forEach { assertEquals("payload-$it", File(subDir, it).readText()) }
        assertFalse(File(subDir, "Selection").exists())
    }

    @Test
    fun `undo with nothing to restore fails and says so`() = runTest {
        val undone = engine.undoMoveBundle(storage, emptyList(), treeUri, null)

        assertFalse(undone.success)
        assertEquals("No files to restore", undone.error)
    }

    // ── B-3 / D-3: undo of a partial bundle move ─────────────────────────

    // AD: undo of a fully partial bundle move deletes the copies and creates no duplicate next to the originals
    @Test
    fun `undo of a fully partial bundle move deletes the copies and creates no duplicate`() = runTest {
        val subDir = seed("sub/day2", "IMG_0020.JPG", "IMG_0020.CR3")
        FakeSafDocumentsProvider.failDeletes = true

        val moved = moveBundle("sub/day2", listOf("IMG_0020.JPG", "IMG_0020.CR3"))

        assertTrue(moved.partial)
        assertEquals("Copied to Selection — original could not be removed", moved.error)
        assertEquals(2, moved.copiesOfSurvivingOriginals.size)
        FakeSafDocumentsProvider.failDeletes = false
        val undone = undo(moved)

        assertTrue(undone.success)
        assertEquals(listOf("IMG_0020.CR3", "IMG_0020.JPG"), subDir.list()!!.sorted())
        assertFalse(File(rootDir, "Selection/JPEG/IMG_0020.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0020.CR3").exists())
    }

    // AD: undo of a mixed partial bundle move restores the moved member and only deletes the copy of the member that stayed
    @Test
    fun `undo of a mixed partial move restores the moved member and deletes only the stuck member's copy`() = runTest {
        val subDir = seed("sub/day3", "IMG_0030.JPG", "IMG_0030.CR3")
        FakeSafDocumentsProvider.failDeletesMatching = "IMG_0030.JPG"

        val moved = moveBundle("sub/day3", listOf("IMG_0030.JPG", "IMG_0030.CR3"))

        assertTrue(moved.partial)
        assertEquals(1, moved.copiesOfSurvivingOriginals.size)
        assertFalse(File(subDir, "IMG_0030.CR3").exists())
        assertTrue(File(subDir, "IMG_0030.JPG").exists())
        FakeSafDocumentsProvider.failDeletesMatching = null
        val undone = undo(moved)

        assertTrue(undone.success)
        // D-13: the result names the deleted copy so data keyed by it can go with it.
        assertEquals(moved.copiesOfSurvivingOriginals.keys.toList(), undone.deletedCopies)
        assertEquals(listOf("IMG_0030.CR3", "IMG_0030.JPG"), subDir.list()!!.sorted())
        assertEquals("payload-IMG_0030.CR3", File(subDir, "IMG_0030.CR3").readText())
        assertFalse(File(rootDir, "Selection/JPEG/IMG_0030.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0030.CR3").exists())
    }

    // AD: undo of a partial move never deletes a copy whose original has disappeared meanwhile
    @Test
    fun `undo of a partial move never deletes a copy whose original has disappeared`() = runTest {
        val subDir = seed("sub/day4", "IMG_0040.JPG", "IMG_0040.CR3")
        FakeSafDocumentsProvider.failDeletes = true
        val moved = moveBundle("sub/day4", listOf("IMG_0040.JPG", "IMG_0040.CR3"))
        assertTrue(moved.partial)
        FakeSafDocumentsProvider.failDeletes = false
        File(subDir, "IMG_0040.JPG").delete() // the user removed the stuck original later

        val undone = undo(moved)

        assertTrue(undone.success)
        assertEquals(1, undone.deletedCopies.size)
        assertTrue(undone.deletedCopies.single().contains("IMG_0040.CR3"))
        assertEquals("payload-IMG_0040.JPG", File(subDir, "IMG_0040.JPG").readText())
        assertEquals("payload-IMG_0040.CR3", File(subDir, "IMG_0040.CR3").readText())
        assertEquals(listOf("IMG_0040.CR3", "IMG_0040.JPG"), subDir.list()!!.sorted())
    }

    @Test
    fun `undo fails and keeps the copy when the stuck original's copy cannot be deleted`() = runTest {
        seed("sub/day5", "IMG_0045.JPG")
        FakeSafDocumentsProvider.failDeletes = true
        val moved = moveBundle("sub/day5", listOf("IMG_0045.JPG"))
        assertTrue(moved.partial)

        val undone = undo(moved) // deletes still fail

        assertFalse(undone.success)
        assertEquals("Could not delete the copy in the destination folder", undone.error)
        assertTrue(File(rootDir, "Selection/JPEG/IMG_0045.JPG").exists())
    }

    // ── B-4 / D-4: restore-folder resolution with opaque document ids ────

    // AD: undo restores into the original folder when the provider uses opaque document ids and creates no folder
    @Test
    fun `undo restores into the original folder when the provider uses opaque ids, creating no folder`() = runTest {
        FakeSafDocumentsProvider.opaqueFolderIds = true
        val folder = seed("Download/trip", "IMG_0050.JPG")
        val parentId = FakeSafDocumentsProvider.documentIdFor(folder)
        assertTrue(parentId.startsWith("msf:"))
        val before = rootDir.walkTopDown().filter { it.isDirectory }.map { it.relativeTo(rootDir).path }.toSet()

        val moved = engine.aggregateBundleResults(
            documentUriFor("Download/trip/IMG_0050.JPG").toString(),
            engine.transferBundle(storage, listOf(documentUriFor("Download/trip/IMG_0050.JPG")), selection(), false, true, parentId),
            parentId,
        )
        assertTrue(moved.success)
        assertFalse(File(folder, "IMG_0050.JPG").exists())

        val undone = undo(moved)

        assertTrue(undone.success)
        assertNull(undone.notice)
        assertEquals("payload-IMG_0050.JPG", File(folder, "IMG_0050.JPG").readText())
        val after = rootDir.walkTopDown().filter { it.isDirectory }.map { it.relativeTo(rootDir).path }.toSet()
        assertEquals(setOf("Selection"), after - before)
        assertFalse(after.any { it.contains("msf:") })
    }

    // AD: undo restores to the opened folder and says so when the original folder is gone
    @Test
    fun `undo restores to the opened folder and says so when the original folder is gone`() = runTest {
        FakeSafDocumentsProvider.opaqueFolderIds = true
        val folder = seed("Download/gone", "IMG_0051.JPG")
        val parentId = FakeSafDocumentsProvider.documentIdFor(folder)
        val moved = engine.aggregateBundleResults(
            documentUriFor("Download/gone/IMG_0051.JPG").toString(),
            engine.transferBundle(storage, listOf(documentUriFor("Download/gone/IMG_0051.JPG")), selection(), false, true, parentId),
            parentId,
        )
        assertTrue(moved.success)
        folder.deleteRecursively()

        val undone = undo(moved)

        assertTrue(undone.success)
        assertEquals("payload-IMG_0051.JPG", File(rootDir, "IMG_0051.JPG").readText())
        assertNotNull(undone.notice)
        assertFalse(File(rootDir, "Download/gone").exists())
        assertFalse(rootDir.walkTopDown().any { it.name.contains("msf:") })
    }

    @Test
    fun `resolveRestoreFolder uses the root for no id and for the tree's own id`() {
        assertFalse(engine.resolveRestoreFolder(storage, treeUri, null)!!.fellBackToRoot)
        assertFalse(engine.resolveRestoreFolder(storage, treeUri, FakeSafDocumentsProvider.ROOT_ID)!!.fellBackToRoot)
        assertTrue(engine.resolveRestoreFolder(storage, treeUri, "root/missing")!!.fellBackToRoot)
    }

    // ── aggregateBundleResults ───────────────────────────────────────────

    @Test
    fun `aggregation is success, partial with the union of surviving originals, or failure`() {
        val ok = FileOperationResult.success("s1", "d1")
        val partA = FileOperationResult.partial("s2", "d2")
        val partB = FileOperationResult.partial("s3", "d3")
        val bad = FileOperationResult.failure("s4", "boom")

        assertTrue(engine.aggregateBundleResults("p", listOf(ok, ok), null).success)
        val partial = engine.aggregateBundleResults("p", listOf(ok, partA, partB), "dir")
        assertTrue(partial.partial)
        assertEquals(mapOf("d2" to "s2", "d3" to "s3"), partial.copiesOfSurvivingOriginals)
        assertEquals("dir", partial.parentDocumentId)
        assertEquals("boom", engine.aggregateBundleResults("p", listOf(bad), null).error)
        assertEquals("No files in bundle", engine.aggregateBundleResults("p", emptyList(), null).error)
    }
}
