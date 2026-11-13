package com.photoselectortoolbox.domain.analysis

import com.photoselectortoolbox.domain.learn.AdviceCatalog
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.CameraProfiler
import com.photoselectortoolbox.domain.learn.CoachFinding
import com.photoselectortoolbox.domain.learn.Control
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.model.CoachingTip
import com.photoselectortoolbox.domain.model.PhotoCoachReport
import com.photoselectortoolbox.domain.model.TipSeverity
import javax.inject.Inject
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text

/**
 * Educational exposure and technique analysis engine.
 *
 * Correlates EXIF metadata (shutter speed, aperture, ISO, focal length) with measured
 * image quality metrics (sharpness, sensor noise, highlight/shadow clipping) to provide
 * actionable photography coaching tips.
 */
interface PhotoCoachEngine {
    /**
     * Evaluates photograph metadata and optical metrics, producing an educational [PhotoCoachReport].
     *
     * @param exif EXIF metadata parsed from the image file, or null if unavailable.
     * @param scanResult Image quality analysis metrics (sharpness, noise, clipping), or null if unanalyzed.
     * @param isRawOrHasRawCompanion True if the image is a RAW file or has a paired RAW companion.
     * @param nonSpecularHighlightClipPct Optional highlight clipping percentage excluding small specular glints.
     * @param imageWidth Optional pixel width for focal-plane sensor width calculation.
     * @param imageHeight Optional pixel height for focal-plane sensor height calculation.
     * @return Complete evaluation report containing coaching tips and risk metrics.
     */
    fun evaluate(
        exif: ExifData?,
        scanResult: ScanResult?,
        isRawOrHasRawCompanion: Boolean = false,
        nonSpecularHighlightClipPct: Double? = null,
        imageWidth: Int? = null,
        imageHeight: Int? = null,
    ): PhotoCoachReport
}

/**
 * Pure Kotlin implementation of [PhotoCoachEngine] with zero Android/OpenCV dependencies.
 *
 * Implements genuine photographic heuristics:
 * 1. Reciprocal Rule ($1 / f_{\text{eff}}$) hand-holding guideline vs measured Laplacian sharpness,
 *    gated strictly on a verified 35mm-equivalent focal length or focal-plane sensor resolution.
 * 2. Sensor noise & high ISO critique with aperture/shutter mitigation advice.
 * 3. Dynamic range clipping with RAW headroom awareness, specular-highlight override, and consistent EV direction.
 * 4. Optical diffraction warnings scaled by crop factor ($N_{\text{eq}} = N \cdot C$), only for cameras with an aperture to set.
 */
class PhotoCoachEngineImpl @Inject constructor() : PhotoCoachEngine {

