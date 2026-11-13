package com.photoselectortoolbox.data.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.photoselector.core.preview.EmbeddedPreviewExtractor
import com.photoselector.core.preview.TiffBitmapDecoder
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.domain.format.ImageFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns any supported photograph into a software `ARGB_8888` bitmap — the one
 * decode path behind both the frames on screen ([decodeForDisplay], used by
 * the Coil fetcher in `PhotoSelectorApp`) and the OpenCV analysers
 * ([decodeForAnalysis], used by `ScanImagesUseCase` and `ImageGrouper`).
 *
 * - **RAW** → the embedded camera JPEG first (fast, no RAW decode; every RAW
 *   the contract requires embeds one), choosing the smallest preview that
 *   reaches the requested size. Where the largest preview is below
 *   [MIN_DISPLAY_LONG_EDGE] (phone DNGs embed ~672 px) the platform decoder is
 *   tried, and the small preview is still used if that fails too.
 * - **TIFF** → [TiffBitmapDecoder]; Android has no platform TIFF codec.
 * - **Everything else** → the platform decoder (`ImageDecoder` on API 28+,
 *   `BitmapFactory` below).
 *
 * Blocking; call from `Dispatchers.IO`.
 */
@Singleton
class PhotoBitmapDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "PhotoBitmapDecoder"

        /** Analysis bitmaps are capped at this long edge (REQ-AND-PERF.09). */
        const val ANALYSIS_MAX_LONG_EDGE = 2048

        /**
         * Below this an embedded preview is not good enough to judge a frame
         * by, and a real decode is attempted instead. The cross-product
         * display minimum (testdata/formats/manifest.json `display`).
         */
        const val MIN_DISPLAY_LONG_EDGE = 1024

        /**
         * The size a [width] × [height] image is decoded at so its long edge is at most
         * [maxLongEdge], aspect kept; null when it already fits (FD-P-11). A power-of-two sample
         * size alone floors: 4000 px / 2048 gave 1, so a 4000 px photo was analysed at full size.
         */
        internal fun sizeWithin(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int>? {
            val longEdge = maxOf(width, height)
            if (width <= 0 || height <= 0 || longEdge <= maxLongEdge) return null
            val scale = maxLongEdge.toDouble() / longEdge
            val w = if (width >= height) maxLongEdge else (width * scale).toInt().coerceAtLeast(1)
            val h = if (height > width) maxLongEdge else (height * scale).toInt().coerceAtLeast(1)
            return w to h
        }

        /** [bitmap] scaled down so its long edge is at most [maxLongEdge]; the original is recycled. */
        internal fun capLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
            val size = sizeWithin(bitmap.width, bitmap.height, maxLongEdge) ?: return bitmap
            val scaled = Bitmap.createScaledBitmap(bitmap, size.first, size.second, true)
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }
    }

    /** A bitmap whose long edge is about [targetLongEdge] (never upscaled). */
    fun decodeForDisplay(uri: Uri, fileName: String, targetLongEdge: Int): Bitmap? =
        decode(uri, fileName, targetLongEdge.coerceAtLeast(1))

    /**
     * The analysis bitmap: long edge at most [ANALYSIS_MAX_LONG_EDGE] (REQ-AND-PERF.09), whatever
     * the decode path — RAW previews and TIFFs come back at or above the target and are scaled
     * down after the decode.
     */
    fun decodeForAnalysis(uri: Uri, fileName: String): Bitmap? =
        decode(uri, fileName, ANALYSIS_MAX_LONG_EDGE)?.let { capLongEdge(it, ANALYSIS_MAX_LONG_EDGE) }

    /**
     * Header-only dimensions (after orientation) for files the platform cannot
     * size — RAWs it has no codec for (CR3), from the embedded preview's frame header, and
     * TIFFs, from IFD0 ([TiffHeader]; FD-C-23). [ImageDimensions.UNKNOWN] otherwise.
     */
    fun containerDimensions(uri: Uri, fileName: String): ImageDimensions {
        if (ImageFormats.isTiff(fileName)) return tiffDimensions(uri)
        if (!ImageFormats.isRaw(fileName)) return ImageDimensions.UNKNOWN
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // The stream is not closed on purpose: the descriptor belongs to `pfd`.
                val preview = EmbeddedPreviewExtractor.find(FileInputStream(pfd.fileDescriptor).channel)
                    ?: return@use ImageDimensions.UNKNOWN
                if (preview.orientation in 5..8) {
                    ImageDimensions.of(preview.height, preview.width)
                } else {
                    ImageDimensions.of(preview.width, preview.height)
                }
            } ?: ImageDimensions.UNKNOWN
        } catch (e: Exception) {
            ImageDimensions.UNKNOWN
        }
    }

    private fun tiffDimensions(uri: Uri): ImageDimensions = try {
        context.contentResolver.openInputStream(uri)?.use(TiffHeader::dimensions) ?: ImageDimensions.UNKNOWN
    } catch (e: Exception) {
        ImageDimensions.UNKNOWN
    }

    private fun decode(uri: Uri, fileName: String, targetLongEdge: Int): Bitmap? = when {
        ImageFormats.isRaw(fileName) -> decodeRaw(uri, targetLongEdge)
        ImageFormats.isTiff(fileName) ->
            TiffBitmapDecoder.decode(context, uri, targetLongEdge)?.toBitmap()
                ?: decodePlatform(uri, targetLongEdge)
        else -> decodePlatform(uri, targetLongEdge)
    }

    private fun decodeRaw(uri: Uri, targetLongEdge: Int): Bitmap? {
        val preview = EmbeddedPreviewExtractor.extract(context, uri, minLongEdge = targetLongEdge)
        val goodEnough = minOf(targetLongEdge, MIN_DISPLAY_LONG_EDGE)
        if (preview != null && preview.preview.longEdge >= goodEnough) {
            preview.decode(targetLongEdge)?.let { return it }
        }
        return decodePlatform(uri, targetLongEdge) ?: preview?.decode(targetLongEdge)
    }

    /**
     * The platform decoders: a software allocation (OpenCV's `bitmapToMat`
     * cannot read HARDWARE bitmaps) whose long edge is at most [maxLongEdge] —
     * exactly, via `setTargetSize`, on API 28+; by a power-of-two sample size
     * that never stays above it below that.
     */
    private fun decodePlatform(uri: Uri, maxLongEdge: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    sizeWithin(info.size.width, info.size.height, maxLongEdge)?.let { (w, h) ->
                        decoder.setTargetSize(w, h)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "ImageDecoder could not decode $uri: ${e.message}")
            }
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
            if (longestEdge <= 0) return null
            var sampleSize = 1
            while (longestEdge / sampleSize > maxLongEdge) sampleSize *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) {
            Log.d(TAG, "BitmapFactory could not decode $uri: ${e.message}")
            null
        }
    }
}
