package com.photoselectortoolbox.domain.grouping

import com.photoselectortoolbox.data.model.ImageItem

/**
 * The selector's bursts on the photos themselves (backlog sweep 2026-11-12, item 5): the grouper
 * gives index lists (`SelectorUiState.groups`), but `SceneComparator` and the shoot debrief read
 * `ImageItem.groupId`, which no production code set, so their burst rules never fired. A photo in a
 * burst of two or more frames gets the burst's number, every other photo none. Derived where it is
 * read, never stored on the list: the list is re-sorted and re-merged, the index lists follow it
 * (`SelectorReorder.remapGroups`), and a stored id would go stale.
 */
object BurstIds {

    fun tag(images: List<ImageItem>, groups: List<List<Int>>): List<ImageItem> {
        if (images.isEmpty()) return images
        val burstOf = HashMap<Int, Int>()
        groups.forEachIndexed { burst, members ->
            if (members.size >= 2) members.forEach { index -> burstOf[index] = burst }
        }
        return images.mapIndexed { index, image ->
            val id = burstOf[index]
            if (image.groupId == id) image else image.copy(groupId = id)
        }
    }
}
