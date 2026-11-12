package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.DuplicateGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldDuplicateDeletesTest {

    private fun held(n: Int) = HeldDuplicateDelete(emptyList(), n, emptyList(), emptySet())

    @Test
    fun `a group left with one copy is no duplicate and leaves the list`() {
        val groups = listOf(DuplicateGroup("x", listOf("a", "b", "c")), DuplicateGroup("y", listOf("d", "e")))

        val left = HeldDuplicateDeletes.remainingGroups(groups, setOf("b", "e"))

        assertEquals(listOf(DuplicateGroup("x", listOf("a", "c"))), left)
    }

    @Test
    fun `the stack is as deep as the cull's undo history and gives back the oldest beyond it`() {
        val stack = (1..UndoPolicy.MAX_UNDO_HISTORY).map(::held)

        val (kept, evicted) = HeldDuplicateDeletes.push(stack, held(99))

        assertEquals(UndoPolicy.MAX_UNDO_HISTORY, kept.size)
        assertEquals(99, kept.last().copies)
        assertEquals(listOf(1), evicted.map { it.copies })
    }

    @Test
    fun `an in-session commit keeps only a file whose known identity changed`() {
        val recorded = FileIdentity(size = 10, lastModified = 1)

        assertFalse(PendingDeleteJournal.keepsInSession(recorded, FileIdentity(10, 1)))
        assertTrue(PendingDeleteJournal.keepsInSession(recorded, FileIdentity(11, 1)))
        // Nothing readable: the delete itself reports it.
        assertFalse(PendingDeleteJournal.keepsInSession(recorded, null))
        // A provider that reports neither field: deleted as it always was in a session.
        assertFalse(PendingDeleteJournal.keepsInSession(FileIdentity(0, 0), FileIdentity(11, 1)))
        assertFalse(PendingDeleteJournal.keepsInSession(null, FileIdentity(11, 1)))
    }
}
