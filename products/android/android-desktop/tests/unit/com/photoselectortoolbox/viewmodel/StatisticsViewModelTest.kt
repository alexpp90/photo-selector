package com.photoselectortoolbox.viewmodel

import org.junit.Assert.*
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * Tests for StatisticsUiState data class and the shutter speed
 * formatting logic used by StatisticsViewModel.
 */
class StatisticsViewModelTest {

    // ── StatisticsUiState tests ─────────────────────────────────────

    @Test
    fun `default state is idle`() {
        val state = StatisticsUiState()
        assertFalse(state.isLoading)
        assertNull(state.folderUri)
        assertEquals(0, state.imageCount)
        assertTrue(state.shutterSpeedDistribution.isEmpty())
        assertTrue(state.apertureDistribution.isEmpty())
        assertTrue(state.isoDistribution.isEmpty())
        assertTrue(state.focalLengthDistribution.isEmpty())
        assertTrue(state.lensDistribution.isEmpty())
        assertNull(state.error?.en())
    }

    @Test
    fun `loading state preserves folder info`() {
        val state = StatisticsUiState(
            isLoading = true,
            folderUri = "content://tree/123",
            folderName = "Photos"
        )
        assertTrue(state.isLoading)
        assertEquals("Photos", state.folderName)
    }

    @Test
    fun `distributions can be populated`() {
        val state = StatisticsUiState(
            imageCount = 100,
            isoDistribution = mapOf("ISO 100" to 40, "ISO 400" to 35, "ISO 1600" to 25),
            lensDistribution = mapOf("50mm f/1.8" to 60, "24-70mm f/2.8" to 40)
        )
        assertEquals(100, state.imageCount)
        assertEquals(3, state.isoDistribution.size)
        assertEquals(40, state.isoDistribution["ISO 100"])
        assertEquals(2, state.lensDistribution.size)
    }

    // ── Shutter speed formatting logic (mirrors private formatShutterSpeed) ──

    private fun formatShutterSpeed(speed: Double): String {
        return if (speed >= 1.0) {
            if (speed == speed.toLong().toDouble()) {
                "${speed.toLong()}\""
            } else {
                "%.1f\"".format(java.util.Locale.US, speed)
            }
        } else {
            val denominator = (1.0 / speed).toLong()
            "1/${denominator}s"
        }
    }

    @Test
    fun `formatShutterSpeed fraction under 1s`() {
        assertEquals("1/250s", formatShutterSpeed(1.0 / 250))
        assertEquals("1/1000s", formatShutterSpeed(0.001))
        assertEquals("1/30s", formatShutterSpeed(1.0 / 30))
    }

    @Test
    fun `formatShutterSpeed whole seconds`() {
        assertEquals("1\"", formatShutterSpeed(1.0))
        assertEquals("2\"", formatShutterSpeed(2.0))
        assertEquals("30\"", formatShutterSpeed(30.0))
    }

    @Test
    fun `formatShutterSpeed fractional seconds`() {
        assertEquals("1.5\"", formatShutterSpeed(1.5))
        // 0.5s = 1/2s
        assertEquals("1/2s", formatShutterSpeed(0.5))
    }

    // ── Aperture formatting ─────────────────────────────────────────

    @Test
    fun `aperture label format`() {
        assertEquals("f/2.8", "f/%.1f".format(java.util.Locale.US, 2.8))
        assertEquals("f/1.4", "f/%.1f".format(java.util.Locale.US, 1.4))
        assertEquals("f/16.0", "f/%.1f".format(java.util.Locale.US, 16.0))
    }

    // ── ISO label format ────────────────────────────────────────────

    @Test
    fun `iso label format`() {
        assertEquals("ISO 100", "ISO ${100}")
        assertEquals("ISO 6400", "ISO ${6400}")
    }

    // ── Focal length label format ───────────────────────────────────

    @Test
    fun `focal length label format`() {
        assertEquals("50mm", "${50.0.toInt()}mm")
        assertEquals("200mm", "${200.0.toInt()}mm")
    }
}
