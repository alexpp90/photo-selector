package com.photoselectortoolbox.domain.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectorLabelsTest {

    @Test
    fun `filmstrip range is one-based and inclusive`() {
        // The strip reports the 0-based indices it can see; the caption has to
        // match the 1-based position shown everywhere else in the app.
        assertEquals("115–156 of 842", SelectorLabels.filmstripRange(114, 155, 842))
    }

    @Test
    fun `a fully visible folder shows only the total`() {
        assertEquals("40 of 40", SelectorLabels.filmstripRange(0, 39, 40))
    }

    @Test
    fun `range bounds are clamped to the folder`() {
        // A lazy list can briefly report indices outside the data while it is
        // settling; the caption must never claim "0–45 of 10".
        assertEquals("10 of 10", SelectorLabels.filmstripRange(-5, 40, 10))
        assertEquals("3–10 of 10", SelectorLabels.filmstripRange(2, 40, 10))
    }

    @Test
    fun `a reversed range does not produce a backwards caption`() {
        assertEquals("6–6 of 10", SelectorLabels.filmstripRange(5, 1, 10))
    }

    @Test
    fun `an empty folder has no range caption`() {
        assertEquals("", SelectorLabels.filmstripRange(0, 0, 0))
    }

    @Test
    fun `burst chip is one-based`() {
        assertEquals("burst 3/7", SelectorLabels.burstChip(indexInSeries = 2, seriesLength = 7))
    }

    @Test
    fun `a frame that is not part of a series gets no burst chip`() {
        // A "burst 1/1" chip is noise — every frame would carry one.
        assertNull(SelectorLabels.burstChip(indexInSeries = 0, seriesLength = 1))
        assertNull(SelectorLabels.burstChip(indexInSeries = null, seriesLength = 7))
        assertNull(SelectorLabels.burstChip(indexInSeries = 2, seriesLength = null))
    }

    @Test
    fun `scan progress reads as a counter`() {
        assertEquals("Scanning 412 / 842", SelectorLabels.scanProgress(412, 842))
    }

    @Test
    fun `delete message is pluralised`() {
        assertEquals("1 image deleted", SelectorLabels.deletedMessage(1))
        assertEquals("4 images deleted", SelectorLabels.deletedMessage(4))
    }

    @Test
    fun `scan completion names the count`() {
        assertEquals("Scan complete · 842 images analysed", SelectorLabels.scanCompleteMessage(842))
    }

    @Test
    fun `the position readout is bare once the folder has finished enumerating`() {
        assertEquals(
            "127 / 842",
            SelectorLabels.position(position = 127, total = 842, stillEnumerating = false),
        )
    }

    @Test
    fun `a still-growing folder says so rather than stating a total nobody counted`() {
        // Discovery streams. During the first seconds of a large shoot the total
        // is a running total, and "24 / 24" is a claim about the folder's size
        // that happens to be false.
        assertEquals(
            "127 / 842+",
            SelectorLabels.position(position = 127, total = 842, stillEnumerating = true),
        )
    }

    @Test
    fun `delete confirmation message states permanent commit and 20-step Ctrl+Z undo`() {
        val msg = SelectorLabels.deleteConfirmationMessage("DSC001.JPG")
        assertEquals(
            "Delete \"DSC001.JPG\"? Deletions in this folder are permanent once committed. You can undo up to 20 actions with Ctrl+Z until you leave the folder.",
            msg,
        )
    }

    @Test
    fun `uncompleted deletions recovery message is pluralised`() {
        assertEquals(
            "1 deletion was not completed — delete now / keep",
            SelectorLabels.uncompletedDeletionsMessage(1),
        )
        assertEquals(
            "3 deletions were not completed — delete now / keep",
            SelectorLabels.uncompletedDeletionsMessage(3),
        )
    }
}
