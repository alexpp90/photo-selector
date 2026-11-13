package com.photoselectortoolbox.ui.inspect

import com.photoselectortoolbox.domain.model.SpiralOrientation
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.learn.SceneComparator
import com.photoselectortoolbox.domain.learn.DebriefMetric
import com.photoselectortoolbox.domain.learn.CameraClass

/**
 * The plain-language words of the Learn screen's Photo Coach and guide bar, kept out of the
 * composables so a JVM test can hold them free of algorithm names (usability panel 2026-10-24:
 * "1/f_eff Guideline", "Phi", "Flip (TL)" read as data, not explanation).
 *
 * The method behind a figure is the lesson's business, not the label's.
 */
object LearnText {

    /** The guide toggles, in the order the bar shows them. */
    const val GUIDE_THIRDS = "Thirds"
    const val GUIDE_GOLDEN_RATIO = "Golden ratio"
    const val GUIDE_SPIRAL = "Spiral"
    const val GUIDE_FLIP_SPIRAL = "Flip spiral"
    const val GUIDE_DIAGONALS = "Diagonals"
    const val GUIDE_HORIZON = "Horizon"

    /** The spoken name of the spiral flip, which says where the spiral now turns. */
    fun flipSpiralDescription(orientation: SpiralOrientation): String {
        val corner = when (orientation) {
            SpiralOrientation.TOP_LEFT -> "top left"
            SpiralOrientation.TOP_RIGHT -> "top right"
            SpiralOrientation.BOTTOM_RIGHT -> "bottom right"
            SpiralOrientation.BOTTOM_LEFT -> "bottom left"
        }
        return "Flip spiral, now turning to the $corner"
    }

    /** The coach's blur-risk badge: the level in words, the percentage as its figure. */
    fun blurRisk(risk: Float): String {
        val pct = (risk * 100).toInt()
        val level = when {
            risk >= 0.7f -> "High"
            risk >= 0.3f -> "Moderate"
            else -> "Low"
        }
        return "$level blur risk ($pct%)"
    }

    /**
     * The shutter line: whether the photo was fast enough to hold by hand, as a sentence with the
     * two speeds in it. [safeSeconds] is the slowest speed the hand-holding guideline allows for the
     * lens, [actualSeconds] the photo's. Null when the guideline is unknown.
     */
    fun shutterLine(safeSeconds: Double?, actualSeconds: Double?): String? {
        val safe = safeSeconds?.takeIf { it > 0.0 } ?: return null
        val safeText = ExifFormatter.shutterSpeed(safe)
        val actual = actualSeconds?.takeIf { it > 0.0 } ?: return "Slowest speed to hand-hold: $safeText"
        val actualText = ExifFormatter.shutterSpeed(actual)
        return if (actual <= safe) {
            "Fast enough to hand-hold: $actualText beats the $safeText minimum"
        } else {
            "Slower than hand-holding allows: $actualText, minimum $safeText"
        }
    }

    /**
     * A remark on noise, only when it is high enough to see: "Grainy at ISO 3200". Null otherwise,
     * and null for an unscanned photo.
     */
    fun noiseRemark(noiseLevel: Double?, iso: Int?): String? {
        val noise = noiseLevel ?: return null
        if (ScoreMetric.NOISE.goodness(noise) >= HighNoiseGoodness) return null
        return if (iso != null && iso > 0) "Grainy at ISO $iso" else "Grainy"
    }

    /** Below this goodness noise is high: the same threshold that turns its bar red. */
    private const val HighNoiseGoodness = 0.33

    /** What a quiz answer says under its question: the result in a word, then the explanation. */
    fun quizFeedback(correct: Boolean, explanation: String): String =
        (if (correct) "Right. " else "Not quite. ") + explanation

    // ── Debrief (shoot debrief SD-4, SD-5) ──

    /** A camera class in words, for the chip and its menu. */
    fun cameraClass(c: CameraClass): String = when (c) {
        CameraClass.PHONE -> "Phone"
        CameraClass.COMPACT -> "Compact"
        CameraClass.INTERCHANGEABLE -> "Interchangeable lens"
        CameraClass.DRONE -> "Drone"
        CameraClass.ACTION -> "Action cam"
        CameraClass.UNKNOWN -> "Unknown camera"
    }

    /** The chip's spoken name: what it says and what it does. */
    fun cameraChipSpoken(c: CameraClass): String =
        "Shot with ${cameraClass(c).lowercase()}, change"

    fun metric(m: DebriefMetric): String = when (m) {
        DebriefMetric.HIGHLIGHTS_KEPT -> "Highlights kept"
        DebriefMetric.LEVEL_HORIZON -> "Level horizon"
        DebriefMetric.SUBJECT_OFF_EDGE -> "Subject off the edge"
        DebriefMetric.HAND_HOLD -> "Fast enough to hand-hold"
        DebriefMetric.SHARP_FRAMES -> "Not clearly soft"
    }

    /** The lesson a focus links to. */
    fun lessonFor(m: DebriefMetric): String = when (m) {
        DebriefMetric.HIGHLIGHTS_KEPT -> "exposure.histogram_clipping"
        DebriefMetric.LEVEL_HORIZON -> "composition.horizon"
        DebriefMetric.SUBJECT_OFF_EDGE -> "composition.rule_of_thirds"
        DebriefMetric.HAND_HOLD,
        DebriefMetric.SHARP_FRAMES -> "motion.reciprocal_rule"
    }

    fun photos(n: Int): String = if (n == 1) "1 photo" else "$n photos"

    /** A metric row as one spoken phrase: label, value, previous value. */
    fun metricSpoken(label: String, matching: Int, total: Int, before: Int?): String =
        "$label, $matching of $total" + (before?.let { ", before $it percent" } ?: "")

    const val NOT_SCANNED = "Scan this folder to see how the shoot went."
    const val NO_CAMERA_DATA = "No camera data"

    fun partial(scanned: Int, total: Int): String = "$scanned of $total photos scanned"

    /** The worked pair's question, from the measurement that tells the frames apart. */
    fun pairQuestion(m: SceneComparator.Metric): String = when (m) {
        SceneComparator.Metric.SHARPNESS -> "Which is sharper?"
        SceneComparator.Metric.HIGHLIGHT_CLIPPING -> "Which kept the bright areas?"
        SceneComparator.Metric.SHADOW_CLIPPING -> "Which kept the shadows?"
        SceneComparator.Metric.NOISE -> "Which is less grainy?"
    }

    /** The label drawn on a frame of the worked pair once revealed: the measured value in words. */
    fun pairValue(m: SceneComparator.Metric, scan: ScanResult?): String = when (m) {
        SceneComparator.Metric.SHARPNESS -> "Sharpness ${scan?.sharpnessScore?.toInt() ?: "?"}"
        SceneComparator.Metric.HIGHLIGHT_CLIPPING ->
            String.format(java.util.Locale.US, "Bright areas lost %.1f %%", scan?.highlightClipping ?: 0.0)
        SceneComparator.Metric.SHADOW_CLIPPING ->
            String.format(java.util.Locale.US, "Shadows lost %.1f %%", scan?.shadowClipping ?: 0.0)
        SceneComparator.Metric.NOISE -> String.format(java.util.Locale.US, "Grain %.1f", scan?.noiseLevel ?: 0.0)
    }
}
