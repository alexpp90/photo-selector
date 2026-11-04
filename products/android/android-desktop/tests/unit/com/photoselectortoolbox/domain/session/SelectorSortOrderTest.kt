package com.photoselectortoolbox.domain.session

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three persisted orders of the selector (`[USER 2026-11-02]`). */
@Requirement("REQ-AND-WORKFLOW.06")
class SelectorSortOrderTest {

    private fun image(name: String, modified: Long) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1,
        lastModified = modified,
        mimeType = "image/jpeg",
    )

    private fun names(items: List<ImageItem>) = items.map { it.fileName }

    // A morning, a burst within one second, an afternoon, and two files the
    // provider reported no time for.
    private val shoot = listOf(
        image("DSC_0003.JPG", 2_000),
        image("dsc_0010.jpg", 9_000),
        image("unknown_b.jpg", 0),
        image("DSC_0001.JPG", 1_000),
        image("DSC_0005.JPG", 2_000),
        image("DSC_0004.JPG", 2_000),
        image("unknown_a.jpg", 0),
    )

    @Test
    fun `newest first is the default`() {
        assertEquals(SelectorSortOrder.NEWEST_FIRST, SelectorSortOrder.DEFAULT)
    }

    @Test
    fun `oldest first runs forward in time, a burst by name, unknown times last`() {
        assertEquals(
            listOf(
                "DSC_0001.JPG", "DSC_0003.JPG", "DSC_0004.JPG", "DSC_0005.JPG",
                "dsc_0010.jpg", "unknown_a.jpg", "unknown_b.jpg",
            ),
            names(SelectorSortOrder.OLDEST_FIRST.order(shoot)),
        )
    }

    @Test
    fun `newest first is the exact reverse over known times, unknown times still last`() {
        val newest = names(SelectorSortOrder.NEWEST_FIRST.order(shoot))
        assertEquals(
            listOf(
                "dsc_0010.jpg", "DSC_0005.JPG", "DSC_0004.JPG", "DSC_0003.JPG",
                "DSC_0001.JPG", "unknown_a.jpg", "unknown_b.jpg",
            ),
            newest,
        )
        val known = names(SelectorSortOrder.OLDEST_FIRST.order(shoot)).take(5)
        assertEquals(known.reversed(), newest.take(5))
    }

    @Test
    fun `file name order ignores time and case`() {
        assertEquals(
            listOf(
                "DSC_0001.JPG", "DSC_0003.JPG", "DSC_0004.JPG", "DSC_0005.JPG",
                "dsc_0010.jpg", "unknown_a.jpg", "unknown_b.jpg",
            ),
            names(SelectorSortOrder.FILE_NAME.order(shoot)),
        )
        val mixed = listOf(image("b.jpg", 1), image("A.jpg", 3), image("a2.jpg", 2))
        assertEquals(listOf("A.jpg", "a2.jpg", "b.jpg"), names(SelectorSortOrder.FILE_NAME.order(mixed)))
    }

    @Test
    fun `ordering is idempotent and isOrdered agrees with it`() {
        SelectorSortOrder.entries.forEach { order ->
            val once = order.order(shoot)
            assertEquals(order.name, once, order.order(once))
            assertTrue(order.name, order.isOrdered(once))
        }
        assertFalse(SelectorSortOrder.NEWEST_FIRST.isOrdered(SelectorSortOrder.OLDEST_FIRST.order(shoot)))
        assertTrue(SelectorSortOrder.NEWEST_FIRST.isOrdered(emptyList()))
        assertTrue(SelectorSortOrder.NEWEST_FIRST.isOrdered(listOf(shoot.first())))
    }

    @Test
    fun `negative times count as unknown`() {
        val list = listOf(image("neg.jpg", -5), image("pos.jpg", 5))
        assertEquals(listOf("pos.jpg", "neg.jpg"), names(SelectorSortOrder.OLDEST_FIRST.order(list)))
        assertEquals(listOf("pos.jpg", "neg.jpg"), names(SelectorSortOrder.NEWEST_FIRST.order(list)))
    }

    @Test
    fun `the stored name round-trips and anything else is the default`() {
        SelectorSortOrder.entries.forEach {
            assertEquals(it, SelectorSortOrder.fromStoredName(it.name))
        }
        assertEquals(SelectorSortOrder.DEFAULT, SelectorSortOrder.fromStoredName(null))
        assertEquals(SelectorSortOrder.DEFAULT, SelectorSortOrder.fromStoredName(""))
        assertEquals(SelectorSortOrder.DEFAULT, SelectorSortOrder.fromStoredName("WORST_FIRST"))
        assertEquals(SelectorSortOrder.DEFAULT, SelectorSortOrder.fromStoredName("oldest_first"))
    }
}
