package com.photoselectortoolbox.ui.inspect

import com.photoselectortoolbox.domain.model.SpiralOrientation
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.learn.SceneComparator
import com.photoselectortoolbox.domain.learn.DebriefMetric
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.plural
import com.photoselectortoolbox.domain.text.text

/**
 * The plain-language words of the Learn screen's Photo Coach and guide bar, kept out of the
 * composables so a JVM test can hold them free of algorithm names (usability panel 2026-10-24:
 * "1/f_eff Guideline", "Phi", "Flip (TL)" read as data, not explanation).
 *
 * The method behind a figure is the lesson's business, not the label's.
 */
object LearnText {

    /** The guide toggles, in the order the bar shows them. */
    val GUIDE_THIRDS: UiText = text(R.string.chip_guide_thirds)
    val GUIDE_GOLDEN_RATIO: UiText = text(R.string.chip_guide_golden_ratio)
    val GUIDE_SPIRAL: UiText = text(R.string.chip_guide_spiral)
    val GUIDE_FLIP_SPIRAL: UiText = text(R.string.chip_guide_flip_spiral)
    val GUIDE_DIAGONALS: UiText = text(R.string.chip_guide_diagonals)
    val GUIDE_HORIZON: UiText = text(R.string.chip_guide_horizon)

    /** The spoken name of the spiral flip, which says where the spiral now turns. */
    fun flipSpiralDescription(orientation: SpiralOrientation): UiText = text(
        when (orientation) {
            SpiralOrientation.TOP_LEFT -> R.string.cd_flip_spiral_top_left
            SpiralOrientation.TOP_RIGHT -> R.string.cd_flip_spiral_top_right
            SpiralOrientation.BOTTOM_RIGHT -> R.string.cd_flip_spiral_bottom_right
            SpiralOrientation.BOTTOM_LEFT -> R.string.cd_flip_spiral_bottom_left
        },
    )

    /** The coach's blur-risk badge: the level in words, the percentage as its figure. */
    fun blurRisk(risk: Float): UiText {
        val pct = (risk * 100).toInt()
        val level = when {
            risk >= 0.7f -> R.string.label_blur_risk_high
            risk >= 0.3f -> R.string.label_blur_risk_moderate
            else -> R.string.label_blur_risk_low
        }
        return text(level, pct)
    }

    /**
     * The shutter line: whether the photo was fast enough to hold by hand, as a sentence with the
     * two speeds in it. [safeSeconds] is the slowest speed the hand-holding guideline allows for the
     * lens, [actualSeconds] the photo's. Null when the guideline is unknown.
     */
    fun shutterLine(safeSeconds: Double?, actualSeconds: Double?): UiText? {
        val safe = safeSeconds?.takeIf { it > 0.0 } ?: return null
        val safeText = ExifFormatter.shutterSpeed(safe)
        val actual = actualSeconds?.takeIf { it > 0.0 } ?: return text(R.string.learn_shutter_slowest, safeText)
        val actualText = ExifFormatter.shutterSpeed(actual)
        return if (actual <= safe) {
            text(R.string.learn_shutter_fast_enough, actualText, safeText)
        } else {
            text(R.string.learn_shutter_too_slow, actualText, safeText)
        }
    }

    /**
     * A remark on noise, only when it is high enough to see: "Grainy at ISO 3200". Null otherwise,
     * and null for an unscanned photo.
     */
    fun noiseRemark(noiseLevel: Double?, iso: Int?): UiText? {
        val noise = noiseLevel ?: return null
        if (ScoreMetric.NOISE.goodness(noise) >= HighNoiseGoodness) return null
        return if (iso != null && iso > 0) text(R.string.learn_grainy_at_iso, iso) else text(R.string.learn_grainy)
    }

    /** Below this goodness noise is high: the same threshold that turns its bar red. */
    private const val HighNoiseGoodness = 0.33

    /** What a quiz answer says under its question: the result in a word, then the explanation. */
    fun quizFeedback(correct: Boolean, explanation: UiText): UiText =
        text(if (correct) R.string.learn_quiz_right else R.string.learn_quiz_not_quite, explanation)

    // ── Debrief (shoot debrief SD-4, SD-5) ──

