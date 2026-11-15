package com.phototok.domain

import com.photoselector.core.Requirement
import com.phototok.data.model.ImageItem
import com.phototok.testsupport.ScaleConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Scale tests of the feed ordering at the contract's sizes (`testdata/conditions/conditions.json`).
 * The stream is what discovery delivers: a first batch of 24, then batches of 250, each merged
 * through [PhoneFeedOrdering.appendBatch] the way `FeedDiscoveryHolder.publishBatch` does while the
 * user has not navigated (`reorder = true`).
 */
class PhoneFeedOrderingScaleTest {

    private companion object {
        /** A stream takes milliseconds; a timing of one is below the scheduler's noise. */
        const val REPEAT = 10
        /**
         * Orientation order: measured 0.42-0.46 with the merge and about 1.0 with the whole-feed sort it
         * replaced (11-15x growth for 4x the photos, F-TOK-100).
         */
        const val MAX_SHARE_ORIENTATION = 0.75

        /**
         * Newest-first and shooting order were never slow: TimSort sees one sorted run, so the old
         * whole-feed sort measured the same as the merge (share 0.99-1.26). The limit only stops a
         * regression to something clearly worse than that sort (a per-photo insertion would be 10x).
         */
        const val MAX_SHARE_SORTED = 2.5
        const val ATTEMPTS = 3
    }

    private fun photos(count: Int): List<ImageItem> {
        val rnd = Random(42)
        return (0 until count).map {
            ImageItem(
                uri = "content://docs/tree/root/document/f$it",
                fileName = "IMG_${rnd.nextInt(1_000_000)}.jpg",
                fileSize = 1L,
                // Directory order is not date order; timestamps are milliseconds, so a tie is rare.
                lastModified = rnd.nextInt(count * 50).toLong(),
                mimeType = "image/jpeg",
                imageWidth = if (it % 3 == 0) 3000 else 4000,
                imageHeight = if (it % 3 == 0) 4000 else 3000,
            )
        }
    }

    private fun batches(all: List<ImageItem>): List<List<ImageItem>> {
        val out = mutableListOf(all.take(24))
        var at = 24
        while (at < all.size) {
            out += all.subList(at, minOf(all.size, at + 250))
            at += 250
        }
        return out
    }

    private fun stream(all: List<ImageItem>, mode: FeedOrderMode): PhoneFeedOrdering.Result {
        var feed = PhoneFeedOrdering.Result(emptyList(), -1)
        for (batch in batches(all)) {
            feed = PhoneFeedOrdering.appendBatch(
                current = feed.images,
                fresh = batch,
                mode = mode,
                reorder = true,
            )
        }
        return feed
    }

    /** The stream as the code before the merge path ran it: the whole feed sorted again per batch. */
    private fun streamFullSort(all: List<ImageItem>, mode: FeedOrderMode): PhoneFeedOrdering.Result {
        var feed = PhoneFeedOrdering.Result(emptyList(), -1)
        for (batch in batches(all)) feed = PhoneFeedOrdering.order(feed.images + batch, mode)
        return feed
    }

    /**
     * The merge must cost a fraction of re-sorting the whole feed per batch, on the same machine in
     * the same run: a ratio of two timings of the same stream at the contract's `pr` size. A
     * wall-clock growth ratio between 2,500 and 10,000 photos cannot tell a re-sort from a runner
     * where copying dominates (CI, 2026-11-15), and a ratio of two timings on one machine can: it
     * is about 1 when `appendBatch` sorts the whole feed again (the code before the merge path)
     * and well below [MAX_SHARE_ORIENTATION] when it merges.
     */
    private fun assertMergeBeatsFullSort(mode: FeedOrderMode, maxShare: Double) {
        val all = photos(ScaleConditions.prPhotos)
        var merge = Long.MAX_VALUE
        var full = Long.MAX_VALUE
        repeat(ATTEMPTS) {
            // Alternating, so a load spike hits both sides.
            merge = minOf(merge, ScaleConditions.bestNanos(warmUps = 3) { repeat(REPEAT) { stream(all, mode) } })
            full = minOf(full, ScaleConditions.bestNanos(warmUps = 3) { repeat(REPEAT) { streamFullSort(all, mode) } })
        }
        val share = merge.toDouble() / full
        println("SCALE ordering $mode ${all.size}: merge ${merge / 1_000_000} ms, full sort ${full / 1_000_000} ms, share=$share")
        assertTrue(
            "merging the streamed batches of $mode took ${"%.2f".format(share)} of re-sorting the feed per batch, limit $maxShare",
            share <= maxShare,
        )
    }

    /** Scale: pr */
    @Test
    @Requirement("REQ-TOK-ARCH.12", "REQ-TOK-ARCH.24")
    fun `newest first merge of streamed batches costs a fraction of sorting the feed per batch`() =
        assertMergeBeatsFullSort(FeedOrderMode.NEWEST_FIRST, MAX_SHARE_SORTED)

    /** Scale: pr */
    @Test
    @Requirement("REQ-TOK-ARCH.12", "REQ-TOK-ARCH.24")
    fun `orientation order merge of streamed batches costs a fraction of sorting the feed per batch`() =
        assertMergeBeatsFullSort(FeedOrderMode.ORIENTATION_THEN_NEWEST, MAX_SHARE_ORIENTATION)

    /** Scale: pr */
    @Test
    @Requirement("REQ-TOK-FEATURE.08", "REQ-TOK-ARCH.24")
    fun `shooting order merge of streamed batches costs a fraction of sorting the feed per batch`() =
        assertMergeBeatsFullSort(FeedOrderMode.SHOOTING, MAX_SHARE_SORTED)

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `the streamed merge equals one sort of the whole folder`() {
        val all = photos(2_000)
        for (mode in listOf(FeedOrderMode.NEWEST_FIRST, FeedOrderMode.SHOOTING, FeedOrderMode.ORIENTATION_THEN_NEWEST)) {
            val streamed = stream(all, mode)
            val once = PhoneFeedOrdering.order(all, mode)
            assertEquals("$mode", once.images.map { it.uri }, streamed.images.map { it.uri })
            assertEquals("$mode split", once.portraitSectionStart, streamed.portraitSectionStart)
        }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `a feed that is not in order is sorted whole, as before the merge path`() {
        val all = photos(600)
        val unordered = all.take(300) // as discovered: not in any order
        for (mode in listOf(FeedOrderMode.NEWEST_FIRST, FeedOrderMode.SHOOTING, FeedOrderMode.ORIENTATION_THEN_NEWEST)) {
            val merged = PhoneFeedOrdering.appendBatch(unordered, all.drop(300), mode, reorder = true)
            val once = PhoneFeedOrdering.order(all, mode)
            assertEquals("$mode", once.images.map { it.uri }, merged.images.map { it.uri })
            assertEquals("$mode split", once.portraitSectionStart, merged.portraitSectionStart)
        }
    }
}
