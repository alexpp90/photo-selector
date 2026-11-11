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
    /** Drops the rating row of a file that no longer exists (a deleted copy), leaving every other row alone. */
    suspend fun deleteRating(uri: String)
    /**
     * A file was deleted: its rating, its cached analysis and its technique facts go with it, so
     * a new file at the same URI (document ids on local and SD storage are paths) starts clean
     * (FD-D-14).
     */
    suspend fun forgetDeletedFile(uri: String)
    suspend fun getRating(uri: String): Int?
    suspend fun getRatings(uris: List<String>): Map<String, Int>
    suspend fun updateCompositionAnalysis(filePath: String, analysis: String?)

    /** How many analysis results are stored (the Settings "Cache" row). */
    suspend fun count(): Int
}

private const val DEFAULT_CACHE_LIMIT = ScoreDao.MRU_LIMIT

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
        return scoreDao.getScores(filePaths)
    }

    override suspend fun updateAccessTimes(filePaths: List<String>, time: Long) {
        if (filePaths.isEmpty()) return
        scoreDao.updateAccessTimes(filePaths, time)
    }

    override suspend fun cacheScore(score: ScoreEntity) {
        scoreDao.insertOrUpdate(score)
    }

    override suspend fun clearAll() {
        scoreDao.deleteAll()
        scoreDao.deleteAllTechniqueFacts()
    }

    override suspend fun count(): Int = scoreDao.getCount()

    override suspend fun pruneToLimit(limit: Int) {
        val count = scoreDao.getCount()
        if (count > limit) {
            scoreDao.pruneToMru(limit)
        }
        scoreDao.deleteTechniqueFactsWithoutScore()
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

    override suspend fun deleteRating(uri: String) {
        scoreDao.deleteUserRating(uri)
    }

    override suspend fun forgetDeletedFile(uri: String) {
        scoreDao.deleteUserRating(uri)
        scoreDao.deleteScore(uri)
        scoreDao.deleteTechniqueFactsForUri(uri)
    }

    override suspend fun getRating(uri: String): Int? {
        return scoreDao.getUserRating(uri)?.rating
            ?: scoreDao.getScore(uri)?.rating?.takeIf { it != 0 }
    }

    override suspend fun getRatings(uris: List<String>): Map<String, Int> {
        if (uris.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Int>()
        scoreDao.getUserRatings(uris).forEach { entity ->
            result[entity.uri] = entity.rating
        }
        return result
    }

    override suspend fun updateCompositionAnalysis(filePath: String, analysis: String?) {
        scoreDao.updateCompositionAnalysis(filePath, analysis)
    }


}
