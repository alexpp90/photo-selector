package com.photoselectortoolbox.data.repository

import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.di.IoDispatcher
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What Learn reads and keeps (shoot debrief SD-1b, findings S1 and S2): the facts the scan stored
 * for a folder's photos, the lessons the user completed and the practice goal. Progress and the
 * goal are the user's: *Clear cache* and the score cache's pruning do not touch them
 * (REQ-AND-CACHE.04).
 */
interface LearnRepository {

    /**
     * The stored facts of [photos], by URI: only rows the current detector wrote for the file as
     * it is now (size and date), so a fact of an edited file or of an older detector is not read
     * before the next scan renews it. A photo without such rows is absent.
     */
    suspend fun factsFor(photos: List<ImageItem>): Map<String, List<TechniqueFactEntity>>

    /** Every completed lesson, by lesson id. */
    suspend fun lessonProgress(): Map<String, LessonProgressEntity>

    suspend fun saveLessonProgress(progress: LessonProgressEntity)

    /** The practice goal, or null; the one most recently started if an older version left several. */
    suspend fun practiceGoal(): PracticeGoalEntity?

    /** [goal] becomes the one practice goal. */
    suspend fun setPracticeGoal(goal: PracticeGoalEntity)

    suspend fun clearPracticeGoal()
}

@Singleton
class LearnRepositoryImpl @Inject constructor(
    private val scoreDao: ScoreDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LearnRepository {

    override suspend fun factsFor(photos: List<ImageItem>): Map<String, List<TechniqueFactEntity>> =
        withContext(ioDispatcher) {
            val byUri = photos.associateBy { it.uri }
            scoreDao.getTechniqueFacts(photos.map { it.uri })
                .filter { row ->
                    val photo = byUri[row.uri]
                    photo != null &&
                        row.detectorVersion == TechniqueFactDetector.DETECTOR_VERSION &&
                        row.fileSize == photo.fileSize &&
                        row.lastModified == photo.lastModified
                }
                .groupBy { it.uri }
        }

    override suspend fun lessonProgress(): Map<String, LessonProgressEntity> = withContext(ioDispatcher) {
        scoreDao.getAllLessonProgress().associateBy { it.lessonId }
    }

    override suspend fun saveLessonProgress(progress: LessonProgressEntity) = withContext(ioDispatcher) {
        scoreDao.upsertLessonProgress(progress)
    }

    override suspend fun practiceGoal(): PracticeGoalEntity? = withContext(ioDispatcher) {
        scoreDao.getAllPracticeGoals().maxByOrNull { it.startedAtEpochMs }
    }

    override suspend fun setPracticeGoal(goal: PracticeGoalEntity) = withContext(ioDispatcher) {
        scoreDao.replacePracticeGoal(goal)
    }

    override suspend fun clearPracticeGoal() = withContext(ioDispatcher) {
        scoreDao.deleteAllPracticeGoals()
    }
}
