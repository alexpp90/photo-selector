package com.photoselectortoolbox.domain.scoring

import com.photoselectortoolbox.data.model.ScanResult

/**
 * Which measurements a scan computes: the four rows of the Scan sheet (REQ-AND-ALGO.07). An
 * unticked one is not computed, its value stays null, and a null metric shows no chip, so it is
 * never read as 0. Highlight and shadow clipping come from one histogram pass: it runs when either
 * is ticked and keeps only the ticked half.
 */
data class ScanMeasures(
    val sharpness: Boolean = true,
    val noise: Boolean = true,
    val highlightClipping: Boolean = true,
    val shadowClipping: Boolean = true,
) {
    val any: Boolean
        get() = sharpness || noise || highlightClipping || shadowClipping

    val clipping: Boolean
        get() = highlightClipping || shadowClipping

    /**
     * What is still to be computed for a photo that already has [cached] values: the ticked
     * measurements it has no value for. A value cached by an earlier scan is kept, whether or not
     * it is ticked now: it was measured, and dropping it would cost a re-scan to get back.
     */
    fun missingFrom(cached: ScanResult): ScanMeasures = ScanMeasures(
        sharpness = sharpness && cached.sharpnessScore == null,
        noise = noise && cached.noiseLevel == null,
        highlightClipping = highlightClipping && cached.highlightClipping == null,
        shadowClipping = shadowClipping && cached.shadowClipping == null,
    )

    companion object {
        val ALL = ScanMeasures()
    }
}

/**
 * [this] with every field it has no value for taken from [computed]: the merge of a scan that
 * measured only what was missing. A value already here wins (*merge the field you computed*).
 */
fun ScanResult.filledFrom(computed: ScanResult): ScanResult = copy(
    sharpnessScore = sharpnessScore ?: computed.sharpnessScore,
    noiseLevel = noiseLevel ?: computed.noiseLevel,
    highlightClipping = highlightClipping ?: computed.highlightClipping,
    shadowClipping = shadowClipping ?: computed.shadowClipping,
    aestheticScore = aestheticScore ?: computed.aestheticScore,
)
