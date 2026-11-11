package com.photoselectortoolbox.domain.usecase

import android.net.Uri
import android.util.Log
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.source.PhotoBitmapDecoder
import com.photoselectortoolbox.di.IoDispatcher
import com.photoselectortoolbox.domain.analysis.CompositionAnalyzer
import com.photoselectortoolbox.domain.model.CompositionResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The composition of one photo for Learn (REQ-AND-COMPOSITION.01, finding B-AD-4): leading
 * lines, visual mass and its 3×3 distribution, from [CompositionAnalyzer].
 *
 * Cache first: the JSON stored in `scores_cache.compositionAnalysis` is used while the row still
 * matches the file's size and date. Otherwise the photo is decoded at the analysis size
 * (≤ 2048 px, [PhotoBitmapDecoder]) on the injected I/O dispatcher, analysed (the analyzer runs on
 * its own CPU dispatcher), and the result stored on the photo's score row when it has one.
 * Cancellation propagates, so a caller that moves to another photo stops the work.
 */
class AnalyzeCompositionUseCase @Inject constructor(
    private val bitmapDecoder: PhotoBitmapDecoder,
    private val analyzer: CompositionAnalyzer,
    private val scoreDao: ScoreDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private companion object {
        const val TAG = "AnalyzeComposition"
    }

    /** The composition of [image], or null when it cannot be decoded or analysed (logged). */
    suspend operator fun invoke(image: ImageItem): CompositionResult? {
        cached(image)?.let { return it }
        val bitmap = withContext(ioDispatcher) {
            bitmapDecoder.decodeForAnalysis(Uri.parse(image.uri), image.fileName)
        } ?: return null
        val result = try {
            analyzer.analyze(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Composition analysis failed for ${image.uri}", e)
            return null
        } finally {
            bitmap.recycle()
        }
        store(image, result)
        return result
    }

    /** The stored result, while the row still describes this file; a failed read is a miss. */
    private suspend fun cached(image: ImageItem): CompositionResult? = withContext(ioDispatcher) {
        try {
            scoreDao.getScore(image.uri)
                ?.takeIf { it.fileSize == image.fileSize && it.lastModified == image.lastModified }
                ?.compositionAnalysis
                ?.let(CompositionResult::fromJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stored composition of ${image.uri} unreadable; analysing again", e)
            null
        }
    }

    /** Best effort: a failed write costs a repeat analysis next time, never the result shown now. */
    private suspend fun store(image: ImageItem, result: CompositionResult) = withContext(ioDispatcher) {
        try {
            scoreDao.updateCompositionAnalysis(image.uri, result.toJson())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not store the composition of ${image.uri}", e)
        }
    }
}
