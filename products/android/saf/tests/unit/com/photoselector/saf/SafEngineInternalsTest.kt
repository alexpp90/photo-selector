package com.photoselector.saf

import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Engine primitives that the end-to-end suites reach only indirectly. "TOK" names the former
 * PhotoTok `LocalImageSourceImplTest` case each test replaces (`determineTargetFolder`,
 * `cleanUpFailedCopy`); PhotoTok's own sorting rule stays tested in PhotoTok.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafEngineInternalsTest {

    private val engine = SafTransferEngine(TestSubfolderRule, PER_FILE_POLICY)
    private val rawDir = mockk<DocumentFile>()
    private val jpegDir = mockk<DocumentFile>()
    private val selectionDir = mockk<DocumentFile> {
        every { findFile("RAW") } returns rawDir
        every { findFile("JPEG") } returns jpegDir
    }

    @Test
    fun `RAW and JPEG go to their existing subfolders, ROOT stays in the Selection folder`() {
        assertSame(rawDir, engine.subfolderIn(SelectionSubfolder.RAW, selectionDir))
        assertSame(jpegDir, engine.subfolderIn(SelectionSubfolder.JPEG, selectionDir))
        assertSame(selectionDir, engine.subfolderIn(SelectionSubfolder.ROOT, selectionDir))
    }

    @Test
    fun `a missing subfolder is created`() {
        val created = mockk<DocumentFile>()
        val dir = mockk<DocumentFile> {
            every { findFile("RAW") } returns null
            every { createDirectory("RAW") } returns created
        }
        assertSame(created, engine.subfolderIn(SelectionSubfolder.RAW, dir))
    }

    // TOK: falls back to selection folder when subfolder cannot be created
    @Test
    fun `falls back to the Selection folder when the subfolder cannot be created`() {
        val brokenDir = mockk<DocumentFile> {
            every { findFile("RAW") } returns null
            every { createDirectory("RAW") } returns null
        }
        assertSame(brokenDir, engine.subfolderIn(SelectionSubfolder.RAW, brokenDir))
    }

    // TOK: cleanUpFailedCopy calls delete on destination file
    @Test
    fun `cleanUpFailedCopy deletes the destination file`() {
        val destFile = mockk<DocumentFile>(relaxed = true) { every { delete() } returns true }
        engine.cleanUpFailedCopy(destFile)
        verify { destFile.delete() }
    }

    // TOK: cleanUpFailedCopy swallows exception on delete without crashing
    @Test
    fun `cleanUpFailedCopy swallows an exception from delete`() {
        val destFile = mockk<DocumentFile>(relaxed = true) { every { delete() } throws SecurityException("denied") }
        engine.cleanUpFailedCopy(destFile) // must not throw
        verify { destFile.delete() }
    }
}
