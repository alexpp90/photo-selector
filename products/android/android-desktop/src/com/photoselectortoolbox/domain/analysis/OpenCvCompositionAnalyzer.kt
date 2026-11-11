package com.photoselectortoolbox.domain.analysis

import android.graphics.Bitmap
import com.photoselectortoolbox.di.DefaultDispatcher
import com.photoselectortoolbox.domain.model.CompositionResult
import com.photoselectortoolbox.domain.model.DetectedLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * OpenCV-powered photographic composition analyzer, run on demand by Learn
 * ([com.photoselectortoolbox.domain.usecase.AnalyzeCompositionUseCase]).
 *
 * Operates on the injected CPU dispatcher (`@DefaultDispatcher`) to prevent UI thread blockage.
 * Computes:
 * 1. Leading perspective lines via Canny edge detection and probabilistic Hough transform.
 * 2. Visual mass centroid using Sobel gradient magnitude spatial moments.
 * 3. 3x3 grid mass density distribution.
 */
class OpenCvCompositionAnalyzer @Inject constructor(
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : CompositionAnalyzer {

    companion object {
        const val MAX_IMAGE_DIMENSION = 1000
        const val MAX_LEADING_LINES = 8
        private const val MIN_LINE_LENGTH_RATIO = 0.12f
        private const val COLLINEAR_ANGLE_TOLERANCE_RAD = 0.087 // ~5 degrees
        private const val COLLINEAR_DIST_TOLERANCE = 0.05f // normalized distance

        /**
         * Pure filtering and collinear suppression algorithm for detected lines.
         * Exposed for unit testing without OpenCV JNI dependencies.
         */
        fun filterAndSuppressCollinear(
            rawLines: List<DetectedLine>,
            maxLines: Int = MAX_LEADING_LINES,
            angleToleranceRad: Double = COLLINEAR_ANGLE_TOLERANCE_RAD,
            distTolerance: Float = COLLINEAR_DIST_TOLERANCE
        ): List<DetectedLine> {
            if (maxLines <= 0) return emptyList()

            val sorted = rawLines.sortedByDescending { it.strength }
            val selected = mutableListOf<DetectedLine>()

            for (line in sorted) {
                var isCollinear = false
                val angleLine = normalizeAngle(
                    atan2((line.endY - line.startY).toDouble(), (line.endX - line.startX).toDouble())
                )
                val midX = (line.startX + line.endX) / 2f
                val midY = (line.startY + line.endY) / 2f

                for (chosen in selected) {
                    val angleChosen = normalizeAngle(
                        atan2((chosen.endY - chosen.startY).toDouble(), (chosen.endX - chosen.startX).toDouble())
                    )
                    val dAngle = min(
                        abs(angleLine - angleChosen),
                        PI - abs(angleLine - angleChosen)
                    )

                    if (dAngle < angleToleranceRad) {
                        // Perpendicular distance from candidate midpoint to chosen line
                        val normalX = -sin(angleChosen).toFloat()
                        val normalY = cos(angleChosen).toFloat()
                        val dist = abs((midX - chosen.startX) * normalX + (midY - chosen.startY) * normalY)

                        if (dist < distTolerance) {
                            isCollinear = true
                            break
                        }
                    }
                }

                if (!isCollinear) {
                    selected.add(line)
                    if (selected.size >= maxLines) break
                }
            }

            return selected
        }

        private fun normalizeAngle(angle: Double): Double {
            val mod = ((angle % PI) + PI) % PI
            return mod
        }
    }

    override suspend fun analyze(bitmap: Bitmap): CompositionResult = withContext(dispatcher) {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        val workingBitmap = if (maxDim > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / maxDim
            val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        try {
            analyzeBitmap(workingBitmap)
        } finally {
            if (workingBitmap != bitmap) {
                workingBitmap.recycle()
            }
        }
    }

    private fun analyzeBitmap(bitmap: Bitmap): CompositionResult {
        val srcMat = Mat()
        val grayMat = Mat()
        val blurredMat = Mat()
        val edgesMat = Mat()
        val linesMat = Mat()
        val gradX = Mat()
        val gradY = Mat()
        val gradMag = Mat()

        try {
            Utils.bitmapToMat(bitmap, srcMat)
            if (srcMat.channels() > 1) {
                Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            } else {
                srcMat.copyTo(grayMat)
            }

            val width = grayMat.cols().toFloat()
            val height = grayMat.rows().toFloat()
            if (width <= 0f || height <= 0f) {
                return defaultResult()
            }

            // 1. Leading Lines Detection
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 1.5)
            Imgproc.Canny(blurredMat, edgesMat, 50.0, 150.0)

            val diag = hypot(width.toDouble(), height.toDouble())
            val minLineLength = diag * MIN_LINE_LENGTH_RATIO
            val maxLineGap = diag * 0.03
            val houghThreshold = (min(width, height) * 0.1).toInt().coerceAtLeast(30)

            Imgproc.HoughLinesP(
                edgesMat,
                linesMat,
                1.0,
                Math.PI / 180.0,
                houghThreshold,
                minLineLength,
                maxLineGap
            )

            val rawLines = mutableListOf<DetectedLine>()
            val lineData = IntArray(4)
            for (i in 0 until linesMat.rows()) {
                linesMat.get(i, 0, lineData)
                val x1 = lineData[0].toFloat()
                val y1 = lineData[1].toFloat()
                val x2 = lineData[2].toFloat()
                val y2 = lineData[3].toFloat()
                val length = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
                val strength = (length / diag.toFloat()).coerceIn(0f, 1f)

                rawLines.add(
                    DetectedLine(
                        startX = (x1 / width).coerceIn(0f, 1f),
                        startY = (y1 / height).coerceIn(0f, 1f),
                        endX = (x2 / width).coerceIn(0f, 1f),
                        endY = (y2 / height).coerceIn(0f, 1f),
                        strength = strength
                    )
                )
            }

            val leadingLines = filterAndSuppressCollinear(rawLines, MAX_LEADING_LINES)

            // 2. Visual Mass & Centroids via Sobel and Spatial Moments
            Imgproc.Sobel(grayMat, gradX, CvType.CV_32F, 1, 0)
            Imgproc.Sobel(grayMat, gradY, CvType.CV_32F, 0, 1)
            Core.magnitude(gradX, gradY, gradMag)

            val moments = Geometry.moments(gradMag, false)
            val m00 = moments.m00
            val m10 = moments.m10
            val m01 = moments.m01

            val centroidX: Float
            val centroidY: Float
            if (m00 > 1e-4) {
                centroidX = ((m10 / m00) / width.toDouble()).toFloat().coerceIn(0f, 1f)
                centroidY = ((m01 / m00) / height.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                centroidX = 0.5f
                centroidY = 0.5f
            }

            // 3. 3x3 Grid Mass Distribution
            val gridDistribution = computeGridDistribution(gradMag, width.toInt(), height.toInt())

            return CompositionResult(
                leadingLines = leadingLines,
                massCentroidX = centroidX,
                massCentroidY = centroidY,
                gridMassDistribution = gridDistribution
            )
        } finally {
            srcMat.release()
            grayMat.release()
            blurredMat.release()
            edgesMat.release()
            linesMat.release()
            gradX.release()
            gradY.release()
            gradMag.release()
        }
    }

    private fun computeGridDistribution(gradMag: Mat, width: Int, height: Int): List<Float> {
        val cellSums = FloatArray(9)
        var totalMass = 0f
        var idx = 0

        for (row in 0..2) {
            val yStart = (row * height) / 3
            val yEnd = ((row + 1) * height) / 3
            val cellH = (yEnd - yStart).coerceAtLeast(1)

            for (col in 0..2) {
                val xStart = (col * width) / 3
                val xEnd = ((col + 1) * width) / 3
                val cellW = (xEnd - xStart).coerceAtLeast(1)

                val cellRect = Rect(xStart, yStart, cellW, cellH)
                val cellMat = Mat(gradMag, cellRect)
                try {
                    val sum = Core.sumElems(cellMat).`val`[0].toFloat()
                    cellSums[idx] = sum
                    totalMass += sum
                } finally {
                    cellMat.release()
                }
                idx++
            }
        }

        return if (totalMass > 1e-4f) {
            cellSums.map { (it / totalMass).coerceIn(0f, 1f) }
        } else {
            List(9) { 1f / 9f }
        }
    }

    private fun defaultResult(): CompositionResult = CompositionResult(
        leadingLines = emptyList(),
        massCentroidX = 0.5f,
        massCentroidY = 0.5f,
        gridMassDistribution = List(9) { 1f / 9f }
    )
}