    /** A camera class in words, for the chip and its menu. */
    fun cameraClass(c: CameraClass): UiText = text(
        when (c) {
            CameraClass.PHONE -> R.string.label_camera_class_phone
            CameraClass.COMPACT -> R.string.label_camera_class_compact
            CameraClass.INTERCHANGEABLE -> R.string.label_camera_class_interchangeable
            CameraClass.DRONE -> R.string.label_camera_class_drone
            CameraClass.ACTION -> R.string.label_camera_class_action
            CameraClass.UNKNOWN -> R.string.label_camera_class_unknown
        },
    )

    /** The chip's spoken name: what it says and what it does. */
    fun cameraChipSpoken(c: CameraClass): UiText = text(
        when (c) {
            CameraClass.PHONE -> R.string.cd_camera_chip_phone
            CameraClass.COMPACT -> R.string.cd_camera_chip_compact
            CameraClass.INTERCHANGEABLE -> R.string.cd_camera_chip_interchangeable
            CameraClass.DRONE -> R.string.cd_camera_chip_drone
            CameraClass.ACTION -> R.string.cd_camera_chip_action
            CameraClass.UNKNOWN -> R.string.cd_camera_chip_unknown
        },
    )

    fun metric(m: DebriefMetric): UiText = text(
        when (m) {
            DebriefMetric.HIGHLIGHTS_KEPT -> R.string.label_debrief_highlights_kept
            DebriefMetric.LEVEL_HORIZON -> R.string.label_debrief_level_horizon
            DebriefMetric.SUBJECT_OFF_EDGE -> R.string.label_debrief_subject_off_edge
            DebriefMetric.HAND_HOLD -> R.string.label_debrief_hand_hold
            DebriefMetric.SHARP_FRAMES -> R.string.label_debrief_sharp_frames
        },
    )

    /** The lesson a focus links to. */
    fun lessonFor(m: DebriefMetric): String = when (m) {
        DebriefMetric.HIGHLIGHTS_KEPT -> "exposure.histogram_clipping"
        DebriefMetric.LEVEL_HORIZON -> "composition.horizon"
        DebriefMetric.SUBJECT_OFF_EDGE -> "composition.rule_of_thirds"
        DebriefMetric.HAND_HOLD,
        DebriefMetric.SHARP_FRAMES -> "motion.reciprocal_rule"
    }

    fun photos(n: Int): UiText = plural(R.plurals.label_count_photos, n)

    /** A metric row as one spoken phrase: label, value, previous value. */
    fun metricSpoken(label: UiText, matching: Int, total: Int, before: Int?): UiText =
        if (before == null) {
            text(R.string.cd_debrief_metric, label, matching, total)
        } else {
            text(R.string.cd_debrief_metric_before, label, matching, total, before)
        }

    val NOT_SCANNED: UiText = text(R.string.empty_debrief_not_scanned)
    val NO_CAMERA_DATA: UiText = text(R.string.label_no_camera_data)

    fun partial(scanned: Int, total: Int): UiText = text(R.string.label_debrief_partial, scanned, total)

    /** The worked pair's question, from the measurement that tells the frames apart. */
    fun pairQuestion(m: SceneComparator.Metric): UiText = text(
        when (m) {
            SceneComparator.Metric.SHARPNESS -> R.string.title_pair_sharper
            SceneComparator.Metric.HIGHLIGHT_CLIPPING -> R.string.title_pair_highlights
            SceneComparator.Metric.SHADOW_CLIPPING -> R.string.title_pair_shadows
            SceneComparator.Metric.NOISE -> R.string.title_pair_noise
        },
    )

    /** The label drawn on a frame of the worked pair once revealed: the measured value in words. */
    fun pairValue(m: SceneComparator.Metric, scan: ScanResult?): UiText = when (m) {
        SceneComparator.Metric.SHARPNESS -> text(R.string.label_pair_sharpness, scan?.sharpnessScore?.toInt()?.toString() ?: "?")
        SceneComparator.Metric.HIGHLIGHT_CLIPPING ->
            text(R.string.label_pair_bright_lost, String.format(java.util.Locale.US, "%.1f", scan?.highlightClipping ?: 0.0))
        SceneComparator.Metric.SHADOW_CLIPPING ->
            text(R.string.label_pair_shadows_lost, String.format(java.util.Locale.US, "%.1f", scan?.shadowClipping ?: 0.0))
        SceneComparator.Metric.NOISE -> text(R.string.label_pair_grain, String.format(java.util.Locale.US, "%.1f", scan?.noiseLevel ?: 0.0))
    }
}
