package com.photoselectortoolbox.domain.scoring

import com.photoselectortoolbox.data.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-scan badges are only useful if every metric carries a readable
 * label, a formatted value and a stated direction — this is what stops them
 * being anonymous icons with a number next to them.
 */
class ScoreMetricTest {

    private val fullResult = ScanResult(
        filePath = "/photos/a.jpg",
        sharpnessScore = 512.34,
        noiseLevel = 3.24,
        highlightClipping = 1.44,
        shadowClipping = 0.0,
        aestheticScore = 7.81,
    )

    @Test
    fun `every metric has a label, name, description and direction`() {
        ScoreMetric.entries.forEach { metric ->
            assertTrue(metric.name, metric.shortLabel.isNotBlank())
            assertTrue(metric.name, metric.displayName.isNotBlank())
            assertTrue(metric.name, metric.description.isNotBlank())
            assertTrue(metric.name, metric.direction.hint.isNotBlank())
        }
    }

    @Test
    fun `short labels stay short enough for a chip`() {
        ScoreMetric.entries.forEach { metric ->
            assertTrue(
                "${metric.name} label too long: ${metric.shortLabel}",
                metric.shortLabel.length <= 5,
            )
        }
    }

    @Test
    fun `values are formatted with the metric's own unit`() {
        assertEquals("512.3", ScoreMetric.SHARPNESS.format(512.34))
        assertEquals("3.2", ScoreMetric.NOISE.format(3.24))
        assertEquals("1.4%", ScoreMetric.HIGHLIGHT_CLIPPING.format(1.44))
        assertEquals("0.0%", ScoreMetric.SHADOW_CLIPPING.format(0.0))
    }

    @Test
    fun `formatting is locale independent`() {
        val previous = java.util.Locale.getDefault()
        try {
            // German uses a decimal comma; the chips must still read "3.2".
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("3.2", ScoreMetric.NOISE.format(3.24))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `direction is correct per metric`() {
        assertEquals(ScoreDirection.HIGHER_IS_BETTER, ScoreMetric.SHARPNESS.direction)
        assertEquals(ScoreDirection.LOWER_IS_BETTER, ScoreMetric.NOISE.direction)
        assertEquals(ScoreDirection.LOWER_IS_BETTER, ScoreMetric.HIGHLIGHT_CLIPPING.direction)
        assertEquals(ScoreDirection.LOWER_IS_BETTER, ScoreMetric.SHADOW_CLIPPING.direction)
    }

    @Test
    fun `valueOf reads the matching field`() {
        assertEquals(512.34, ScoreMetric.SHARPNESS.valueOf(fullResult))
        assertEquals(3.24, ScoreMetric.NOISE.valueOf(fullResult))
        assertEquals(1.44, ScoreMetric.HIGHLIGHT_CLIPPING.valueOf(fullResult))
        assertEquals(0.0, ScoreMetric.SHADOW_CLIPPING.valueOf(fullResult))
    }

    @Test
    fun `present returns only computed metrics, in declaration order`() {
        val partial = ScanResult(
            filePath = "/photos/b.jpg",
            sharpnessScore = 100.0,
            aestheticScore = 5.0,
        )

        assertEquals(
            listOf(ScoreMetric.SHARPNESS),
            ScoreMetric.present(partial).map { it.first },
        )
        assertEquals(4, ScoreMetric.present(fullResult).size)
        assertEquals(emptyList<Any>(), ScoreMetric.present(null))
        assertEquals(
            emptyList<Any>(),
            ScoreMetric.present(ScanResult(filePath = "/photos/c.jpg")),
        )
    }

    @Test
    fun `accessibility label names the metric, the value and the direction`() {
        val label = ScoreMetric.NOISE.accessibilityLabel(3.24)

        assertTrue(label, label.contains("Noise"))
        assertTrue(label, label.contains("3.2"))
        assertTrue(label, label.contains("lower is better"))
    }

    @Test
    fun `an uncomputed metric yields null rather than a zero`() {
        assertNull(ScoreMetric.NOISE.valueOf(ScanResult(filePath = "/photos/d.jpg")))
    }

    @Test
    fun `every metric the legend can show is computed by a scan`() {
        // The scan produces exactly these four fields. A metric that cannot read a
        // value from them would be advertised in the guide yet always blank.
        val scanned = ScanResult(
            filePath = "/photos/d.jpg",
            sharpnessScore = 1.0,
            noiseLevel = 1.0,
            highlightClipping = 1.0,
            shadowClipping = 1.0,
        )
        ScoreMetric.entries.forEach { metric ->
            assertTrue("${metric.name} is always null", metric.valueOf(scanned) != null)
        }
    }
}
