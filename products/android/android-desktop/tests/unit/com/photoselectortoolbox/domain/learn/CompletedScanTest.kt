package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a finished scan tells Learn (shoot debrief SD-1a, S14). */
class CompletedScanTest {

    private val photos = (1..3).map { ImageItem("content://f/$it.jpg", "$it.jpg", 1L, 1L, "image/jpeg") }

    @Test
    fun `a scan of a settled folder with a result for every photo is complete`() {
        val scan = CompletedScan.of("content://f", photos, photos.associate { it.uri to ScanResult(it.uri) }, false, 99L)

        assertFalse(scan.partial)
        assertEquals(3, scan.scannedCount)
        assertEquals(99L, scan.finishedAtEpochMs)
    }

    @Test
    fun `a photo without a result, or a folder still being listed, makes it partial`() {
        val two = photos.take(2).associate { it.uri to ScanResult(it.uri) }

        val missing = CompletedScan.of("content://f", photos, two, stillListing = false, finishedAtEpochMs = 1L)
        val listing = CompletedScan.of("content://f", photos.take(2), two, stillListing = true, finishedAtEpochMs = 1L)

        assertTrue(missing.partial)
        assertEquals(1, missing.withoutResult)
        assertTrue(listing.partial)
        assertEquals(0, listing.withoutResult)
    }
}
