package com.photoselectortoolbox.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * The direction bar on a score chip is only trustworthy if "longer and greener
 * is better" holds for every metric, including the ones where the raw number
 * runs the other way. These tests pin that invariant down, because a bar that
 * lies about direction is worse than no bar at all.
 */
class ScoreGoodnessTest {

    @Test
    fun `goodness is bounded to zero and one`() {
        ScoreMetric.entries.forEach { metric ->
            listOf(-1000.0, -1.0, 0.0, 1.0, 50.0, 1_000_000.0).forEach { value ->
                val goodness = metric.goodness(value)
                assertTrue(
                    "${metric.name} goodness($value) = $goodness out of range",
                    goodness in 0.0..1.0,
                )
            }
        }
    }

    @Test
    fun `higher-is-better metrics rise with the value`() {
        ScoreMetric.entries
            .filter { it.direction == ScoreDirection.HIGHER_IS_BETTER }
            .forEach { metric ->
                assertTrue(
                    metric.name,
                    metric.goodness(90.0) >= metric.goodness(20.0),
                )
            }
    }

    @Test
    fun `lower-is-better metrics fall as the value rises`() {
        ScoreMetric.entries
            .filter { it.direction == ScoreDirection.LOWER_IS_BETTER }
            .forEach { metric ->
                assertTrue(
                    metric.name,
                    metric.goodness(0.1) >= metric.goodness(9.0),
                )
            }
    }

    @Test
    fun `a blurry frame is bad and a sharp one is good`() {
        assertEquals(0.0, ScoreMetric.SHARPNESS.goodness(5.0), 1e-9)
        assertEquals(1.0, ScoreMetric.SHARPNESS.goodness(100.0), 1e-9)
        assertEquals(0.5, ScoreMetric.SHARPNESS.goodness(52.5), 1e-9)
    }

    @Test
    fun `a clean frame scores better than a noisy one`() {
        assertEquals(1.0, ScoreMetric.NOISE.goodness(0.2), 1e-9)
        assertEquals(0.0, ScoreMetric.NOISE.goodness(7.0), 1e-9)
        assertTrue(ScoreMetric.NOISE.goodness(1.0) > ScoreMetric.NOISE.goodness(5.0))
    }

    @Test
    fun `no clipping at all is the best possible clipping score`() {
        assertEquals(1.0, ScoreMetric.HIGHLIGHT_CLIPPING.goodness(0.0), 1e-9)
        assertEquals(1.0, ScoreMetric.SHADOW_CLIPPING.goodness(0.0), 1e-9)
    }

    @Test
    fun `best of three picks the highest sharpness`() {
        val best = ScoreMetric.SHARPNESS.bestIndexOf(listOf(22.4, 88.3, 61.9))
        assertEquals(1, best)
    }

    @Test
    fun `best of three picks the lowest noise, not the highest number`() {
        val best = ScoreMetric.NOISE.bestIndexOf(listOf(4.1, 0.8, 2.6))
        assertEquals(1, best)
    }

    @Test
    fun `frames without a value are skipped rather than counted as zero`() {
        val best = ScoreMetric.SHARPNESS.bestIndexOf(listOf(null, 30.0, 70.0))
        assertEquals(2, best)
    }

    @Test
    fun `a single scored frame gets no best-of marker`() {
        // "Best of one" is not information; marking it would train the user to
        // ignore the marker where it does mean something.
        assertNull(ScoreMetric.SHARPNESS.bestIndexOf(listOf(null, 42.0, null)))
        assertNull(ScoreMetric.SHARPNESS.bestIndexOf(listOf(null, null, null)))
    }

    @Test
    fun `ties resolve to the earlier frame so the marker does not flicker`() {
        val best = ScoreMetric.SHARPNESS.bestIndexOf(listOf(60.0, 60.0, 10.0))
        assertEquals(0, best)
    }

    @Test
    fun `accessibility label states the direction and the best-of status`() {
        val plain = ScoreMetric.SHARPNESS.accessibilityLabel(82.4).en()
        assertTrue(plain, plain.contains("Sharpness"))
        assertTrue(plain, plain.contains("82.4"))
        assertTrue(plain, plain.contains("higher is better"))
        assertTrue(plain, !plain.contains("best of"))

        val best = ScoreMetric.SHARPNESS.accessibilityLabel(82.4, isBestOfVisible = true).en()
        assertTrue(best, best.contains("best of the three visible frames"))
    }
}
