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

    private fun assertLinear(mode: FeedOrderMode) {
        val n = ScaleConditions.baseN
        val big = ScaleConditions.prPhotos
        val small = photos(n)
        val large = photos(big)
        val tSmall = ScaleConditions.bestNanos(warmUps = 5) { repeat(REPEAT) { stream(small, mode) } }
        val tLarge = ScaleConditions.bestNanos(warmUps = 5) { repeat(REPEAT) { stream(large, mode) } }
        val ratio = tLarge.toDouble() / tSmall
        println("SCALE ordering $mode N=$n: ${tSmall / 1_000_000} ms, 4N=$big: ${tLarge / 1_000_000} ms, ratio=$ratio")
        assertTrue(
            "ordering the $big-photo stream took ${"%.1f".format(ratio)}x the $n-photo stream, limit ${ScaleConditions.maxRatio}",
            ratio <= ScaleConditions.maxRatio,
        )
    }

    /** Scale: pr */
    @Test
    @Requirement("REQ-TOK-ARCH.12", "REQ-TOK-ARCH.24")
    fun `newest first merge of streamed batches grows linearly with the folder`() =
        assertLinear(FeedOrderMode.NEWEST_FIRST)

    /** Scale: pr */
    @Test
    @Requirement("REQ-TOK-ARCH.12", "REQ-TOK-ARCH.24")
    fun `orientation order merge of streamed batches grows linearly with the folder`() =
        assertLinear(FeedOrderMode.ORIENTATION_THEN_NEWEST)

    /** Scale: pr */
    @Test
    @Requirement("REQ-TOK-FEATURE.08", "REQ-TOK-ARCH.24")
    fun `shooting order merge of streamed batches grows linearly with the folder`() =
        assertLinear(FeedOrderMode.SHOOTING)

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
