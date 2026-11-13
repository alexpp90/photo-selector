package com.photoselectortoolbox.testing

import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.LearnRepository
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.CameraId
import com.photoselectortoolbox.domain.learn.DebriefGroup
import com.photoselectortoolbox.domain.learn.LearnHistoryCounts
import com.photoselectortoolbox.domain.learn.ShootSummary

/**
 * Learn's store as the database keeps it, in memory: what a screen or view model test writes
 * through one instance a second instance reads back, as after leaving Learn or a restart.
 */
class InMemoryLearnRepository : LearnRepository {
    val facts = mutableMapOf<String, List<TechniqueFactEntity>>()
    val progress = mutableMapOf<String, LessonProgressEntity>()
    var goal: PracticeGoalEntity? = null
    val overrides = mutableMapOf<CameraId, CameraClass>()
    /** Saved shoots by folder URI: the groups and whether the scan was partial. */
    val shoots = mutableMapOf<String, Pair<List<DebriefGroup>, Boolean>>()
    /** Stored summaries [shootOf] returns, by folder URI (a test sets them, with their previous shoot). */
    val summaries = mutableMapOf<String, List<ShootSummary>>()
    var resets = 0

    override suspend fun factsFor(photos: List<ImageItem>): Map<String, List<TechniqueFactEntity>> =
        photos.mapNotNull { p -> facts[p.uri]?.let { p.uri to it } }.toMap()

    override suspend fun lessonProgress() = progress.toMap()
    override suspend fun saveLessonProgress(progress: LessonProgressEntity) { this.progress[progress.lessonId] = progress }
    override suspend fun practiceGoal() = goal
    override suspend fun setPracticeGoal(goal: PracticeGoalEntity) { this.goal = goal }
    override suspend fun clearPracticeGoal() { goal = null }
    override suspend fun cameraOverrides(): Map<CameraId, CameraClass> = overrides.toMap()
    override suspend fun setCameraOverride(camera: CameraId, cameraClass: CameraClass) { overrides[camera] = cameraClass }
    override suspend fun saveShoot(folderUri: String, groups: List<DebriefGroup>, partial: Boolean, nowEpochMs: Long) {
        shoots[folderUri] = groups to partial
    }
    override suspend fun shootOf(folderUri: String): List<ShootSummary> = summaries[folderUri].orEmpty()
    override suspend fun historyCounts() = LearnHistoryCounts(shoots.size + summaries.size, progress.size, if (goal == null) 0 else 1)
    override suspend fun resetHistory() {
        resets++
        shoots.clear(); summaries.clear(); overrides.clear(); progress.clear(); goal = null
    }
}
