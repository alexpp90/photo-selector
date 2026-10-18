package com.phototok.domain

import com.phototok.data.model.ImageItem

/**
 * Finds "related" files: same base name (stem) but different extension, e.g.
 * IMG_001.JPG and IMG_001.ARW. Pure logic so it is independent of the user's
 * file-type filter and easily unit-tested.
 */
object RelatedFiles {

    /**
     * Sibling images of [target] within [all] (excludes [target] itself). Case-insensitive.
     * Matches only files in the same directory (when parent URIs are known) with matching stems
     * and differing, complementary extensions.
     */
    fun siblings(all: List<ImageItem>, target: ImageItem): List<ImageItem> {
        val stem = stemOf(target.fileName)
        val targetExt = PhotoExtensions.extensionOf(target.fileName)
        return all.filter { candidate ->
            candidate.uri != target.uri &&
                stemOf(candidate.fileName) == stem &&
                PhotoExtensions.extensionOf(candidate.fileName) != targetExt &&
                (target.parentUri == null || candidate.parentUri == null || candidate.parentUri == target.parentUri)
        }
    }

    /**
     * Everything that travels with [target] on a linked move, copy or delete, per the shared
     * contract `testdata/formats/companions.json`: the other photos of the shot (RAW/JPEG twin,
     * `-Edit` / `_Edit` / `-Enhanced-NR` derivatives) from [all], then the `.xmp` sidecars that
     * discovery attached to the shot. Only files in the **same parent folder** qualify: a
     * same-named file in a sibling folder is another photo. Moving a derivative therefore moves
     * its whole shot, as on Android Desktop.
     */
    fun bundleOf(all: List<ImageItem>, target: ImageItem): List<ImageItem> {
        val stem = CompanionFiles.shotStemOf(target.fileName)
        val photos = all.filter { candidate ->
            candidate.uri != target.uri &&
                candidate.parentUri == target.parentUri &&
                CompanionFiles.shotStemOf(candidate.fileName) == stem
        }
        val sidecars = (target.sidecars + photos.flatMap { it.sidecars })
            .distinctBy { it.uri }
            .filter { it.parentUri == target.parentUri }
        return photos + sidecars
    }

    /** Collapse `RAW + JPEG` pairs into a single feed entry per shot `(parentUri, stem)`:
     * - When both a processed image (JPEG / HEIC / non-RAW) and a RAW exist for the same shot,
     *   show the processed image for instant decode.
     * - When only a RAW or only a processed file exists, show it as-is.
     */
    fun collapseOnePerShot(images: List<ImageItem>): List<ImageItem> {
        if (images.isEmpty()) return emptyList()
        val byShot = LinkedHashMap<Pair<String?, String>, MutableList<ImageItem>>()
        for (image in images) {
            byShot.getOrPut(shotKey(image)) { mutableListOf() }.add(image)
        }
        return byShot.values.map { group ->
            if (group.size == 1) {
                group[0]
            } else {
                group.firstOrNull { PhotoExtensions.isJpeg(it.fileName) }
                    ?: group.firstOrNull { !PhotoExtensions.isRaw(it.fileName) }
                    ?: group[0]
            }
        }
    }

    /**
     * True when [target] is a processed image that has at least one RAW sibling in [all]
     * (used to render the subtle `"RAW+JPG"` indicator pill when `ONE_PER_SHOT` is active).
     */
    fun hasRawCompanion(all: List<ImageItem>, target: ImageItem): Boolean {
        if (PhotoExtensions.isRaw(target.fileName)) return false
        return siblings(all, target).any { PhotoExtensions.isRaw(it.fileName) }
    }

    /**
     * For sharing (`U-13`): if [target] is part of a RAW+JPEG pair, return the processed
     * companion (JPEG/HEIC); if [target] is already processed or a lone RAW, return [target].
     */
    fun preferredShareItem(all: List<ImageItem>, target: ImageItem): ImageItem {
        if (!PhotoExtensions.isRaw(target.fileName)) return target
        val sibs = siblings(all, target)
        return sibs.firstOrNull { PhotoExtensions.isJpeg(it.fileName) }
            ?: sibs.firstOrNull { !PhotoExtensions.isRaw(it.fileName) }
            ?: target
    }

    /**
     * Checks if [images] contains both RAW and JPEG formats of the same photo
     * (matching stem in the same parent folder, with at least one RAW and at least one
     * JPEG extension). Camera names such as DSC_0001 repeat across folders, so a RAW in
     * one folder and a same-named JPEG in another are not a pair.
     */
    fun hasRawJpegPairs(images: List<ImageItem>): Boolean {
        val byStem = images.groupBy { shotKey(it) }
        return byStem.values.any { group ->
            val hasRaw = group.any { PhotoExtensions.isRaw(it.fileName) }
            val hasJpeg = group.any { PhotoExtensions.isJpeg(it.fileName) }
            hasRaw && hasJpeg
        }
    }

    /**
     * Checks if the majority of unique photos (stems) in [images] do NOT match [filter].
     *
     * Photos with matching RAW and JPEG pairs of the same shot are considered matching
     * if either format satisfies the filter (e.g. shooting RAW+JPEG does not trigger a warning
     * when filtered to RAW only or JPEG only).
     */
    fun hasFilterMismatch(images: List<ImageItem>, filter: FileTypeFilter): Boolean {
        if (filter == FileTypeFilter.ALL || filter == FileTypeFilter.ONE_PER_SHOT || images.isEmpty()) return false
        val byStem = images.groupBy { shotKey(it) }
        val matchingCount = byStem.values.count { group ->
            when (filter) {
                FileTypeFilter.ALL, FileTypeFilter.ONE_PER_SHOT -> true
                FileTypeFilter.RAW -> group.any { PhotoExtensions.isRaw(it.fileName) }
                FileTypeFilter.JPG -> group.any { PhotoExtensions.isJpeg(it.fileName) }
            }
        }
        val unmatchedCount = byStem.size - matchingCount
        return unmatchedCount > matchingCount
    }

    /** One photographic shot: same parent folder and same case-insensitive stem. */
    private fun shotKey(image: ImageItem): Pair<String?, String> =
        image.parentUri to stemOf(image.fileName)

    private fun stemOf(fileName: String): String =
        fileName.substringBeforeLast('.').lowercase()
}

