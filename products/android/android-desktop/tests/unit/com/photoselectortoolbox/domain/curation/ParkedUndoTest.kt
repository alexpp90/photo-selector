package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Switching folders must not drop a finished operation's undo record. */
class ParkedUndoTest {

    private fun move(name: String) = UndoableOperation.ReverseMove(
        slots = emptyList(),
        sourceUri = "content://tree/$name",
        destinationUri = "content://sel/$name",
    )

    private val pendingDelete: UndoableOperation = UndoableOperation.RevertPendingDelete(
        PendingDeletion(
            listOf(ImageSlot(ImageItem("content://tree/d", "d", 1L, 0L, "image/jpeg"), 0)),
            requestedAtMillis = 0L,
        ),
    )

    @Test
    fun `parked records come back, newest first, when the folder is taken again`() {
        val parked = ParkedUndo().park("A", listOf(move("2"), move("1")))

        val (taken, rest) = parked.take("A")

        assertEquals(listOf(move("2"), move("1")), taken)
        assertTrue(rest.folders.isEmpty())
    }

    @Test
    fun `pending deletions are never parked`() {
        val parked = ParkedUndo().park("A", listOf(pendingDelete, move("1")))

        assertEquals(listOf(move("1")), parked.historyOf("A"))
        assertTrue(ParkedUndo().park("A", listOf(pendingDelete)).folders.isEmpty())
    }

    @Test
    fun `a late record goes in front of what the folder already has`() {
        val parked = ParkedUndo().park("A", listOf(move("1"))).park("A", listOf(move("2")))

        assertEquals(listOf(move("2"), move("1")), parked.historyOf("A"))
    }

    @Test
    fun `the oldest parked folder is evicted past the folder bound`() {
        var parked = ParkedUndo()
        (1..ParkedUndo.MAX_PARKED_FOLDERS + 1).forEach { parked = parked.park("F$it", listOf(move("$it"))) }

        assertEquals(ParkedUndo.MAX_PARKED_FOLDERS, parked.folders.size)
        assertTrue("F1" !in parked.folders)
        assertTrue("F${ParkedUndo.MAX_PARKED_FOLDERS + 1}" in parked.folders)
    }

    @Test
    fun `parking again refreshes a folder so it is evicted last`() {
        var parked = ParkedUndo()
        (1..ParkedUndo.MAX_PARKED_FOLDERS).forEach { parked = parked.park("F$it", listOf(move("$it"))) }
        parked = parked.park("F1", listOf(move("x"))).park("NEW", listOf(move("n")))

        assertTrue("F1" in parked.folders)
        assertTrue("F2" !in parked.folders)
    }

    @Test
    fun `one folder's history is bounded to the undo depth`() {
        var parked = ParkedUndo()
        (1..UndoPolicy.MAX_UNDO_HISTORY + 5).forEach { parked = parked.park("A", listOf(move("$it"))) }

        assertEquals(UndoPolicy.MAX_UNDO_HISTORY, parked.historyOf("A").size)
        assertEquals(move("${UndoPolicy.MAX_UNDO_HISTORY + 5}"), parked.historyOf("A").first())
    }

    @Test
    fun `the pin set holds every parked folder plus the open one while its history is non-empty`() {
        val parked = ParkedUndo().park("A", listOf(move("1")))

        assertEquals(setOf("A", "B"), UndoParking.foldersToPin("B", listOf(move("2")), parked))
        assertEquals(setOf("A"), UndoParking.foldersToPin("B", emptyList(), parked))
        assertTrue(UndoParking.foldersToPin("B", emptyList(), ParkedUndo()).isEmpty())
    }
}
