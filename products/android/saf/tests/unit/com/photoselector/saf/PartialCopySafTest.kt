package com.photoselector.saf

import android.net.Uri
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.OutputStream

/** Stands in for the process dying: an Error, so no `catch (e: Exception)` cleanup runs. */
private class ProcessDied : Error("killed mid-copy")

/**
 * A copy that is cut short (process death, card pulled, out of memory) never leaves a
 * truncated file under its final name. Requirements: REQ-TOK-FEATURE.15 (H-JP-1) and
 * REQ-AND-TRANSFER (Frameduel) via the shared engine; docs/shared/FILE_OPERATIONS.md.
 */
@RunWith(RobolectricTestRunner::class)
class PartialCopySafTest : SafTestHarness(PER_FILE_POLICY) {

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    /** A storage whose destination streams die after [killAfter] bytes. */
    private fun dyingStorage(killAfter: Int): SafStorage {
        val real = storage
        val resolver = spyk(real.contentResolver)
        every { resolver.openOutputStream(any<Uri>()) } answers {
            val inner = callOriginal()!!
            object : OutputStream() {
                var written = 0
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val room = killAfter - written
                    if (len > room) {
                        inner.write(b, off, room.coerceAtLeast(0)); inner.flush()
                        throw ProcessDied()
                    }
                    inner.write(b, off, len); written += len
                }
                override fun flush() = inner.flush()
                override fun close() = inner.close()
            }
        }
        return object : SafStorage by real {
            override val contentResolver get() = resolver
        }
    }

    private fun selectionFiles(): List<File> =
        File(rootDir, "Selection").walkTopDown().filter { it.isFile }.toList()

    private suspend fun copyDying(name: String, killAfter: Int) {
        try {
            engine.transferBundle(
                dyingStorage(killAfter), listOf(documentUriFor(name)), selection(), true, isMove = true,
            )
            org.junit.Assert.fail("the simulated process death must propagate")
        } catch (_: ProcessDied) {
        }
    }

    @Test
    fun `an interrupted copy leaves no file under the final name`() = runTest {
        val src = File(rootDir, "IMG_0900.CR3").apply { writeBytes(payload) }

        copyDying(src.name, killAfter = 1000)

        assertFalse(
            "a truncated file must not carry the final name: ${selectionFiles()}",
            File(rootDir, "Selection/RAW/IMG_0900.CR3").exists(),
        )
        assertEquals(payload.size.toLong(), src.length())
    }

    @Test
    fun `after an interrupted copy a rerun copies the file whole and removes the stale temporary`() = runTest {
        val src = File(rootDir, "IMG_0901.CR3").apply { writeBytes(payload) }
        copyDying(src.name, killAfter = 1000)

        val result = move(src.name)

        assertTrue(result.error, result.success)
        val final = File(rootDir, "Selection/RAW/IMG_0901.CR3")
        assertEquals(payload.size.toLong(), final.length())
        assertEquals(listOf(final), selectionFiles().filter { it.parentFile == final.parentFile })
        assertFalse(src.exists())
    }

    @Test
    fun `a completed copy carries the final name and the verified size`() = runTest {
        val src = File(rootDir, "IMG_0902.JPG").apply { writeBytes(payload) }

        val result = copy(src.name)

        assertTrue(result.success)
        val final = File(rootDir, "Selection/JPEG/IMG_0902.JPG")
        assertEquals(payload.size.toLong(), final.length())
        assertEquals(listOf(final), selectionFiles().filter { it.name.startsWith("IMG_0902") })
        assertEquals(listOf("IMG_0902.JPG.photoselector-partial -> IMG_0902.JPG"), FakeSafDocumentsProvider.renames)
    }

    @Test
    fun `a stale temporary of the engine is removed and a user file with a similar name is kept`() = runTest {
        File(rootDir, "Selection/JPEG").mkdirs()
        val stale = File(rootDir, "Selection/JPEG/IMG_0903.CR3.photoselector-partial").apply { writeBytes(ByteArray(10)) }
        val users = listOf("IMG_0904.partial", "notes.photoselector", "IMG_0905.CR3").map {
            File(rootDir, "Selection/JPEG/$it").apply { writeText("mine") }
        }
        val src = File(rootDir, "IMG_0906.JPG").apply { writeBytes(payload) }

        assertTrue(copy(src.name).success)

        assertFalse(stale.exists())
        users.forEach { assertEquals("mine", it.readText()) }
    }

    @Test
    fun `a provider without rename falls back to a direct copy that is removed when it fails`() = runTest {
        FakeSafDocumentsProvider.supportsRename = false
        val ok = File(rootDir, "IMG_0907.CR3").apply { writeBytes(payload) }

        val result = move(ok.name)

        assertTrue(result.error, result.success)
        assertEquals(payload.size.toLong(), File(rootDir, "Selection/RAW/IMG_0907.CR3").length())
        assertTrue(FakeSafDocumentsProvider.renames.isEmpty())
        assertEquals(1, selectionFiles().count { it.name.startsWith("IMG_0907") })

        // A write that fails with an exception leaves no truncated final file either.
        val bad = File(rootDir, "IMG_0908.CR3").apply { writeBytes(payload) }
        val failing = failingStorage()
        val failed = engine.transferBundle(failing, listOf(documentUriFor(bad.name)), selection(), true, isMove = true).single()
        assertFalse(failed.success)
        assertFalse(File(rootDir, "Selection/RAW/IMG_0908.CR3").exists())
        assertTrue(bad.exists())
    }

    private fun failingStorage(): SafStorage {
        val real = storage
        val resolver = spyk(real.contentResolver)
        every { resolver.openOutputStream(any<Uri>()) } answers {
            val inner = callOriginal()!!
            object : OutputStream() {
                override fun write(b: Int) { inner.write(b); throw java.io.IOException("card pulled") }
                override fun write(b: ByteArray, off: Int, len: Int) { throw java.io.IOException("card pulled") }
                override fun close() = inner.close()
            }
        }
        return object : SafStorage by real {
            override val contentResolver get() = resolver
        }
    }
}
