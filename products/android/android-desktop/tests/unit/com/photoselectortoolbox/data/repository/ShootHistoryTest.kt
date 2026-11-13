package com.photoselectortoolbox.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.cache.ScoresDatabase
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.CameraId
import com.photoselectortoolbox.domain.learn.CompletedScan
import com.photoselectortoolbox.domain.learn.DebriefMetric
import com.photoselectortoolbox.domain.learn.FolderKey
import com.photoselectortoolbox.domain.learn.LearnHistoryCounts
import com.photoselectortoolbox.domain.learn.ScanFacts
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import com.photoselectortoolbox.domain.usecase.UpdateShootSummaryUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Shoot summaries over a real database (shoot debrief SD-3): the second shoot with a camera class
 * carries the first as its previous value, after *Clear cache* too; a partial shoot is never the
 * previous one; the folder is a hash, and two spellings of its URI are one shoot; pruning, a file
 * delete and *Clear cache* leave the history; *Reset Learn* empties all four tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ShootHistoryTest {

    private lateinit var db: ScoresDatabase
    private lateinit var dao: ScoreDao
    private lateinit var learn: LearnRepository
    private lateinit var update: UpdateShootSummaryUseCase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScoresDatabase::class.java).allowMainThreadQueries().build()
        dao = db.scoreDao()
        learn = LearnRepositoryImpl(dao, db.learnDao(), Dispatchers.IO)
        update = UpdateShootSummaryUseCase(learn)
    }

    @After
    fun tearDown() = db.close()

    /** [count] photos of [folder] as the scan stores them: a camera, a horizon, a capture time from [from]. */
    private suspend fun shoot(folder: String, count: Int, levelCount: Int, from: Long, model: String = "ILCE-7M4"): List<ImageItem> {
        val photos = (0 until count).map { ImageItem("$folder/document/p$it.jpg", "p$it.jpg", 10L + it, 5L, "image/jpeg") }
        photos.forEachIndexed { i, p ->
            dao.insertOrUpdate(ScoreEntity(p.uri, p.fileSize, p.lastModified, sharpnessScore = 50.0))
            fun f(kind: String, value: String) =
                TechniqueFactEntity(p.uri, kind, value, 0.9, null, p.fileSize, p.lastModified, TechniqueFactDetector.DETECTOR_VERSION)
            dao.replaceTechniqueFacts(
                p.uri,
                listOf(
                    f(ScanFacts.MARKER, "1"),
                    f(ScanFacts.CAMERA_MAKE, "Sony"),
                    f(ScanFacts.CAMERA_MODEL, model),
                    f(ScanFacts.CAPTURE_TIME, (from + i * 1_000L).toString()),
                    f("composition.horizon", if (i < levelCount) "level" else "tilted"),
                ),
            )
        }
        return photos
    }

    private fun done(folder: String, partial: Boolean = false) = CompletedScan(folder, 0L, 1, if (partial) 1 else 0, false)

    private val sony = CameraId("Sony", "ILCE-7M4")

    @Test
    fun `the second shoot with a camera class carries the first as its previous value, after Clear cache too`() = runBlocking {
        learn.setCameraOverride(sony, CameraClass.INTERCHANGEABLE)
        val first = shoot("content://cam/tree/primary%3ADCIM%2FMay", 25, levelCount = 20, from = 1_000_000L)
        update(done("content://cam/tree/primary%3ADCIM%2FMay"), first)
        val second = shoot("content://cam/tree/primary%3ADCIM%2FJune", 30, levelCount = 15, from = 9_000_000L)
        update(done("content://cam/tree/primary%3ADCIM%2FJune"), second)

        CacheRepositoryImpl(dao).clearAll()

        val june = learn.shootOf("content://cam/tree/primary%3ADCIM%2FJune").single()
        assertEquals(CameraClass.INTERCHANGEABLE, june.cameraClass)
        assertEquals(listOf("Sony ILCE-7M4"), june.cameraNames)
        assertEquals(30, june.photoCount)
        assertNotNull(june.previous)
        assertEquals(25, june.previous!!.photoCount)
        assertEquals(80, june.previousPercent(DebriefMetric.LEVEL_HORIZON))
        assertEquals(50, june.metrics.single { it.metric == DebriefMetric.LEVEL_HORIZON }.percent)
    }

    @Test
    fun `a partial shoot is never the previous one, and the previous is the latest before`() = runBlocking {
        update(done("content://a"), shoot("content://a", 20, 20, from = 1_000L))
        update(done("content://b"), shoot("content://b", 20, 10, from = 2_000_000L))
        update(done("content://c", partial = true), shoot("content://c", 20, 0, from = 3_000_000L))
        update(done("content://d"), shoot("content://d", 20, 5, from = 9_000_000L))

        val d = learn.shootOf("content://d").single()
        assertEquals(50, d.previousPercent(DebriefMetric.LEVEL_HORIZON)) // b, not the partial c, not a
        assertTrue(learn.shootOf("content://c").single().partial)
        assertNull(learn.shootOf("content://a").single().previous)
    }

    @Test
    fun `two spellings of one folder URI are one shoot, stored as a hash`() = runBlocking {
        val photos = shoot("content://cam/tree/primary%3ADCIM", 3, 3, from = 1L)
        update(done("content://cam/tree/primary%3ADCIM"), photos)
        update(done("CONTENT://CAM/tree/primary%3aDCIM/"), photos)

        assertEquals(1, learn.historyCounts().shoots)
        val stored = db.learnDao().summariesOf(FolderKey.of("content://cam/tree/primary:DCIM")).single()
        assertTrue(Regex("[0-9a-f]{64}").matches(stored.folderKey))
        assertFalse("DCIM" in stored.folderKey || "DCIM" in stored.metricsJson || "DCIM" in stored.cameraNames)
    }

    @Test
    fun `no column of the two tables holds a coordinate or a path`() = runBlocking {
        val forbidden = Regex("(?i)uri|path|folder(?!Key)|lat|lon|gps|place|location")
        listOf("shoot_summaries", "camera_overrides").forEach { table ->
            val columns = mutableListOf<String>()
            db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { c ->
                while (c.moveToNext()) columns += c.getString(c.getColumnIndexOrThrow("name"))
            }
            assertTrue(table, columns.isNotEmpty())
            assertTrue("$table: $columns", columns.none { forbidden.containsMatchIn(it) })
        }
    }

    @Test
    fun `pruning, a file delete and Clear cache leave the history, Reset Learn empties all four`() = runBlocking {
        val photos = shoot("content://a", 3, 3, from = 1L)
        update(done("content://a"), photos)
        learn.setCameraOverride(sony, CameraClass.COMPACT)
        learn.saveLessonProgress(LessonProgressEntity("composition.horizon", 1L, 2, 2))
        learn.setPracticeGoal(PracticeGoalEntity("composition.horizon", 1L, 0.5, 20))
        val cache = CacheRepositoryImpl(dao)

        dao.pruneToMru(0)
        cache.forgetDeletedFile(photos.first().uri)
        cache.clearAll()

        assertEquals(LearnHistoryCounts(shoots = 1, lessons = 1, goals = 1), learn.historyCounts())
        assertEquals(1, learn.cameraOverrides().size)

        learn.resetHistory()

        assertEquals(LearnHistoryCounts(0, 0, 0), learn.historyCounts())
        assertTrue(learn.cameraOverrides().isEmpty())
    }
}
