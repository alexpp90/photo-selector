package com.photoselectortoolbox.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Learn's history tables (shoot debrief SD-3): shoot summaries and camera overrides. */
@Dao
interface LearnDao {

    @Query("SELECT * FROM shoot_summaries WHERE folderKey = :folderKey")
    suspend fun summariesOf(folderKey: String): List<ShootSummaryEntity>

    /**
     * The newest complete summary of [cameraClass] from another folder that ended before
     * [before]: the "previous shoot" of a debrief group. Partial ones are left out.
     */
    @Query(
        """
        SELECT * FROM shoot_summaries
        WHERE cameraClass = :cameraClass AND folderKey != :folderKey AND partial = 0
            AND lastCaptureTime IS NOT NULL AND lastCaptureTime < :before
        ORDER BY lastCaptureTime DESC LIMIT 1
        """
    )
    suspend fun previousSummary(cameraClass: String, folderKey: String, before: Long): ShootSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummaries(summaries: List<ShootSummaryEntity>)

    @Query("DELETE FROM shoot_summaries WHERE folderKey = :folderKey")
    suspend fun deleteSummariesOf(folderKey: String)

    /** [folderKey]'s summaries replaced by [summaries] in one transaction: a class gone from the folder goes. */
    @Transaction
    suspend fun replaceSummaries(folderKey: String, summaries: List<ShootSummaryEntity>) {
        deleteSummariesOf(folderKey)
        upsertSummaries(summaries)
    }

    @Query("SELECT COUNT(DISTINCT folderKey) FROM shoot_summaries")
    suspend fun shootCount(): Int

    @Query("SELECT * FROM camera_overrides")
    suspend fun cameraOverrides(): List<CameraOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCameraOverride(override: CameraOverrideEntity)

    @Query("DELETE FROM shoot_summaries")
    suspend fun deleteAllSummaries()

    @Query("DELETE FROM camera_overrides")
    suspend fun deleteAllCameraOverrides()

    @Query("DELETE FROM lesson_progress")
    suspend fun deleteAllLessonProgress()

    @Query("DELETE FROM practice_goals")
    suspend fun deleteAllPracticeGoals()

    @Query("SELECT COUNT(*) FROM lesson_progress")
    suspend fun lessonCount(): Int

    @Query("SELECT COUNT(*) FROM practice_goals")
    suspend fun goalCount(): Int

    /** *Reset Learn*: summaries, overrides, lesson progress and goals, in one transaction. */
    @Transaction
    suspend fun resetHistory() {
        deleteAllSummaries()
        deleteAllCameraOverrides()
        deleteAllLessonProgress()
        deleteAllPracticeGoals()
    }
}
