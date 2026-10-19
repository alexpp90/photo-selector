package com.photoselectortoolbox.domain.guidance

import org.junit.Assert.assertEquals
import org.junit.Test

class CoachPagingTest {

    @Test
    fun `everything that fits is one page`() {
        assertEquals(listOf(listOf(0, 1, 2)), CoachPaging.pack(listOf(100, 100, 100), 320, 10))
    }

    @Test
    fun `the gap counts, so an exact fit without it spills`() {
        // 100 + 10 + 100 = 210 fits 210; adding a third needs 320.
        assertEquals(listOf(listOf(0, 1), listOf(2)), CoachPaging.pack(listOf(100, 100, 100), 210, 10))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), CoachPaging.pack(listOf(100, 100, 100), 209, 10))
    }

    @Test
    fun `order is preserved and every item appears exactly once`() {
        val heights = listOf(50, 120, 80, 200, 40, 90, 60)
        val pages = CoachPaging.pack(heights, 250, 10)
        assertEquals(heights.indices.toList(), pages.flatten())
        pages.forEach { page ->
            val used = page.sumOf { heights[it] } + 10 * (page.size - 1)
            assert(page.size == 1 || used <= 250) { "page $page needs $used" }
        }
    }

    @Test
    fun `an item taller than the page gets a page to itself`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), CoachPaging.pack(listOf(40, 500, 40), 300, 10))
    }

    @Test
    fun `nothing to show is still one page`() {
        assertEquals(listOf(emptyList<Int>()), CoachPaging.pack(emptyList(), 300, 10))
    }
}
