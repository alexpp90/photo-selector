package com.photoselectortoolbox.data.cache

import io.mockk.mockk
import java.util.Collections

/**
 * A [ScoreDao] that records the key lists its raw `IN (:list)` queries (`*Batch`) bind, behind
 * the production chunking entry points ([ScoreDao.getScores], [ScoreDao.updateAccessTimes],
 * [ScoreDao.getUserRatings], [ScoreDao.getTechniqueFacts], [ScoreDao.getTechniqueFactsOfKind]):
 * those run their interface bodies, not the delegate's.
 * Everything else is a relaxed mock.
 */
class RecordingScoreDao(
    private val delegate: ScoreDao = mockk(relaxed = true),
    private val scoreFor: (String) -> ScoreEntity? = { null },
    private val ratingFor: (String) -> UserRatingEntity? = { null },
    private val factsFor: (String) -> List<TechniqueFactEntity> = { emptyList() },
) : ScoreDao by delegate {

    val scoreBatches: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val accessBatches: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val ratingBatches: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val factBatches: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

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

    override suspend fun getTechniqueFactsBatch(uris: List<String>): List<TechniqueFactEntity> {
        factBatches += uris.toList()
        return uris.flatMap(factsFor)
    }

    override suspend fun getTechniqueFactsOfKindBatch(kind: String, uris: List<String>): List<TechniqueFactEntity> {
        factBatches += uris.toList()
        return uris.flatMap(factsFor).filter { it.kind == kind }
    }

    override suspend fun getTechniqueFacts(uris: List<String>): List<TechniqueFactEntity> = super.getTechniqueFacts(uris)

    override suspend fun getTechniqueFactsOfKind(kind: String, uris: List<String>): List<TechniqueFactEntity> =
        super.getTechniqueFactsOfKind(kind, uris)

    override suspend fun getScores(filePaths: List<String>): List<ScoreEntity> = super.getScores(filePaths)

    override suspend fun updateAccessTimes(filePaths: List<String>, time: Long) = super.updateAccessTimes(filePaths, time)

    override suspend fun getUserRatings(uris: List<String>): List<UserRatingEntity> = super.getUserRatings(uris)
}
