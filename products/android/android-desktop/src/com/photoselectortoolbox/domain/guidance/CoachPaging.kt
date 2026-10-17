package com.photoselectortoolbox.domain.guidance

/**
 * How the coach-mark cards are split into pages (AD-3).
 *
 * The guide used to stack every card in the flank beside the current frame and
 * let the stack overflow: at 1480 x 924, 1280 x 800 and 1024 x 768 the cards were
 * truncated and the title and "Got it" were drawn over the real controls. A
 * scrolling column is not an answer (a callout scrolled out of sight teaches
 * nothing), so the cards are packed greedily, in reading order, into pages
 * that each fit the space they are given.
 *
 * Pure and unit-free: heights and gaps are whatever integer unit the caller
 * measured in (pixels), so the composable and the JVM test use the same code.
 */
object CoachPaging {

    /**
     * Item indices per page, in order. Every index appears exactly once.
     *
     * An item taller than [available] gets a page to itself rather than being
     * dropped; an empty list is one empty page, so there is always a page to show.
     */
    fun pack(heights: List<Int>, available: Int, gap: Int): List<List<Int>> {
        if (heights.isEmpty()) return listOf(emptyList())
        val pages = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var used = 0
        heights.forEachIndexed { index, height ->
            if (current.isEmpty()) {
                current.add(index)
                used = height
            } else if (used + gap + height <= available) {
                current.add(index)
                used += gap + height
            } else {
                pages.add(current)
                current = mutableListOf(index)
                used = height
            }
        }
        pages.add(current)
        return pages
    }
}
