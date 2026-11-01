package com.phototok.ui.phonemode

/**
 * Which feed pages the viewer warms into Coil's caches around the current page, weighted toward
 * the direction the user is scrolling: most pages ahead of them, a few behind. Pure, so the
 * window can be tested without Compose.
 */
internal object PrefetchWindow {

    /** Pages warmed in the scroll direction / against it, local folders. */
    const val AHEAD = 4
    const val BEHIND = 1

    /** Cloud folders download every page, so they warm fewer. */
    const val CLOUD_AHEAD = 2
    const val CLOUD_BEHIND = 0

    /**
     * Page indices to warm, most urgent first: [center] + 1 … + [ahead] in the scroll direction,
     * then [center] − 1 … − [behind] against it. [backward] is true while the user scrolls toward
     * index 0. Indices outside `0 until pageCount` are dropped; [center] itself is the visible
     * page and is never in the list.
     */
    fun pages(center: Int, pageCount: Int, backward: Boolean, ahead: Int, behind: Int): List<Int> {
        val step = if (backward) -1 else 1
        val forward = (1..ahead).map { center + step * it }
        val reverse = (1..behind).map { center - step * it }
        return (forward + reverse).filter { it in 0 until pageCount }
    }
}
