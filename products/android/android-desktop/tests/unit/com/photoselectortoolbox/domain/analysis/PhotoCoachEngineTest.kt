package com.photoselectortoolbox.domain.analysis

import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.model.TipSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

@Requirement("REQ-AND-COACH.01")
class PhotoCoachEngineTest {

    private companion object {
        /**
         * A camera whose class is known (SD-2b): exposure-compensation and aperture advice is given
         * only to a camera that has those controls; an unknown camera gets image-based remarks.
         */
        val FULL_FRAME = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 50.0, focalLength35mm = 50.0)
    }


    private val engine = PhotoCoachEngineImpl()

    @Test
    fun fastShutterSpeed_noCameraShakeRisk() {
        // 50mm 35mm-eq focal length -> safe shutter speed = 1/50s (0.02s).
        // Actual shutter speed = 1/500s (0.002s), which is much faster than safe speed.
        val exif = ExifData(
            shutterSpeed = 0.002,
            focalLength = 50.0,
            focalLength35mm = 50.0,
            aperture = 2.8,
            iso = 100
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 75.0,
            noiseLevel = 1.0,
            highlightClipping = 0.0,
            shadowClipping = 0.0
        )

        val report = engine.evaluate(exif, scan)

        assertEquals(0.02, report.safeShutterSpeed!!, 1e-4)
        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        assertTrue(report.tips.none { it.category.en() == "Shutter Speed" })
    }

    @Test
    fun slowShutterSpeed_lowSharpness_cameraShakeWarningOrCritical() {
        // 100mm 35mm-eq focal length -> safe shutter speed = 1/100s (0.01s).
        // Actual shutter speed = 1/10s (0.1s) -> 10x slower than safe shutter speed.
        // Sharpness is low (18.0 < 20.0 critical threshold).
        val exif = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 100.0,
            shutterSpeed = 0.1,
            focalLength35mm = 100.0,
            aperture = 4.0,
            iso = 400
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 18.0
        )

        val report = engine.evaluate(exif, scan)

        assertEquals(0.01, report.safeShutterSpeed!!, 1e-4)
        assertTrue(report.cameraShakeRisk >= 0.7f)
        val shutterTip = report.tips.find { it.category.en() == "Shutter Speed" }
        assertNotNull("Expected shutter speed guideline tip", shutterTip)
        assertEquals(TipSeverity.CRITICAL, shutterTip!!.severity)
        assertEquals("Much Slower Than Hand-Holding Guideline", shutterTip.title.en())
        assertTrue(shutterTip.recommendation.en().contains("shutter speed", ignoreCase = true))
    }

    @Test
    fun slowShutterSpeed_moderateBlur_cameraShakeWarning() {
        // 50mm 35mm-eq focal length -> safe shutter speed = 0.02s (1/50s).
        // Actual shutter speed = 0.04s (1/25s, 2x slower).
        // Sharpness is 32.0 (below 40.0, but above 20.0 critical).
        val exif = ExifData(
            shutterSpeed = 0.04,
            focalLength = 50.0,
            focalLength35mm = 50.0
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 32.0
        )

        val report = engine.evaluate(exif, scan)

        assertEquals(0.02, report.safeShutterSpeed!!, 1e-4)
        assertEquals(0.6f, report.cameraShakeRisk, 0.01f)
        val shutterTip = report.tips.find { it.category.en() == "Shutter Speed" }
        assertNotNull("Expected shutter speed guideline tip", shutterTip)
        assertEquals(TipSeverity.WARNING, shutterTip!!.severity)
        assertEquals("Slower Than Hand-Holding Guideline", shutterTip.title.en())
    }

    @Test
    fun slowShutterSpeed_highSharpness_stabilizationInfoTip() {
        // 200mm focal length -> safe shutter speed = 1/200s (0.005s).
        // Actual shutter speed = 1/30s (0.0333s) -> much slower than safe speed.
        // But sharpness is high (78.0 >= 40.0) -> effective OIS/IBIS/tripod!
        val exif = ExifData(
            shutterSpeed = 0.0333,
            focalLength35mm = 200.0
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 78.0
        )

        val report = engine.evaluate(exif, scan)

        assertEquals(0.005, report.safeShutterSpeed!!, 1e-4)
        assertEquals(0.1f, report.cameraShakeRisk, 0.01f)
        val stabTip = report.tips.find { it.category.en() == "Stabilization" }
        assertNotNull("Expected stabilization tip", stabTip)
        assertEquals(TipSeverity.INFO, stabTip!!.severity)
        assertEquals("Sharp Below the Hand-Holding Guideline", stabTip.title.en())
        assertTrue(stabTip.recommendation.en().contains("stabilization", ignoreCase = true))
    }

    @Test
    fun fastShutterSpeed_lowSharpness_reportsMissedFocusRatherThanShake() {
        // 50mm -> safe shutter speed 0.02s. Actual 0.001s (1/1000s, fast).
        // Low sharpness (25.0) -> not slow shutter, but missed focus.
        val exif = ExifData(
            shutterSpeed = 0.001,
            focalLength = 50.0,
            focalLength35mm = 50.0
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 25.0
        )

        val report = engine.evaluate(exif, scan)

        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        val focusTip = report.tips.find { it.category.en() == "Focus" }
        assertNotNull("Expected focus tip", focusTip)
        assertEquals(TipSeverity.INFO, focusTip!!.severity)
        assertEquals("Low Detail at Fast Shutter Speed", focusTip.title.en())
        assertTrue(focusTip.description.en().contains("missed focus", ignoreCase = true))
    }

    @Test
    fun highIso_elevatedNoise_isoNoiseWarning() {
        val exif = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 50.0, focalLength35mm = 50.0,
            iso = 3200,
            aperture = 4.0,
            shutterSpeed = 0.005
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            noiseLevel = 18.0
        )

        val report = engine.evaluate(exif, scan)

        val noiseTip = report.tips.find { it.category.en() == "Sensor Noise" }
        assertNotNull("Expected sensor noise tip", noiseTip)
        assertEquals(TipSeverity.WARNING, noiseTip!!.severity)
        assertTrue(noiseTip.recommendation.en().contains("aperture", ignoreCase = true))
        assertTrue(noiseTip.recommendation.en().contains("ISO", ignoreCase = true))
    }

    @Test
    fun highIso_criticalNoise_isoNoiseCritical() {
        val exif = ExifData(
            iso = 12800,
            aperture = 2.8
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            noiseLevel = 22.0
        )

        val report = engine.evaluate(exif, scan)

        val noiseTip = report.tips.find { it.category.en() == "Sensor Noise" }
        assertNotNull("Expected sensor noise tip", noiseTip)
        assertEquals(TipSeverity.CRITICAL, noiseTip!!.severity)
    }

    @Test
    fun cleanNoise_highIso_noNoiseWarning() {
        val exif = ExifData(
            iso = 3200,
            aperture = 2.8
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            noiseLevel = 1.5 // clean sensor noise
        )

        val report = engine.evaluate(exif, scan)

        assertTrue(report.tips.none { it.category.en() == "Sensor Noise" })
    }

    @Test
    fun blownHighlights_negativeEvCompensationTip() {
        val scan = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 3.5, // 3.5% blown highlights
            shadowClipping = 0.2
        )

        val report = engine.evaluate(FULL_FRAME, scan)

        assertEquals(-0.7, report.recommendedEvCompensation!!, 0.01)
        val expTip = report.tips.find { it.category.en() == "Exposure" && it.title.en().contains("Blown Highlights") }
        assertNotNull("Expected blown highlights tip", expTip)
        assertEquals(TipSeverity.WARNING, expTip!!.severity)
        assertTrue(expTip.recommendation.en().contains("negative EV", ignoreCase = true))
    }

    @Test
    fun severeBlownHighlights_largerNegativeEvCompensation() {
        val scan = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 8.5, // > 5.0% severe
            shadowClipping = 0.5
        )

        val report = engine.evaluate(null, scan)

        assertEquals(-1.0, report.recommendedEvCompensation!!, 0.01)
    }

    @Test
    fun crushedShadows_positiveEvCompensationTip() {
        val scan = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 0.3,
            shadowClipping = 7.2 // 7.2% crushed shadows
        )

        val report = engine.evaluate(FULL_FRAME, scan)

        assertEquals(0.7, report.recommendedEvCompensation!!, 0.01)
        val expTip = report.tips.find { it.category.en() == "Exposure" && it.title.en().contains("Crushed Shadows") }
        assertNotNull("Expected crushed shadows tip", expTip)
        assertEquals(TipSeverity.WARNING, expTip!!.severity)
        assertTrue(expTip.recommendation.en().contains("positive EV", ignoreCase = true))
    }

    @Test
    fun bothBlownHighlightsAndCrushedShadows_hdrBracketingTip() {
        val scan = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 4.2,
            shadowClipping = 8.0
        )

        val report = engine.evaluate(FULL_FRAME, scan)

        // Cannot fix both with a single exposure offset
        assertNull(report.recommendedEvCompensation)
        val drTip = report.tips.find { it.category.en() == "Dynamic Range" }
        assertNotNull("Expected dynamic range bracketing tip", drTip)
        assertEquals(TipSeverity.WARNING, drTip!!.severity)
        assertTrue(drTip.recommendation.en().contains("HDR", ignoreCase = true))
    }

    @Test
    fun diffractionWarning_f22() {
        val exif = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 50.0, focalLength35mm = 50.0, aperture = 22.0)
        val scan = ScanResult(filePath = "test.jpg")

        val report = engine.evaluate(exif, scan)

        val diffTip = report.tips.find { it.category.en() == "Optics" && it.title.en().contains("Diffraction") }
        assertNotNull("Expected diffraction tip", diffTip)
        assertEquals(TipSeverity.WARNING, diffTip!!.severity)
        assertTrue(diffTip.recommendation.en().contains("sweet spot", ignoreCase = true))
    }

    @Test
    fun diffractionInfo_f16() {
        val exif = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 50.0, focalLength35mm = 50.0, aperture = 16.0)

        val report = engine.evaluate(exif, null)

        val diffTip = report.tips.find { it.category.en() == "Optics" && it.title.en().contains("Diffraction") }
        assertNotNull("Expected diffraction tip at f/16", diffTip)
        assertEquals(TipSeverity.INFO, diffTip!!.severity)
    }

    @Test
    fun wideOpenSoftnessTip_f14_lowSharpness() {
        val exif = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 50.0, focalLength35mm = 50.0, aperture = 1.4)
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 28.0 // soft image
        )

        val report = engine.evaluate(exif, scan)

        val wideOpenTip = report.tips.find { it.category.en() == "Optics" && it.title.en().contains("Wide-Open") }
        assertNotNull("Expected wide-open softness tip", wideOpenTip)
        assertEquals(TipSeverity.INFO, wideOpenTip!!.severity)
        assertTrue(wideOpenTip.recommendation.en().contains("Stop down", ignoreCase = true))
    }

    @Test
    fun wideOpenSharp_noWideOpenTip() {
        val exif = ExifData(aperture = 1.4)
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 80.0 // crisp sharpness despite f/1.4
        )

        val report = engine.evaluate(exif, scan)

        assertTrue(report.tips.none { it.title.en().contains("Wide-Open") })
    }

    @Test
    fun nullOrMissingMetadata_safeDefaultReportWithoutCrashing() {
        val reportFromNulls = engine.evaluate(null, null)
        assertTrue(reportFromNulls.tips.isEmpty())
        assertEquals(0.0f, reportFromNulls.cameraShakeRisk, 0.001f)
        assertNull(reportFromNulls.safeShutterSpeed)
        assertNull(reportFromNulls.recommendedEvCompensation)

        val emptyExif = ExifData()
        val emptyScan = ScanResult(filePath = "test.jpg")
        val reportFromEmpty = engine.evaluate(emptyExif, emptyScan)
        assertTrue(reportFromEmpty.tips.isEmpty())
        assertEquals(0.0f, reportFromEmpty.cameraShakeRisk, 0.001f)
        assertNull(reportFromEmpty.safeShutterSpeed)
        assertNull(reportFromEmpty.recommendedEvCompensation)
    }

    @Test
    fun scanResultExifFallback_worksWhenExifParamIsNull() {
        val scan = ScanResult(
            filePath = "test.jpg",
            exifData = ExifData(
                iso = 6400,
                aperture = 4.0
            ),
            noiseLevel = 20.0
        )

        val report = engine.evaluate(null, scan)

        val noiseTip = report.tips.find { it.category.en() == "Sensor Noise" }
        assertNotNull("Expected noise tip from scanResult.exifData", noiseTip)
    }

    @Test
    fun fractionalClipping_handlesFractionsCorrectly() {
        // Fractional scale: 0.035 = 3.5%, 0.001 = 0.1%
        val scan = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 0.035,
            shadowClipping = 0.001
        )
        val report = engine.evaluate(null, scan)
        assertEquals(-0.7, report.recommendedEvCompensation!!, 0.01)

        // Fractional scale: shadow clipping 0.08 = 8%
        val scanShadow = ScanResult(
            filePath = "test.jpg",
            highlightClipping = 0.001,
            shadowClipping = 0.08
        )
        val reportShadow = engine.evaluate(null, scanShadow)
        assertEquals(0.7, reportShadow.recommendedEvCompensation!!, 0.01)
    }

    @Test
    fun missing35mmAndFocalPlaneResolution_strictlyEmitsNoHandHoldingTipAndNullSafeSpeed() {
        val exif = ExifData(
            shutterSpeed = 0.1,
            focalLength = 50.0,
            focalLength35mm = null,
            focalPlaneXResolution = null,
            focalPlaneResolutionUnit = null
        )
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 12.0)

        val report = engine.evaluate(exif, scan)

        assertNull(report.safeShutterSpeed)
        assertEquals(0.0f, report.cameraShakeRisk, 0.001f)
        assertTrue(report.tips.none { it.category.en() == "Shutter Speed" || it.category.en() == "Stabilization" })
    }

    @Test
    fun focalPlaneResolutionFallback_derivesCropFactorAndEffectiveFocalLength() {
        // APS-C 24mm width at 6000px -> 250 px/mm (unit = 4 -> mm) -> cropFactor = 36 / 24 = 1.5
        // focalLength = 50mm -> f_eff = 75mm -> safeShutterSpeed = 1/75s = 0.01333s
        val exif = ExifData(
            shutterSpeed = 0.05,
            focalLength = 50.0,
            focalLength35mm = null,
            focalPlaneXResolution = 250.0,
            focalPlaneResolutionUnit = 4
        )
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 15.0)

        val report = engine.evaluate(exif, scan, imageWidth = 6000, imageHeight = 4000)

        assertEquals(1.0 / 75.0, report.safeShutterSpeed!!, 1e-4)
        assertTrue(report.cameraShakeRisk >= 0.7f)
        assertNotNull(report.tips.find { it.category.en() == "Shutter Speed" })
    }

    @Test
    fun noCoachingTipContainsWordShake() {
        val exif = ExifData(
            shutterSpeed = 0.2,
            focalLength = 50.0,
            focalLength35mm = 75.0,
            aperture = 1.4,
            iso = 12800
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 12.0,
            noiseLevel = 30.0,
            highlightClipping = 6.0,
            shadowClipping = 1.0
        )
        val report = engine.evaluate(exif, scan)
        for (tip in report.tips) {
            assertTrue("Category must not contain 'shake'", !tip.category.en().contains("shake", ignoreCase = true))
            assertTrue("Title must not contain 'shake'", !tip.title.en().contains("shake", ignoreCase = true))
            assertTrue("Description must not contain 'shake'", !tip.description.en().contains("shake", ignoreCase = true))
            assertTrue("Recommendation must not contain 'shake'", !tip.recommendation.en().contains("shake", ignoreCase = true))
        }
    }

    @Test
    fun phoneSensorGuard_suppressesWideOpenShallowDofTip() {
        val phoneExif = ExifData(
            focalLength = 6.86,
            focalLength35mm = 24.0,
            aperture = 1.78,
            model = "iPhone 15 Pro"
        )
        val scan = ScanResult(filePath = "test.jpg", sharpnessScore = 25.0)

        val report = engine.evaluate(phoneExif, scan)

        assertTrue(report.tips.none { it.title.en().contains("Wide-Open") || it.title.en().contains("Shallow Depth of Field") })
    }

    @Test
    fun cropScaledDiffraction_apsC_triggersAtF11AndF16() {
        // APS-C C = 1.5: f/11 * 1.5 = 16.5 (>= 16.0 INFO), f/16 * 1.5 = 24.0 (>= 22.0 WARNING)
        val exifF11 = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 35.0, focalLength35mm = 52.5, aperture = 11.0)
        val reportF11 = engine.evaluate(exifF11, null)
        val tipF11 = reportF11.tips.find { it.category.en() == "Optics" && it.title.en().contains("Diffraction") }
        assertNotNull(tipF11)
        assertEquals(TipSeverity.INFO, tipF11!!.severity)

        val exifF16 = ExifData(make = "SONY", model = "ILCE-7M4", focalLength = 35.0, focalLength35mm = 52.5, aperture = 16.0)
        val reportF16 = engine.evaluate(exifF16, null)
        val tipF16 = reportF16.tips.find { it.category.en() == "Optics" && it.title.en().contains("Diffraction") }
        assertNotNull(tipF16)
        assertEquals(TipSeverity.WARNING, tipF16!!.severity)
    }

    @Test
    fun rawCompanionHighlightClipping_mentionsRecoverableHeadroomAndNeverPermanentlyUnrecoverable() {
        val scan = ScanResult(filePath = "test.arw", highlightClipping = 4.0, shadowClipping = 0.2)
        val reportRaw = engine.evaluate(null, scan, isRawOrHasRawCompanion = true)
        val expTip = reportRaw.tips.find { it.category.en() == "Exposure" }
        assertNotNull(expTip)
        assertTrue(expTip!!.description.en().contains("1–2 EV", ignoreCase = true))
        assertTrue(!expTip.description.en().contains("permanently unrecoverable", ignoreCase = true))
        assertTrue(!expTip.recommendation.en().contains("ETTR", ignoreCase = true))
    }

    @Test
    fun nonSpecularHighlightOverride_suppressesFalseBlownHighlightTipFromGlints() {
        // Raw highlightClipping is 3.5% due to tiny specular chrome glints, but nonSpecularHighlightClipPct is 0.4%
        val scan = ScanResult(filePath = "test.jpg", highlightClipping = 3.5, shadowClipping = 0.1)
        val report = engine.evaluate(null, scan, nonSpecularHighlightClipPct = 0.4)
        assertTrue(report.tips.none { it.title.en().contains("Blown Highlights") })
    }
}
