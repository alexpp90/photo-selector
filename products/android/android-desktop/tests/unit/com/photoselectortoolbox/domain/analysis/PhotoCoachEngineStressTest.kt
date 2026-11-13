package com.photoselectortoolbox.domain.analysis

import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.model.TipSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Empirical stress test harness for [PhotoCoachEngineImpl].
 *
 * Exercises adversarial edge cases, numerical boundaries (NaN, Infinity, 0, negatives),
 * scale normalization, and extreme photographic inputs.
 */
class PhotoCoachEngineStressTest {

    private val engine = PhotoCoachEngineImpl()

    // =========================================================================
    // 1. Adversarial & Extreme Shutter Speeds
    // =========================================================================

    @Test
    fun shutterSpeed_zero_handledGracefullyWithoutDivisionByZeroOrCrash() {
        val exif = ExifData(shutterSpeed = 0.0, focalLength = 50.0, focalLength35mm = 50.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 50.0)

        val report = engine.evaluate(exif, scan)

        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        assertTrue(report.tips.none { it.category == "Shutter Speed" })
        assertEquals(0.02, report.safeShutterSpeed!!, 1e-4)
    }

    @Test
    fun shutterSpeed_negative_handledGracefullyWithoutCrash() {
        val exif = ExifData(shutterSpeed = -0.01, focalLength = 50.0, focalLength35mm = 50.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 15.0)

        val report = engine.evaluate(exif, scan)

        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        assertTrue(report.tips.none { it.category == "Shutter Speed" })
    }

    @Test
    fun shutterSpeed_positiveInfinity_doesNotLeakInfinityOrNanIntoReport() {
        val exif = ExifData(shutterSpeed = Double.POSITIVE_INFINITY, focalLength = 50.0, focalLength35mm = 50.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 10.0)

        val report = engine.evaluate(exif, scan)

        assertFalse("Risk must not be NaN", report.cameraShakeRisk.isNaN())
        assertFalse("Risk must not be Infinite", report.cameraShakeRisk.isInfinite())
        assertTrue("Risk must be clamped to [0, 1]", report.cameraShakeRisk in 0.0f..1.0f)
    }

    @Test
    fun shutterSpeed_nan_handledGracefullyWithoutCrash() {
        val exif = ExifData(shutterSpeed = Double.NaN, focalLength = 50.0, focalLength35mm = 50.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 10.0)

        val report = engine.evaluate(exif, scan)

        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        assertFalse("Risk must not be NaN", report.cameraShakeRisk.isNaN())
    }

    @Test
    fun shutterSpeed_extremelyFast_1_8000s_zeroShakeRisk() {
        val exif = ExifData(shutterSpeed = 1.0 / 8000.0, focalLength = 50.0, focalLength35mm = 50.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 80.0)

        val report = engine.evaluate(exif, scan)

        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        assertTrue(report.tips.none { it.category == "Shutter Speed" })
    }

    @Test
    fun shutterSpeed_longExposure_30s_clampedRiskAndValidTip() {
        val exif = ExifData(shutterSpeed = 30.0, focalLength = 50.0, focalLength35mm = 50.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 15.0)

        val report = engine.evaluate(exif, scan)

        assertTrue(report.cameraShakeRisk in 0.7f..1.0f)
        val tip = report.tips.find { it.category == "Shutter Speed" }
        assertNotNull(tip)
        assertEquals(TipSeverity.CRITICAL, tip!!.severity)
    }

    // =========================================================================
    // 2. Adversarial & Extreme Focal Lengths
    // =========================================================================

    @Test
    fun focalLength_zero_nullSafeShutterSpeedWithoutDivisionByZero() {
        val exif = ExifData(shutterSpeed = 0.01, focalLength = 0.0, focalLength35mm = 0.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 50.0)

        val report = engine.evaluate(exif, scan)

        assertNull(report.safeShutterSpeed)
        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
    }

    @Test
    fun focalLength_negative_nullSafeShutterSpeed() {
        val exif = ExifData(shutterSpeed = 0.01, focalLength = -50.0, focalLength35mm = -50.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 50.0)

        val report = engine.evaluate(exif, scan)

        assertNull(report.safeShutterSpeed)
        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
    }

    @Test
    fun focalLength_infinite_doesNotProduceZeroDivision() {
        val exif = ExifData(shutterSpeed = 0.01, focalLength = Double.POSITIVE_INFINITY, focalLength35mm = Double.POSITIVE_INFINITY)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 50.0)

        val report = engine.evaluate(exif, scan)

