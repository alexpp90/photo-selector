package com.photoselectortoolbox.data.cache

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.photoselectortoolbox.data.repository.CacheRepositoryImpl
import com.photoselectortoolbox.testing.ScaleConditions
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cached-score restore of a folder of the PR size (testdata/conditions/conditions.json)
 * against a real in-memory Room database, every SQL statement counted. A folder open restores
 * its scores and ratings in statements of at most [ScoreDao.SQLITE_MAX_BIND_PARAMS] keys, never
 * one query per photo, and the prune at the end of a scan keeps a folder of that size whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScoreRestoreScaleTest {

    private val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var db: ScoresDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScoresDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> statements += sql }, Executor { it.run() })
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun uris(n: Int, folder: String = "shoot") = (1..n).map { "content://scale/$folder/IMG_$it.jpg" }

    private suspend fun store(uris: List<String>, accessedAt: Long) = db.withTransaction {
        val dao = db.scoreDao()
        uris.forEach { uri ->
            dao.insertOrUpdate(ScoreEntity(uri, 1_000L, 1_000L, 80.0, 5.0, 0.1, 0.2, lastAccessTime = accessedAt))
            dao.upsertUserRating(UserRatingEntity(uri, 3, accessedAt))
        }
    }

    private fun selectsOn(table: String) = statements.count { it.trimStart().startsWith("SELECT", ignoreCase = true) && table in it }

    /**
     * REQ-AND-CACHE.03, REQ-AND-CACHE.06: the scores and ratings of a folder of the PR size come
     * back in ⌈n / 900⌉ statements each, every row of them.
     *
     * Scale: pr
     */
    @Test
    fun `a folder of the PR size restores its scores and ratings in chunked statements`() = runBlocking {
        val n = ScaleConditions.prPhotos
        val folder = uris(n)
        store(folder, accessedAt = 1L)
        val repository = CacheRepositoryImpl(db.scoreDao())
        val chunks = (n + ScoreDao.SQLITE_MAX_BIND_PARAMS - 1) / ScoreDao.SQLITE_MAX_BIND_PARAMS
        statements.clear()

        val scores = repository.getCachedScores(folder)
        val ratings = repository.getRatings(folder)

        assertEquals(n, scores.size)
        assertEquals(n, ratings.size)
        assertEquals("one statement per ${ScoreDao.SQLITE_MAX_BIND_PARAMS} scores", chunks, selectsOn("scores_cache"))
        assertEquals("one statement per ${ScoreDao.SQLITE_MAX_BIND_PARAMS} ratings", chunks, selectsOn("user_ratings"))
    }

    /**
     * REQ-AND-CACHE.03: the batch restore grows linearly with the folder.
     *
     * Scale: pr
     */
    @Test
    fun `the batch score restore grows linearly with the folder`() = runBlocking {
        val folder = uris(ScaleConditions.prPhotos)
        store(folder, accessedAt = 1L)
        val repository = CacheRepositoryImpl(db.scoreDao())
        ScaleConditions.assertGrowth("batch score restore (Room)") { n ->
            val wanted = folder.take(n);
            { runBlocking { check(repository.getCachedScores(wanted).size == n) } }
        }
        Unit
    }

    /**
     * REQ-AND-CACHE.02: the prune at the end of a scan keeps the [ScoreDao.MRU_LIMIT] most recently
     * used rows — a whole folder of the PR size, just used — and drops the older folder's rows;
     * ratings are never pruned.
     *
     * Scale: pr
     */
    @Test
    fun `the MRU prune keeps a just-used folder of the PR size whole`() = runBlocking {
        val current = uris(ScaleConditions.prPhotos)
        val older = uris(ScaleConditions.baseN, folder = "older")
        store(older, accessedAt = 1L)
        store(current, accessedAt = 2L)
        val repository = CacheRepositoryImpl(db.scoreDao())
        repository.updateAccessTimes(current, time = 3L)

        repository.pruneToLimit()

        assertTrue(
            "the MRU limit (${ScoreDao.MRU_LIMIT}) holds a folder of the PR size (${current.size})",
            ScoreDao.MRU_LIMIT >= current.size,
        )
        assertEquals(minOf(ScoreDao.MRU_LIMIT, current.size + older.size), repository.count())
        assertEquals(current.size, repository.getCachedScores(current).size)
        assertEquals(current.size + older.size, repository.getRatings(current + older).size)
    }
}
