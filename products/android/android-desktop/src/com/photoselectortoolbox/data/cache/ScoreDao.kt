package com.photoselectortoolbox.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ScoreDao {
    @Query("SELECT * FROM scores_cache WHERE filePath = :filePath LIMIT 1")
    suspend fun getScore(filePath: String): ScoreEntity?

    /** One `IN (:filePaths)` statement: at most [SQLITE_MAX_BIND_PARAMS] paths. Callers use [getScores]. */
    @Query("SELECT * FROM scores_cache WHERE filePath IN (:filePaths)")
    suspend fun getScoresBatch(filePaths: List<String>): List<ScoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(score: ScoreEntity)

    @Query("DELETE FROM scores_cache")
    suspend fun deleteAll()

    @Query("DELETE FROM scores_cache WHERE filePath = :filePath")
    suspend fun deleteScore(filePath: String)

    @Query(
        """
        DELETE FROM scores_cache WHERE filePath NOT IN (
            SELECT filePath FROM scores_cache ORDER BY lastAccessTime DESC LIMIT :limit
        )
        """
    )
    suspend fun pruneToMru(limit: Int)
    @Query("UPDATE scores_cache SET lastAccessTime = :time WHERE filePath = :filePath")
    suspend fun updateAccessTime(filePath: String, time: Long)

    /** One `IN (:filePaths)` statement: at most [SQLITE_MAX_BIND_PARAMS] paths. Callers use [updateAccessTimes]. */
    @Query("UPDATE scores_cache SET lastAccessTime = :time WHERE filePath IN (:filePaths)")
    suspend fun updateAccessTimesBatch(filePaths: List<String>, time: Long)

    @Query("UPDATE scores_cache SET rating = :rating WHERE filePath = :filePath")
    suspend fun updateRating(filePath: String, rating: Int)

    @Query("UPDATE scores_cache SET compositionAnalysis = :analysis WHERE filePath = :filePath")
    suspend fun updateCompositionAnalysis(filePath: String, analysis: String?)

    @Query("SELECT COUNT(*) FROM scores_cache")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserRating(entity: UserRatingEntity)

    @Query("DELETE FROM user_ratings WHERE uri = :uri")
    suspend fun deleteUserRating(uri: String)

    @Query("SELECT * FROM user_ratings WHERE uri = :uri LIMIT 1")
    suspend fun getUserRating(uri: String): UserRatingEntity?

    /** One `IN (:uris)` statement: at most [SQLITE_MAX_BIND_PARAMS] URIs. Callers use [getUserRatings]. */
    @Query("SELECT * FROM user_ratings WHERE uri IN (:uris)")
    suspend fun getUserRatingsBatch(uris: List<String>): List<UserRatingEntity>

    // ── Any number of keys (FD-Q-13) ─────────────────────────────────────
    //
    // The public entry points for a list of keys: each splits it so no single SQLite
    // statement binds more than SQLITE_MAX_BIND_PARAMS variables (`SQLITE_MAX_VARIABLE_NUMBER`
    // is 999 on older Android builds). The raw `IN (:list)` queries are the `*Batch` methods.

    /** The cached scores of [filePaths], any number of them. */
    suspend fun getScores(filePaths: List<String>): List<ScoreEntity> =
        filePaths.chunked(SQLITE_MAX_BIND_PARAMS).flatMap { chunk -> getScoresBatch(chunk) }

    /** Mark [filePaths], any number of them, as used at [time] (MRU pruning). */
    suspend fun updateAccessTimes(filePaths: List<String>, time: Long) {
        filePaths.chunked(SQLITE_MAX_BIND_PARAMS).forEach { chunk -> updateAccessTimesBatch(chunk, time) }
    }

    /** The user ratings of [uris], any number of them. */
    suspend fun getUserRatings(uris: List<String>): List<UserRatingEntity> =
        uris.chunked(SQLITE_MAX_BIND_PARAMS).flatMap { chunk -> getUserRatingsBatch(chunk) }

    companion object {
        const val SQLITE_MAX_BIND_PARAMS = 900

        /** The analysis cache keeps the most recently used this many rows (REQ-AND-CACHE.02). */
        const val MRU_LIMIT = 10_000
    }

    // ── Technique Facts (U-22a) ───────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTechniqueFacts(facts: List<TechniqueFactEntity>)

    @Query("SELECT * FROM technique_facts WHERE uri = :uri")
    suspend fun getTechniqueFactsForUri(uri: String): List<TechniqueFactEntity>

    @Query("DELETE FROM technique_facts WHERE uri = :uri")
    suspend fun deleteTechniqueFactsForUri(uri: String)

    /** One `IN (:uris)` statement: at most [SQLITE_MAX_BIND_PARAMS] URIs. Callers use [getTechniqueFacts]. */
    @Query("SELECT * FROM technique_facts WHERE uri IN (:uris)")
    suspend fun getTechniqueFactsBatch(uris: List<String>): List<TechniqueFactEntity>

    /** One `IN (:uris)` statement, plus [kind]. Callers use [getTechniqueFactsOfKind]. */
    @Query("SELECT * FROM technique_facts WHERE kind = :kind AND uri IN (:uris)")
    suspend fun getTechniqueFactsOfKindBatch(kind: String, uris: List<String>): List<TechniqueFactEntity>

    /**
     * [uri]'s stored facts replaced by [facts] in one transaction (shoot debrief SD-1a): a kind
     * the detector no longer emits does not outlive the renewal, as it did under the upsert alone.
     */
    @Transaction
    suspend fun replaceTechniqueFacts(uri: String, facts: List<TechniqueFactEntity>) {
        deleteTechniqueFactsForUri(uri)
        upsertTechniqueFacts(facts)
    }

    /** The stored facts of [uris], any number of them. */
    suspend fun getTechniqueFacts(uris: List<String>): List<TechniqueFactEntity> =
        uris.chunked(SQLITE_MAX_BIND_PARAMS).flatMap { chunk -> getTechniqueFactsBatch(chunk) }

    /** The stored facts of one [kind] for [uris], any number of them (the kind is one more bound variable). */
    suspend fun getTechniqueFactsOfKind(kind: String, uris: List<String>): List<TechniqueFactEntity> =
        uris.chunked(SQLITE_MAX_BIND_PARAMS - 1).flatMap { chunk -> getTechniqueFactsOfKindBatch(kind, chunk) }

    @Query("DELETE FROM technique_facts")
    suspend fun deleteAllTechniqueFacts()

    /** Facts are written with a photo's scores; once its score row is pruned they go too (FD-P-12). */
    @Query("DELETE FROM technique_facts WHERE uri NOT IN (SELECT filePath FROM scores_cache)")
    suspend fun deleteTechniqueFactsWithoutScore()

    // ── Lesson Progress (U-26) ────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLessonProgress(progress: LessonProgressEntity)

    @Query("SELECT * FROM lesson_progress WHERE lessonId = :lessonId LIMIT 1")
    suspend fun getLessonProgress(lessonId: String): LessonProgressEntity?

    @Query("SELECT * FROM lesson_progress")
    suspend fun getAllLessonProgress(): List<LessonProgressEntity>

    // ── Practice Goals (U-28) ─────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPracticeGoal(goal: PracticeGoalEntity)

    @Query("SELECT * FROM practice_goals WHERE lessonId = :lessonId LIMIT 1")
    suspend fun getPracticeGoal(lessonId: String): PracticeGoalEntity?

    @Query("SELECT * FROM practice_goals")
    suspend fun getAllPracticeGoals(): List<PracticeGoalEntity>

    @Query("DELETE FROM practice_goals WHERE lessonId = :lessonId")
    suspend fun deletePracticeGoal(lessonId: String)
}
