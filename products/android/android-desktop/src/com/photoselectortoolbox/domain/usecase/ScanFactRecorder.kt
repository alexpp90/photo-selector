package com.photoselectortoolbox.domain.usecase

import android.graphics.Bitmap
import android.util.Log
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.di.DefaultDispatcher
import com.photoselectortoolbox.domain.format.ImageFormats
import com.photoselectortoolbox.domain.learn.ScanFacts
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A photo's EXIF, read for the scan when the photo has none loaded: the cull screen loads EXIF
 * only for the photos on screen and beside them, so the scan saw none for nearly every photo
 * (shoot debrief S12). Bound to `ImageRepository.getExifData` in `di/RepositoryModule`.
 */
fun interface PhotoExifReader {
    suspend fun read(uri: String): ExifData?
}

/**
 * The scan's part of Learn ([USER 2026-11-12] decision #19, option C; shoot debrief SD-1a):
 * which photos' stored facts are current, and detecting and storing [ScanFacts] for a photo the
 * scan has decoded. Facts never fail a scan: a photo whose facts cannot be read, detected or
 * written keeps its scores and is tried again by the next scan.
 */
class ScanFactRecorder(
    private val detector: TechniqueFactDetector,
    private val exifReader: PhotoExifReader,
    private val scoreDao: ScoreDao,
    private val defaultDispatcher: CoroutineDispatcher,
    private val zone: () -> ZoneId,
) {
    @Inject constructor(
        detector: TechniqueFactDetector,
        exifReader: PhotoExifReader,
        scoreDao: ScoreDao,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ) : this(detector, exifReader, scoreDao, defaultDispatcher, ZoneId::systemDefault)

    private companion object {
        const val TAG = "ScanFactRecorder"

        /** The detectors work on a grid of at most this many samples a side. */
        const val SAMPLE_EDGE = 64
    }

    /**
     * The URIs of [images] whose stored facts are current ([ScanFacts.isCurrent]): one query per
     * 899 photos. When the query fails every photo counts as current, so a broken fact table
     * costs Learn its facts, never a second decode of the whole folder.
     */
    suspend fun currentUris(images: List<ImageItem>): Set<String> = try {
        val markers = scoreDao.getTechniqueFactsOfKind(ScanFacts.MARKER, images.map { it.uri })
            .associateBy { it.uri }
        images.filter { image -> markers[image.uri]?.let { ScanFacts.isCurrent(it, image) } == true }
            .mapTo(HashSet()) { it.uri }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read which photos have current facts", e)
        images.mapTo(HashSet()) { it.uri }
    }

    /**
     * Detects [image]'s facts from [bitmap] (the scan's analysis decode, not recycled yet), its
     * EXIF (read here when the photo has none loaded) and its [scores], and replaces its stored
     * rows with them in one transaction. Call on the I/O dispatcher; the detection runs on the
     * default one.
     */
    suspend fun record(image: ImageItem, bitmap: Bitmap, scores: ScanResult) {
        try {
            val exif = image.exifData ?: readExif(image)
            val rows = withContext(defaultDispatcher) {
                val (luma, rgb, w, h) = sample(bitmap)
                val detected = detector.detectFacts(
                    exif = exif,
                    luma = luma,
                    rgbPixels = rgb,
                    width = if (luma != null) w else (image.imageWidth ?: 0),
                    height = if (luma != null) h else (image.imageHeight ?: 0),
                    highlightClipping = scores.highlightClipping,
                    shadowClipping = scores.shadowClipping,
                    isRawOrHasRawCompanion = image.hasRawCompanion || ImageFormats.isRaw(image.fileName),
                    only = ScanFacts.STORED_KINDS,
                    frameLongEdge = maxOf(image.imageWidth ?: 0, image.imageHeight ?: 0).takeIf { it > 0 },
                )
                ScanFacts.entities(image, exif, detected, zone())
            }
            scoreDao.replaceTechniqueFacts(image.uri, rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Facts not stored for ${image.uri}", e)
        }
    }

    private suspend fun readExif(image: ImageItem): ExifData? = try {
        exifReader.read(image.uri)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A photo without readable EXIF still gets its image-based facts.
        Log.w(TAG, "No EXIF for ${image.uri}", e)
        null
    }

    private data class Samples(val luma: FloatArray?, val rgb: IntArray?, val width: Int, val height: Int)

    /** A grid of at most [SAMPLE_EDGE] × [SAMPLE_EDGE] pixels, as luma and as ARGB; none below 8 px. */
    private fun sample(bitmap: Bitmap): Samples {
        val w = bitmap.width.coerceIn(1, SAMPLE_EDGE)
        val h = bitmap.height.coerceIn(1, SAMPLE_EDGE)
        if (bitmap.width < 8 || bitmap.height < 8) return Samples(null, null, w, h)
        val pixels = IntArray(w * h)
        val luma = FloatArray(w * h)
        val stepX = (bitmap.width.toFloat() / w).coerceAtLeast(1f)
        val stepY = (bitmap.height.toFloat() / h).coerceAtLeast(1f)
        for (sy in 0 until h) {
            val py = (sy * stepY).toInt().coerceIn(0, bitmap.height - 1)
            for (sx in 0 until w) {
                val px = (sx * stepX).toInt().coerceIn(0, bitmap.width - 1)
                val argb = bitmap.getPixel(px, py)
                val i = sy * w + sx
                pixels[i] = argb
                luma[i] = 0.299f * ((argb shr 16) and 0xFF) + 0.587f * ((argb shr 8) and 0xFF) + 0.114f * (argb and 0xFF)
            }
        }
        return Samples(luma, pixels, w, h)
    }
}