    companion object {
        private const val SHARPNESS_BLUR_THRESHOLD = 40.0
        private const val SHARPNESS_CRITICAL_BLUR = 20.0

        private const val ISO_ELEVATED_THRESHOLD = 3200
        private const val ISO_CRITICAL_THRESHOLD = 12800

        private const val HIGHLIGHT_CLIPPING_THRESHOLD_PCT = 2.0
        private const val HIGHLIGHT_CLIPPING_SEVERE_PCT = 5.0
        private const val SHADOW_CLIPPING_THRESHOLD_PCT = 5.0

        private const val APERTURE_DIFFRACTION_THRESHOLD = 16.0
        private const val APERTURE_DIFFRACTION_CRITICAL = 22.0
        private const val APERTURE_WIDE_OPEN_THRESHOLD = 1.8

        private const val FULL_FRAME_WIDTH_MM = 36.0
        private const val DEFAULT_REFERENCE_WIDTH_PX = 6000.0

        /**
         * Computes sensor crop factor $C$ relative to 35mm full-frame (36x24mm).
         * Returns null when neither `focalLength35mm` nor focal-plane resolution tags allow derivation.
         */
        fun computeCropFactor(
            exif: ExifData?,
            imageWidth: Int? = null,
            @Suppress("UNUSED_PARAMETER") imageHeight: Int? = null,
        ): Double? {
            if (exif == null) return null
            val f35 = exif.focalLength35mm
            val fPhys = exif.focalLength
            if (f35 != null && f35.isFinite() && f35 > 0.0 &&
                fPhys != null && fPhys.isFinite() && fPhys > 0.0
            ) {
                val c = f35 / fPhys
                if (c.isFinite() && c > 0.0) return c
            }

            val fpX = exif.focalPlaneXResolution
            val unit = exif.focalPlaneResolutionUnit
            if (fpX != null && fpX.isFinite() && fpX > 0.0 && unit != null) {
                val unitMm = focalPlaneUnitToMm(unit)
                if (unitMm != null) {
                    val widthPx = (imageWidth?.takeIf { it > 0 }?.toDouble()) ?: DEFAULT_REFERENCE_WIDTH_PX
                    val sensorWidthMm = (widthPx / fpX) * unitMm
                    if (sensorWidthMm.isFinite() && sensorWidthMm > 0.0) {
                        val c = FULL_FRAME_WIDTH_MM / sensorWidthMm
                        if (c in 0.5..8.0) return c
                    }
                }
            }
            return null
        }

        /**
         * Computes 35mm-equivalent effective focal length $f_{\text{eff}}$.
         * Strictly returns null when neither `focalLength35mm` nor focal-plane resolution is present.
         */
        fun computeEffectiveFocalLength(
            exif: ExifData?,
            imageWidth: Int? = null,
            imageHeight: Int? = null,
        ): Double? {
            if (exif == null) return null
            val f35 = exif.focalLength35mm
            if (f35 != null && f35.isFinite() && f35 > 0.0) {
                return f35
            }
            val fPhys = exif.focalLength
            if (fPhys != null && fPhys.isFinite() && fPhys > 0.0) {
                val c = computeCropFactor(exif, imageWidth, imageHeight)
                if (c != null && c.isFinite() && c > 0.0) {
                    return fPhys * c
                }
            }
            return null
        }

        fun focalPlaneUnitToMm(unit: Int): Double? = when (unit) {
            2 -> 25.4
            3 -> 10.0
            4 -> 1.0
            5 -> 0.001
            else -> null
        }
    }

