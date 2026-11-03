package com.photoselectortoolbox.domain.curation

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A delete the photographer can still take back.
 *
 * These are the list mechanics behind it: the frame leaves the list now and
 * comes back on undo; the files go when the folder is left. There is no timer.
 */
class DeferredDeletionTest {

    private fun image(name: String) = ImageItem(
        uri = "content://tree/$name",
        fileName = name,
        fileSize = 1_000L,
        lastModified = 0L,
        mimeType = "image/jpeg",
    )

    private fun stateOf(vararg names: String, currentIndex: Int = 0) =
        SelectorListState(names.map(::image), currentIndex)

    @Test
    @Requirement("REQ-AND-LAYOUT.21", "REQ-AND-LAYOUT.22")
    fun `the frame leaves the list at once and the files are held back`() {
        val (state, pending) = DeferredDeletion.beginForCurrent(
            stateOf("a.jpg", "b.jpg", "c.jpg", currentIndex = 1),
            nowMillis = 0L,
        )!!

        assertEquals(listOf("a.jpg", "c.jpg"), state.images.map { it.fileName })
        assertEquals(listOf("content://tree/b.jpg"), pending.uris)
    }

    @Test
    fun `revert puts the frame back in its original slot`() {
        val original = stateOf("a.jpg", "b.jpg", "c.jpg", currentIndex = 1)
        val (afterDelete, pending) = DeferredDeletion.beginForCurrent(original, 0L)!!

        val reverted = DeferredDeletion.revert(afterDelete, pending)

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), reverted.images.map { it.fileName })
        // Still on c.jpg: the frame on screen must not change under the user
        // just because an earlier one came back.
        assertEquals("c.jpg", reverted.images[reverted.currentIndex].fileName)
    }

    @Test
    fun `reverting the last frame in a folder shows it again`() {
        val (afterDelete, pending) = DeferredDeletion.beginForCurrent(stateOf("only.jpg"), 0L)!!
        assertTrue(afterDelete.images.isEmpty())

        val reverted = DeferredDeletion.revert(afterDelete, pending)

        assertEquals(listOf("only.jpg"), reverted.images.map { it.fileName })
        assertEquals(0, reverted.currentIndex)
    }

    @Test
    fun `deleting nothing yields no pending deletion`() {
        assertNull(DeferredDeletion.beginForCurrent(SelectorListState(emptyList(), 0), 0L))
        assertNull(DeferredDeletion.begin(stateOf("a.jpg"), listOf("content://ghost"), 0L))
    }

    @Test
    fun `several frames deleted together all come back`() {
        val original = stateOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", currentIndex = 3)
        val (afterDelete, pending) = DeferredDeletion.begin(
            original,
            listOf("content://tree/c.jpg", "content://tree/a.jpg"),
            0L,
        )!!
        assertEquals(listOf("b.jpg", "d.jpg"), afterDelete.images.map { it.fileName })

        val reverted = DeferredDeletion.revert(afterDelete, pending)

        assertEquals(
            listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg"),
            reverted.images.map { it.fileName },
        )
        assertEquals("d.jpg", reverted.images[reverted.currentIndex].fileName)
    }

    @Test
    fun `pending deletion uris include primary and all companion files of the bundle`() {
        val bundle = ImageItem(
            uri = "content://tree/sub/IMG_0001.JPG",
            fileName = "IMG_0001.JPG",
            fileSize = 1_000L,
            lastModified = 0L,
            mimeType = "image/jpeg",
            companions = listOf(
                com.photoselectortoolbox.data.model.CompanionFile(
                    uri = "content://tree/sub/IMG_0001.CR3",
                    fileName = "IMG_0001.CR3",
                ),
                com.photoselectortoolbox.data.model.CompanionFile(
                    uri = "content://tree/sub/IMG_0001.CR3.xmp",
                    fileName = "IMG_0001.CR3.xmp",
                ),
            ),
            parentDocumentId = "sub",
        )
        val (_, pending) = DeferredDeletion.beginForCurrent(
            SelectorListState(listOf(bundle), 0),
            nowMillis = 0L,
        )!!

        assertEquals(
            listOf(
                "content://tree/sub/IMG_0001.JPG",
                "content://tree/sub/IMG_0001.CR3",
                "content://tree/sub/IMG_0001.CR3.xmp",
            ),
            pending.uris,
        )
    }
}
