package com.photoselectortoolbox.data.cache

import io.mockk.mockk
import java.util.Collections

/**
 * A [ScoreDao] that records the key lists its raw `IN (:list)` queries (`*Batch`) bind, behind
 * the production chunking entry points ([ScoreDao.getScores], [ScoreDao.updateAccessTimes],
 * [ScoreDao.getUserRatings]): those three run their interface bodies, not the delegate's.
 * Everything else is a relaxed mock.
 */
class RecordingScoreDao(
    private val delegate: ScoreDao = mockk(relaxed = true),
    private val scoreFor: (String) -> ScoreEntity? = { null },
    private val ratingFor: (String) -> UserRatingEntity? = { null },
) : ScoreDao by delegate {

    val scoreBatches: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val accessBatches: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val ratingBatches: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    override suspend fun getScoresBatch(filePaths: List<String>): List<ScoreEntity> {
        scoreBatches += filePaths.toList()
        return filePaths.mapNotNull(scoreFor)
    }

    override suspend fun updateAccessTimesBatch(filePaths: List<String>, time: Long) {
        accessBatches += filePaths.toList()
    }

    override suspend fun getUserRatingsBatch(uris: List<String>): List<UserRatingEntity> {
        ratingBatches += uris.toList()
        return uris.mapNotNull(ratingFor)
    }

    override suspend fun getScores(filePaths: List<String>): List<ScoreEntity> = super.getScores(filePaths)

    override suspend fun updateAccessTimes(filePaths: List<String>, time: Long) = super.updateAccessTimes(filePaths, time)

    override suspend fun getUserRatings(uris: List<String>): List<UserRatingEntity> = super.getUserRatings(uris)
}
