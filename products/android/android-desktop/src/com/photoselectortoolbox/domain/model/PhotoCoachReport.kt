package com.photoselectortoolbox.domain.model

import com.photoselectortoolbox.domain.text.UiText

/**
 * Severity level of an educational photo coaching tip.
 */
enum class TipSeverity {
    INFO,
    WARNING,
    CRITICAL
}

/**
 * An actionable educational photography critique item.
 *
 * @property category Category of critique (e.g. "Camera Shake", "Sensor Noise", "Exposure", "Optics").
 * @property title Concise heading summarizing the issue or technique.
 * @property description Detailed explanation of the detected exposure condition.
 * @property recommendation Actionable photography technique advice to remedy or improve the capture.
 * @property severity Severity rating indicating urgency of the critique.
 */
data class CoachingTip(
    val category: UiText,
    val title: UiText,
    val description: UiText,
    val recommendation: UiText,
    val severity: TipSeverity
)

/**
 * Complete evaluation report produced by the Photo Coach engine.
 *
 * @property tips List of educational photography critiques and recommendations.
 * @property cameraShakeRisk Estimated risk of motion blur / camera shake in range [0.0, 1.0].
 * @property safeShutterSpeed Calculated reciprocal rule safe shutter speed threshold in seconds (e.g. 1/125s -> 0.008), or null if unknown.
 * @property recommendedEvCompensation Suggested exposure compensation in EV (e.g. -0.7 for blown highlights, +0.7 for crushed shadows), or null if no adjustment is recommended.
 */
data class PhotoCoachReport(
    val tips: List<CoachingTip>,
    val cameraShakeRisk: Float,
    val safeShutterSpeed: Double?,
    val recommendedEvCompensation: Double?
)
