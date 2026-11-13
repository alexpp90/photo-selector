package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.TechniqueFactEntity

/**
 * The facts Explore shows for the photo on screen (shoot debrief SD-1b): what the scan stored,
 * and for every other kind what the detector finds on demand. The scan stores the smaller set
 * ([ScanFacts.STORED_KINDS], decision #19), so a stored set alone would hide the tonal key,
 * separation, lines, simplicity and symmetry that Explore always showed.
 *
 * Per kind the stored rows win. A stored set with its [ScanFacts.MARKER] also decides the kinds
 * it found nothing for: "the scan looked and found no horizon" is not filled from a second
 * detection. Without a stored set every row is the on-demand one.
 */
object ExploreFacts {

    fun merge(stored: List<TechniqueFactEntity>?, onDemand: List<TechniqueFactEntity>): List<TechniqueFactEntity> {
        if (stored.isNullOrEmpty()) return onDemand
        val decided = stored.mapTo(mutableSetOf()) { it.kind }
        if (stored.any { it.kind == ScanFacts.MARKER }) decided += ScanFacts.STORED_KINDS
        return stored + onDemand.filter { it.kind !in decided }
    }
}
