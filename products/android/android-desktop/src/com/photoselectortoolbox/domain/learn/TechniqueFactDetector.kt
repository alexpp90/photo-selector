package com.photoselectortoolbox.domain.learn

import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl
import com.photoselectortoolbox.domain.format.CaptureTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class TechniqueFact(
    val kind: String,
    val value: String,
    val confidence: Double,
    val geometryJson: String? = null,
)

data class SubjectBoundingBox(
    val xMin: Double,
    val yMin: Double,
    val xMax: Double,
    val yMax: Double,
    val centroidX: Double,
    val centroidY: Double,
    val confidence: Double,
    val subjectSharpnessRatio: Double = 1.0,
)

fun interface SubjectDetector {
    fun detectSubject(luma: FloatArray, width: Int, height: Int): SubjectBoundingBox?
}

/**
 * Default local gradient/saliency subject detector (`U-22b`).
 * Divides the frame into an 8x8 grid of local contrast + center-weighted saliency,
 * clusters high-energy cells above threshold, and computes the subject bounding box,
 * centroid, and inside-vs-outside sharpness ratio.
 */
class DefaultSubjectDetector @Inject constructor() : SubjectDetector {
    override fun detectSubject(luma: FloatArray, width: Int, height: Int): SubjectBoundingBox? {
        if (width < 16 || height < 16 || luma.size < width * height) return null
        val gridCols = 8
        val gridRows = 8
        val cellEnergy = DoubleArray(gridCols * gridRows)
        val cellCount = IntArray(gridCols * gridRows)

        for (y in 1 until height - 1) {
            val gy = ((y * gridRows) / height).coerceIn(0, gridRows - 1)
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val gx = ((x * gridCols) / width).coerceIn(0, gridCols - 1)
                val idx = rowOffset + x
                val c = luma[idx].toDouble()
                val lap = abs(
                    4.0 * c -
                        luma[idx - 1].toDouble() -
                        luma[idx + 1].toDouble() -
                        luma[idx - width].toDouble() -
                        luma[idx + width].toDouble()
                )
                val cellIdx = gy * gridCols + gx
                cellEnergy[cellIdx] += lap
                cellCount[cellIdx]++
            }
        }

        var totalEnergy = 0.0
        var maxEnergy = 0.0
        for (i in cellEnergy.indices) {
            if (cellCount[i] > 0) {
                cellEnergy[i] /= cellCount[i].toDouble()
            }
            totalEnergy += cellEnergy[i]
            if (cellEnergy[i] > maxEnergy) maxEnergy = cellEnergy[i]
        }
        val meanEnergy = totalEnergy / cellEnergy.size
        if (maxEnergy < 1.5 || maxEnergy < meanEnergy * 1.25) return null

        val threshold = meanEnergy + (maxEnergy - meanEnergy) * 0.45
        var minGx = gridCols
        var maxGx = -1
        var minGy = gridRows
        var maxGy = -1
        var weightedX = 0.0
        var weightedY = 0.0
        var weightSum = 0.0
        var insideEnergySum = 0.0
        var insideCells = 0
        var outsideEnergySum = 0.0
        var outsideCells = 0

        for (gy in 0 until gridRows) {
            for (gx in 0 until gridCols) {
                val e = cellEnergy[gy * gridCols + gx]
                if (e >= threshold) {
                    if (gx < minGx) minGx = gx
                    if (gx > maxGx) maxGx = gx
                    if (gy < minGy) minGy = gy
                    if (gy > maxGy) maxGy = gy
                    val cx = (gx + 0.5) / gridCols
                    val cy = (gy + 0.5) / gridRows
                    weightedX += cx * e
                    weightedY += cy * e
                    weightSum += e
                    insideEnergySum += e
                    insideCells++
                } else {
                    outsideEnergySum += e
                    outsideCells++
                }
            }
        }

        if (insideCells == 0 || insideCells > (gridCols * gridRows * 3) / 4 || weightSum <= 0.0) {
            return null
        }

        val insideMean = insideEnergySum / insideCells
        val outsideMean = if (outsideCells > 0) max(0.1, outsideEnergySum / outsideCells) else 1.0
        val ratio = insideMean / outsideMean
        val confidence = ((ratio - 1.0) / 2.5).coerceIn(0.45, 0.95)

