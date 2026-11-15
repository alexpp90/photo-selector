package com.phototok.viewmodel

import com.photoselector.core.Requirement
import com.phototok.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed screen reads [rawCompanionUris] for the whole folder. A per-photo scan of the folder
 * was quadratic and froze the feed for 3–5 s per swipe in a camera roll of 3,000 photos
 * (Pixel 4a, Moto G77; reported on a Poco F3, 2026-11-15).
 */
class PhoneModeUiStateScaleTest {

    private fun img(name: String) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1L,
        lastModified = 1L,
        mimeType = null,
        parentUri = "content://photos",
    )

    @Test
    @Requirement("REQ-TOK-ARCH.07")
    fun `the RAW+JPG pill set of a camera roll takes milliseconds`() {
        val roll = (1..10_000).map { img("PXL_%05d.JPG".format(it)) } + img("PXL_00042.DNG")
        val state = PhoneModeUiState(images = roll, allImages = roll)

        val started = System.nanoTime()
        val uris = state.rawCompanionUris
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(setOf("content://photos/PXL_00042.JPG"), uris)
        // Linear: a few milliseconds. The quadratic scan took tens of seconds here.
        assertTrue("took $elapsedMs ms", elapsedMs < 1_000)
    }
}
