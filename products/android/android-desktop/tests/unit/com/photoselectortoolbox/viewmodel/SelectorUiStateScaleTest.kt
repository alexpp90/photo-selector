package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.grouping.BurstIds
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.testing.ScaleConditions
import org.junit.Test

/**
 * The derivations of [SelectorUiState] that composition reads on every recomposition, at the folder
 * sizes users have (`testdata/conditions/conditions.json`). Jackpix's per-swipe freeze (F-TOK-098)
 * was a getter that called a per-item helper scanning the folder inside a filter over it; here each
 * read must be one pass at most, so 100 reads (100 recompositions) grow linearly with the folder.
 */
class SelectorUiStateScaleTest {

    private val recompositions = 100

    /** A folder of [n] photos, every other one rated 3, half decided, on a burst of four near the end. */
    private fun state(n: Int): SelectorUiState {
        val images = (0 until n).map { i ->
            ImageItem("content://scale/IMG_$i.jpg", "IMG_$i.jpg", 1_000L, i.toLong(), "image/jpeg", rating = if (i % 2 == 0) 3 else 0)
        }
        val tally = SessionTally().record(images.take(n / 2).map { it.uri }, FilingDecision.COPIED)
        return SelectorUiState(
            images = images,
            currentIndex = n - 2,
            ratingFilter = RatingFilter.MinRating(3),
            tally = tally,
            groups = listOf(listOf(n - 4, n - 3, n - 2, n - 1)),
        )
    }

    /**
     * REQ-AND-PERF.10: what the selector and Learn read per recomposition (whether every photo is
     * decided, Learn's filtered strip and position, the bursts on the photos) is linear in the
     * folder: 100 reads at 4N take about 4 times as long as at N.
     *
     * Scale: pr
     */
    @Test
    fun `the derivations composition reads stay linear in the folder`() {
        ScaleConditions.assertGrowth("selector state read per recomposition") { n ->
            val state = state(n);
            {
                var sink = 0
                repeat(recompositions) {
                    if (state.everyPhotoDecided) sink++
                    sink += state.sessionRemainingCount
                    sink += state.filteredPosition
                    sink += state.filteredImages.size
                    sink += BurstIds.tag(state.images, state.groups).size
                }
                check(sink > 0)
            }
        }
    }
}