        assertFalse("Risk must not be NaN", report.cameraShakeRisk.isNaN())
        assertTrue("Risk must be valid float", report.cameraShakeRisk in 0.0f..1.0f)
    }

    @Test
    fun focalLength_ultraWide_8mm_safeSpeedCalculation() {
        // 8mm -> 1/8s = 0.125s safe threshold
        val exif = ExifData(shutterSpeed = 0.05, focalLength = 8.0, focalLength35mm = 8.0) // 1/20s (faster than 1/8s)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 75.0)

        val report = engine.evaluate(exif, scan)

        assertEquals(0.125, report.safeShutterSpeed!!, 1e-4)
        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
    }

    @Test
    fun focalLength_extremeTelephoto_800mm_requiresFastShutter() {
        // 800mm -> 1/800s = 0.00125s safe threshold
        // At 1/250s (0.004s), shutter is ~3.2x slower than safe threshold
        val exif = ExifData(shutterSpeed = 0.004, focalLength = 800.0, focalLength35mm = 800.0)
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 15.0)

        val report = engine.evaluate(exif, scan)

        assertEquals(0.00125, report.safeShutterSpeed!!, 1e-5)
        assertTrue(report.cameraShakeRisk >= 0.7f)
        val tip = report.tips.find { it.category == "Shutter Speed" }
        assertNotNull(tip)
        assertEquals(TipSeverity.CRITICAL, tip!!.severity)
    }

    // =========================================================================
    // 3. Adversarial & Extreme Apertures
    // =========================================================================

    @Test
    fun aperture_zeroOrNegative_doesNotCrash() {
        val exifZero = ExifData(aperture = 0.0)
        val reportZero = engine.evaluate(exifZero, null)
        assertNotNull(reportZero)

        val exifNeg = ExifData(aperture = -2.8)
        val reportNeg = engine.evaluate(exifNeg, null)
        assertNotNull(reportNeg)
    }

    @Test
    fun aperture_nanOrInfinite_doesNotCrash() {
        val exifNan = ExifData(aperture = Double.NaN)
        val reportNan = engine.evaluate(exifNan, null)
        assertNotNull(reportNan)

        val exifInf = ExifData(aperture = Double.POSITIVE_INFINITY)
        val reportInf = engine.evaluate(exifInf, null)
        assertNotNull(reportInf)
    }

    @Test
    fun aperture_diffraction_f32_and_f64() {
        val exifF32 = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 50.0, focalLength35mm = 50.0, aperture = 32.0)
        val reportF32 = engine.evaluate(exifF32, null)
        val tip = reportF32.tips.find { it.category == "Optics" && it.title.contains("Diffraction") }
        assertNotNull(tip)
        assertEquals(TipSeverity.WARNING, tip!!.severity)
    }

    // =========================================================================
    // 4. Adversarial & Extreme ISOs
    // =========================================================================

    @Test
    fun iso_ultraHigh_102400_and_409600_triggersCriticalNoiseWarningWhenNoisePresent() {
        val exif102k = ExifData(iso = 102400, aperture = 2.8)
        val scan1 = ScanResult(filePath = "test.jpg", noiseLevel = 25.0)
        val report1 = engine.evaluate(exif102k, scan1)
        val tip1 = report1.tips.find { it.category == "Sensor Noise" }
        assertNotNull("Expected sensor noise tip for ISO 102400", tip1)
        assertEquals(TipSeverity.CRITICAL, tip1!!.severity)

        val exif409k = ExifData(iso = 409600, aperture = 2.8)
        val scan2 = ScanResult(filePath = "test.jpg", noiseLevel = 18.0)
        val report2 = engine.evaluate(exif409k, scan2)
        val tip2 = report2.tips.find { it.category == "Sensor Noise" }
        assertNotNull("Expected sensor noise tip for ISO 409600", tip2)
        assertEquals(TipSeverity.CRITICAL, tip2!!.severity)
    }

    @Test
    fun iso_base_50_and_100_noNoiseWarningEvenIfNoiseSensorIsHigh() {
        // Base ISO: noise is not sensor-amplification grain
        val exifIso50 = ExifData(iso = 50, aperture = 2.8)
        val scan = ScanResult(filePath = "test.jpg", noiseLevel = 25.0)

        val reportIso50 = engine.evaluate(exifIso50, scan)
        assertTrue(reportIso50.tips.none { it.category == "Sensor Noise" })

        val exifIso100 = ExifData(iso = 100, aperture = 2.8)
        val reportIso100 = engine.evaluate(exifIso100, scan)
        assertTrue(reportIso100.tips.none { it.category == "Sensor Noise" })
    }

    @Test
    fun iso_zeroOrNegative_noNoiseWarningAndNoCrash() {
        val exifZero = ExifData(iso = 0, aperture = 2.8)
        val scan = ScanResult(filePath = "test.jpg", noiseLevel = 25.0)
        val reportZero = engine.evaluate(exifZero, scan)
        assertTrue(reportZero.tips.none { it.category == "Sensor Noise" })

        val exifNeg = ExifData(iso = -100, aperture = 2.8)
        val reportNeg = engine.evaluate(exifNeg, scan)
        assertTrue(reportNeg.tips.none { it.category == "Sensor Noise" })
    }

    @Test
    fun iso_noiseCritiqueConsistency_higherNoiseShouldBeAtLeastAsSevereAsLowerNoise() {
        // Test that noiseLevel = 20.0 is at least as severe as noiseLevel = 8.0 at ISO 3200
        val exif = ExifData(iso = 3200, aperture = 2.8)
        val scanLower = ScanResult(filePath = "test.jpg", noiseLevel = 8.0)
        val reportLower = engine.evaluate(exif, scanLower)
        val tipLower = reportLower.tips.find { it.category == "Sensor Noise" }

        val scanHigher = ScanResult(filePath = "test.jpg", noiseLevel = 20.0)
        val reportHigher = engine.evaluate(exif, scanHigher)
        val tipHigher = reportHigher.tips.find { it.category == "Sensor Noise" }

        assertNotNull("Tip for noise=8.0 must exist", tipLower)
        assertNotNull("Tip for noise=20.0 must exist", tipHigher)

        // Monotonicity property: higher noise should never have a lower severity!
        assertTrue(
            "Noise 20.0 severity (${tipHigher!!.severity}) must be >= Noise 8.0 severity (${tipLower!!.severity})",
            tipHigher.severity >= tipLower.severity
        )
    }

    // =========================================================================
    // 5. Clipping Scale Normalization & Boundary Conditions
    // =========================================================================

    @Test
    fun clipping_percentageScale_subOnePercent_shouldNotTriggerFalseBlownHighlights() {
        // In real photos from ClippingAnalyzer, highlightClipping = 0.5 means 0.5% (very minor, < 2.0% threshold).
        // It must NOT be scaled up to 50.0% and trigger a false-positive blown highlight warning.
        val scan = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 0.5, // 0.5% highlight clipping (percentage scale)
            shadowClipping = 0.1     // 0.1% shadow clipping
        )

        val report = engine.evaluate(null, scan)

        // If the engine incorrectly treats 0.5 as a fraction (0.5 = 50%), this assertion will reveal the bug.
        val highlightTip = report.tips.find { it.category == "Exposure" && it.title.contains("Blown Highlights") }
        val drTip = report.tips.find { it.category == "Dynamic Range" }

        assertNull("0.5% highlight clipping must NOT trigger blown highlights warning (threshold is 2.0%)", highlightTip)
        assertNull("0.5% highlights and 0.1% shadows must NOT trigger HDR dynamic range warning", drTip)
    }

    @Test
    fun clipping_percentageScale_exactOnePercent_shouldNotTriggerFalseWarning() {
        // 1.0% highlight clipping is below the 2.0% threshold.
        val scan = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 1.0,
            shadowClipping = 0.0
        )

        val report = engine.evaluate(null, scan)

        val highlightTip = report.tips.find { it.category == "Exposure" && it.title.contains("Blown Highlights") }
        assertNull("1.0% highlight clipping must NOT trigger blown highlights warning", highlightTip)
    }

    @Test
    fun clipping_negativeOrOver100_doesNotCrash() {
        val scanNeg = ScanResult(
            filePath = "test.jpg",
            highlightClipping = -5.0,
            shadowClipping = -10.0
        )
        val reportNeg = engine.evaluate(null, scanNeg)
        assertNotNull(reportNeg)

        val scanOver = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 150.0,
            shadowClipping = 200.0
        )
        val reportOver = engine.evaluate(null, scanOver)
        assertNotNull(reportOver)
    }

    @Test
    fun clipping_nanOrInfinite_doesNotCrash() {
        val scanNan = ScanResult(
            filePath = "test.jpg",
            highlightClipping = Double.NaN,
            shadowClipping = Double.NaN
        )
        val reportNan = engine.evaluate(null, scanNan)
        assertNotNull(reportNan)

        val scanInf = ScanResult(
            filePath = "test.jpg",
            highlightClipping = Double.POSITIVE_INFINITY,
            shadowClipping = Double.POSITIVE_INFINITY
        )
        val reportInf = engine.evaluate(null, scanInf)
        assertNotNull(reportInf)
    }

    // =========================================================================
    // 6. Complete Null EXIF and ScanResult
    // =========================================================================

    @Test
    fun completeNulls_allFieldsNull_returnsSafeEmptyReport() {
        val report = engine.evaluate(null, null)

        assertTrue(report.tips.isEmpty())
        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        assertNull(report.safeShutterSpeed)
        assertNull(report.recommendedEvCompensation)
    }

    @Test
    fun scanResultWithNullScores_returnsSafeReport() {
        val scan = ScanResult(
            filePath = "empty.jpg",
            sharpnessScore = null,
            noiseLevel = null,
            highlightClipping = null,
            shadowClipping = null,
            aestheticScore = null
        )
        val report = engine.evaluate(null, scan)

        assertTrue(report.tips.isEmpty())
        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        assertNull(report.safeShutterSpeed)
        assertNull(report.recommendedEvCompensation)
    }
}
