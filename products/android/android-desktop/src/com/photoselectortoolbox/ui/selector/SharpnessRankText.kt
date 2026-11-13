package com.photoselectortoolbox.ui.selector

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.scoring.BurstRank
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text
import com.photoselectortoolbox.domain.scoring.ScoreMetric

/**
 * The words for a frame's sharpness as its place in its burst (backlog 2026-11-11, [BurstRank]):
 * "3rd of 10" on the readout, and spoken with the area measured and the figure kept, so the
 * screen reader hears what the sighted user sees plus the number the sort order uses.
 */
object SharpnessRankText {

    /**
     * 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st in English; the English suffix picks the
     * resource, and German writes every one of them `3.`.
     */
    fun ordinal(n: Int): UiText {
        val id = if (n % 100 in 11..13) {
            R.string.label_ordinal_th
        } else {
            when (n % 10) {
                1 -> R.string.label_ordinal_st
                2 -> R.string.label_ordinal_nd
                3 -> R.string.label_ordinal_rd
                else -> R.string.label_ordinal_th
            }
        }
        return text(id, n)
    }

    /** "3rd of 10". */
    fun visible(rank: BurstRank): UiText = text(R.string.label_rank_of, ordinal(rank.position), rank.of)

    /** "Sharpness at the centre of the frame 3rd of 10 in its burst, 64.0, higher is better". */
    fun spoken(rank: BurstRank, value: Double, isBestOfVisible: Boolean = false): UiText {
        val metric = ScoreMetric.SHARPNESS
        return text(
            if (isBestOfVisible) R.string.cd_rank_spoken_best else R.string.cd_rank_spoken,
            metric.spokenName,
            visible(rank),
            metric.format(value),
            metric.direction.hint,
        )
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
