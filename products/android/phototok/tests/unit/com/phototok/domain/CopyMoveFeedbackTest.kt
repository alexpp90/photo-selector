package com.phototok.domain

import com.phototok.R
import com.phototok.de
import com.phototok.en
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The feedback is a [UiText] (resource id + arguments); the wording itself is checked in EN and DE. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyMoveFeedbackTest {

    private val selection = UiText.raw("Selection")
    private val folder = UiText.raw("Rejects")

    @Test
    fun `all succeeded without related files`() {
        assertEquals(
            UiText.of(R.string.snackbar_copied, selection),
            CopyMoveFeedback.message(isCopy = true, destination = selection, succeeded = 1, failed = 0, relatedCount = 0),
        )
        assertEquals(
            UiText.of(R.string.snackbar_moved, folder),
            CopyMoveFeedback.message(isCopy = false, destination = folder, succeeded = 1, failed = 0, relatedCount = 0),
        )
    }

    @Test
    fun `the message names the configured verb and folder in both languages`() {
        val copied = CopyMoveFeedback.message(true, selection, succeeded = 1, failed = 0, relatedCount = 0)
        val moved = CopyMoveFeedback.message(false, folder, succeeded = 1, failed = 0, relatedCount = 0)
        assertEquals("Copied to Selection", copied.en())
        assertEquals("Moved to Rejects", moved.en())
        assertEquals("Kopiert nach Selection", copied.de())
        assertEquals("Verschoben nach Rejects", moved.de())
    }

    @Test
    fun `all succeeded with related files reports sibling count`() {
        val message = CopyMoveFeedback.message(false, selection, succeeded = 3, failed = 0, relatedCount = 2)
        assertEquals(UiText.of(R.string.snackbar_moved_related, selection, 2), message)
        assertEquals("Moved to Selection +2", message.en())
    }

    @Test
    fun `total failure`() {
        assertEquals(
            UiText.of(R.string.error_copy_failed, selection),
            CopyMoveFeedback.message(true, selection, succeeded = 0, failed = 1, relatedCount = 0),
        )
        assertEquals(
            UiText.of(R.string.error_move_failed, folder),
            CopyMoveFeedback.message(false, folder, succeeded = 0, failed = 2, relatedCount = 1),
        )
    }

    @Test
    fun `partial failure reports how many of the files went through`() {
        val moved = CopyMoveFeedback.message(false, selection, succeeded = 2, failed = 1, relatedCount = 2)
        assertEquals(UiText.of(R.string.error_moved_some, 2, 3, selection), moved)
        assertEquals("Moved 2 of 3 to Selection", moved.en())
        val copied = CopyMoveFeedback.message(true, folder, succeeded = 1, failed = 1, relatedCount = 1)
        assertEquals(UiText.of(R.string.error_copied_some, 1, 2, folder), copied)
    }

    @Test
    fun `isError only when something failed`() {
        assertFalse(CopyMoveFeedback.isError(0))
        assertTrue(CopyMoveFeedback.isError(1))
        assertTrue(CopyMoveFeedback.isError(failed = 0, partial = 1))
    }

    // REQ-TOK-ARCH.16
    @Test
    fun `partial source-delete failure reports copied with original not removed`() {
        val message = CopyMoveFeedback.message(
            isCopy = false,
            destination = selection,
            succeeded = 0,
            failed = 0,
            relatedCount = 0,
            partial = 1,
        )
        assertEquals(UiText.of(R.string.error_move_partial, selection), message)
        assertEquals("Copied to Selection, original not removed", message.en())
    }

    @Test
    fun `a collision-suffixed move names the file it became`() {
        val message = CopyMoveFeedback.message(
            isCopy = false,
            destination = selection,
            succeeded = 2,
            failed = 0,
            relatedCount = 1,
            renamedTo = "IMG_0001-2.jpg",
        )
        assertEquals(UiText.of(R.string.snackbar_moved_renamed_related, "IMG_0001-2.jpg", 1), message)
        assertEquals("Moved as IMG_0001-2.jpg +1", message.en())
    }

    @Test
    fun `a collision-suffixed copy without companions has no related count`() {
        val message = CopyMoveFeedback.message(
            isCopy = true,
            destination = selection,
            succeeded = 1,
            failed = 0,
            relatedCount = 0,
            renamedTo = "IMG_0001-3.jpg",
        )
        assertEquals(UiText.of(R.string.snackbar_copied_renamed, "IMG_0001-3.jpg"), message)
    }

    @Test
    fun `the rename is only reported when everything succeeded`() {
        assertEquals(
            UiText.of(R.string.error_moved_some, 1, 2, selection),
            CopyMoveFeedback.message(
                isCopy = false,
                destination = selection,
                succeeded = 1,
                failed = 1,
                relatedCount = 1,
                renamedTo = "IMG_0001-2.jpg",
            ),
        )
    }

    @Test
    fun `the renamed name is the one the engine read back, never a guess from the uri`() {
        assertEquals("IMG_1-2.JPG", CopyMoveFeedback.renamedName("IMG_1.JPG", "IMG_1-2.JPG"))
        assertNull("same name", CopyMoveFeedback.renamedName("IMG_1.JPG", "IMG_1.JPG"))
        assertNull("the engine could not read the name", CopyMoveFeedback.renamedName("IMG_1.JPG", null))
        assertNull("an empty name is no name", CopyMoveFeedback.renamedName("IMG_1.JPG", ""))
    }
}
