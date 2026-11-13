package com.photoselectortoolbox.domain.scoring

import com.photoselectortoolbox.data.model.ImageItem

/**
 * A frame's sharpness as its place within its burst ("3rd of 10"), for the readout of a grouped
 * frame (backlog 2026-11-11, REQ-AND-ALGO.01). The score is the highest Laplacian variance of the
 * centre of the frame: it compares frames of one scene, not a folder, so within a burst its rank
 * says something a bare "217.3" does not. The rank never hides, orders or pre-selects a photo;
 * the absolute figure stays for the sort order and the Legend.
 */
data class BurstRank(
    /** 1 for the sharpest frame of the burst. */
    val position: Int,
    /** Frames of the burst with a measured sharpness. */
    val of: Int,
) {
    companion object {
        /**
         * [image]'s rank among the frames of its burst in [images], or null when it is in no
         * burst, has no measured sharpness, or fewer than two frames of its burst have one.
         * Equal scores share the better position (1, 1, 3), so a tie never reads as a loss.
         */
        fun sharpnessOf(image: ImageItem, images: List<ImageItem>): BurstRank? {
            val group = image.groupId ?: return null
            val own = image.scanResult?.sharpnessScore ?: return null
            val scores = images.asSequence()
                .filter { it.groupId == group }
                .distinctBy { it.uri }
                .mapNotNull { it.scanResult?.sharpnessScore }
                .toList()
            if (scores.size < 2) return null
            return BurstRank(position = 1 + scores.count { it > own }, of = scores.size)
        }
    }
}
