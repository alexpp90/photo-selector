package com.phototok.ui.phonemode

import com.photoselector.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchWindowTest {

    @Test
    @Requirement("REQ-TOK-ARCH.23")
    fun `scrolling forward warms the pages ahead first, then one behind`() {
        val pages = PrefetchWindow.pages(center = 10, pageCount = 100, backward = false, ahead = 4, behind = 1)
        assertEquals(listOf(11, 12, 13, 14, 9), pages)
    }

    @Test
    @Requirement("REQ-TOK-ARCH.23")
    fun `scrolling back warms the earlier pages first, then one after`() {
        val pages = PrefetchWindow.pages(center = 10, pageCount = 100, backward = true, ahead = 4, behind = 1)
        assertEquals(listOf(9, 8, 7, 6, 11), pages)
    }

    @Test
    @Requirement("REQ-TOK-ARCH.23")
    fun `pages outside the feed are dropped at both ends`() {
        assertEquals(
            listOf(1, 2, 3, 4),
            PrefetchWindow.pages(center = 0, pageCount = 100, backward = false, ahead = 4, behind = 1),
        )
        assertEquals(
            listOf(1, 0, 3),
            PrefetchWindow.pages(center = 2, pageCount = 4, backward = true, ahead = 4, behind = 1),
        )
        assertEquals(
            listOf(98),
            PrefetchWindow.pages(center = 99, pageCount = 100, backward = false, ahead = 4, behind = 1),
        )
    }

    @Test
    @Requirement("REQ-TOK-ARCH.23")
    fun `the visible page is never prefetched and a one-photo feed warms nothing`() {
        val pages = PrefetchWindow.pages(center = 5, pageCount = 50, backward = false, ahead = 4, behind = 1)
        assertTrue(5 !in pages)
        assertEquals(emptyList<Int>(), PrefetchWindow.pages(0, 1, backward = true, ahead = 4, behind = 1))
    }

    @Test
    @Requirement("REQ-TOK-ARCH.23")
    fun `cloud folders warm two ahead and none behind`() {
        val pages = PrefetchWindow.pages(
            center = 10,
            pageCount = 100,
            backward = true,
            ahead = PrefetchWindow.CLOUD_AHEAD,
            behind = PrefetchWindow.CLOUD_BEHIND,
        )
        assertEquals(listOf(9, 8), pages)
    }

    @Test
    @Requirement("REQ-TOK-ARCH.23")
    fun `the local window leans ahead`() {
        assertTrue(PrefetchWindow.AHEAD > PrefetchWindow.BEHIND)
        assertTrue(PrefetchWindow.CLOUD_AHEAD > PrefetchWindow.CLOUD_BEHIND)
    }
}