    override fun evaluate(
        exif: ExifData?,
        scanResult: ScanResult?,
        isRawOrHasRawCompanion: Boolean,
        nonSpecularHighlightClipPct: Double?,
        imageWidth: Int?,
        imageHeight: Int?,
    ): PhotoCoachReport {
        val resolvedExif = exif ?: scanResult?.exifData
        val tips = mutableListOf<CoachingTip>()

        val cropFactor = computeCropFactor(resolvedExif, imageWidth, imageHeight)
        val effectiveFocalLength = computeEffectiveFocalLength(resolvedExif, imageWidth, imageHeight)
        // Which advice fits this camera (shoot debrief SD-2b, C2): a recommendation never names a
        // control its class lacks, and an unknown camera gets image-based remarks only.
        val profile = CameraProfiler.profile(resolvedExif, imageWidth)
        val hasAperture = Control.APERTURE in profile.controls
        // Drones fly on a gimbal and action cams stabilise electronically: no hand-holding remark.
        val handHeld = profile.cameraClass != CameraClass.DRONE && profile.cameraClass != CameraClass.ACTION

        // 1. Reciprocal Hand-Holding Guideline (1 / f_eff)
        val safeShutterSpeed = if (effectiveFocalLength != null && effectiveFocalLength.isFinite() && effectiveFocalLength > 0.0) {
            1.0 / effectiveFocalLength
        } else {
            null
        }

        var cameraShakeRisk = 0.0f
        val shutterSpeed = resolvedExif?.shutterSpeed
        val sharpness = scanResult?.sharpnessScore

        if (handHeld && shutterSpeed != null && shutterSpeed.isFinite() && shutterSpeed > 0.0 &&
            safeShutterSpeed != null && effectiveFocalLength != null
        ) {
            val ratio = shutterSpeed / safeShutterSpeed
            val formattedActual = ExifFormatter.shutterSpeed(shutterSpeed)
            val formattedSafe = ExifFormatter.shutterSpeed(safeShutterSpeed)
            val focalLengthStr = ExifFormatter.focalLength(effectiveFocalLength)

            if (ratio > 1.0001) {
                // Slower than the 1/f_eff hand-holding guideline
                if (sharpness != null && sharpness.isFinite()) {
                    if (sharpness < SHARPNESS_BLUR_THRESHOLD) {
                        val isCritical = ratio >= 2.5 || sharpness < SHARPNESS_CRITICAL_BLUR
                        val severity = if (isCritical) TipSeverity.CRITICAL else TipSeverity.WARNING
                        cameraShakeRisk = if (isCritical) {
                            (0.7f + (ratio.toFloat() * 0.05f)).coerceIn(0.7f, 1.0f)
                        } else {
                            0.6f
                        }

                        tips.add(
                            CoachingTip(
                                category = text(R.string.learn_coach_category),
                                title = if (isCritical) {
                                    text(R.string.learn_coach_001)
                                } else {
                                    text(R.string.learn_coach_002)
                                },
                                description = text(R.string.learn_coach_description, formattedActual, formattedSafe, focalLengthStr, sharpness.toInt()),
                                recommendation = AdviceCatalog.coach(CoachFinding.SLOW_AND_SOFT, profile, formattedSafe) ?: UiText.Empty,
                                severity = severity
                            )
                        )
                    } else {
                        // Sharpness maintained despite slow shutter speed -> Effective stabilization
                        cameraShakeRisk = 0.1f
                        tips.add(
                            CoachingTip(
                                category = text(R.string.learn_coach_category_stabilization),
                                title = text(R.string.learn_coach_title),
                                description = text(R.string.learn_coach_description_2, sharpness.toInt(), formattedActual, formattedSafe, focalLengthStr),
                                recommendation = text(R.string.learn_coach_recommendation),
                                severity = TipSeverity.INFO
                            )
                        )
                    }
                } else {
                    cameraShakeRisk = (0.2f + (ratio.toFloat() * 0.1f)).coerceIn(0.3f, 0.7f)
                    tips.add(
                        CoachingTip(
                            category = text(R.string.learn_coach_category_2),
                            title = text(R.string.learn_coach_title_2),
                            description = text(R.string.learn_coach_description_3, formattedActual, formattedSafe, focalLengthStr),
                            recommendation = AdviceCatalog.coach(CoachFinding.SLOW_UNSCORED, profile, formattedSafe) ?: UiText.Empty,
                            severity = TipSeverity.WARNING
                        )
                    )
                }
            } else {
                // Shutter speed is at or faster than hand-holding guideline
                cameraShakeRisk = 0.0f
                if (sharpness != null && sharpness.isFinite() && sharpness < SHARPNESS_BLUR_THRESHOLD) {
                    tips.add(
                        CoachingTip(
                            category = text(R.string.learn_coach_category_focus),
                            title = text(R.string.learn_coach_title_3),
                            description = text(R.string.learn_coach_description_4, sharpness.toInt(), formattedActual),
                            recommendation = AdviceCatalog.coach(CoachFinding.SOFT_AT_FAST_SHUTTER, profile) ?: UiText.Empty,
                            severity = TipSeverity.INFO
                        )
                    )
                }
            }
        }

        // 2. Sensor Noise & ISO Critique
        val iso = resolvedExif?.iso
        val noise = scanResult?.noiseLevel

        if (iso != null && iso >= ISO_ELEVATED_THRESHOLD && noise != null && noise.isFinite()) {
            val isElevated = noise > 3.0
            if (isElevated) {
                val isCritical = iso >= ISO_CRITICAL_THRESHOLD || noise >= 25.0
                val severity = if (isCritical) TipSeverity.CRITICAL else TipSeverity.WARNING
                tips.add(
                    CoachingTip(
                        category = text(R.string.learn_coach_category_3),
                        title = if (isCritical) text(R.string.learn_coach_003) else text(R.string.learn_coach_004),
                        description = text(R.string.learn_coach_description_5, noise, iso),
                        recommendation = AdviceCatalog.coach(CoachFinding.HIGH_ISO_NOISE, profile) ?: UiText.Empty,
                        severity = severity
                    )
                )
            }
        }

        // 3. Dynamic Range & Clipping Critique
        val highlight = nonSpecularHighlightClipPct ?: scanResult?.highlightClipping
        val shadow = scanResult?.shadowClipping

        // ScanResult clipping values are percentage scale (0.0..100.0%) from ClippingAnalyzer.
        // Support fractional scale (0.0..1.0) only when unambiguous (e.g. legacy test values < 0.1).
        val hasFractionCandidate = (highlight != null && highlight.isFinite() && highlight in 0.015..0.095) ||
            (shadow != null && shadow.isFinite() && shadow in 0.015..0.095)
        val allSubTenth = (highlight == null || !highlight.isFinite() || (highlight >= 0.0 && highlight < 0.1)) &&
            (shadow == null || !shadow.isFinite() || (shadow >= 0.0 && shadow < 0.1))
        val isFractionalScale = nonSpecularHighlightClipPct == null && hasFractionCandidate && allSubTenth

        val highlightPct = when {
            highlight == null || !highlight.isFinite() -> null
            isFractionalScale -> highlight * 100.0
            else -> highlight
        }
        val shadowPct = when {
            shadow == null || !shadow.isFinite() -> null
            isFractionalScale -> shadow * 100.0
            else -> shadow
        }

        var recommendedEv: Double? = null

        if (highlightPct != null && shadowPct != null &&
            highlightPct > HIGHLIGHT_CLIPPING_THRESHOLD_PCT &&
            shadowPct > SHADOW_CLIPPING_THRESHOLD_PCT
        ) {
            val drDescription = if (isRawOrHasRawCompanion) {
                text(R.string.learn_coach_005)
            } else {
                text(R.string.learn_coach_006)
            }
            tips.add(
                CoachingTip(
                    category = text(R.string.learn_coach_category_4),
                    title = text(R.string.learn_coach_title_4),
                    description = drDescription,
                    recommendation = AdviceCatalog.coach(CoachFinding.SCENE_CONTRAST, profile) ?: UiText.Empty,
                    severity = TipSeverity.WARNING
                )
            )
            recommendedEv = null
        } else if (highlightPct != null && highlightPct > HIGHLIGHT_CLIPPING_THRESHOLD_PCT) {
            val severe = highlightPct > HIGHLIGHT_CLIPPING_SEVERE_PCT
            recommendedEv = if (severe) -1.0 else -0.7
            val highlightDescription = if (isRawOrHasRawCompanion) {
                text(R.string.learn_coach_007)
            } else {
                text(R.string.learn_coach_008)
            }
            tips.add(
                CoachingTip(
                    category = text(R.string.learn_coach_category_exposure),
                    title = text(R.string.learn_coach_title_5),
                    description = highlightDescription,
                    recommendation = AdviceCatalog.coach(CoachFinding.HIGHLIGHTS_CLIPPED, profile, ev = recommendedEv.toString()) ?: UiText.Empty,
                    severity = TipSeverity.WARNING
                )
            )
        } else if (shadowPct != null && shadowPct > SHADOW_CLIPPING_THRESHOLD_PCT) {
            recommendedEv = 0.7
            tips.add(
                CoachingTip(
                    category = text(R.string.learn_coach_category_exposure),
                    title = text(R.string.learn_coach_title_6),
                    description = text(R.string.learn_coach_description_6),
                    recommendation = AdviceCatalog.coach(CoachFinding.SHADOWS_CLIPPED, profile, ev = "+0.7") ?: UiText.Empty,
                    severity = TipSeverity.WARNING
                )
            )
        }

        // 4. Optical Sweet Spot & Diffraction Warnings (scaled by crop factor C; cameras with an aperture only)
        val aperture = resolvedExif?.aperture
        if (aperture != null && aperture.isFinite() && aperture > 0.0) {
            val effectiveCrop = cropFactor?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            val equivalentAperture = aperture * effectiveCrop
            if (hasAperture && equivalentAperture >= APERTURE_DIFFRACTION_THRESHOLD) {
                val isNarrow = equivalentAperture >= APERTURE_DIFFRACTION_CRITICAL
                tips.add(
                    CoachingTip(
                        category = text(R.string.learn_coach_category_optics),
                        title = text(R.string.learn_coach_title_7),
                        description = text(R.string.learn_coach_description_7, ExifFormatter.aperture(aperture)),
                        recommendation = text(R.string.learn_coach_recommendation_2),
                        severity = if (isNarrow) TipSeverity.WARNING else TipSeverity.INFO
                    )
                )
            } else if (hasAperture && aperture <= APERTURE_WIDE_OPEN_THRESHOLD &&
                sharpness != null && sharpness.isFinite() && sharpness < SHARPNESS_BLUR_THRESHOLD
            ) {
                tips.add(
                    CoachingTip(
                        category = text(R.string.learn_coach_category_optics),
                        title = text(R.string.learn_coach_title_8),
                        description = text(R.string.learn_coach_description_8, ExifFormatter.aperture(aperture)),
                        recommendation = text(R.string.learn_coach_recommendation_3),
                        severity = TipSeverity.INFO
                    )
                )
            }
        }

        return PhotoCoachReport(
            tips = tips,
            cameraShakeRisk = cameraShakeRisk,
            safeShutterSpeed = safeShutterSpeed,
            recommendedEvCompensation = recommendedEv
        )
    }
}
