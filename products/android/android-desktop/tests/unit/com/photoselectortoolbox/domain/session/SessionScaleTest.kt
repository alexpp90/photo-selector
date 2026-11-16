package com.photoselectortoolbox.domain.session

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.curation.SelectorListState
import com.photoselectortoolbox.testing.ScaleConditions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The selector's pure list functions over the event streams of a folder of the sizes users have
 * (testdata/conditions/conditions.json): discovery batches as `LocalImageSource` delivers them,
 * and decisions at the end of the list.
 */
class SessionScaleTest {

    private fun photos(n: Int) = (1..n).map { i ->
        ImageItem("content://scale/doc/IMG_$i.jpg", "IMG_$i.jpg", 1_000L, 1_000_000L + i, "image/jpeg")
    }

    /** The cumulative emissions of discovery: 24, then +250 each, then the complete list. */
    private fun discoveryEmissions(all: List<ImageItem>): List<List<ImageItem>> {
        val sizes = generateSequence(24) { it + 250 }.takeWhile { it < all.size }.toList() + all.size
        return sizes.map { all.subList(0, it) }
    }

    /**
     * REQ-AND-PERF.03, REQ-AND-PERF.10: merging every discovery batch of a folder, the way the
     * selector does (the new tail of each cumulative emission against everything published, then
     * appended), grows linearly.
     *
     * Scale: pr
     */
    @Test
    fun `merging the discovery batches of a folder grows linearly`() {
        ScaleConditions.assertGrowth("discovery batch merge") { n ->
            val emissions = discoveryEmissions(photos(n));
            {
                val published = HashSet<String>()
                var images = emptyList<ImageItem>()
                var index = 0
                var seen = 0
                for (batch in emissions) {
                    val fresh = ProgressiveMerge.newItems(batch, published, from = seen)
                    seen = batch.size
                    fresh.mapTo(published) { it.uri }
                    val merged = ProgressiveMerge.append(images, index, fresh, emptySet(), isFirstBatch = images.isEmpty())
                    images = merged.images
                    index = merged.currentIndex
                }
                check(images.size == n)
            }
        }
    }

    /**
     * REQ-AND-PERF.10, REQ-AND-WORKFLOW.06: 100 decisions at the end of the list (each removes the
     * frame on screen and carries the burst groups over by URI, `SelectorReorder.remapGroups`)
     * take time linear in the folder, not more.
     *
     * Scale: pr
     */
    @Test
    fun `decisions at the end of a large folder carry the bursts over in linear time`() {
        ScaleConditions.assertGrowth("100 decisions with burst remap") { n ->
            val start = photos(n)
            // Bursts of 20 frames (the contract's short burst) over the whole folder.
            val groups = (0 until n).chunked(20);
            {
                var images = start
                var bursts = groups
                repeat(100) {
                    val next = images.subList(0, images.size - 1).toList()
                    bursts = SelectorReorder.remapGroups(bursts, images, next)
                    images = next
                }
                check(images.size == n - 100)
            }
        }
    }

    /**
     * REQ-AND-WORKFLOW.06: the one re-order at enumeration settle (newest first over a folder
     * appended in file-name order) grows no faster than a sort.
     *
     * Scale: pr
     */
    @Test
    fun `the settle re-order of a large folder grows like a sort`() {
        ScaleConditions.assertGrowth("settle re-order") { n ->
            val state = SelectorListState(photos(n), currentIndex = 0);
            {
                val settled = SelectorReorder.atSettle(state, SelectorSortOrder.NEWEST_FIRST, photographerHasActed = false)
                check(settled.changed && settled.state.images.first().fileName == "IMG_$n.jpg")
            }
        }
    }

    // REQ-AND-PERF.03
    @Test
    fun `the new tail of a cumulative batch skips what was published and what repeats within it`() {
        val all = photos(10)
        val batch = all + all[7]

        val fresh = ProgressiveMerge.newItems(batch, published = setOf(all[6].uri), from = 5)

        assertEquals(listOf(all[5], all[7], all[8], all[9]), fresh)
        assertEquals(emptyList<ImageItem>(), ProgressiveMerge.newItems(batch, emptySet(), from = batch.size))
    }

    // REQ-AND-PERF.03
    @Test
    fun `merging the cumulative batches publishes every photo once, in order`() {
        val all = photos(600)
        val published = HashSet<String>()
        var images = emptyList<ImageItem>()
        for (batch in discoveryEmissions(all) + listOf(all)) {
            val fresh = ProgressiveMerge.newItems(batch, published)
            fresh.mapTo(published) { it.uri }
            images = ProgressiveMerge.append(images, 0, fresh, emptySet(), isFirstBatch = images.isEmpty()).images
        }
        assertEquals(all, images)
    }
}
