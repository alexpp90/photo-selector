package com.photoselectortoolbox.domain.session

import com.photoselectortoolbox.data.model.ImageItem

/**
 * The order the selector shows a folder in (persisted, `selector_sort_order`).
 *
 * The time orders are keyed on [ImageItem.lastModified] — the SAF
 * `COLUMN_LAST_MODIFIED`, the one timestamp discovery has for every file without
 * opening it. Ties are broken by file name, case-insensitively, so a burst shot
 * within one second still reads in sequence.
 *
 * [NEWEST_FIRST] is the exact reverse of [OLDEST_FIRST] over the photos whose
 * time is known, tie-break included: the burst DSC_0001…DSC_0005 taken in one
 * second reads DSC_0005, DSC_0004, … newest first, and DSC_0001, DSC_0002, …
 * oldest first. A photo whose time is unknown (`lastModified <= 0`) has no
 * place on a time axis, so it goes to the **end** in both time orders, among
 * the unknowns by file name ascending.
 *
 * Worst-first triage (`U-34`) is not one of these: it is a session-only view
 * over scores, and leaving it returns to the persisted order.
 */
enum class SelectorSortOrder {
    /** Newest photo first. The default (`[USER 2026-11-02]`). */
    NEWEST_FIRST,

    /** Oldest photo first — what the selector showed before the setting existed. */
    OLDEST_FIRST,

    /** By file name, case-insensitively, ascending; time plays no part. */
    FILE_NAME;

    /** The total order this value stands for. Deterministic: ties end on the URI. */
    val comparator: Comparator<ImageItem>
        get() = when (this) {
            NEWEST_FIRST -> UNKNOWN_TIME_LAST.thenComparator { a, b ->
                if (hasTime(a) && hasTime(b)) BY_TIME.compare(b, a) else BY_NAME.compare(a, b)
            }
            OLDEST_FIRST -> UNKNOWN_TIME_LAST.thenComparator { a, b ->
                if (hasTime(a) && hasTime(b)) BY_TIME.compare(a, b) else BY_NAME.compare(a, b)
            }
            FILE_NAME -> BY_NAME
        }

    /** [images] in this order, as a new list. Stable and total, so idempotent. */
    fun order(images: List<ImageItem>): List<ImageItem> = images.sortedWith(comparator)

    /** Whether [images] is already in this order, so re-ordering would be a no-op. */
    fun isOrdered(images: List<ImageItem>): Boolean {
        val c = comparator
        for (i in 1 until images.size) {
            if (c.compare(images[i - 1], images[i]) > 0) return false
        }
        return true
    }

    companion object {
        /**
         * The order a fresh install and an upgrade both get. Before the setting
         * existed the selector showed filename order, which for camera files is
         * oldest first — the order the user reported as wrong.
         */
        val DEFAULT: SelectorSortOrder = NEWEST_FIRST

        /** The persisted [name], or [DEFAULT] when it is absent or not a known value. */
        fun fromStoredName(name: String?): SelectorSortOrder =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        private fun hasTime(image: ImageItem): Boolean = image.lastModified > 0L

        private val UNKNOWN_TIME_LAST: Comparator<ImageItem> =
            compareBy { if (hasTime(it)) 0 else 1 }

        private val BY_NAME: Comparator<ImageItem> =
            compareBy<ImageItem> { it.fileName.lowercase() }
                .thenBy { it.fileName }
                .thenBy { it.uri }

        private val BY_TIME: Comparator<ImageItem> =
            compareBy<ImageItem> { it.lastModified }.then(BY_NAME)
    }
}
