package com.photoselectortoolbox.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The glyph is the only identifier a neighbour's numbers get.
 *
 * On the Previous and Next frames the value overlay has no room for metric
 * names, so a duplicated or missing glyph does not degrade the display — it
 * makes two different numbers indistinguishable.
 */
class ScoreMetricIconTest {

    @Test
    fun `every metric has a distinct glyph`() {
        val icons = ScoreMetric.entries.map { it.icon }

        assertEquals(
            "two metrics share a glyph: ${icons.groupBy { it }.filter { it.value.size > 1 }.keys}",
            icons.size,
            icons.toSet().size,
        )
    }

    @Test
    fun `every glyph in the vocabulary is used`() {
        // A glyph nobody uses is a glyph someone will reuse for the wrong
        // metric later. Both directions of the mapping stay total.
        assertEquals(
            ScoreMetricIcon.entries.toSet(),
            ScoreMetric.entries.map { it.icon }.toSet(),
        )
    }

    @Test
    fun `metric order is stable, because three frames read it top to bottom`() {
        // The current frame's readout and both neighbour overlays render in
        // declaration order. If that order were decided per composable the
        // three frames could disagree, and comparing them at a glance — the
        // only way an overlay is ever read — would stop working.
        assertEquals(
            listOf(
                ScoreMetric.SHARPNESS,
                ScoreMetric.NOISE,
                ScoreMetric.HIGHLIGHT_CLIPPING,
                ScoreMetric.SHADOW_CLIPPING,
            ),
            ScoreMetric.entries.toList(),
        )
    }
}
