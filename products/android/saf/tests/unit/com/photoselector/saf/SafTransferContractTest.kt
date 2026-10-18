package com.photoselector.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The data-safety contract both products rely on (`docs/shared/FILE_OPERATIONS.md`), run once
 * per [BundleAtomicity] by [AllOrNothingSafTransferTest] (Android Desktop's policy) and
 * [PerFileSafTransferTest] (PhotoTok's).
 *
 * Each test names where it came from: "AD" is Android Desktop's former
 * `data/repository/SafTransferTest`, "TOK" PhotoTok's former `data/source/SafTransferTest`.
 * Tests that only one policy can pass live in the subclass of that policy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SafTransferContractTest(policy: SafTransferPolicy) : SafTestHarness(policy) {

    // ── 1. Collision-free bundle naming, end to end ──────────────────────

    // AD: end-to-end SAF bundle move preserves existing file in Selection JPEG and suffixes entire bundle
    @Test
    fun `bundle move preserves an existing file in Selection JPEG and suffixes the entire bundle`() = runTest {
        val existingJpeg = File(rootDir, "Selection/JPEG/IMG_0001.JPG").apply {
            parentFile?.mkdirs()
            writeText("pre-existing-jpeg-must-survive")
        }
        val names = listOf("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001.CR3.xmp", "IMG_0001-Edit.tif")
        val payloads = listOf("new-raw-bytes-12345", "new-jpeg-bytes-67890", "<x:xmpmeta/>", "new-edit-tiff-bytes")
        names.zip(payloads).forEach { (n, p) -> File(rootDir, n).writeText(p) }

        val results = engine.transferBundle(
            storage, names.map(::documentUriFor), selection(), sorting = true, isMove = true,
        )

        assertTrue(results.all { it.success })
        assertEquals("pre-existing-jpeg-must-survive", existingJpeg.readText())
        assertEquals("new-raw-bytes-12345", File(rootDir, "Selection/RAW/IMG_0001-2.CR3").readText())
        assertEquals("new-jpeg-bytes-67890", File(rootDir, "Selection/JPEG/IMG_0001-2.JPG").readText())
        assertEquals("<x:xmpmeta/>", File(rootDir, "Selection/RAW/IMG_0001-2.CR3.xmp").readText())
        assertEquals("new-edit-tiff-bytes", File(rootDir, "Selection/RAW/IMG_0001-2-Edit.tif").readText())
        names.forEach { assertFalse("$it must be removed after verification", File(rootDir, it).exists()) }
    }

    // TOK: transferBundle moves two identically named CR3+JPG+XMP bundles in sequence and keeps both paired
    @Test
    fun `two identically named bundles moved in sequence both stay paired`() = runTest {
        for (card in listOf("card1", "card2")) {
            val dir = File(rootDir, card).apply { mkdirs() }
            val tail = if (card == "card2") "-longer" else ""
            File(dir, "IMG_0001.CR3").writeText("raw-$card$tail")
            File(dir, "IMG_0001.JPG").writeText("jpg-$card$tail")
            File(dir, "IMG_0001.CR3.xmp").writeText("xmp-$card$tail")
        }
        for (card in listOf("card1", "card2")) {
            val results = engine.transferBundle(
                storage,
                listOf("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001.CR3.xmp").map { documentUriFor("$card/$it") },
                selection(), sorting = true, isMove = true,
            )
            assertTrue(results.all { it.success })
        }

        assertEquals("raw-card1", File(rootDir, "Selection/RAW/IMG_0001.CR3").readText())
        assertEquals("jpg-card1", File(rootDir, "Selection/JPEG/IMG_0001.JPG").readText())
        assertEquals("xmp-card1", File(rootDir, "Selection/RAW/IMG_0001.CR3.xmp").readText())
        assertEquals("raw-card2-longer", File(rootDir, "Selection/RAW/IMG_0001-2.CR3").readText())
        assertEquals("jpg-card2-longer", File(rootDir, "Selection/JPEG/IMG_0001-2.JPG").readText())
        assertEquals("xmp-card2-longer", File(rootDir, "Selection/RAW/IMG_0001-2.CR3.xmp").readText())
        assertFalse(File(rootDir, "card1/IMG_0001.CR3").exists())
        assertFalse(File(rootDir, "card2/IMG_0001.CR3").exists())
    }

    // TOK: a collision on the RAW gives the JPEG, the edit and both sidecars the same suffix (B-6)
    @Test
    fun `a collision on the RAW gives the JPEG, the edit and both sidecars the same suffix`() = runTest {
        val dest = File(rootDir, "Selection/RAW").apply { mkdirs() }
        File(dest, "IMG_0001.CR3").writeText("already-there")
        val src = File(rootDir, "card").apply { mkdirs() }
        val names = listOf("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001-Edit.tif", "IMG_0001.xmp", "IMG_0001.CR3.xmp")
        names.forEach { File(src, it).writeText("payload-$it") }

        val results = engine.transferBundle(
            storage, names.map { documentUriFor("card/$it") }, selection(), sorting = true, isMove = true,
        )

        assertTrue(results.all { it.success })
        assertEquals("already-there", File(dest, "IMG_0001.CR3").readText())
        assertEquals("payload-IMG_0001.CR3", File(dest, "IMG_0001-2.CR3").readText())
        assertEquals("payload-IMG_0001.JPG", File(rootDir, "Selection/JPEG/IMG_0001-2.JPG").readText())
        assertEquals("payload-IMG_0001-Edit.tif", File(dest, "IMG_0001-2-Edit.tif").readText())
        assertEquals("payload-IMG_0001.CR3.xmp", File(dest, "IMG_0001-2.CR3.xmp").readText())
        assertEquals("payload-IMG_0001.xmp", File(rootDir, "Selection/IMG_0001-2.xmp").readText())
    }

    // ── 2. Size verification before the source is deleted ────────────────

    // AD: size mismatch after copy cleans up destination and keeps source untouched
    // TOK: size mismatch after copy deletes partial destination and never deletes source on move
    @Test
    fun `size mismatch after copy cleans up the destination and never deletes the source`() = runTest {
        val srcFile = File(rootDir, "IMG_0002.CR3").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        FakeSafDocumentsProvider.reportedSizeOverride = { file ->
            if (file.parentFile?.name == "RAW" && file.name == "IMG_0002.CR3") 1024L else file.length()
        }

        val result = move(srcFile.name)

        assertFalse(result.success)
        assertFalse(result.partial)
        assertNull(result.destinationUri)
        assertEquals("Copied file size mismatch", result.error)
        assertFalse(File(rootDir, "Selection/RAW/IMG_0002.CR3").exists())
        assertTrue(srcFile.exists())
        assertEquals(4096L, srcFile.length())
    }

    // ── 3. Source-delete failure reported as partial ─────────────────────

    // AD: source delete failure after verified copy keeps destination and returns partial result
    // TOK: source delete failure after verified copy returns partial result and keeps destination copy
    @Test
    fun `source delete failure after a verified copy keeps both and returns partial`() = runTest {
        val payload = "verified-camera-original-bytes"
        val srcFile = File(rootDir, "IMG_0003.JPG").apply { writeText(payload) }
        FakeSafDocumentsProvider.failDeletes = true

        val result = move(srcFile.name)

        assertFalse(result.success)
        assertTrue(result.partial)
        assertNotNull(result.destinationUri)
        // B-7: the feedback names the folder the copy landed in.
        assertEquals("Copied to Selection — original could not be removed", result.error)
        assertEquals(mapOf(result.destinationUri!! to documentUriFor(srcFile.name).toString()), result.copiesOfSurvivingOriginals)
        assertEquals(payload, File(rootDir, "Selection/JPEG/IMG_0003.JPG").readText())
        assertTrue(srcFile.exists())
    }

    // ── 4. Atomic DocumentsContract.moveDocument when supported ──────────

    // AD + TOK: moveImage uses DocumentsContract moveDocument when same authority and FLAG_SUPPORTS_MOVE and no rename needed
    @Test
    fun `move uses DocumentsContract moveDocument when same authority, FLAG_SUPPORTS_MOVE and no rename`() = runTest {
        val srcFile = File(rootDir, "IMG_0004.ARW").apply { writeText("atomic-move-payload") }
        FakeSafDocumentsProvider.advertiseSupportsMove = true

        val result = move(srcFile.name)

        assertTrue(result.success)
        assertFalse(result.partial)
        assertEquals(1, FakeSafDocumentsProvider.moveDocumentCalls.get())
        assertFalse(srcFile.exists())
        assertEquals("atomic-move-payload", File(rootDir, "Selection/RAW/IMG_0004.ARW").readText())
    }

    // AD + TOK: moveImage falls back to verified copy with -2 suffix when collision exists even if FLAG_SUPPORTS_MOVE is set
    @Test
    fun `move falls back to a verified copy with -2 when the name collides, even with FLAG_SUPPORTS_MOVE`() = runTest {
        File(rootDir, "Selection/RAW/IMG_0004.ARW").apply { parentFile?.mkdirs(); writeText("existing-raw") }
        val srcFile = File(rootDir, "IMG_0004.ARW").apply { writeText("second-raw") }
        FakeSafDocumentsProvider.advertiseSupportsMove = true

        val result = move(srcFile.name)

        assertTrue(result.success)
        assertEquals(0, FakeSafDocumentsProvider.moveDocumentCalls.get())
        assertEquals("existing-raw", File(rootDir, "Selection/RAW/IMG_0004.ARW").readText())
        assertEquals("second-raw", File(rootDir, "Selection/RAW/IMG_0004-2.ARW").readText())
        assertFalse(srcFile.exists())
    }

    // ── 5. Provider silent auto-rename mismatch ──────────────────────────

    // AD + TOK: provider auto-rename on createDocument cleans up renamed file and fails without deleting source
    @Test
    fun `provider auto-rename on createDocument cleans up the renamed file and fails without deleting the source`() = runTest {
        val srcFile = File(rootDir, "IMG_0005.CR3").apply { writeText("raw-content") }
        FakeSafDocumentsProvider.autoRenameOnCreate = { requested ->
            if (requested == "IMG_0005.CR3") "IMG_0005 (1).CR3" else requested
        }

        val result = move(srcFile.name)

        assertFalse(result.success)
        assertNull(result.destinationUri)
        assertTrue(result.error.orEmpty().contains("IMG_0005 (1).CR3"))
        assertFalse(File(rootDir, "Selection/RAW/IMG_0005 (1).CR3").exists())
        assertFalse(File(rootDir, "Selection/RAW/IMG_0005.CR3").exists())
        assertTrue(srcFile.exists())
    }

    // ── 6. Semaphore and CancellationException hygiene ───────────────────

    // AD + TOK: transferSemaphore bounds concurrent transfers to at most 2 in flight
    @Test
    fun `at most two transfers are in flight at once`() = runTest {
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        val inFlight = AtomicInteger(0)
        val maxObservedInFlight = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        // Enter the semaphore on `standardDispatcher`, then hold the transfer body until the gate opens.
        val gatingDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                standardDispatcher.dispatch(context, Runnable {
                    val current = inFlight.incrementAndGet()
                    maxObservedInFlight.updateAndGet { prev -> maxOf(prev, current) }
                    if (!gate.isCompleted) {
                        gate.invokeOnCompletion { inFlight.decrementAndGet(); block.run() }
                    } else {
                        inFlight.decrementAndGet()
                        block.run()
                    }
                })
            }
        }
        engine.ioDispatcher = gatingDispatcher

        // Transfers into one destination are serialised (D-5), so each file goes to its own
        // destination: only the semaphore then bounds the parallelism.
        val files = (1..5).map { File(rootDir, "CONC_$it.JPG").apply { writeText("photo-$it") } }
        val destinations = (1..5).map { idx ->
            File(rootDir, "dest$idx").mkdirs()
            DocumentsContract.buildTreeDocumentUri(FakeSafDocumentsProvider.AUTHORITY, "${FakeSafDocumentsProvider.ROOT_ID}/dest$idx")
        }
        val deferreds = files.mapIndexed { idx, f ->
            async(standardDispatcher) {
                engine.transferBundle(storage, listOf(documentUriFor(f.name)), selection(root = destinations[idx]), true, false)
            }
        }

        testScheduler.runCurrent()
        assertEquals(2, inFlight.get())
        assertEquals(2, maxObservedInFlight.get())

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(deferreds.map { it.await() }.flatten().all { it.success })
        assertEquals(2, maxObservedInFlight.get())
    }

    // AD: CancellationException is rethrown and never swallowed into FileOperationResult failure
    // TOK: CancellationException is rethrown and never swallowed into false or failure
    @Test
    fun `CancellationException is rethrown and never swallowed into a failure`() = runTest {
        engine.ioDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                throw CancellationException("Transfer cancelled by scope")
            }
        }
        val srcFile = File(rootDir, "CANCEL_ME.JPG").apply { writeText("bytes") }

        for (isMove in listOf(true, false)) {
            try {
                engine.transferBundle(storage, listOf(documentUriFor(srcFile.name)), selection(), true, isMove)
                fail("Expected CancellationException to be rethrown (isMove=$isMove)")
            } catch (expected: CancellationException) {
                assertEquals("Transfer cancelled by scope", expected.message)
            }
        }
    }

    // ── 7. Destination: always the Selection subfolder ───────────────────

    // AD: sorting false files bundle flat into Selection without RAW or JPEG subfolders and undo restores to original folder (U-04c; the filing half — the undo half is in AllOrNothingSafTransferTest)
    // TOK (lab 2026-10-18): default copy/move lands flat in Selection, never next to the original
    @Test
    fun `sorting off files flat into Selection, never into the source folder`() = runTest {
        val subDir = File(rootDir, "sub/day2").apply { mkdirs() }
        listOf("IMG_0020.JPG", "IMG_0020.CR3", "IMG_0020.CR3.xmp").forEach { File(subDir, it).writeText("flat-$it") }
        File(rootDir, "IMG_0021.JPG").writeText("root-photo")

        val moved = engine.transferBundle(
            storage,
            listOf("IMG_0020.JPG", "IMG_0020.CR3", "IMG_0020.CR3.xmp").map { documentUriFor("sub/day2/$it") },
            selection(), sorting = false, isMove = true,
        )
        val copied = copy("IMG_0021.JPG", sorting = false)

        assertTrue(moved.all { it.success })
        assertTrue(copied.success)
        for (n in listOf("IMG_0020.JPG", "IMG_0020.CR3", "IMG_0020.CR3.xmp")) {
            assertEquals("flat-$n", File(rootDir, "Selection/$n").readText())
            assertFalse(File(subDir, n).exists())
        }
        assertEquals("root-photo", File(rootDir, "Selection/IMG_0021.JPG").readText())
        assertTrue(File(rootDir, "IMG_0021.JPG").exists())
        assertFalse(File(rootDir, "Selection/RAW").exists())
        assertFalse(File(rootDir, "Selection/JPEG").exists())
        assertFalse(File(rootDir, "IMG_0021-2.JPG").exists())
        assertTrue(File(rootDir, "Selection/${SafTransferEngine.SELECTION_MARKER_FILE_NAME}").exists())
    }

    // TOK: default left-swipe copy lands flat in PhotoTok_LeftSwipe (the folder name is the caller's)
    @Test
    fun `the destination folder name is the caller's`() = runTest {
        File(rootDir, "DSC_0007.JPG").writeText("payload")

        assertTrue(copy("DSC_0007.JPG", sorting = false, folderName = "PhotoTok_LeftSwipe").success)

        assertTrue(File(rootDir, "PhotoTok_LeftSwipe/DSC_0007.JPG").exists())
        assertEquals(listOf("DSC_0007.JPG", "PhotoTok_LeftSwipe"), rootDir.list()!!.sorted())
    }

    // Android Desktop's second pass: the folder name may depend on the resolved destination root.
    @Test
    fun `folderNameIn chooses the folder once the destination root is resolved`() = runTest {
        File(rootDir, "IMG_0007.JPG").writeText("payload")
        val destination = SelectionDestination(treeUri, "Selection") { root -> if (root.name == "shoot") "Final" else "Selection" }

        val result = engine.transferBundle(storage, listOf(documentUriFor("IMG_0007.JPG")), destination, false, false).single()

        assertTrue(result.success)
        assertTrue(File(rootDir, "Final/IMG_0007.JPG").exists())
        assertFalse(File(rootDir, "Selection").exists())
    }

    // B-7: feedback for a source that cannot be resolved; PhotoTok's single move overrides it.
    @Test
    fun `an unresolvable source fails with the folder named, or with the caller's override`() = runTest {
        val missing = documentUriFor("NOPE.JPG")
        val default = engine.transferBundle(storage, listOf(missing), selection(), true, true).single()
        val overridden = engine.transferBundle(storage, listOf(missing), selection(), true, true, unresolvedSourceError = "Copy step failed").single()

        assertFalse(default.success)
        assertEquals("Could not write to the Selection folder", default.error)
        assertEquals("Copy step failed", overridden.error)
    }

    @Test
    fun `an empty bundle transfers nothing`() = runTest {
        assertTrue(engine.transferBundle(storage, emptyList(), selection(), true, true).isEmpty())
        assertFalse(File(rootDir, "Selection").exists())
    }

    // Removable media: the policy decides which destinations are fsynced.
    @Test
    fun `syncDestination is asked for every written copy`() = runTest {
        val asked = mutableListOf<Uri>()
        engine = newEngine(SafTransferPolicy(BundleAtomicity.PER_FILE, MarkerTiming.WHEN_FOLDER_RESOLVED) { asked += it; true })
        File(rootDir, "IMG_0008.JPG").writeText("payload")

        assertTrue(copy("IMG_0008.JPG").success)

        assertEquals(1, asked.size)
        assertTrue(asked.single().toString().contains("IMG_0008.JPG"))
    }

    // ── 8. Delete ────────────────────────────────────────────────────────

    // AD: deleteBundle deletes primary and all companion files (the per-file unlink it is built on)
    @Test
    fun `delete unlinks the document and reports a provider refusal as false`() = runTest {
        val a = File(rootDir, "IMG_0012.JPG").apply { writeText("jpg") }
        val b = File(rootDir, "IMG_0013.JPG").apply { writeText("jpg") }

        assertTrue(engine.delete(storage, documentUriFor(a.name)))
        FakeSafDocumentsProvider.failDeletes = true
        assertFalse(engine.delete(storage, documentUriFor(b.name)))

        assertFalse(a.exists())
        assertTrue(b.exists())
    }

    // ── B-5 / D-5: concurrent transfers into the same destination ────────

    // AD: two concurrent transfers of same-named files into one Selection both succeed with a suffix and one Selection folder
    // TOK: two concurrent copies of same-named files into one Selection both succeed with a suffix and one Selection folder
    @Test
    fun `two concurrent transfers of same-named files into one Selection both succeed with a suffix and one Selection folder`() {
        engine.ioDispatcher = Dispatchers.IO
        FakeSafDocumentsProvider.createDelayMs = 150L
        File(rootDir, "dayA").mkdirs(); File(rootDir, "dayA/IMG_0100.JPG").writeText("payload-A")
        File(rootDir, "dayB").mkdirs(); File(rootDir, "dayB/IMG_0100.JPG").writeText("payload-B")

        val results = kotlinx.coroutines.runBlocking {
            val a = async(Dispatchers.IO) {
                engine.transferBundle(storage, listOf(documentUriFor("dayA/IMG_0100.JPG")), selection(), true, false)
            }
            val b = async(Dispatchers.IO) {
                engine.transferBundle(storage, listOf(documentUriFor("dayB/IMG_0100.JPG")), selection(), true, false)
            }
            a.await() + b.await()
        }

        assertTrue(results.all { it.success })
        val jpeg = File(rootDir, "Selection/JPEG").list()!!.sorted()
        assertEquals(listOf("IMG_0100-2.JPG", "IMG_0100.JPG"), jpeg)
        assertEquals(setOf("payload-A", "payload-B"), jpeg.map { File(rootDir, "Selection/JPEG/$it").readText() }.toSet())
        assertEquals(listOf("Selection"), rootDir.list()!!.filter { it.startsWith("Selection") }.sorted())
    }

    // ── Provider seam ────────────────────────────────────────────────────

    @Test
    fun `a provider that refuses the atomic move falls back to the verified copy`() = runTest {
        val srcFile = File(rootDir, "IMG_0009.ARW").apply { writeText("payload") }
        val calls = AtomicInteger(0)
        engine.documentMover = object : SafDocumentMover {
            override fun canMoveDocument(contentResolver: ContentResolver, sourceUri: Uri, sourceParentUri: Uri?, targetFolderUri: Uri) = true
            override fun moveDocument(contentResolver: ContentResolver, sourceUri: Uri, sourceParentUri: Uri, targetFolderUri: Uri): Uri? {
                calls.incrementAndGet()
                return null
            }
        }

        val result = move(srcFile.name)

        assertTrue(result.success)
        assertEquals(1, calls.get())
        assertEquals("payload", File(rootDir, "Selection/RAW/IMG_0009.ARW").readText())
        assertFalse(srcFile.exists())
    }
}
