package com.photoselectortoolbox.domain.learn

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.photoselectortoolbox.domain.text.text
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.R

enum class CropAspectRatio(val label: String, val ratio: Double) {
    RATIO_3_2("3:2", 3.0 / 2.0),
    RATIO_4_3("4:3", 4.0 / 3.0),
    RATIO_16_9("16:9", 16.0 / 9.0),
    RATIO_4_5("4:5", 4.0 / 5.0),
    RATIO_1_1("1:1", 1.0),
}

data class SuggestedCrop(
    val xMin: Double,
    val yMin: Double,
    val xMax: Double,
    val yMax: Double,
    val aspectRatio: CropAspectRatio,
    val targetPowerPoint: Pair<Double, Double>,
    val rationale: UiText,
) {
    val widthFraction: Double get() = xMax - xMin
    val heightFraction: Double get() = yMax - yMin
    val areaFraction: Double get() = widthFraction * heightFraction
}

/**
 * Pure non-destructive Rule-of-Thirds crop suggester (`U-25`).
 *
 * Computes a crop rectangle in normalized `[0, 1] x [0, 1]` coordinates with area >= 50%
 * of the original frame, keeping >= 8% headroom around the subject bounding box and placing
 * the subject centroid onto the nearest Rule-of-Thirds power point of the cropped frame.
 */
object CropSuggester {

    private const val MIN_AREA_FRACTION = 0.50
    private const val HEADROOM_MARGIN = 0.08
    private const val ALREADY_ALIGNED_THRESHOLD = 0.06

    private val POWER_POINTS = listOf(
        Pair(1.0 / 3.0, 1.0 / 3.0),
        Pair(2.0 / 3.0, 1.0 / 3.0),
        Pair(1.0 / 3.0, 2.0 / 3.0),
        Pair(2.0 / 3.0, 2.0 / 3.0),
    )

    fun suggestCrop(
        subjectBox: SubjectBoundingBox?,
        imageWidth: Int,
        imageHeight: Int,
        requestedAspect: CropAspectRatio? = null,
    ): SuggestedCrop? {
        if (subjectBox == null || imageWidth <= 0 || imageHeight <= 0) return null

        val nearestExisting = POWER_POINTS.minByOrNull { (px, py) ->
            hypot(subjectBox.centroidX - px, subjectBox.centroidY - py)
        }!!
        val existingDist = hypot(
            subjectBox.centroidX - nearestExisting.first,
            subjectBox.centroidY - nearestExisting.second,
        )
        if (existingDist <= ALREADY_ALIGNED_THRESHOLD && requestedAspect == null) {
            return null
        }

        val frameAspect = imageWidth.toDouble() / imageHeight.toDouble()
        val chosenAspect = requestedAspect ?: defaultAspectForFrame(frameAspect)
        val targetAspectInNormalizedCoords = if (frameAspect >= 1.0) {
            chosenAspect.ratio / frameAspect
        } else {
            val portraitRatio = if (chosenAspect.ratio > 1.0) 1.0 / chosenAspect.ratio else chosenAspect.ratio
            portraitRatio / frameAspect
        }

        // Determine the largest crop box of the target aspect that fits inside [0, 1] x [0, 1],
        // scaled down to ~0.82 of max so we have room to shift the subject onto a power point
        // while keeping area >= 0.50.
        val maxW = min(1.0, targetAspectInNormalizedCoords)
        val maxH = min(1.0, 1.0 / targetAspectInNormalizedCoords)

        val subjectSpanW = (subjectBox.xMax - subjectBox.xMin) + 2.0 * HEADROOM_MARGIN
        val subjectSpanH = (subjectBox.yMax - subjectBox.yMin) + 2.0 * HEADROOM_MARGIN
        val minScaleForArea = sqrt(MIN_AREA_FRACTION / (maxW * maxH)).coerceAtLeast(0.72)
        val scale = max(
            minScaleForArea,
            max(subjectSpanW / maxW, subjectSpanH / maxH),
        ).coerceIn(minScaleForArea, max(minScaleForArea, 0.94))

        val cropW = (maxW * scale).coerceIn(0.50, 1.0)
        val cropH = (maxH * scale).coerceIn(0.50, 1.0)
        if (cropW * cropH < MIN_AREA_FRACTION - 1e-6) return null

        // Try each of the 4 power points and pick the one that places the subject centroid
        // closest to that power point within the cropped frame while keeping the subject + headroom inside.
        val reqXMin = (subjectBox.xMin - HEADROOM_MARGIN).coerceAtLeast(0.0)
        val reqYMin = (subjectBox.yMin - HEADROOM_MARGIN).coerceAtLeast(0.0)
        val reqXMax = (subjectBox.xMax + HEADROOM_MARGIN).coerceAtMost(1.0)
        val reqYMax = (subjectBox.yMax + HEADROOM_MARGIN).coerceAtMost(1.0)

        var bestCrop: SuggestedCrop? = null
        var bestResidual = Double.MAX_VALUE

        for ((px, py) in POWER_POINTS) {
            // Ideal crop origin so that subjectBox.centroidX == x0 + px * cropW
            val idealX0 = subjectBox.centroidX - px * cropW
            val idealY0 = subjectBox.centroidY - py * cropH

            // Clamp x0, y0 so that [x0, x0 + cropW] stays inside [0, 1] and covers [reqXMin, reqXMax]
            val lowX = max(0.0, reqXMax - cropW)
            val highX = min(1.0 - cropW, reqXMin)
            val x0 = if (lowX <= highX) {
                idealX0.coerceIn(lowX, highX)
            } else {
                idealX0.coerceIn(0.0, 1.0 - cropW)
            }

            val lowY = max(0.0, reqYMax - cropH)
            val highY = min(1.0 - cropH, reqYMin)
            val y0 = if (lowY <= highY) {
                idealY0.coerceIn(lowY, highY)
            } else {
                idealY0.coerceIn(0.0, 1.0 - cropH)
            }

            val newRelX = (subjectBox.centroidX - x0) / cropW
            val newRelY = (subjectBox.centroidY - y0) / cropH
            val residual = hypot(newRelX - px, newRelY - py)

            if (residual < bestResidual) {
                bestResidual = residual
                val corner = when {
                    py < 0.5 && px < 0.5 -> R.string.learn_crop_upper_left
                    py < 0.5 -> R.string.learn_crop_upper_right
                    px < 0.5 -> R.string.learn_crop_lower_left
                    else -> R.string.learn_crop_lower_right
                }
                bestCrop = SuggestedCrop(
                    xMin = x0,
                    yMin = y0,
                    xMax = (x0 + cropW).coerceAtMost(1.0),
                    yMax = (y0 + cropH).coerceAtMost(1.0),
                    aspectRatio = chosenAspect,
                    targetPowerPoint = px to py,
                    rationale = text(corner, chosenAspect.label, String.format(Locale.US, "%.0f", cropW * cropH * 100.0)),
                )
            }
        }

        return bestCrop
    }

    private fun defaultAspectForFrame(frameAspect: Double): CropAspectRatio = when {
        abs(frameAspect - 1.0) < 0.12 -> CropAspectRatio.RATIO_1_1
        frameAspect < 1.0 -> CropAspectRatio.RATIO_4_5
        abs(frameAspect - 4.0 / 3.0) < 0.1 -> CropAspectRatio.RATIO_4_3
        abs(frameAspect - 16.0 / 9.0) < 0.15 -> CropAspectRatio.RATIO_16_9
        else -> CropAspectRatio.RATIO_3_2
    }
}
