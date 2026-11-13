package com.photoselectortoolbox.data.repository

import android.util.Log
import com.photoselectortoolbox.data.cache.CameraOverrideEntity
import com.photoselectortoolbox.data.cache.LearnDao
import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ShootSummaryEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.di.IoDispatcher
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.CameraId
import com.photoselectortoolbox.domain.learn.DebriefGroup
import com.photoselectortoolbox.domain.learn.DebriefMetric
import com.photoselectortoolbox.domain.learn.FolderKey
import com.photoselectortoolbox.domain.learn.LearnHistoryCounts
import com.photoselectortoolbox.domain.learn.MetricCount
import com.photoselectortoolbox.domain.learn.ShootSummary
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What Learn reads and keeps (shoot debrief SD-1b, findings S1 and S2): the facts the scan stored
 * for a folder's photos, the lessons the user completed and the practice goal. Progress and the
 * goal are the user's: *Clear cache* and the score cache's pruning do not touch them
 * (REQ-AND-CACHE.04). So are the shoot summaries and camera overrides (SD-3), which only
 * [resetHistory] removes.
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

    /** The classes the user set per camera. */
    suspend fun cameraOverrides(): Map<CameraId, CameraClass>

    suspend fun setCameraOverride(camera: CameraId, cameraClass: CameraClass)

    /** [groups] become [folderUri]'s stored summaries, replacing the ones it had (one transaction). */
    suspend fun saveShoot(folderUri: String, groups: List<DebriefGroup>, partial: Boolean, nowEpochMs: Long)

    /** [folderUri]'s stored summaries, each with its previous shoot ([ShootSummary.previous]). */
    suspend fun shootOf(folderUri: String): List<ShootSummary>

    suspend fun historyCounts(): LearnHistoryCounts

    /** *Reset Learn*: summaries, camera overrides, lesson progress and goals, in one transaction. */
    suspend fun resetHistory()
}

@Singleton
class LearnRepositoryImpl @Inject constructor(
    private val scoreDao: ScoreDao,
    private val learnDao: LearnDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LearnRepository {

    private companion object {
        const val TAG = "LearnRepository"
    }

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

    override suspend fun cameraOverrides(): Map<CameraId, CameraClass> = withContext(ioDispatcher) {
        learnDao.cameraOverrides().associate { CameraId(it.make, it.model) to CameraClass.ofKey(it.cameraClass) }
    }

    override suspend fun setCameraOverride(camera: CameraId, cameraClass: CameraClass) = withContext(ioDispatcher) {
        learnDao.upsertCameraOverride(CameraOverrideEntity(camera.make, camera.model, cameraClass.key))
    }

    override suspend fun saveShoot(folderUri: String, groups: List<DebriefGroup>, partial: Boolean, nowEpochMs: Long) =
        withContext(ioDispatcher) {
            val key = FolderKey.of(folderUri)
            learnDao.replaceSummaries(
                key,
                groups.map { group ->
                    ShootSummaryEntity(
                        folderKey = key,
                        cameraClass = group.cameraClass.key,
                        cameraNames = group.cameraNames.joinToString("\n"),
                        firstCaptureTime = group.firstCaptureTime,
                        lastCaptureTime = group.lastCaptureTime,
                        photoCount = group.photoCount,
                        metricsJson = encode(group.metrics),
                        detectorVersion = TechniqueFactDetector.DETECTOR_VERSION,
                        partial = partial,
                        updatedAt = nowEpochMs,
                    )
                },
            )
        }

    override suspend fun shootOf(folderUri: String): List<ShootSummary> = withContext(ioDispatcher) {
        val key = FolderKey.of(folderUri)
        learnDao.summariesOf(key).map { row ->
            val previous = row.firstCaptureTime?.let { first -> learnDao.previousSummary(row.cameraClass, key, first) }
            row.toSummary(previous = previous?.toSummary(previous = null))
        }
    }

    override suspend fun historyCounts(): LearnHistoryCounts = withContext(ioDispatcher) {
        LearnHistoryCounts(learnDao.shootCount(), learnDao.lessonCount(), learnDao.goalCount())
    }

    override suspend fun resetHistory() = withContext(ioDispatcher) {
        learnDao.resetHistory()
    }

    private fun ShootSummaryEntity.toSummary(previous: ShootSummary?) = ShootSummary(
        cameraClass = CameraClass.ofKey(cameraClass),
        cameraNames = cameraNames.split('\n').filter { it.isNotEmpty() },
        firstCaptureTime = firstCaptureTime,
        lastCaptureTime = lastCaptureTime,
        photoCount = photoCount,
        metrics = decode(metricsJson),
        partial = partial,
        updatedAt = updatedAt,
        previous = previous,
    )

    private fun encode(metrics: List<MetricCount>): String = JSONObject().apply {
        metrics.forEach { put(it.metric.key, JSONObject().put("matching", it.matching).put("total", it.total)) }
    }.toString()

    /** The stored counts; a metric this version does not know, or a garbled row, is left out. */
    private fun decode(json: String): List<MetricCount> = try {
        val obj = JSONObject(json)
        obj.keys().asSequence().mapNotNull { key ->
            val metric = DebriefMetric.ofKey(key) ?: return@mapNotNull null
            val count = obj.getJSONObject(key)
            MetricCount(metric, count.getInt("matching"), count.getInt("total"))
        }.toList()
    } catch (e: JSONException) {
        Log.w(TAG, "Unreadable shoot summary metrics", e)
        emptyList()
    }
}
