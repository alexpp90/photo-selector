package com.photoselectortoolbox.domain.session

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.curation.CurationAction
import com.photoselectortoolbox.domain.curation.DeferredDeletion
import com.photoselectortoolbox.domain.curation.OptimisticEdits
import com.photoselectortoolbox.domain.curation.SelectorListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The once-per-folder re-order at settle, and re-ordering from the UI. */
@Requirement("REQ-AND-WORKFLOW.06")
class SelectorReorderTest {

    private fun image(name: String, modified: Long) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1,
        lastModified = modified,
        mimeType = "image/jpeg",
    )

    private fun names(items: List<ImageItem>) = items.map { it.fileName }

    /** How discovery publishes a folder today: filename order, oldest first. */
    private val discovered = (1..6).map { image("IMG_%04d.JPG".format(it), it * 1_000L) }

    @Test
    fun `an untouched session lands on the newest photo`() {
        val result = SelectorReorder.atSettle(
            SelectorListState(discovered, 0),
            SelectorSortOrder.NEWEST_FIRST,
            photographerHasActed = false,
        )

        assertTrue(result.changed)
        assertEquals(0, result.state.currentIndex)
        assertEquals("IMG_0006.JPG", result.state.images[0].fileName)
        assertEquals(names(discovered).reversed(), names(result.state.images))
    }

    @Test
    fun `a photographer who has acted keeps the frame on screen`() {
        // On IMG_0002 (index 1); newest first puts it at index 4.
        val result = SelectorReorder.atSettle(
            SelectorListState(discovered, 1),
            SelectorSortOrder.NEWEST_FIRST,
            photographerHasActed = true,
        )

        assertTrue(result.changed)
        assertEquals("IMG_0002.JPG", result.state.currentUri?.substringAfterLast('/'))
        assertEquals(4, result.state.currentIndex)
    }

    @Test
    fun `acting on frame 1 still keeps frame 1's photo, not the new frame 1`() {
        // Rated or filed-and-undone on frame 1: index 0, but they have acted.
        val result = SelectorReorder.atSettle(
            SelectorListState(discovered, 0),
            SelectorSortOrder.NEWEST_FIRST,
            photographerHasActed = true,
        )

        assertEquals(discovered[0].uri, result.state.currentUri)
        assertEquals(5, result.state.currentIndex)
    }

    @Test
    fun `an untouched session off frame 1 keeps its frame`() {
        val result = SelectorReorder.atSettle(
            SelectorListState(discovered, 3),
            SelectorSortOrder.NEWEST_FIRST,
            photographerHasActed = false,
        )

        assertEquals(discovered[3].uri, result.state.currentUri)
    }

    @Test
    fun `an already ordered list is a no-op`() {
        val state = SelectorListState(discovered, 2)
        val result = SelectorReorder.atSettle(state, SelectorSortOrder.OLDEST_FIRST, photographerHasActed = false)

        assertFalse(result.changed)
        assertSame(state, result.state)
    }

    @Test
    fun `an empty list is a no-op`() {
        val state = SelectorListState(emptyList(), 0)
        SelectorSortOrder.entries.forEach { order ->
            listOf(true, false).forEach { acted ->
                val result = SelectorReorder.atSettle(state, order, acted)
                assertFalse(result.changed)
                assertSame(state, result.state)
            }
        }
        assertFalse(SelectorReorder.keepingFrame(state, SelectorSortOrder.NEWEST_FIRST).changed)
    }

    @Test
    fun `photos without a time go to the end and the frame survives`() {
        val list = listOf(image("a.jpg", 0), image("b.jpg", 2_000), image("c.jpg", 1_000))
        val untouched = SelectorReorder.atSettle(
            SelectorListState(list, 0),
            SelectorSortOrder.NEWEST_FIRST,
            photographerHasActed = false,
        )
        assertEquals(listOf("b.jpg", "c.jpg", "a.jpg"), names(untouched.state.images))
        assertEquals(0, untouched.state.currentIndex)

        val acted = SelectorReorder.atSettle(
            SelectorListState(list, 0),
            SelectorSortOrder.NEWEST_FIRST,
            photographerHasActed = true,
        )
        assertEquals("content://photos/a.jpg", acted.state.currentUri)
        assertEquals(2, acted.state.currentIndex)
    }

    @Test
    fun `changing the order from the UI keeps the frame on screen`() {
        val newest = SelectorListState(SelectorSortOrder.NEWEST_FIRST.order(discovered), 2)
        val onScreen = newest.currentUri

        val byName = SelectorReorder.keepingFrame(newest, SelectorSortOrder.FILE_NAME)

        assertTrue(byName.changed)
        assertEquals(onScreen, byName.state.currentUri)
        assertEquals(names(discovered), names(byName.state.images))
    }

    @Test
    fun `a revert after a re-order puts the frame back where the active order says`() {
        // Delete IMG_0002 in oldest-first order (slot index 1), then the list is
        // re-ordered newest first before the undo. Restoring the slot inserts at
        // index 1 of the *new* order; re-applying the order puts it right.
        val oldest = SelectorListState(discovered, 1)
        val (afterDelete, pending) = DeferredDeletion.beginForCurrent(oldest, nowMillis = 0)!!
        val reordered = SelectorReorder.keepingFrame(afterDelete, SelectorSortOrder.NEWEST_FIRST).state
        val onScreen = reordered.currentUri

        val restored = DeferredDeletion.revert(reordered, pending)
        val fixed = SelectorReorder.keepingFrame(restored, SelectorSortOrder.NEWEST_FIRST).state

        assertFalse(
            "the raw restore uses the old order's index",
            SelectorSortOrder.NEWEST_FIRST.isOrdered(restored.images),
        )
        assertEquals(names(discovered).reversed(), names(fixed.images))
        assertEquals(onScreen, fixed.currentUri)
        assertEquals(discovered.size, fixed.images.distinctBy { it.uri }.size)
    }

    @Test
    fun `a rollback after a re-order neither duplicates nor moves the photographer`() {
        val oldest = SelectorListState(discovered, 4)
        val edit = OptimisticEdits.applyToCurrent(oldest, CurationAction.MOVE)
        val reordered = SelectorReorder.keepingFrame(edit.state, SelectorSortOrder.NEWEST_FIRST).state

        val rolledBack = OptimisticEdits.rollback(reordered, edit)
        val fixed = SelectorReorder.keepingFrame(rolledBack, SelectorSortOrder.NEWEST_FIRST).state

        assertEquals(reordered.currentUri, fixed.currentUri)
        assertEquals(names(discovered).reversed(), names(fixed.images))
    }

    @Test
    fun `burst groups follow the re-order by URI, ascending in the new order`() {
        val before = discovered // indices 0..5, oldest first
        val after = SelectorSortOrder.NEWEST_FIRST.order(before) // 0006 … 0001
        // Groups as the grouper returns them: ascending time.
        val groups = listOf(listOf(0, 1, 2), listOf(3), listOf(4, 5))

        val remapped = SelectorReorder.remapGroups(groups, before, after)

        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4, 5)), remapped)
        // Same members by URI.
        assertEquals(
            groups.map { g -> g.map { before[it].uri }.toSet() }.toSet(),
            remapped.map { g -> g.map { after[it].uri }.toSet() }.toSet(),
        )
    }

    @Test
    fun `burst groups drop members that left the list`() {
        val before = discovered
        val after = discovered.filterNot { it.fileName == "IMG_0002.JPG" || it.fileName == "IMG_0004.JPG" }
        val groups = listOf(listOf(0, 1, 2), listOf(3), listOf(4, 5))

        assertEquals(
            listOf(listOf(0, 1), listOf(2, 3)),
            SelectorReorder.remapGroups(groups, before, after),
        )
        assertEquals(emptyList<List<Int>>(), SelectorReorder.remapGroups(emptyList(), before, after))
    }
}
