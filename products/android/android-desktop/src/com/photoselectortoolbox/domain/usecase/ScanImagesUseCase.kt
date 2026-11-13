package com.photoselectortoolbox.domain.usecase

import android.net.Uri
import android.util.Log
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.data.source.PhotoBitmapDecoder
import com.photoselectortoolbox.domain.analysis.ClippingAnalyzer
import com.photoselectortoolbox.domain.analysis.NoiseAnalyzer
import com.photoselectortoolbox.domain.analysis.SharpnessAnalyzer
import com.photoselectortoolbox.domain.scoring.ScanMeasures
import com.photoselectortoolbox.domain.scoring.filledFrom
import com.photoselectortoolbox.di.DefaultDispatcher
import com.photoselectortoolbox.di.IoDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Progress state emitted during image scanning.
 */
data class ScanProgress(
    val processed: Int,
    val total: Int,
    val currentFile: String,
    val results: Map<String, ScanResult>
)

/**
 * Orchestrates running the chosen analysis tools (sharpness, noise, clipping;
 * [ScanMeasures]) on a list of images. Checks cache before computing, caches results after,
 * and runs analysis in parallel using coroutines with semaphore-gated concurrency.
 *
 * Optimizations applied:
 * - Multiple images are analyzed concurrently (gated by thread count setting).
 * - Combined clipping analysis avoids duplicate bitmap→Mat/grayscale conversions.
 * - Sharpness and noise analysis run in parallel within each image.
 */
