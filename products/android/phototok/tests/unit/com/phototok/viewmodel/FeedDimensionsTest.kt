package com.phototok.viewmodel

import com.photoselector.core.Requirement
import com.phototok.data.model.ImageItem
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.PhoneFeedOrdering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Random

/**
 * [withDimensions], the step that sizes photos for landscape-first order. A photo whose size is not
 * read counts as landscape, so sizing moves portraits out of the landscape section. The feed must
 * stay in its order after every chunk: then the next discovery batch is merged into it
 * (`PhoneFeedOrdering.appendBatch`) instead of sorting the whole feed again.
 */
class FeedDimensionsTest {

    private fun photo(i: Int, modified: Long) = ImageItem(
        uri = "content://photos/f$i",
        fileName = "IMG_%05d.jpg".format(i),
        fileSize = 1L,
        lastModified = modified,
        mimeType = "image/jpeg",
    )

    private fun sizeOf(i: Int) = if (i % 3 == 0) 3000 to 4000 else 4000 to 3000

    private fun folder(count: Int): List<ImageItem> {
        val rnd = Random(7)
        return (0 until count).map { photo(it, rnd.nextInt(count * 50).toLong()) }
    }

    private fun landscapeFirst(images: List<ImageItem>, currentIndex: Int = 0): PhoneModeUiState {
        val ordered = PhoneFeedOrdering.order(images, FeedOrderMode.ORIENTATION_THEN_NEWEST)
        return PhoneModeUiState(
            images = ordered.images,
            allImages = images,
            portraitSectionStart = ordered.portraitSectionStart,
            currentIndex = currentIndex,
            feedOrder = FeedOrderMode.ORIENTATION_THEN_NEWEST,
            sortByOrientation = true,
        )
    }

    private fun chunk(state: PhoneModeUiState, from: Int, to: Int): Map<String, Pair<Int, Int>> =
        state.images.subList(from, to).associate { it.uri to sizeOf(it.uri.substringAfterLast('f').toInt()) }

    @Test
    @Requirement("REQ-TOK-FEATURE.08", "REQ-TOK-ARCH.12")
    fun `a feed sized chunk by chunk stays landscape first, each section newest first`() {
        var state = landscapeFirst(folder(600))
        // Nearest first from the top, chunks of 24, 48, 96, … as the dimension pass applies them.
        var size = 24
        var read = 0
        val total = state.images.size
        while (read < total) {
            val unread = state.images.filter { it.imageWidth == 0 }
            val sizes = unread.take(size).associate { it.uri to sizeOf(it.uri.substringAfterLast('f').toInt()) }
            state = withDimensions(state, sizes, feedInOrder = true)
            read += sizes.size
            size *= 2

            val expected = PhoneFeedOrdering.order(state.images, FeedOrderMode.ORIENTATION_THEN_NEWEST)
            assertEquals("after $read photos", expected.images.map { it.uri }, state.images.map { it.uri })
            assertEquals("split after $read photos", expected.portraitSectionStart, state.portraitSectionStart)
        }
        assertEquals("every photo sized in allImages too", 0, state.allImages.count { it.imageWidth == 0 })
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `the photo on screen is followed into its section`() {
        val state = landscapeFirst(folder(100), currentIndex = 5)
        val onScreen = state.images[5].uri

        val sized = withDimensions(state, chunk(state, 0, 24), feedInOrder = true)

        assertEquals(onScreen, sized.images[sized.currentIndex].uri)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `sizes that change no section keep the feed and its split`() {
        val all = folder(50)
        val state = landscapeFirst(all)
        val landscapeOnly = state.images.filter { sizeOf(it.uri.substringAfterLast('f').toInt()).first > 3000 }
            .associate { it.uri to (4000 to 3000) }

        val sized = withDimensions(state, landscapeOnly, feedInOrder = true)

        assertEquals(state.images.map { it.uri }, sized.images.map { it.uri })
        assertEquals(state.portraitSectionStart, sized.portraitSectionStart)
        assertEquals(state.currentIndex, sized.currentIndex)
        assertEquals(4000, sized.images.first { it.uri in landscapeOnly }.imageWidth)
    }

    @Test
    @Requirement("REQ-TOK-ARCH.12")
    fun `an append-only feed after a swipe is regrouped in its own order, as before`() {
        // Not in date order: batches appended after the user swiped.
        val images = folder(60).shuffled(Random(3))
        val state = PhoneModeUiState(
            images = images,
            allImages = images,
            feedOrder = FeedOrderMode.ORIENTATION_THEN_NEWEST,
            sortByOrientation = true,
        )
        val sizes = images.associate { it.uri to sizeOf(it.uri.substringAfterLast('f').toInt()) }

        val sized = withDimensions(state, sizes, feedInOrder = false)

        val (landscape, portrait) = images.partition { sizeOf(it.uri.substringAfterLast('f').toInt()).first > 3000 }
        assertEquals((landscape + portrait).map { it.uri }, sized.images.map { it.uri })
        assertEquals(landscape.size, sized.portraitSectionStart)
    }

    @Test
    @Requirement("REQ-TOK-ARCH.12")
    fun `an append-only feed is regrouped even when the chunk moves no photo`() {
        // After a swipe, a batch of unread photos (counted landscape) was appended behind a portrait.
        val portrait = photo(0, 50).copy(imageWidth = 3000, imageHeight = 4000)
        val landscape = photo(1, 40).copy(imageWidth = 4000, imageHeight = 3000)
        val appended = listOf(photo(2, 30), photo(3, 20))
        val images = listOf(landscape, portrait) + appended
        val state = PhoneModeUiState(
            images = images,
            allImages = images,
            portraitSectionStart = 1,
            feedOrder = FeedOrderMode.ORIENTATION_THEN_NEWEST,
            sortByOrientation = true,
        )

        val sized = withDimensions(state, mapOf(appended[0].uri to (4000 to 3000)), feedInOrder = false)

        assertEquals(listOf(landscape, appended[0], appended[1], portrait).map { it.uri }, sized.images.map { it.uri })
        assertEquals(3, sized.portraitSectionStart)
    }

    @Test
    fun `no sizes, no new state`() {
        val state = landscapeFirst(folder(10))
        assertSame(state, withDimensions(state, emptyMap(), feedInOrder = true))
    }
}
