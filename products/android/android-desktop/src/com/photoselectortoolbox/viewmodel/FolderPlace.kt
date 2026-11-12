package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.data.model.ImageItem

/**
 * Where a reopened folder resumes (backlog 2026-11-11, REQ-AND-WORKFLOW.06). The cull is
 * 500–3,000 photos over an hour, and Android may end the process whenever the tablet is put
 * down; the place is the photo on screen, by URI, stored per folder.
 */
internal object FolderPlace {

    /** Quiet period before the photo on screen is written: one write per pause, not per key. */
    const val SAVE_DEBOUNCE_MILLIS = 800L

    /**
     * The index to resume at once enumeration has settled, or null to stay on frame 1: the
     * stored photo by URI; a photo that is gone keeps frame 1 and says nothing. Without a stored
     * photo, the index v0.4.0 stored (`storedIndex`), when the folder still has that many.
     */
    fun resumeIndex(images: List<ImageItem>, storedPhoto: String?, storedIndex: Int): Int? {
        if (storedPhoto != null) return images.indexOfFirst { it.uri == storedPhoto }.takeIf { it > 0 }
        return storedIndex.takeIf { it in 1 until images.size }
    }
}
