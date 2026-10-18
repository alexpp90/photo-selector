package com.photoselectortoolbox.data.repository

import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.cache.UserRatingEntity
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CacheRepositoryImpl with a mocked ScoreDao.
 */
class CacheRepositoryImplTest {

    private lateinit var dao: ScoreDao
    private lateinit var repo: CacheRepositoryImpl

    @Before
    fun setup() {
        dao = mockk(relaxed = true)
        repo = CacheRepositoryImpl(dao)
    }

    @Test
    fun `moveRating re-keys the rating row to the new uri and removes the old one`() = runTest {
        coEvery { dao.getUserRating("old") } returns UserRatingEntity("old", 4, 1L)

        repo.moveRating("old", "new")

        coVerifyOrder {
            dao.upsertUserRating(match { it.uri == "new" && it.rating == 4 })
            dao.deleteUserRating("old")
        }
    }

    @Test
    fun `moveRating without a rating does nothing`() = runTest {
        coEvery { dao.getUserRating("old") } returns null

        repo.moveRating("old", "new")

        coVerify(exactly = 0) { dao.upsertUserRating(any()) }
        coVerify(exactly = 0) { dao.deleteUserRating(any()) }
    }

    @Test
    fun `moveRating to the same uri keeps the row`() = runTest {
        repo.moveRating("same", "same")

        coVerify(exactly = 0) { dao.deleteUserRating(any()) }
    }

    @Test
    fun `copyRating gives the copy the rating and keeps the original's`() = runTest {
        coEvery { dao.getUserRating("src") } returns UserRatingEntity("src", -1, 1L)

        repo.copyRating("src", "copy")

        coVerify { dao.upsertUserRating(match { it.uri == "copy" && it.rating == -1 }) }
        coVerify(exactly = 0) { dao.deleteUserRating(any()) }
    }

    @Test
    fun `deleteRating drops only the named row`() = runTest {
        repo.deleteRating("copy")

        coVerify(exactly = 1) { dao.deleteUserRating("copy") }
        coVerify(exactly = 0) { dao.upsertUserRating(any()) }
    }

    @Test
    fun `getCachedScore returns entity and updates access time`() = runTest {
        val entity = ScoreEntity("path", 100, 200, sharpnessScore = 42.0)
        coEvery { dao.getScore("path") } returns entity
        coEvery { dao.updateAccessTime(any(), any()) } just Runs

        val result = repo.getCachedScore("path")

        assertEquals(42.0, result!!.sharpnessScore!!, 1e-9)
        coVerify { dao.updateAccessTime("path", any()) }
    }

    @Test
    fun `getCachedScore returns null for missing entry`() = runTest {
        coEvery { dao.getScore("missing") } returns null

        val result = repo.getCachedScore("missing")

        assertNull(result)
        coVerify(exactly = 0) { dao.updateAccessTime(any(), any()) }
    }

    @Test
    fun `cacheScore delegates to DAO insertOrUpdate`() = runTest {
        val entity = ScoreEntity("path", 100, 200, noiseLevel = 3.5)
        coEvery { dao.insertOrUpdate(entity) } just Runs

        repo.cacheScore(entity)

        coVerify { dao.insertOrUpdate(entity) }
    }

    @Test
    fun `clearAll delegates to DAO deleteAll and deleteAllTechniqueFacts`() = runTest {
        coEvery { dao.deleteAll() } just Runs
        coEvery { dao.deleteAllTechniqueFacts() } just Runs

        repo.clearAll()

        coVerify { dao.deleteAll() }
        coVerify { dao.deleteAllTechniqueFacts() }
    }

    @Test
    fun `pruneToLimit prunes when count exceeds limit`() = runTest {
        coEvery { dao.getCount() } returns 15000
        coEvery { dao.pruneToMru(10000) } just Runs

        repo.pruneToLimit(10000)

        coVerify { dao.pruneToMru(10000) }
    }

    @Test
    fun `pruneToLimit does nothing when count is under limit`() = runTest {
        coEvery { dao.getCount() } returns 500

        repo.pruneToLimit(10000)

        coVerify(exactly = 0) { dao.pruneToMru(any()) }
    }

    @Test
    fun `pruneToLimit uses default limit of 10000`() = runTest {
        coEvery { dao.getCount() } returns 12000
        coEvery { dao.pruneToMru(10000) } just Runs

        repo.pruneToLimit() // uses default

        coVerify { dao.pruneToMru(10000) }
    }

    @Test
    fun `updateRating upserts UserRatingEntity and updates scores_cache`() = runTest {
        coEvery { dao.upsertUserRating(any()) } just Runs
        coEvery { dao.updateRating("path", 5) } just Runs

        repo.updateRating("path", 5)

        coVerify { dao.upsertUserRating(match { it.uri == "path" && it.rating == 5 }) }
        coVerify { dao.updateRating("path", 5) }
    }

    @Test
    fun `getRating returns user_ratings entry even when scores_cache row is absent`() = runTest {
        coEvery { dao.getUserRating("unscanned") } returns UserRatingEntity("unscanned", 4, 100L)
        coEvery { dao.getScore("unscanned") } returns null

        assertEquals(4, repo.getRating("unscanned"))
    }

    @Test
    fun `getRatings chunks queries and returns map of user ratings`() = runTest {
        coEvery { dao.getUserRatings(listOf("a", "b")) } returns listOf(
            UserRatingEntity("a", 5, 100L),
            UserRatingEntity("b", -1, 101L)
        )

        val result = repo.getRatings(listOf("a", "b"))
        assertEquals(mapOf("a" to 5, "b" to -1), result)
    }

    @Test
    fun `updateCompositionAnalysis delegates to DAO`() = runTest {
        coEvery { dao.updateCompositionAnalysis("path", "analysis_json") } just Runs

        repo.updateCompositionAnalysis("path", "analysis_json")

        coVerify { dao.updateCompositionAnalysis("path", "analysis_json") }
    }

    // REQ-AND-CACHE.03, REQ-AND-CACHE.06
    @Test
    fun `getCachedScores updateAccessTimes and getRatings chunk 2500 URIs into 3 calls of at most 900`() = runTest {
        val uris = (1..2500).map { "content://test/photo_$it.jpg" }
        val scoreChunks = mutableListOf<List<String>>()
        val accessChunks = mutableListOf<List<String>>()
        val ratingChunks = mutableListOf<List<String>>()

        coEvery { dao.getScores(any()) } answers {
            val chunk = firstArg<List<String>>()
            scoreChunks.add(chunk)
            chunk.map { ScoreEntity(it, 100, 200, sharpnessScore = 50.0) }
        }
        coEvery { dao.updateAccessTimes(any(), any()) } answers {
            accessChunks.add(firstArg())
        }
        coEvery { dao.getUserRatings(any()) } answers {
            val chunk = firstArg<List<String>>()
            ratingChunks.add(chunk)
            chunk.map { UserRatingEntity(it, 4, 1000L) }
        }

        val scores = repo.getCachedScores(uris)
        repo.updateAccessTimes(uris, 9999L)
        val ratings = repo.getRatings(uris)

        assertEquals(2500, scores.size)
        assertEquals(2500, ratings.size)
        assertEquals(listOf(900, 900, 700), scoreChunks.map { it.size })
        assertEquals(listOf(900, 900, 700), accessChunks.map { it.size })
        assertEquals(listOf(900, 900, 700), ratingChunks.map { it.size })
        assertTrue(scoreChunks.all { it.size <= 900 })
        assertTrue(accessChunks.all { it.size <= 900 })
        assertTrue(ratingChunks.all { it.size <= 900 })
    }
}
