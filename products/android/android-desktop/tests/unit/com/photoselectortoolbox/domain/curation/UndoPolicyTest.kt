package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An UNDO that silently does nothing is worse than no UNDO at all.
 *
 * So the snackbar's `onUndo` is non-null only for the operations this policy
 * says are genuinely reversible, and null everywhere else.
 */
class UndoPolicyTest {

    private val image = ImageItem(
        uri = "content://tree/a.jpg",
        fileName = "a.jpg",
        fileSize = 1L,
        lastModified = 0L,
        mimeType = "image/jpeg",
    )
    private val slots = listOf(ImageSlot(image, 0))

    @Test
    fun `a deferred delete is always reversible`() {
        val pending = PendingDeletion(slots, requestedAtMillis = 0L)

        val undo = UndoPolicy.forPendingDelete(pending)

        assertTrue(undo is UndoableOperation.RevertPendingDelete)
        assertEquals(slots, undo!!.slots)
    }

    @Test
    fun `no pending deletion means no undo`() {
        assertNull(UndoPolicy.forPendingDelete(null))
        assertNull(UndoPolicy.forPendingDelete(PendingDeletion(emptyList(), 0L)))
    }

    @Test
    fun `a committed delete is reversible only where the backend trashes`() {
        val trashed = UndoPolicy.forCommittedDelete(slots, canTrash = true)
        assertTrue(trashed is UndoableOperation.RestoreFromTrash)
        assertEquals(listOf(image.uri), (trashed as UndoableOperation.RestoreFromTrash).uris)

        // A SAF delete is an unlink with nothing behind it. Offer nothing.
        assertNull(UndoPolicy.forCommittedDelete(slots, canTrash = false))
    }

    @Test
    fun `a move is reversible once the repository reports where the file went`() {
        val undo = UndoPolicy.forFiling(
            action = CurationAction.MOVE,
            slots = slots,
            sourceUri = image.uri,
            destinationUri = "content://tree/Selection/a.jpg",
        )

        assertTrue(undo is UndoableOperation.ReverseMove)
        assertEquals("content://tree/Selection/a.jpg", (undo as UndoableOperation.ReverseMove).destinationUri)
    }

    @Test
    fun `a move with no destination uri offers no undo`() {
        // This is exactly what the Boolean-returning repository used to force:
        // the file is somewhere, and nothing below the UI can find it again.
        assertNull(
            UndoPolicy.forFiling(CurationAction.MOVE, slots, image.uri, destinationUri = null),
        )
    }

    @Test
    fun `a copy offers no undo, because undoing it would delete a file`() {
        // The original was never touched, so there is nothing to restore; the
        // only thing an "undo" could do is destroy the copy the photographer
        // just asked for. An undo must never be the destructive action.
        assertNull(
            UndoPolicy.forFiling(
                CurationAction.COPY,
                slots,
                image.uri,
                destinationUri = "content://tree/Selection/a.jpg",
            ),
        )
    }

    @Test
    fun `push and pop maintain LIFO order up to 20 entries and report evicted pending deletions`() {
        var history = emptyList<UndoableOperation>()
        for (i in 1..20) {
            val item = image.copy(uri = "content://tree/$i.jpg", fileName = "$i.jpg")
            val op = UndoableOperation.RevertPendingDelete(PendingDeletion(listOf(ImageSlot(item, i - 1)), 0L))
            val result = UndoPolicy.push(history, op)
            assertTrue(result.evictedPendingDeletions.isEmpty())
            history = result.history
        }
        assertEquals(20, history.size)

        // Pushing a 21st entry evicts the oldest (1.jpg) and reports it as an evicted pending deletion.
        val item21 = image.copy(uri = "content://tree/21.jpg", fileName = "21.jpg")
        val push21 = UndoPolicy.push(
            history,
            UndoableOperation.RevertPendingDelete(PendingDeletion(listOf(ImageSlot(item21, 20)), 0L)),
        )
        assertEquals(20, push21.history.size)
        assertEquals(1, push21.evictedPendingDeletions.size)
        assertEquals("content://tree/1.jpg", push21.evictedPendingDeletions.first().slots.first().image.uri)

        // Popping returns the most recent (21.jpg) first.
        val (popped, remaining) = UndoPolicy.pop(push21.history)
        assertEquals("content://tree/21.jpg", (popped as UndoableOperation.RevertPendingDelete).slots.first().image.uri)
        assertEquals(19, remaining.size)
    }

    @Test
    fun `a failed restore from the trash keeps exactly the files that are still in the trash`() {
        val b = image.copy(uri = "content://tree/b.jpg", fileName = "b.jpg")
        val op = UndoableOperation.RestoreFromTrash(
            slots = listOf(ImageSlot(image, 0), ImageSlot(b, 1)),
            uris = listOf(image.uri, b.uri),
        )

        val remainder = UndoPolicy.remainderAfterRestore(op, failedUris = listOf(b.uri))

        assertEquals(listOf(b.uri), remainder!!.uris)
        assertEquals(listOf(b.uri), remainder.slots.map { it.uri })
        assertNull(UndoPolicy.remainderAfterRestore(op, failedUris = emptyList()))
    }
}
