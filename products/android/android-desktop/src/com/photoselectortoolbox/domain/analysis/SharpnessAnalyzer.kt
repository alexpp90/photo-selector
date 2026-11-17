package com.photoselectortoolbox.domain.analysis

import android.graphics.Bitmap
import javax.inject.Inject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The sharpness of a photo (REQ-AND-ALGO.01): OpenCV turns the bitmap into grey, and
 * [NoiseFloorSharpness] does the maths — centre 50 % crop, a grid of blocks, the Laplacian
 * variance of the crispest block above the sensor-noise floor (`[USER 2026-11-16]` D6). Higher is
 * sharper; the unit is the Laplacian variance, as before the floor.
 */
class SharpnessAnalyzer @Inject constructor() {

    /**
     * Analyze the sharpness of the given bitmap.
     *
     * @param bitmap The image to analyze.
     * @param gridSize Number of blocks in each dimension (gridSize x gridSize).
     * @return The largest block Laplacian variance above the noise floor (higher = sharper).
     */
    fun analyze(bitmap: Bitmap, gridSize: Int = NoiseFloorSharpness.DEFAULT_GRID_SIZE): Double {
        val srcMat = Mat()
        val grayMat = Mat()
        try {
            Utils.bitmapToMat(bitmap, srcMat)
            val width = srcMat.cols()
            val height = srcMat.rows()
            if (width <= 0 || height <= 0) return 0.0
            if (srcMat.channels() > 1) {
                Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            } else {
                srcMat.copyTo(grayMat)
            }
            val luma = ByteArray(width * height)
            grayMat.get(0, 0, luma)
            return NoiseFloorSharpness.score(luma, width, height, gridSize)
        } finally {
            grayMat.release()
            srcMat.release()
        }
    }
}
