package com.photoselectortoolbox.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for ScoreDao — runs on a real device or emulator
 * using an in-memory Room database.
 */
@RunWith(AndroidJUnit4::class)
class ScoreDaoTest {

    private lateinit var db: ScoresDatabase
    private lateinit var dao: ScoreDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScoresDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.scoreDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndRetrieve() = runTest {
        val entity = ScoreEntity(
            filePath = "content://media/1",
            fileSize = 1024,
            lastModified = 1700000000000L,
            sharpnessScore = 42.5,
            noiseLevel = 2.1
        )
        dao.insertOrUpdate(entity)

        val result = dao.getScore("content://media/1")
        assertNotNull(result)
        assertEquals(42.5, result!!.sharpnessScore!!, 1e-9)
        assertEquals(2.1, result.noiseLevel!!, 1e-9)
    }

    @Test
    fun getNonExistentReturnsNull() = runTest {
        val result = dao.getScore("nonexistent")
        assertNull(result)
    }

    @Test
    fun insertOrUpdateReplacesExisting() = runTest {
        val original = ScoreEntity("path", 100, 200, sharpnessScore = 10.0)
        dao.insertOrUpdate(original)

        val updated = ScoreEntity("path", 100, 200, sharpnessScore = 99.0)
        dao.insertOrUpdate(updated)

        val result = dao.getScore("path")
        assertEquals(99.0, result!!.sharpnessScore!!, 1e-9)
    }

    @Test
    fun deleteAllClearsTable() = runTest {
        dao.insertOrUpdate(ScoreEntity("a", 1, 1))
        dao.insertOrUpdate(ScoreEntity("b", 2, 2))
        assertEquals(2, dao.getCount())

        dao.deleteAll()
        assertEquals(0, dao.getCount())
    }

    @Test
    fun getCountReturnsCorrectNumber() = runTest {
        assertEquals(0, dao.getCount())
        dao.insertOrUpdate(ScoreEntity("a", 1, 1))
        assertEquals(1, dao.getCount())
        dao.insertOrUpdate(ScoreEntity("b", 2, 2))
        assertEquals(2, dao.getCount())
    }

    @Test
    fun updateAccessTimeModifiesTimestamp() = runTest {
        val entity = ScoreEntity("path", 100, 200, lastAccessTime = 1000L)
        dao.insertOrUpdate(entity)

        dao.updateAccessTime("path", 9999L)

        val result = dao.getScore("path")
        assertEquals(9999L, result!!.lastAccessTime)
    }

    @Test
    fun pruneToMruKeepsOnlyNMostRecent() = runTest {
        // Insert 5 entities with different access times
        for (i in 1..5) {
            dao.insertOrUpdate(
                ScoreEntity("path$i", i.toLong(), i.toLong(), lastAccessTime = i * 1000L)
            )
        }
        assertEquals(5, dao.getCount())

        // Keep only 2 most recently accessed
        dao.pruneToMru(2)

        assertEquals(2, dao.getCount())
        // The two with highest lastAccessTime (path4 & path5) should remain
        assertNotNull(dao.getScore("path5"))
        assertNotNull(dao.getScore("path4"))
        assertNull(dao.getScore("path1"))
    }

    @Test
    fun updateRatingModifiesRatingColumn() = runTest {
        val entity = ScoreEntity("path", 100, 200, rating = 0)
        dao.insertOrUpdate(entity)

        dao.updateRating("path", 5)

        val result = dao.getScore("path")
        assertEquals(5, result!!.rating)
    }

    @Test
    fun updateCompositionAnalysisModifiesAnalysisColumn() = runTest {
        val entity = ScoreEntity("path", 100, 200)
        dao.insertOrUpdate(entity)

        val analysisJson = """{"leadingLines":[{"startX":0.1,"startY":0.2,"endX":0.8,"endY":0.9,"strength":0.95}]}"""
        dao.updateCompositionAnalysis("path", analysisJson)

        val result = dao.getScore("path")
        assertEquals(analysisJson, result!!.compositionAnalysis)
    }
}
