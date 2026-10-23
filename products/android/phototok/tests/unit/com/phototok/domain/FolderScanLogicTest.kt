package com.phototok.domain

import com.phototok.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderScanLogicTest {

    private fun sampleImage(name: String, lastModified: Long): ImageItem = ImageItem(
        uri = "content://media/$name",
        fileName = name,
        fileSize = 1000L,
        lastModified = lastModified,
        mimeType = "image/jpeg",
    )

    @Test
    fun `findFirstNewImageIndex returns -1 when previous timestamp is zero or negative`() {
        val list = listOf(
            sampleImage("a.jpg", 100L),
            sampleImage("b.jpg", 200L),
        )
        assertEquals(-1, FolderScanLogic.findFirstNewImageIndex(list, 0L))
        assertEquals(-1, FolderScanLogic.findFirstNewImageIndex(list, -10L))
    }

    @Test
    fun `findFirstNewImageIndex returns -1 when list is empty`() {
        assertEquals(-1, FolderScanLogic.findFirstNewImageIndex(emptyList(), 100L))
    }

    @Test
    fun `findFirstNewImageIndex returns -1 when no items are newer than previous timestamp`() {
        val list = listOf(
            sampleImage("a.jpg", 100L),
            sampleImage("b.jpg", 200L),
            sampleImage("c.jpg", 300L),
        )
        assertEquals(-1, FolderScanLogic.findFirstNewImageIndex(list, 300L))
        assertEquals(-1, FolderScanLogic.findFirstNewImageIndex(list, 400L))
    }

    @Test
    fun `findFirstNewImageIndex returns index of first item strictly newer than previous timestamp`() {
        val list = listOf(
            sampleImage("a.jpg", 100L),
            sampleImage("b.jpg", 200L),
            sampleImage("c.jpg", 300L),
            sampleImage("d.jpg", 400L),
        )
        // Item at index 2 (300L) is the first newer than 250L
        assertEquals(2, FolderScanLogic.findFirstNewImageIndex(list, 250L))
        // Item at index 0 (100L) is newer than 50L
        assertEquals(0, FolderScanLogic.findFirstNewImageIndex(list, 50L))
        // Item at index 3 (400L) is newer than 300L
        assertEquals(3, FolderScanLogic.findFirstNewImageIndex(list, 300L))
    }

    @Test
    fun `countNewImages counts items strictly newer than previous timestamp`() {
        val list = listOf(
            sampleImage("a.jpg", 100L),
            sampleImage("b.jpg", 200L),
            sampleImage("c.jpg", 300L),
            sampleImage("d.jpg", 400L),
        )
        assertEquals(0, FolderScanLogic.countNewImages(list, 0L))
        assertEquals(0, FolderScanLogic.countNewImages(list, 400L))
        assertEquals(2, FolderScanLogic.countNewImages(list, 200L))
        assertEquals(4, FolderScanLogic.countNewImages(list, 50L))
    }

    // Feed is newest-first: new photos are at the start.
    private val feed = listOf(
        sampleImage("d.jpg", 400L),
        sampleImage("c.jpg", 300L),
        sampleImage("b.jpg", 200L),
        sampleImage("a.jpg", 100L),
    )

    private fun landing(
        prevMax: Long = 0L,
        navigated: Boolean = false,
        randomized: Boolean = false,
        uri: String? = null,
        current: Int = 0,
        images: List<ImageItem> = feed,
    ) = FolderScanLogic.resolveLandingIndex(images, prevMax, navigated, randomized, uri, current)

    @Test
    fun `newest-first feed puts the first new photo at the start`() {
        assertEquals(0, FolderScanLogic.findFirstNewImageIndex(feed, 250L))
        assertEquals(2, FolderScanLogic.countNewImages(feed, 250L))
    }

    @Test
    fun `fresh folder lands on the newest photo`() {
        assertEquals(0, landing())
    }

    @Test
    fun `new photos land on the newest `() {
        assertEquals(0, landing(prevMax = 250L))
    }

    @Test
    fun `no new photos still lands on the newest photo`() {
        assertEquals(0, landing(prevMax = 400L))
    }

    @Test
    fun `user who already swiped keeps their photo by uri`() {
        assertEquals(2, landing(prevMax = 50L, navigated = true, uri = "content://media/b.jpg", current = 0))
        assertEquals(1, landing(navigated = true, uri = "content://media/gone.jpg", current = 1))
    }

    @Test
    fun `randomized feed keeps the current photo even when new photos exist`() {
        assertEquals(2, landing(randomized = true, uri = "content://media/b.jpg"))
        assertEquals(2, landing(randomized = true, prevMax = 350L, uri = "content://media/b.jpg"))
    }

    @Test
    fun `shooting feed lands on first unseen photo`() {
        val shootingFeed = listOf(
            sampleImage("a.jpg", 100L),
            sampleImage("b.jpg", 200L),
            sampleImage("c.jpg", 300L),
        )
        val idx = FolderScanLogic.resolveLandingIndex(
            images = shootingFeed,
            previousMaxLastModified = 100L,
            userHasNavigated = false,
            randomized = false,
            preservedUri = null,
            currentIndex = 0,
            mode = FeedOrderMode.SHOOTING,
            seenUris = setOf("content://media/a.jpg", "content://media/b.jpg"),
        )
        assertEquals(2, idx)
    }

    @Test
    fun `empty feed lands on zero`() {
        assertEquals(0, landing(images = emptyList()))
    }

    @Test
    fun `computeUpdatedScanInfo extracts max lastModified and size`() {
        val emptyInfo = FolderScanLogic.computeUpdatedScanInfo(emptyList())
        assertEquals(0L, emptyInfo.maxLastModified)
        assertEquals(0, emptyInfo.fileCount)

        val list = listOf(
            sampleImage("a.jpg", 100L),
            sampleImage("b.jpg", 500L),
            sampleImage("c.jpg", 300L),
        )
        val info = FolderScanLogic.computeUpdatedScanInfo(list, lastViewedUri = "content://media/c.jpg", lastViewedIndex = 2)
        assertEquals(500L, info.maxLastModified)
        assertEquals(3, info.fileCount)
        assertEquals("content://media/c.jpg", info.lastViewedUri)
        assertEquals(2, info.lastViewedIndex)
    }

    @Test
    fun `resolveResumeIndex prefers matching URI and falls back to clamped index`() {
        // Exact URI match even when new photos shifted its index
        assertEquals(2, FolderScanLogic.resolveResumeIndex(feed, "content://media/b.jpg", lastViewedIndex = 0))
        // Fallback to clamped index when URI was removed
        assertEquals(1, FolderScanLogic.resolveResumeIndex(feed, "content://media/deleted.jpg", lastViewedIndex = 1))
        assertEquals(3, FolderScanLogic.resolveResumeIndex(feed, null, lastViewedIndex = 99))
        assertEquals(0, FolderScanLogic.resolveResumeIndex(emptyList(), "content://media/b.jpg", lastViewedIndex = 2))
    }

    @Test
    fun `findNewPhotos identifies photos newer than previousMaxLastModified or absent from urisBeforeReload`() {
        val byTimestamp = FolderScanLogic.findNewPhotos(feed, previousMaxLastModified = 250L)
        assertEquals(listOf("d.jpg", "c.jpg"), byTimestamp.map { it.fileName })

        val byUriSet = FolderScanLogic.findNewPhotos(
            images = feed,
            previousMaxLastModified = 400L,
            urisBeforeReload = setOf("content://media/c.jpg", "content://media/b.jpg", "content://media/a.jpg"),
        )
        assertEquals(listOf("d.jpg"), byUriSet.map { it.fileName })
    }

    // ── latestIndex: where an explicit Reload lands ──────────────────────

    @Test
    fun `latestIndex of a newest-first feed is 0`() {
        val feed = listOf(sampleImage("c.jpg", 300L), sampleImage("b.jpg", 200L), sampleImage("a.jpg", 100L))
        assertEquals(0, FolderScanLogic.latestIndex(feed))
    }

    @Test
    fun `latestIndex of a shooting-order (ascending) feed is the last photo`() {
        val feed = listOf(sampleImage("a.jpg", 100L), sampleImage("b.jpg", 200L), sampleImage("c.jpg", 300L))
        assertEquals(2, FolderScanLogic.latestIndex(feed))
    }

    @Test
    fun `latestIndex finds the newest photo in an unseen-first partitioned feed`() {
        // Unseen photos first (older), seen ones after, one of which is the newest overall.
        val feed = listOf(
            sampleImage("unseen_old.jpg", 100L),
            sampleImage("unseen_mid.jpg", 150L),
            sampleImage("seen_newest.jpg", 900L),
            sampleImage("seen_old.jpg", 50L),
        )
        assertEquals(2, FolderScanLogic.latestIndex(feed))
    }

    @Test
    fun `latestIndex takes the first occurrence on a tie`() {
        val feed = listOf(sampleImage("a.jpg", 100L), sampleImage("b.jpg", 300L), sampleImage("c.jpg", 300L))
        assertEquals(1, FolderScanLogic.latestIndex(feed))
        assertEquals(0, FolderScanLogic.latestIndex(listOf(sampleImage("x.jpg", 5L), sampleImage("y.jpg", 5L))))
    }

    @Test
    fun `latestIndex of an empty or single-photo list is 0`() {
        assertEquals(0, FolderScanLogic.latestIndex(emptyList()))
        assertEquals(0, FolderScanLogic.latestIndex(listOf(sampleImage("only.jpg", 42L))))
    }
}