class ScanImagesUseCase @Inject constructor(
    private val sharpnessAnalyzer: SharpnessAnalyzer,
    private val noiseAnalyzer: NoiseAnalyzer,
    private val clippingAnalyzer: ClippingAnalyzer,
    private val scoreDao: ScoreDao,
    private val settingsRepository: SettingsRepository,
    private val bitmapDecoder: PhotoBitmapDecoder,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {

    private companion object {
        const val TAG = "ScanImagesUseCase"
    }

    /**
     * Scan a list of images, emitting progress updates as a Flow.
     * Results are cached and retrieved from cache when available.
     * Multiple images are analyzed concurrently.
     *
     * @param images The images to scan.
     * @param measures The measurements to compute (the Scan sheet's rows). An unticked one is not
     *   computed; a photo whose cached row lacks a ticked one has only that one computed, and the
     *   cached values are kept.
     * @return Flow emitting scan progress with accumulated results.
     */
    operator fun invoke(
        images: List<ImageItem>,
        measures: ScanMeasures = ScanMeasures.ALL,
    ): Flow<ScanProgress> = channelFlow {
        val results = mutableMapOf<String, ScanResult>()

        send(ScanProgress(0, images.size, "", results.toMap()))

        // Read thread count setting (default: min(4, availableProcessors))
        val threadCount = settingsRepository.analysisThreadCount.first()
        val semaphore = Semaphore(threadCount)

        // Bulk load the cache (the DAO splits the list at the SQLite bind limit)
        val imageUris = images.map { it.uri }
        val cachedScores = scoreDao.getScores(imageUris).associateBy { it.filePath }

        // Find which images have valid cache hits and update their access times
        val currentTime = System.currentTimeMillis()
        val validCacheHits = mutableMapOf<String, ScanResult>()
        // A valid row that lacks a ticked measurement: only the missing ones are computed.
        val partialHits = mutableMapOf<String, ScanResult>()
        val urisToUpdate = mutableListOf<String>()

        for (image in images) {
            val cached = cachedScores[image.uri] ?: continue

            // Validate cache entry matches current file
            if (cached.fileSize != image.fileSize || cached.lastModified != image.lastModified) {
                continue
            }

            val cachedResult = ScanResult(
                filePath = image.uri,
                sharpnessScore = cached.sharpnessScore,
                noiseLevel = cached.noiseLevel,
                highlightClipping = cached.highlightClipping,
                shadowClipping = cached.shadowClipping,
                aestheticScore = cached.aestheticScore
            )
            if (measures.missingFrom(cachedResult).any) {
                partialHits[image.uri] = cachedResult
            } else {
                validCacheHits[image.uri] = cachedResult
            }
            urisToUpdate.add(image.uri)
        }

        // Bulk update access times in background
        if (urisToUpdate.isNotEmpty()) {
            launch(ioDispatcher) {
                scoreDao.updateAccessTimes(urisToUpdate, currentTime)
            }
        }

        // Channel for progress updates from concurrent workers
        val progressChannel = Channel<Pair<Int, Pair<String, ScanResult?>>>(Channel.BUFFERED)

        // Launch concurrent image analysis jobs
        for ((index, image) in images.withIndex()) {
            launch(ioDispatcher) {
                semaphore.withPermit {
                    ensureActive()

                    // Check cache first (now from pre-computed map)
                    val cached = validCacheHits[image.uri]
                    if (cached != null) {
                        progressChannel.send(index to (image.fileName to cached))
                        return@withPermit
                    }

                    // Compute what is ticked and not cached yet, and keep what was cached.
                    val partial = partialHits[image.uri]
                    val computed = analyzeImage(image, partial?.let(measures::missingFrom) ?: measures)
                    val scanResult = if (partial != null) {
                        computed?.let { partial.filledFrom(it) } ?: partial
                    } else {
                        computed
                    }
                    if (computed != null && scanResult != null) {
                        cacheResult(image, scanResult)
                    }
                    progressChannel.send(index to (image.fileName to scanResult))
                }
            }
        }

        // Collect progress from workers and emit to downstream
        launch {
            var received = 0
            while (received < images.size) {
                val (_, pair) = progressChannel.receive()
                val (fileName, scanResult) = pair
                received++

                if (scanResult != null) {
                    results[scanResult.filePath] = scanResult
                }

                send(
                    ScanProgress(
                        processed = received,
                        total = images.size,
                        currentFile = fileName,
                        results = results.toMap()
                    )
                )
            }
            progressChannel.close()
            pruneCache()
        }
    }.flowOn(ioDispatcher)

    /**
     * Keeps the cache at its MRU limit after every scan (REQ-AND-CACHE.02, FD-P-12): the limit used
     * to be enforced only when Settings opened, and `technique_facts` never. Ratings live in
     * `user_ratings` and are not touched. A failure here costs disk, not results, so it is logged.
     */
    private suspend fun pruneCache() {
        try {
            if (scoreDao.getCount() > ScoreDao.MRU_LIMIT) scoreDao.pruneToMru(ScoreDao.MRU_LIMIT)
            scoreDao.deleteTechniqueFactsWithoutScore()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not prune the analysis cache", e)
        }
    }

    /**
     * Run all analysis tools on an image.
     * Uses combined clipping analysis to avoid duplicate conversions.
     * Sharpness and noise run in parallel; clipping runs in a third coroutine.
     * Returns null if the image cannot be decoded.
     */
    private suspend fun analyzeImage(
        image: ImageItem,
        measures: ScanMeasures,
    ): ScanResult? = coroutineScope {
        val uri = Uri.parse(image.uri)

        // Decode the bitmap once for all analyzers (≤ 2048 px, software ARGB_8888).
        // RAWs go through their embedded camera JPEG — the platform has no
        // decoder for most of them (CR3 at all) — see PhotoBitmapDecoder.
        val bitmap = bitmapDecoder.decodeForAnalysis(uri, image.fileName)
            ?: return@coroutineScope null

        try {
            // Run sharpness and noise in parallel
            val sharpnessDeferred = async(defaultDispatcher) {
                if (!measures.sharpness) return@async null
                try {
                    sharpnessAnalyzer.analyze(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The metric stays empty for this photo; the others are still computed.
                    Log.w(TAG, "sharpness analysis failed for ${image.uri}", e)
                    null
                }
            }

            val noiseDeferred = async(defaultDispatcher) {
                if (!measures.noise) return@async null
                try {
                    noiseAnalyzer.analyze(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The metric stays empty for this photo; the others are still computed.
                    Log.w(TAG, "noise analysis failed for ${image.uri}", e)
                    null
                }
            }

            // Combined clipping analysis: single bitmap→Mat + grayscale conversion
            val clippingDeferred = async(defaultDispatcher) {
                if (!measures.clipping) return@async null
                try {
                    clippingAnalyzer.analyzeClipping(bitmap)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The metric stays empty for this photo; the others are still computed.
                    Log.w(TAG, "clipping analysis failed for ${image.uri}", e)
                    null
                }
            }

            val clippingPass = clippingDeferred.await()
            val sharpness = sharpnessDeferred.await()

            val noise = noiseDeferred.await()

            // One histogram pass gives both; an unticked half is not kept.
            val clippingResult = clippingPass?.let { (highlights, shadows) ->
                (if (measures.highlightClipping) highlights else null) to (if (measures.shadowClipping) shadows else null)
            }

            // No technique facts here ([USER 2026-11-12] decision #19, plan D-5): nothing read the
            // ones the scan stored; Learn detects them for the photo it shows.
            ScanResult(
                filePath = image.uri,
                sharpnessScore = sharpness,
                noiseLevel = noise,
                highlightClipping = clippingResult?.first,
                shadowClipping = clippingResult?.second,
                aestheticScore = null
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Cache the scan result for future lookups.
     */
    private suspend fun cacheResult(image: ImageItem, result: ScanResult) {
        try {
            scoreDao.insertOrUpdate(
                ScoreEntity(
                    filePath = image.uri,
                    fileSize = image.fileSize,
                    lastModified = image.lastModified,
                    sharpnessScore = result.sharpnessScore,
                    noiseLevel = result.noiseLevel,
                    highlightClipping = result.highlightClipping,
                    shadowClipping = result.shadowClipping,
                    aestheticScore = result.aestheticScore,
                    rating = image.rating
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Non-critical: the result is still shown; the photo is analysed again next time.
            Log.w(TAG, "Could not cache the scores of ${image.uri}", e)
        }
    }
}
