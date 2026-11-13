package com.photoselectortoolbox.ui.selector

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.scoring.BurstRank
import com.photoselectortoolbox.domain.scoring.ScoreMetric

/**
 * The words for a frame's sharpness as its place in its burst (backlog 2026-11-11, [BurstRank]):
 * "3rd of 10" on the readout, and spoken with the area measured and the figure kept, so the
 * screen reader hears what the sighted user sees plus the number the sort order uses.
 */
object SharpnessRankText {

    /** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st. */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) {
            "th"
        } else {
            when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$n$suffix"
    }

    /** "3rd of 10". */
    fun visible(rank: BurstRank): String = "${ordinal(rank.position)} of ${rank.of}"

    /** "Sharpness at the centre of the frame 3rd of 10 in its burst, 64.0, higher is better". */
    fun spoken(rank: BurstRank, value: Double, isBestOfVisible: Boolean = false): String {
        val metric = ScoreMetric.SHARPNESS
        val base = "${metric.spokenName} ${visible(rank)} in its burst, ${metric.format(value)}, ${metric.direction.hint}"
        return if (isBestOfVisible) "$base, best of the three visible frames" else base
    }

    /** The bar for a ranked frame: full for the sharpest of its burst, the floor for the softest. */
    fun goodness(rank: BurstRank): Double =
        if (rank.of <= 1) 1.0 else 1.0 - (rank.position - 1).toDouble() / (rank.of - 1)
}

/**
 * The frame at [currentIndex]'s sharpness rank within its burst, from the selector's [groups]
 * (index lists, `SelectorUiState.groups`) — the bursts the cull marks. `ImageItem.groupId` is not
 * set by the grouper, so the members are tagged with one key here and ranked by [BurstRank].
 */
internal fun burstSharpnessRank(groups: List<List<Int>>, images: List<ImageItem>, currentIndex: Int): BurstRank? {
    val series = groups.firstOrNull { currentIndex in it } ?: return null
    val members = series.mapNotNull { images.getOrNull(it)?.copy(groupId = BURST_KEY) }
    val current = images.getOrNull(currentIndex)?.copy(groupId = BURST_KEY) ?: return null
    return BurstRank.sharpnessOf(current, members)
}

private const val BURST_KEY = 0
