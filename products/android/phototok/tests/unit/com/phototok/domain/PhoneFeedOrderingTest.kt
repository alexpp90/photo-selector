package com.phototok.domain

import com.photoselector.core.Requirement
import com.phototok.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFeedOrderingTest {

    private fun img(
        name: String,
        date: Long,
        width: Int = 0,
        height: Int = 0,
    ) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1L,
        lastModified = date,
        mimeType = "image/jpeg",
        imageWidth = width,
        imageHeight = height,
    )

    @Test
    fun `default order is by date newest first`() {
        val input = listOf(
            img("old.jpg", date = 100),
            img("new.jpg", date = 300),
            img("mid.jpg", date = 200),
        )

        val result = PhoneFeedOrdering.order(input, randomize = false, sortByOrientation = false)

        assertEquals(listOf("new.jpg", "mid.jpg", "old.jpg"), result.images.map { it.fileName })
        assertEquals(-1, result.portraitSectionStart)
    }

    @Test
    fun `orientation on groups landscape first then portrait each newest first`() {
        val input = listOf(
            img("p_old.jpg", date = 100, width = 3000, height = 4000), // portrait
            img("l_new.jpg", date = 400, width = 4000, height = 3000), // landscape
            img("p_new.jpg", date = 300, width = 3000, height = 4000), // portrait
            img("l_old.jpg", date = 200, width = 4000, height = 3000), // landscape
        )

        val result = PhoneFeedOrdering.order(input, randomize = false, sortByOrientation = true)

        // Landscape group newest first, then portrait group newest first
        assertEquals(
            listOf("l_new.jpg", "l_old.jpg", "p_new.jpg", "p_old.jpg"),
            result.images.map { it.fileName },
        )
        // Portrait section starts after the 2 landscape images
        assertEquals(2, result.portraitSectionStart)
    }

    @Test
    fun `equal dates tie-break by filename then uri deterministically`() {
        val input = listOf(img("b.jpg", 100), img("A.jpg", 100), img("c.jpg", 100))
        val a = PhoneFeedOrdering.order(input, false, false).images.map { it.fileName }
        val b = PhoneFeedOrdering.order(input.reversed(), false, false).images.map { it.fileName }
        assertEquals(listOf("A.jpg", "b.jpg", "c.jpg"), a)
        assertEquals(a, b)
    }

    @Test
    fun `orientation on with no portraits yields no split`() {
        val input = listOf(
            img("l1.jpg", date = 100, width = 4000, height = 3000),
            img("l2.jpg", date = 200, width = 4000, height = 3000),
        )

        val result = PhoneFeedOrdering.order(input, randomize = false, sortByOrientation = true)

        assertEquals(listOf("l2.jpg", "l1.jpg"), result.images.map { it.fileName })
        assertEquals(-1, result.portraitSectionStart)
    }

    @Test
    fun `randomize uses injected shuffler and ignores date and orientation`() {
        val input = listOf(
            img("a.jpg", date = 100),
            img("b.jpg", date = 200),
            img("c.jpg", date = 300),
        )

        // Deterministic "shuffle" = reverse, so we can assert exactly.
        val result = PhoneFeedOrdering.order(
            input,
            randomize = true,
            sortByOrientation = true,
            shuffler = { it.reversed() },
        )

        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), result.images.map { it.fileName })
        assertEquals(-1, result.portraitSectionStart)
    }

    @Test
    fun `empty input is handled`() {
        val result = PhoneFeedOrdering.order(emptyList(), randomize = false, sortByOrientation = true)
        assertEquals(emptyList<String>(), result.images.map { it.fileName })
        assertEquals(-1, result.portraitSectionStart)
    }

    // ── Progressive discovery ────────────────────────────────────────────

    @Test
    fun `newItems returns everything when nothing is known yet`() {
        val incoming = listOf(img("a.jpg", 1), img("b.jpg", 2))

        val fresh = PhoneFeedOrdering.newItems(incoming, emptySet())

        assertEquals(listOf("a.jpg", "b.jpg"), fresh.map { it.fileName })
    }

    @Test
    fun `newItems filters out URIs already published`() {
        val a = img("a.jpg", 1)
        val b = img("b.jpg", 2)
        val c = img("c.jpg", 3)

        val fresh = PhoneFeedOrdering.newItems(listOf(a, b, c), setOf(a.uri, b.uri))

        assertEquals(listOf("c.jpg"), fresh.map { it.fileName })
    }

    @Test
    fun `newItems treats removed-but-published items as known`() {
        // The user moved a.jpg out of the feed while discovery was still running;
        // the next cumulative batch must not resurrect it.
        val a = img("a.jpg", 1)
        val b = img("b.jpg", 2)

        val fresh = PhoneFeedOrdering.newItems(listOf(a, b), knownUris = setOf(a.uri, b.uri))

        assertEquals(emptyList<String>(), fresh.map { it.fileName })
    }

    @Test
    fun `appendBatch on an empty feed behaves exactly like order`() {
        val fresh = listOf(img("old.jpg", 100), img("new.jpg", 300))

        val appended = PhoneFeedOrdering.appendBatch(
            current = emptyList(),
            fresh = fresh,
            randomize = false,
            sortByOrientation = false,
        )
        val ordered = PhoneFeedOrdering.order(fresh, randomize = false, sortByOrientation = false)

        assertEquals(ordered.images.map { it.fileName }, appended.images.map { it.fileName })
    }

    @Test
    fun `appendBatch appends batches without reordering when not randomized`() {
        val current = PhoneFeedOrdering.order(
            listOf(img("b1_old.jpg", 100), img("b1_mid.jpg", 200)),
            randomize = false,
            sortByOrientation = false,
        ).images
        val fresh = listOf(img("b2_newest.jpg", 900), img("b2_newer.jpg", 800))

        val result = PhoneFeedOrdering.appendBatch(
            current = current,
            fresh = fresh,
            randomize = false,
            sortByOrientation = false,
        )

        assertEquals(
            listOf("b1_mid.jpg", "b1_old.jpg", "b2_newest.jpg", "b2_newer.jpg"),
            result.images.map { it.fileName },
        )
    }

    @Test
    fun `appendBatch with reorder puts the newest known photo first`() {
        val current = listOf(img("b1_mid.jpg", 200), img("b1_old.jpg", 100))
        val fresh = listOf(img("b2_newest.jpg", 900), img("b2_older.jpg", 150))

        val result = PhoneFeedOrdering.appendBatch(
            current = current,
            fresh = fresh,
            randomize = false,
            sortByOrientation = false,
            reorder = true,
        )

        assertEquals(
            listOf("b2_newest.jpg", "b1_mid.jpg", "b2_older.jpg", "b1_old.jpg"),
            result.images.map { it.fileName },
        )
    }

    @Test
    fun `appendBatch with reorder never reorders a randomized feed`() {
        val current = listOf(img("seen.jpg", 100))
        val result = PhoneFeedOrdering.appendBatch(
            current = current,
            fresh = listOf(img("n.jpg", 900)),
            randomize = true,
            sortByOrientation = false,
            shuffler = { it },
            reorder = true,
        )
        assertEquals(listOf("seen.jpg", "n.jpg"), result.images.map { it.fileName })
    }

    @Test
    fun `appendBatch shuffles only the incoming batch when randomizing`() {
        val current = listOf(img("seen1.jpg", 100), img("seen2.jpg", 200))
        val fresh = listOf(img("f1.jpg", 300), img("f2.jpg", 400), img("f3.jpg", 500))

        val result = PhoneFeedOrdering.appendBatch(
            current = current,
            fresh = fresh,
            randomize = true,
            sortByOrientation = false,
            shuffler = { it.reversed() },
        )

        assertEquals(
            listOf("seen1.jpg", "seen2.jpg", "f3.jpg", "f2.jpg", "f1.jpg"),
            result.images.map { it.fileName },
        )
    }

    @Test
    fun `appendBatch with nothing new leaves the feed untouched`() {
        val current = listOf(img("a.jpg", 100), img("b.jpg", 200))

        val result = PhoneFeedOrdering.appendBatch(
            current = current,
            fresh = emptyList(),
            randomize = false,
            sortByOrientation = false,
        )

        assertEquals(current.map { it.fileName }, result.images.map { it.fileName })
    }

    @Test
    fun `appendBatch recomputes the portrait split over the merged feed`() {
        val current = listOf(
            img("l1.jpg", 400, width = 4000, height = 3000),
            img("l2.jpg", 300, width = 4000, height = 3000),
        )
        val fresh = listOf(img("p1.jpg", 200, width = 3000, height = 4000))

        val result = PhoneFeedOrdering.appendBatch(
            current = current,
            fresh = fresh,
            randomize = false,
            sortByOrientation = true,
        )

        assertEquals(3, result.images.size)
        assertEquals(2, result.portraitSectionStart)
    }

    @Test
    fun `SHOOTING order sorts ascending by date with natural filename tie-break`() {
        val input = listOf(
            img("DSC_10.JPG", date = 100),
            img("DSC_2.JPG", date = 100),
            img("DSC_1.JPG", date = 50),
        )

        val result = PhoneFeedOrdering.order(
            images = input,
            mode = FeedOrderMode.SHOOTING,
        )

        assertEquals(
            listOf("DSC_1.JPG", "DSC_2.JPG", "DSC_10.JPG"),
            result.images.map { it.fileName },
        )
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `newest first keeps the newest photo at position 1 even when it was seen`() {
        val a = img("a.jpg", date = 300)
        val b = img("b.jpg", date = 200)
        val c = img("c.jpg", date = 100)

        val result = PhoneFeedOrdering.order(
            images = listOf(c, a, b),
            mode = FeedOrderMode.NEWEST_FIRST,
            seenUris = setOf(a.uri),
        )

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), result.images.map { it.fileName })
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `shooting and orientation orders ignore what was seen`() {
        val a = img("a.jpg", date = 300, width = 4, height = 3)
        val b = img("b.jpg", date = 200, width = 3, height = 4)
        val c = img("c.jpg", date = 100, width = 4, height = 3)
        val seen = setOf(a.uri, c.uri)

        val shooting = PhoneFeedOrdering.order(listOf(b, a, c), mode = FeedOrderMode.SHOOTING, seenUris = seen)
        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), shooting.images.map { it.fileName })

        val orientation = PhoneFeedOrdering.order(
            listOf(b, a, c),
            mode = FeedOrderMode.ORIENTATION_THEN_NEWEST,
            seenUris = seen,
        )
        assertEquals(listOf("a.jpg", "c.jpg", "b.jpg"), orientation.images.map { it.fileName })
        assertEquals(2, orientation.portraitSectionStart)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `shuffle still deals unseen photos before seen ones`() {
        val a = img("a.jpg", date = 300)
        val b = img("b.jpg", date = 200)
        val c = img("c.jpg", date = 100)

        val result = PhoneFeedOrdering.order(
            images = listOf(a, b, c),
            mode = FeedOrderMode.SHUFFLE,
            seenUris = setOf(a.uri),
            shuffler = { it },
        )

        assertEquals(listOf("b.jpg", "c.jpg", "a.jpg"), result.images.map { it.fileName })
    }

    @Test
    fun `appendBatch in SHUFFLE mode freezes prefix up to currentIndex plus prefetch window`() {
        val current = (0..4).map { img("c$it.jpg", date = it.toLong()) }
        val fresh = listOf(img("f1.jpg", date = 10), img("f2.jpg", date = 11))

        val result = PhoneFeedOrdering.appendBatch(
            current = current,
            fresh = fresh,
            mode = FeedOrderMode.SHUFFLE,
            shuffler = { it.reversed() },
            currentIndex = 1,
        )

        // Indices 0..3 (currentIndex=1 + PREFETCH_WINDOW=2) must remain untouched
        assertEquals(
            listOf("c0.jpg", "c1.jpg", "c2.jpg", "c3.jpg"),
            result.images.take(4).map { it.fileName },
        )
        assertEquals(7, result.images.size)
    }

    @Test
    fun `filterByType ONE_PER_SHOT collapses paired RAW and JPEG to JPEG`() {
        val jpg = img("IMG_001.JPG", date = 100)
        val raw = img("IMG_001.CR3", date = 100).copy(mimeType = "image/x-canon-cr3")
        val soloRaw = img("IMG_002.ARW", date = 200).copy(mimeType = "image/x-sony-arw")

        val filtered = PhoneFeedOrdering.filterByType(
            images = listOf(raw, jpg, soloRaw),
            filter = FileTypeFilter.ONE_PER_SHOT,
        )

        assertEquals(listOf("IMG_001.JPG", "IMG_002.ARW"), filtered.map { it.fileName })
    }
}
