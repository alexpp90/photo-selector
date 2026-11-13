package com.photoselectortoolbox.domain.scoring

import com.photoselectortoolbox.domain.guidance.SelectorTour
import com.photoselectortoolbox.ui.selector.SharpnessRankText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * Every text about the sharpness score says what it measures: the crispest spot of the centre of
 * the frame (`SharpnessAnalyzer` crops the middle 50 %), a figure for comparing frames of one
 * burst (backlog 2026-11-12, centre-only sharpness; 2026-11-11, an absolute number). The legend
 * said "the sharpest part of the frame", which an off-centre subject is not.
 */
class SharpnessWordingTest {

    @Test
    fun `the description names the centre of the frame and drops the whole-frame claim`() {
        val text = ScoreMetric.SHARPNESS.description.en()
        assertTrue(text, text.contains("centre of the frame"))
        assertFalse(text, text.contains("sharpest part of the frame"))
        assertTrue("it says what the figure is for", text.contains("burst"))
    }

    @Test
    fun `the spoken label says where it was measured, the other metrics do not claim an area`() {
        val spoken = ScoreMetric.SHARPNESS.accessibilityLabel(64.0).en()
        assertEquals("Sharpness at the centre of the frame 64.0, higher is better", spoken)
        assertEquals("Noise 1.2, lower is better", ScoreMetric.NOISE.accessibilityLabel(1.2).en())
    }

    @Test
    fun `the guide's legend row says centre too`() {
        val row = SelectorTour.metricLegend().single { it.metric == ScoreMetric.SHARPNESS }
        assertTrue(row.meaning.en(), row.meaning.en().contains("centre of the frame"))
    }

    @Test
    fun `ordinals read as English ordinals`() {
        val expected = mapOf(
            1 to "1st", 2 to "2nd", 3 to "3rd", 4 to "4th", 11 to "11th", 12 to "12th", 13 to "13th",
            21 to "21st", 22 to "22nd", 23 to "23rd", 101 to "101st", 111 to "111th", 112 to "112th",
        )
        expected.forEach { (n, word) -> assertEquals(word, SharpnessRankText.ordinal(n).en()) }
    }

    @Test
    fun `the rank reads 3rd of 10 and is spoken with its burst`() {
        val rank = BurstRank(position = 3, of = 10)
        assertEquals("3rd of 10", SharpnessRankText.visible(rank).en())
        assertEquals(
            "Sharpness at the centre of the frame 3rd of 10 in its burst, 64.0, higher is better",
            SharpnessRankText.spoken(rank, 64.0).en(),
        )
    }
}
