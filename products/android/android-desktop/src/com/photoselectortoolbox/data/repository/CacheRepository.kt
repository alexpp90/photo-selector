package com.photoselectortoolbox.data.repository

import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.cache.UserRatingEntity
import javax.inject.Inject
import javax.inject.Singleton

interface CacheRepository {
    suspend fun getCachedScore(filePath: String): ScoreEntity?
    suspend fun getCachedScores(filePaths: List<String>): List<ScoreEntity>
    suspend fun updateAccessTimes(filePaths: List<String>, time: Long = System.currentTimeMillis())
    suspend fun cacheScore(score: ScoreEntity)
    suspend fun clearAll()
    suspend fun pruneToLimit(limit: Int = DEFAULT_CACHE_LIMIT)
    suspend fun updateRating(filePath: String, rating: Int)
    /** A rating belongs to the photograph, not to its address: re-key it when the file moves (D-13). */
    suspend fun moveRating(fromUri: String, toUri: String)
    /** A copy starts with the rating of its original. */
    suspend fun copyRating(fromUri: String, toUri: String)
    suspend fun getRating(uri: String): Int?
    suspend fun getRatings(uris: List<String>): Map<String, Int>
    suspend fun updateCompositionAnalysis(filePath: String, analysis: String?)
}

private const val DEFAULT_CACHE_LIMIT = 10_000
internal const val SQLITE_BIND_CHUNK_SIZE = ScoreDao.SQLITE_MAX_BIND_PARAMS

@Singleton
class CacheRepositoryImpl @Inject constructor(
    private val scoreDao: ScoreDao
) : CacheRepository {

    override suspend fun getCachedScore(filePath: String): ScoreEntity? {
        val entity = scoreDao.getScore(filePath)
        if (entity != null) {
            scoreDao.updateAccessTime(filePath, System.currentTimeMillis())
        }
        return entity
    }

    override suspend fun getCachedScores(filePaths: List<String>): List<ScoreEntity> {
        if (filePaths.isEmpty()) return emptyList()
        return filePaths.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap { chunk ->
            scoreDao.getScores(chunk)
        }
    }

    override suspend fun updateAccessTimes(filePaths: List<String>, time: Long) {
        if (filePaths.isEmpty()) return
        filePaths.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            scoreDao.updateAccessTimes(chunk, time)
        }
    }

    override suspend fun cacheScore(score: ScoreEntity) {
        scoreDao.insertOrUpdate(score)
    }

    override suspend fun clearAll() {
        scoreDao.deleteAll()
        scoreDao.deleteAllTechniqueFacts()
    }

    override suspend fun pruneToLimit(limit: Int) {
        val count = scoreDao.getCount()
        if (count > limit) {
            scoreDao.pruneToMru(limit)
        }
    }

    override suspend fun updateRating(filePath: String, rating: Int) {
        scoreDao.upsertUserRating(
            UserRatingEntity(
                uri = filePath,
                rating = rating,
                updatedAt = System.currentTimeMillis()
            )
        )
        scoreDao.updateRating(filePath, rating)
    }

    override suspend fun moveRating(fromUri: String, toUri: String) {
        if (fromUri == toUri) return
        val rating = scoreDao.getUserRating(fromUri) ?: return
        // New row first: a crash in between leaves two rows, never none.
        scoreDao.upsertUserRating(rating.copy(uri = toUri))
        scoreDao.deleteUserRating(fromUri)
    }

    override suspend fun copyRating(fromUri: String, toUri: String) {
        if (fromUri == toUri) return
        val rating = scoreDao.getUserRating(fromUri) ?: return
        scoreDao.upsertUserRating(rating.copy(uri = toUri))
    }

    override suspend fun getRating(uri: String): Int? {
        return scoreDao.getUserRating(uri)?.rating
            ?: scoreDao.getScore(uri)?.rating?.takeIf { it != 0 }
    }

    override suspend fun getRatings(uris: List<String>): Map<String, Int> {
        if (uris.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Int>()
        uris.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            scoreDao.getUserRatings(chunk).forEach { entity ->
                result[entity.uri] = entity.rating
            }
        }
        return result
    }

    override suspend fun updateCompositionAnalysis(filePath: String, analysis: String?) {
        scoreDao.updateCompositionAnalysis(filePath, analysis)
    }


}