        return SubjectBoundingBox(
            xMin = (minGx.toDouble() / gridCols).coerceIn(0.0, 1.0),
            yMin = (minGy.toDouble() / gridRows).coerceIn(0.0, 1.0),
            xMax = ((maxGx + 1).toDouble() / gridCols).coerceIn(0.0, 1.0),
            yMax = ((maxGy + 1).toDouble() / gridRows).coerceIn(0.0, 1.0),
            centroidX = (weightedX / weightSum).coerceIn(0.0, 1.0),
            centroidY = (weightedY / weightSum).coerceIn(0.0, 1.0),
            confidence = confidence,
            subjectSharpnessRatio = ratio,
        )
    }
}

@Singleton
class TechniqueFactDetector(
    private val subjectDetector: SubjectDetector,
) {
    @Inject constructor() : this(DefaultSubjectDetector())

    companion object {
        /**
         * The version every stored fact row carries. 2: the scan reads a photo's EXIF itself and
         * stores the debrief's fact set ([ScanFacts]); rows of version 1, written without EXIF
         * for nearly every photo, are replaced by the next scan of their folder (SD-1a).
         */
        const val DETECTOR_VERSION = 2

        private val POWER_POINTS = listOf(
            Pair(1.0 / 3.0, 1.0 / 3.0),
            Pair(2.0 / 3.0, 1.0 / 3.0),
            Pair(1.0 / 3.0, 2.0 / 3.0),
            Pair(2.0 / 3.0, 2.0 / 3.0),
        )

        /**
         * NOAA solar elevation angle in degrees for a UTC/offset timestamp and GPS coordinates (`U-23` #2).
         * Returns null when timestamp cannot be parsed or GPS coordinates are missing/zero.
         */
        fun computeSolarElevationDeg(
            dateTimeOriginal: String?,
            offsetTimeOriginal: String?,
            latitude: Double?,
            longitude: Double?,
        ): Double? {
            if (dateTimeOriginal.isNullOrBlank() || latitude == null || longitude == null) return null
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (abs(latitude) < 0.001 && abs(longitude) < 0.001) return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

            val parsed = CaptureTime.wallClock(dateTimeOriginal) ?: return null
            val offsetHours = CaptureTime.offset(offsetTimeOriginal)?.let { it.totalSeconds / 3600.0 } ?: (longitude / 15.0)

            val dayOfYear = parsed.dayOfYear
            val localDecimalHours = parsed.hour + parsed.minute / 60.0 + parsed.second / 3600.0
            val utcHours = localDecimalHours - offsetHours

            val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1 + (utcHours - 12.0) / 24.0)
            val eqTimeMinutes = 229.18 * (
                0.000075 +
                    0.001868 * cos(gamma) -
                    0.032077 * sin(gamma) -
                    0.014615 * cos(2.0 * gamma) -
                    0.040849 * sin(2.0 * gamma)
                )
            val declRad = 0.006918 -
                0.399912 * cos(gamma) +
                0.070257 * sin(gamma) -
                0.006758 * cos(2.0 * gamma) +
                0.000907 * sin(2.0 * gamma) -
                0.002697 * cos(3.0 * gamma) +
                0.00148 * sin(3.0 * gamma)

            val timeOffsetMinutes = eqTimeMinutes + 4.0 * longitude - 60.0 * offsetHours
            var trueSolarTimeMinutes = (localDecimalHours * 60.0 + timeOffsetMinutes) % 1440.0
            if (trueSolarTimeMinutes < 0) trueSolarTimeMinutes += 1440.0

            val hourAngleDeg = if (trueSolarTimeMinutes / 4.0 < 0) {
                trueSolarTimeMinutes / 4.0 + 180.0
            } else {
                trueSolarTimeMinutes / 4.0 - 180.0
            }
            val hourAngleRad = Math.toRadians(hourAngleDeg)
            val latRad = Math.toRadians(latitude)

            val cosZenith = (
                sin(latRad) * sin(declRad) +
                    cos(latRad) * cos(declRad) * cos(hourAngleRad)
                ).coerceIn(-1.0, 1.0)
            val zenithDeg = Math.toDegrees(acos(cosZenith))
            return 90.0 - zenithDeg
        }

        fun classifyLightHourFromElevation(elevationDeg: Double): Pair<String, Double> = when {
            elevationDeg in -6.0..-0.833 -> "blue_hour" to 0.90
            elevationDeg > -0.833 && elevationDeg <= 6.0 -> "golden_hour" to 0.90
            elevationDeg > 6.0 -> "day" to 0.85
            else -> "night" to 0.85
        }

        /**
         * Detects a true sky-ground / water-sky horizon line and its tilt angle in degrees (`U-23` #3).
         * Rejects indoor scenes, portraits, and verticals where upper texture exceeds lower texture
         * or gradient sign is inconsistent across the frame width.
         */
        fun detectHorizon(luma: FloatArray, width: Int, height: Int): TechniqueFact? {
            if (width < 16 || height < 16 || luma.size < width * height) return null

            val yStart = (height * 0.15).toInt().coerceAtLeast(2)
            val yEnd = (height * 0.85).toInt().coerceAtMost(height - 3)
            if (yStart >= yEnd) return null

            // Find the strongest horizontal edge profile per column x
            val columnBestY = IntArray(width) { -1 }
            val columnGradSign = IntArray(width) { 0 }
            val columnStrength = DoubleArray(width)

            for (x in 2 until width - 2) {
                var bestY = -1
                var bestMag = 0.0
                var bestSign = 0
                for (y in yStart..yEnd) {
                    val gy = luma[(y + 1) * width + x].toDouble() - luma[(y - 1) * width + x].toDouble()
                    val gx = luma[y * width + (x + 1)].toDouble() - luma[y * width + (x - 1)].toDouble()
                    val mag = abs(gy)
                    // Require predominantly horizontal gradient (|gy| >= 2.2 * |gx|)
                    if (mag > bestMag && mag >= 12.0 && mag >= 2.2 * abs(gx)) {
                        bestMag = mag
                        bestY = y
                        bestSign = if (gy >= 0) 1 else -1
                    }
                }
                columnBestY[x] = bestY
                columnGradSign[x] = bestSign
                columnStrength[x] = bestMag
            }

            // Check sign consistency across columns
            val posCount = columnGradSign.count { it > 0 }
            val negCount = columnGradSign.count { it < 0 }
            val dominantSign = if (posCount >= negCount) 1 else -1
            val validPoints = mutableListOf<Pair<Double, Double>>()
            for (x in 2 until width - 2) {
                if (columnGradSign[x] == dominantSign && columnBestY[x] >= 0) {
                    validPoints.add(x.toDouble() to columnBestY[x].toDouble())
                }
            }
            if (validPoints.size < width * 0.5) return null

            // Linear regression y = a + b * x with outlier rejection
            val meanX = validPoints.sumOf { it.first } / validPoints.size
            val meanY = validPoints.sumOf { it.second } / validPoints.size
            var num = 0.0
            var den = 0.0
            for ((px, py) in validPoints) {
                num += (px - meanX) * (py - meanY)
                den += (px - meanX) * (px - meanX)
            }
            if (den <= 1e-6) return null
            val slope = num / den
            val intercept = meanY - slope * meanX

            // Verify colinearity (inliers within 2.5% of height)
            val tolerancePx = max(2.5, height * 0.025)
            val inliers = validPoints.filter { (px, py) ->
                abs(py - (intercept + slope * px)) <= tolerancePx
            }
            if (inliers.size < width * 0.5) return null

            // Refit on inliers
            val inMeanX = inliers.sumOf { it.first } / inliers.size
            val inMeanY = inliers.sumOf { it.second } / inliers.size
            var inNum = 0.0
            var inDen = 0.0
            for ((px, py) in inliers) {
                inNum += (px - inMeanX) * (py - inMeanY)
                inDen += (px - inMeanX) * (px - inMeanX)
            }
            val finalSlope = if (inDen > 1e-6) inNum / inDen else slope
            val finalIntercept = inMeanY - finalSlope * inMeanX

            // Check sky vs ground texture: upper band above line must not have much higher variance than lower band
            var upperVar = 0.0
            var upperCount = 0
            var lowerVar = 0.0
            var lowerCount = 0
            for (x in 2 until width - 2 step 2) {
                val lineY = (finalIntercept + finalSlope * x).toInt().coerceIn(2, height - 3)
                for (y in 1 until lineY) {
                    val diff = abs(luma[y * width + x] - luma[(y - 1) * width + x])
                    upperVar += diff
                    upperCount++
                }
                for (y in (lineY + 1) until height - 1) {
                    val diff = abs(luma[y * width + x] - luma[(y - 1) * width + x])
                    lowerVar += diff
                    lowerCount++
                }
            }
            val upperMeanGrad = if (upperCount > 0) upperVar / upperCount else 0.0
            val lowerMeanGrad = if (lowerCount > 0) lowerVar / lowerCount else 0.0
            if (upperMeanGrad > lowerMeanGrad * 1.6 + 8.0) return null

            val angleDeg = Math.toDegrees(atan2(finalSlope, 1.0))
            val absAngle = abs(angleDeg)
            if (absAngle > 15.0) return null

            val classification = when {
                absAngle <= 0.5 -> "level"
                absAngle <= 1.5 -> "slight_tilt"
                absAngle <= 8.0 -> "tilted"
                else -> "dutch_or_not_horizon"
            }
            val y1Norm = (finalIntercept / height).coerceIn(0.0, 1.0)
            val y2Norm = ((finalIntercept + finalSlope * (width - 1)) / height).coerceIn(0.0, 1.0)
            val confidence = (inliers.size.toDouble() / width.toDouble()).coerceIn(0.55, 0.95)
            val geometryJson = String.format(
                Locale.US,
                """{"type":"line","x1":0.0,"y1":%.4f,"x2":1.0,"y2":%.4f,"angleDeg":%.2f}""",
                y1Norm,
                y2Norm,
                angleDeg,
            )

            return TechniqueFact(
                kind = "composition.horizon",
                value = classification,
                confidence = confidence,
                geometryJson = geometryJson,
            )
        }

        /**
         * Computes highlight clipping percentage excluding tiny high-contrast specular glints (`U-23` #4).
         * Returns percentage on `[0.0, 100.0]` scale.
         */
        fun computeSpecularExcludedHighlightClipPct(luma: FloatArray, width: Int, height: Int): Double {
            val totalPixels = width * height
            if (width < 4 || height < 4 || luma.size < totalPixels) return 0.0

            // Normalize scale: luma may be 0..255 or 0..1
            val maxSample = luma.maxOrNull() ?: 0f
            val clipThreshold = if (maxSample <= 1.05f) 0.98f else 250.0f
            val contrastThreshold = if (maxSample <= 1.05f) 0.35f else 89.25f
            val maxSpecularComponentPixels = max(1, (totalPixels * 0.0005).toInt())

            val visited = BooleanArray(totalPixels)
            val queue = IntArray(totalPixels)
            var nonSpecularClippedCount = 0

            for (idx in 0 until totalPixels) {
                if (visited[idx] || luma[idx] < clipThreshold) continue
                // BFS to collect 8-connected clipped component
                var head = 0
                var tail = 0
                queue[tail++] = idx
                visited[idx] = true

                var ringSum = 0.0
                var ringCount = 0

                while (head < tail) {
                    val curr = queue[head++]
                    val cy = curr / width
                    val cx = curr % width
                    for (dy in -2..2) {
                        val ny = cy + dy
                        if (ny !in 0 until height) continue
                        for (dx in -2..2) {
                            val nx = cx + dx
                            if (nx !in 0 until width) continue
                            val nIdx = ny * width + nx
                            if (abs(dx) <= 1 && abs(dy) <= 1) {
                                if (!visited[nIdx] && luma[nIdx] >= clipThreshold) {
                                    visited[nIdx] = true
                                    queue[tail++] = nIdx
                                }
                            } else if (luma[nIdx] < clipThreshold) {
                                ringSum += luma[nIdx].toDouble()
                                ringCount++
                            }
                        }
                    }
                }

                val componentSize = tail
                val ringMean = if (ringCount > 0) ringSum / ringCount else clipThreshold.toDouble()
                val ringContrast = clipThreshold.toDouble() - ringMean
                val isSpecularGlint = componentSize <= maxSpecularComponentPixels && ringContrast >= contrastThreshold
                if (!isSpecularGlint) {
                    nonSpecularClippedCount += componentSize
                }
            }

            return (nonSpecularClippedCount.toDouble() * 100.0) / totalPixels.toDouble()
        }

        /**
         * Classifies tonal key (`high_key`, `low_key`, `silhouette`, `normal`) from luma distribution (`U-23` #4).
         */
        fun classifyTonalKey(luma: FloatArray, width: Int, height: Int): TechniqueFact? {
            val total = width * height
            if (total < 16 || luma.size < total) return null
            val maxSample = luma.maxOrNull() ?: 0f
            val scale = if (maxSample <= 1.05f) 255.0 else 1.0

            val hist = IntArray(256)
            for (i in 0 until total) {
                val bin = (luma[i] * scale).toInt().coerceIn(0, 255)
                hist[bin]++
            }

            var cum = 0
            var p10 = 0
            var p50 = 0
            var p90 = 0
            for (b in 0..255) {
                cum += hist[b]
                if (cum >= total * 0.10 && p10 == 0) p10 = b
                if (cum >= total * 0.50 && p50 == 0) p50 = b
                if (cum >= total * 0.90 && p90 == 0) p90 = b
            }

            val darkFraction = (0..45).sumOf { hist[it] }.toDouble() / total
            val brightFraction = (200..255).sumOf { hist[it] }.toDouble() / total
            val midFraction = (80..170).sumOf { hist[it] }.toDouble() / total
            val shadowClipFraction = (0..5).sumOf { hist[it] }.toDouble() / total
            val highlightClipFraction = (250..255).sumOf { hist[it] }.toDouble() / total

            val key = when {
                darkFraction >= 0.22 && brightFraction >= 0.22 && midFraction < 0.25 -> "silhouette"
                p50 >= 170 && shadowClipFraction < 0.01 -> "high_key"
                p50 <= 65 && highlightClipFraction < 0.01 -> "low_key"
                else -> "normal"
            }
            return TechniqueFact(
                kind = "exposure.tonal_key",
                value = key,
                confidence = if (key == "normal") 0.70 else 0.85,
            )
        }

        /**
         * Computes background simplicity (`clean`, `moderate`, `busy`) from peripheral edge density (`U-23` #5).
         */
        fun detectSimplicity(
            luma: FloatArray,
            width: Int,
            height: Int,
            subjectBox: SubjectBoundingBox? = null,
        ): TechniqueFact? {
            if (width < 8 || height < 8 || luma.size < width * height) return null
            val maxSample = luma.maxOrNull() ?: 0f
            val scale = if (maxSample <= 1.05f) 255.0 else 1.0

            var edgePixels = 0
            var backgroundPixels = 0
            for (y in 1 until height - 1) {
                val ny = y.toDouble() / height
                for (x in 1 until width - 1) {
                    val nx = x.toDouble() / width
                    val inSubject = if (subjectBox != null) {
                        nx in subjectBox.xMin..subjectBox.xMax && ny in subjectBox.yMin..subjectBox.yMax
                    } else {
                        nx in 0.30..0.70 && ny in 0.30..0.70
                    }
                    if (inSubject) continue
                    backgroundPixels++
                    val gx = (luma[y * width + (x + 1)] - luma[y * width + (x - 1)]) * scale
                    val gy = (luma[(y + 1) * width + x] - luma[(y - 1) * width + x]) * scale
                    if (hypot(gx, gy) >= 28.0) {
                        edgePixels++
                    }
                }
            }
            if (backgroundPixels == 0) return null
            val edgeDensity = edgePixels.toDouble() / backgroundPixels.toDouble()
            val simplicityScore = (1.0 - edgeDensity * 2.5).coerceIn(0.0, 1.0)
            val label = when {
                simplicityScore >= 0.72 -> "clean"
                simplicityScore >= 0.40 -> "moderate"
                else -> "busy"
            }
            return TechniqueFact(
                kind = "composition.simplicity",
                value = label,
                confidence = 0.80,
                geometryJson = String.format(Locale.US, """{"score":%.2f}""", simplicityScore),
            )
        }

        /**
         * Detects vertical or horizontal bilateral symmetry (`U-23` #6).
         */
        fun detectSymmetry(luma: FloatArray, width: Int, height: Int): TechniqueFact? {
            if (width < 8 || height < 8 || luma.size < width * height) return null
            val maxSample = (luma.maxOrNull() ?: 255f).coerceAtLeast(1f)

            // Vertical symmetry (left vs right mirror around x = 0.5)
            var vDiffSum = 0.0
            var vCount = 0
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width / 2) {
                    val mirrorX = width - 1 - x
                    vDiffSum += abs(luma[row + x] - luma[row + mirrorX]) / maxSample
                    vCount++
                }
            }
            val verticalScore = if (vCount > 0) (1.0 - (vDiffSum / vCount) * 2.2).coerceIn(0.0, 1.0) else 0.0

            // Horizontal symmetry (top vs bottom mirror around y = 0.5)
            var hDiffSum = 0.0
            var hCount = 0
            for (y in 0 until height / 2) {
                val mirrorY = height - 1 - y
                val row1 = y * width
                val row2 = mirrorY * width
                for (x in 0 until width) {
                    hDiffSum += abs(luma[row1 + x] - luma[row2 + x]) / maxSample
                    hCount++
                }
            }
            val horizontalScore = if (hCount > 0) (1.0 - (hDiffSum / hCount) * 2.2).coerceIn(0.0, 1.0) else 0.0

            val bestScore = max(verticalScore, horizontalScore)
            if (bestScore < 0.78) return null

            val isVertical = verticalScore >= horizontalScore
            val axisValue = if (isVertical) "vertical" else "horizontal"
            val geometryJson = if (isVertical) {
                """{"type":"line","x1":0.5,"y1":0.0,"x2":0.5,"y2":1.0}"""
            } else {
                """{"type":"line","x1":0.0,"y1":0.5,"x2":1.0,"y2":0.5}"""
            }
            return TechniqueFact(
                kind = "composition.symmetry",
                value = axisValue,
                confidence = bestScore,
                geometryJson = geometryJson,
            )
        }
    }

    /**
     * Runs all technique fact detectors (`U-22b`, `U-22c`, `U-23`) and returns Room entities
     * ready to persist in `technique_facts`.
     */
    fun detectAll(
        uri: String,
        fileSize: Long,
        lastModified: Long,
        exif: ExifData?,
        luma: FloatArray? = null,
        rgbPixels: IntArray? = null,
        width: Int = 0,
        height: Int = 0,
        @Suppress("UNUSED_PARAMETER") sharpnessScore: Double? = null,
        @Suppress("UNUSED_PARAMETER") noiseLevel: Double? = null,
        highlightClipping: Double? = null,
        shadowClipping: Double? = null,
        isRawOrHasRawCompanion: Boolean = false,
    ): List<TechniqueFactEntity> {
        val facts = detectFacts(
            exif = exif,
            luma = luma,
            rgbPixels = rgbPixels,
            width = width,
            height = height,
            highlightClipping = highlightClipping,
            shadowClipping = shadowClipping,
            isRawOrHasRawCompanion = isRawOrHasRawCompanion,
        )
        return facts.map { fact ->
            TechniqueFactEntity(
                uri = uri,
                kind = fact.kind,
                value = fact.value,
                confidence = fact.confidence,
                geometryJson = fact.geometryJson,
                fileSize = fileSize,
                lastModified = lastModified,
                detectorVersion = DETECTOR_VERSION,
            )
        }
    }

    fun detectFacts(
        exif: ExifData?,
        luma: FloatArray? = null,
        rgbPixels: IntArray? = null,
        width: Int = 0,
        height: Int = 0,
        highlightClipping: Double? = null,
        shadowClipping: Double? = null,
        isRawOrHasRawCompanion: Boolean = false,
        only: Set<String>? = null,
        frameLongEdge: Int? = null,
    ): List<TechniqueFact> {
        val facts = mutableListOf<TechniqueFact>()
        // [only] names the kinds wanted (the scan's [ScanFacts.STORED_KINDS]); a detector none of
        // whose kinds is wanted is not run. Null: all of them, as Explore asks for the photo shown.
        fun wants(vararg kinds: String) = only == null || kinds.any { it in only }
        // The focal-plane resolution is per pixel of the original frame, not of the luma grid:
        // [frameLongEdge] is that frame's long edge where the caller knows it (the scan does).
        val opticsWidth = frameLongEdge?.takeIf { it > 0 } ?: width.takeIf { it > 0 }
        val opticsHeight = if (frameLongEdge != null && frameLongEdge > 0) null else height.takeIf { it > 0 }

        // 1. Crop factor, effective focal length, and phone-sensor guard
        val cropFactor = PhotoCoachEngineImpl.computeCropFactor(exif, opticsWidth, opticsHeight)
        if (cropFactor != null) {
            facts.add(
                TechniqueFact(
                    kind = "optics.crop_factor",
                    value = String.format(Locale.US, "%.2f", cropFactor),
                    confidence = 0.95,
                )
            )
        }
        val effectiveFocalLength = PhotoCoachEngineImpl.computeEffectiveFocalLength(exif, opticsWidth, opticsHeight)
        if (effectiveFocalLength != null) {
            facts.add(
                TechniqueFact(
                    kind = "optics.effective_focal_length",
                    value = String.format(Locale.US, "%.1f", effectiveFocalLength),
                    confidence = 0.95,
                )
            )
        }
        val phoneSensor = PhotoCoachEngineImpl.isPhoneSensor(exif, cropFactor)
        if (phoneSensor) {
            facts.add(
                TechniqueFact(
                    kind = "optics.phone_sensor",
                    value = "true",
                    confidence = 0.90,
                )
            )
        }

        // 2. Solar elevation & Golden / Blue hour
        if (exif != null) {
            val elevation = computeSolarElevationDeg(
                dateTimeOriginal = exif.dateTimeOriginal,
                offsetTimeOriginal = exif.offsetTimeOriginal,
                latitude = exif.gpsLatitude,
                longitude = exif.gpsLongitude,
            )
            if (elevation != null) {
                val (hourClass, conf) = classifyLightHourFromElevation(elevation)
                facts.add(
                    TechniqueFact(
                        kind = "light.hour",
                        value = hourClass,
                        confidence = conf,
                        geometryJson = String.format(Locale.US, """{"solarElevationDeg":%.2f}""", elevation),
                    )
                )
            } else {
                // Fallback: local clock hour + warm color ratio
                val parsed = CaptureTime.wallClock(exif.dateTimeOriginal)
                if (parsed != null) {
                    val decimalHour = parsed.hour + parsed.minute / 60.0
                    val isGoldenClockWindow = decimalHour in 5.5..8.0 || decimalHour in 17.0..20.5
                    val warmRatio = computeRedBlueRatio(rgbPixels)
                    if (isGoldenClockWindow && (warmRatio == null || warmRatio >= 1.12)) {
                        facts.add(
                            TechniqueFact(
                                kind = "light.hour",
                                value = "golden_hour",
                                confidence = if (warmRatio != null) 0.65 else 0.55,
                            )
                        )
                    }
                }
            }

            // 7. EXIF intent detectors
            val shutter = exif.shutterSpeed
            if (shutter != null && shutter.isFinite() && shutter > 0.0) {
                when {
                    shutter >= 0.5 -> facts.add(
                        TechniqueFact("intent.shutter", "long_exposure", 0.90)
                    )
                    shutter <= 1.0 / 1000.0 -> facts.add(
                        TechniqueFact("intent.shutter", "action_freeze", 0.90)
                    )
                }
            }
        }

        // Pixel/Luma based detectors
        if (luma != null && width >= 8 && height >= 8 && luma.size >= width * height) {
            // 3. Horizon angle
            if (wants("composition.horizon")) detectHorizon(luma, width, height)?.let { facts.add(it) }

            // 4. Specular-excluded clipping & tonal key
            if (wants("exposure.clipping")) {
                val nonSpecularClipPct = computeSpecularExcludedHighlightClipPct(luma, width, height)
                val effectiveShadowPct = shadowClipping ?: 0.0
                val clipLabel = when {
                    nonSpecularClipPct > 2.0 && effectiveShadowPct > 5.0 -> "both_clipped"
                    nonSpecularClipPct > 2.0 -> if (isRawOrHasRawCompanion) "highlights_recoverable_raw" else "highlights_clipped"
                    effectiveShadowPct > 5.0 -> "shadows_clipped"
                    else -> "well_exposed"
                }
                facts.add(
                    TechniqueFact(
                        kind = "exposure.clipping",
                        value = clipLabel,
                        confidence = 0.88,
                        geometryJson = String.format(
                            Locale.US,
                            """{"nonSpecularHighlightClipPct":%.2f,"shadowClipPct":%.2f}""",
                            nonSpecularClipPct,
                            effectiveShadowPct,
                        ),
                    )
                )
            }
            if (wants("exposure.tonal_key")) classifyTonalKey(luma, width, height)?.let { facts.add(it) }

            // Subject-dependent detectors (`U-22b`): subject placement, background separation, leading lines
            val subjectBox = if (
                wants(
                    "composition.subject_placement",
                    "depth.background_separation",
                    "composition.leading_lines",
                    "composition.simplicity",
                )
            ) {
                subjectDetector.detectSubject(luma, width, height)
            } else {
                null
            }
            if (subjectBox != null) {
                val nearestPower = POWER_POINTS.minByOrNull { (px, py) ->
                    hypot(subjectBox.centroidX - px, subjectBox.centroidY - py)
                }!!
                val powerDist = hypot(subjectBox.centroidX - nearestPower.first, subjectBox.centroidY - nearestPower.second)
                val centerDist = hypot(subjectBox.centroidX - 0.5, subjectBox.centroidY - 0.5)
                val touchesEdge = subjectBox.xMin <= 0.04 || subjectBox.yMin <= 0.04 ||
                    subjectBox.xMax >= 0.96 || subjectBox.yMax >= 0.96

                val placementValue = when {
                    touchesEdge -> "edge_crowded"
                    powerDist <= 0.12 -> "thirds_aligned"
                    centerDist <= 0.10 -> "centered"
                    else -> "off_center"
                }
                val placementGeometry = String.format(
                    Locale.US,
                    """{"type":"subject_box","xMin":%.3f,"yMin":%.3f,"xMax":%.3f,"yMax":%.3f,"centroidX":%.3f,"centroidY":%.3f,"targetX":%.3f,"targetY":%.3f}""",
                    subjectBox.xMin,
                    subjectBox.yMin,
                    subjectBox.xMax,
                    subjectBox.yMax,
                    subjectBox.centroidX,
                    subjectBox.centroidY,
                    nearestPower.first,
                    nearestPower.second,
                )
                facts.add(
                    TechniqueFact(
                        kind = "composition.subject_placement",
                        value = placementValue,
                        confidence = subjectBox.confidence,
                        geometryJson = placementGeometry,
                    )
                )

                if (!phoneSensor) {
                    val separationValue = when {
                        subjectBox.subjectSharpnessRatio >= 2.2 -> "strong_isolation"
                        subjectBox.subjectSharpnessRatio >= 1.3 -> "moderate_separation"
                        else -> "deep_dof"
                    }
                    facts.add(
                        TechniqueFact(
                            kind = "depth.background_separation",
                            value = separationValue,
                            confidence = subjectBox.confidence,
                            geometryJson = String.format(
                                Locale.US,
                                """{"ratio":%.2f}""",
                                subjectBox.subjectSharpnessRatio,
                            ),
                        )
                    )
                }

                // Leading lines converging toward the detected subject centroid
                if (wants("composition.leading_lines")) {
                    detectLeadingLinesToSubject(luma, width, height, subjectBox)?.let { facts.add(it) }
                }
            }

            // 5. Simplicity
            if (wants("composition.simplicity")) detectSimplicity(luma, width, height, subjectBox)?.let { facts.add(it) }

            // 6. Symmetry
            if (wants("composition.symmetry")) detectSymmetry(luma, width, height)?.let { facts.add(it) }
        } else if (highlightClipping != null || shadowClipping != null) {
            val hPct = highlightClipping ?: 0.0
            val sPct = shadowClipping ?: 0.0
            val clipLabel = when {
                hPct > 2.0 && sPct > 5.0 -> "both_clipped"
                hPct > 2.0 -> if (isRawOrHasRawCompanion) "highlights_recoverable_raw" else "highlights_clipped"
                sPct > 5.0 -> "shadows_clipped"
                else -> "well_exposed"
            }
            facts.add(
                TechniqueFact(
                    kind = "exposure.clipping",
                    value = clipLabel,
                    confidence = 0.75,
                    geometryJson = String.format(
                        Locale.US,
                        """{"nonSpecularHighlightClipPct":%.2f,"shadowClipPct":%.2f}""",
                        hPct,
                        sPct,
                    ),
                )
            )
        }

        return if (only == null) facts else facts.filter { it.kind in only }
    }

    private fun detectLeadingLinesToSubject(
        luma: FloatArray,
        width: Int,
        height: Int,
        subjectBox: SubjectBoundingBox,
    ): TechniqueFact? {
        var convergingCount = 0
        val step = max(2, min(width, height) / 16)
        for (y in step until height - step step step) {
            val ny = y.toDouble() / height
            for (x in step until width - step step step) {
                val nx = x.toDouble() / width
                if (nx in subjectBox.xMin..subjectBox.xMax && ny in subjectBox.yMin..subjectBox.yMax) continue
                val gx = luma[y * width + (x + 1)].toDouble() - luma[y * width + (x - 1)].toDouble()
                val gy = luma[(y + 1) * width + x].toDouble() - luma[(y - 1) * width + x].toDouble()
                val mag = hypot(gx, gy)
                if (mag < 25.0) continue
                // Edge tangent direction is (-gy, gx)
                val tx = -gy / mag
                val ty = gx / mag
                // Distance from subject centroid to line passing through (nx, ny) with direction (tx, ty)
                val dx = subjectBox.centroidX - nx
                val dy = subjectBox.centroidY - ny
                val perpDist = abs(dx * ty - dy * tx)
                if (perpDist <= 0.12) {
                    convergingCount++
                }
            }
        }
        if (convergingCount < 6) return null
        val geometryJson = String.format(
            Locale.US,
            """{"type":"converging_lines","targetX":%.3f,"targetY":%.3f,"count":%d}""",
            subjectBox.centroidX,
            subjectBox.centroidY,
            convergingCount,
        )
        return TechniqueFact(
            kind = "composition.leading_lines",
            value = "converging_to_subject",
            confidence = (0.55 + min(0.35, convergingCount * 0.02)),
            geometryJson = geometryJson,
        )
    }

    private fun computeRedBlueRatio(rgbPixels: IntArray?): Double? {
        if (rgbPixels == null || rgbPixels.isEmpty()) return null
        var rSum = 0L
        var bSum = 0L
        for (px in rgbPixels) {
            rSum += (px shr 16) and 0xFF
            bSum += px and 0xFF
        }
        if (bSum <= 0L) return null
        return rSum.toDouble() / bSum.toDouble()
    }
}
