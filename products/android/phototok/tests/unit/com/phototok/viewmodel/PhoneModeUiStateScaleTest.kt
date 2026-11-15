package com.phototok.viewmodel

import com.photoselector.core.Requirement
import com.phototok.data.model.ImageItem
import com.phototok.testsupport.ScaleConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole-folder derivations of [PhoneModeUiState] that the feed screen reads. A per-photo scan of
 * the folder was quadratic and froze the feed for 3–5 s per swipe in a camera roll of 3,000 photos
 * (Pixel 4a, Moto G77; reported on a Poco F3, 2026-11-15).
 */
class PhoneModeUiStateScaleTest {

    private companion object {
        /** Timings below this are scheduler noise: a sub-millisecond N must not inflate the ratio. */
        const val FLOOR_NANOS = 2_000_000L

        /** One derivation takes well under a millisecond at N: time a series of them. */
        const val REPEAT = 20
    }

    private fun img(name: String, sidecars: List<ImageItem> = emptyList()) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1L,
        lastModified = 1L,
        mimeType = null,
        parentUri = "content://photos",
        sidecars = sidecars,
    )

    @Test
    @Requirement("REQ-TOK-ARCH.07")
    fun `the RAW+JPG pill set of a camera roll takes milliseconds`() {
        val roll = (1..ScaleConditions.prPhotos).map { img("PXL_%05d.JPG".format(it)) } + img("PXL_00042.DNG")
        val state = PhoneModeUiState(images = roll, allImages = roll)

        val started = System.nanoTime()
        val uris = state.rawCompanionUris
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(setOf("content://photos/PXL_00042.JPG"), uris)
        // Linear: a few milliseconds. The quadratic scan took tens of seconds here.
        assertTrue("took $elapsedMs ms", elapsedMs < 1_000)
    }

    /** A card as discovered: RAW+JPEG pairs, every tenth shot with an `.xmp` sidecar on both files. */
    private fun card(photos: Int): PhoneModeUiState {
        val all = (0 until photos).map { i ->
            val stem = "DSC%05d".format(i / 2)
            val name = if (i % 2 == 0) "$stem.ARW" else "$stem.JPG"
            img(name, sidecars = if ((i / 2) % 10 == 0) listOf(img("$stem.xmp")) else emptyList())
        }
        return PhoneModeUiState(images = all.filter { it.fileName.endsWith(".JPG") }, allImages = all)
    }

    /**
     * Scale: pr — what "Delete all" counts and removes (REQ-TOK-FEATURE.15), read by the card
     * dialogs on each of their recompositions: one hashed pass over the card's files.
     */
    @Test
    @Requirement("REQ-TOK-FEATURE.15", "REQ-TOK-ARCH.24")
    fun `the files Delete all removes are counted in time linear in the card`() {
        val small = card(ScaleConditions.baseN)
        val large = card(ScaleConditions.prPhotos)
        // Each pair shares one sidecar, attached to both: counted once.
        assertEquals(ScaleConditions.prPhotos + ScaleConditions.prPhotos / 20, large.cardClearFileCount)

        // The best time per size across attempts, never the best ratio (a noisy N run lowers it).
        var ratio = Double.MAX_VALUE
        var report = ""
        var tSmall = Long.MAX_VALUE
        var tLarge = Long.MAX_VALUE
        for (attempt in 1..3) {
            tSmall = minOf(tSmall, ScaleConditions.bestNanos { repeat(REPEAT) { small.cardClearFileCount } })
            tLarge = minOf(tLarge, ScaleConditions.bestNanos { repeat(REPEAT) { large.cardClearFileCount } })
            ratio = tLarge.toDouble() / maxOf(tSmall, FLOOR_NANOS)
            report = "card clear targets x$REPEAT: N=${small.allImages.size} ${"%.1f".format(tSmall / 1e6)} ms, " +
                "4N=${large.allImages.size} ${"%.1f".format(tLarge / 1e6)} ms, ratio ${"%.2f".format(ratio)}"
            println("SCALE $report")
            if (ratio <= ScaleConditions.maxRatio) break
        }
        assertTrue("$report, limit ${ScaleConditions.maxRatio}", ratio <= ScaleConditions.maxRatio)
    }
}
