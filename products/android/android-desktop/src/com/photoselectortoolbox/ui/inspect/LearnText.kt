package com.photoselectortoolbox.ui.inspect

import com.photoselectortoolbox.domain.model.SpiralOrientation
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.scoring.ScoreMetric

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
}
