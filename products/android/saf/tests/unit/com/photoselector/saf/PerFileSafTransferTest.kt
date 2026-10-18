package com.photoselector.saf

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SafTransferContractTest] under [BundleAtomicity.PER_FILE] with
 * [MarkerTiming.WHEN_FOLDER_RESOLVED] (PhotoTok's policy), plus what only that policy does:
 * members are transferred one by one under the bundle's shared names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PerFileSafTransferTest : SafTransferContractTest(PER_FILE_POLICY) {

    @Test
    fun `a member that fails does not roll back the members that landed`() = runTest {
        File(rootDir, "IMG_0011.JPG").writeText("primary-jpeg-ok")
        File(rootDir, "IMG_0011.CR3").writeBytes(ByteArray(2048) { (it % 127).toByte() })
        FakeSafDocumentsProvider.reportedSizeOverride = { file ->
            if (file.parentFile?.name == "RAW" && file.name == "IMG_0011.CR3") 512L else file.length()
        }

        val results = engine.transferBundle(
            storage, listOf(documentUriFor("IMG_0011.JPG"), documentUriFor("IMG_0011.CR3")), selection(), true, true,
        )

        assertTrue(results[0].success)
        assertEquals("Copied file size mismatch", results[1].error)
        assertEquals("primary-jpeg-ok", File(rootDir, "Selection/JPEG/IMG_0011.JPG").readText())
        assertFalse(File(rootDir, "IMG_0011.JPG").exists())
        assertTrue("the failed member's source stays", File(rootDir, "IMG_0011.CR3").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0011.CR3").exists())
    }

    @Test
    fun `an unresolvable member fails alone and the others are transferred`() = runTest {
        File(rootDir, "IMG_0014.JPG").writeText("payload")

        val results = engine.transferBundle(
            storage, listOf(documentUriFor("IMG_0014.JPG"), documentUriFor("IMG_0014.CR3")), selection(), true, true,
        )

        assertTrue(results[0].success)
        assertEquals("Could not write to the Selection folder", results[1].error)
        assertTrue(File(rootDir, "Selection/JPEG/IMG_0014.JPG").exists())
    }

    @Test
    fun `every member of a bundle may use the provider's atomic move`() = runTest {
        File(rootDir, "IMG_0016.JPG").writeText("jpg")
        File(rootDir, "IMG_0016.ARW").writeText("raw")
        FakeSafDocumentsProvider.advertiseSupportsMove = true

        val results = engine.transferBundle(
            storage, listOf(documentUriFor("IMG_0016.JPG"), documentUriFor("IMG_0016.ARW")), selection(), true, true,
        )

        assertTrue(results.all { it.success })
        assertEquals(2, FakeSafDocumentsProvider.moveDocumentCalls.get())
        assertEquals("raw", File(rootDir, "Selection/RAW/IMG_0016.ARW").readText())
    }

    @Test
    fun `a partial member names only its own surviving original`() = runTest {
        File(rootDir, "IMG_0017.JPG").writeText("jpg")
        File(rootDir, "IMG_0017.CR3").writeText("raw")
        FakeSafDocumentsProvider.failDeletesMatching = "IMG_0017.JPG"

        val results = engine.transferBundle(
            storage, listOf(documentUriFor("IMG_0017.JPG"), documentUriFor("IMG_0017.CR3")), selection(), true, true,
        )

        assertTrue(results[0].partial)
        assertEquals("Copied to Selection — original could not be removed", results[0].error)
        assertEquals(mapOf(results[0].destinationUri!! to documentUriFor("IMG_0017.JPG").toString()), results[0].copiesOfSurvivingOriginals)
        assertTrue(results[1].success)
    }

    @Test
    fun `the Selection marker is written as soon as the folder is resolved, even if nothing lands`() = runTest {
        val result = engine.transferBundle(storage, listOf(documentUriFor("NOPE.JPG")), selection(), true, true).single()

        assertFalse(result.success)
        assertTrue(File(rootDir, "Selection/${SafTransferEngine.SELECTION_MARKER_FILE_NAME}").exists())
    }

    // TOK single copy/move: an unresolvable source fails before the destination is touched.
    @Test
    fun `transferSingle with an unresolvable source creates no Selection folder and no marker`() = runTest {
        val moved = engine.transferSingle(storage, documentUriFor("NOPE.JPG"), selection(), true, true, unresolvedSourceError = "Copy step failed")
        val copied = engine.transferSingle(storage, documentUriFor("NOPE.JPG"), selection(), true, false)

        assertEquals("Copy step failed", moved.error)
        assertEquals("Could not write to the Selection folder", copied.error)
        assertFalse(File(rootDir, "Selection").exists())
    }

    @Test
    fun `transferSingle transfers a resolvable source like a bundle of one`() = runTest {
        File(rootDir, "IMG_0018.JPG").writeText("payload")

        val result = engine.transferSingle(storage, documentUriFor("IMG_0018.JPG"), selection(), false, true)

        assertTrue(result.success)
        assertEquals("payload", File(rootDir, "Selection/IMG_0018.JPG").readText())
        assertTrue(File(rootDir, "Selection/${SafTransferEngine.SELECTION_MARKER_FILE_NAME}").exists())
    }
}
