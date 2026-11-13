package com.photoselectortoolbox.data.cache

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `*Batch` rule (FD-Q-13, REQ-AND-CACHE.06): a raw `IN (:list)` query is never a public entry
 * point; the public method takes any number of keys and splits them at
 * [ScoreDao.SQLITE_MAX_BIND_PARAMS].
 */
class ScoreDaoBatchTest {

    private val uris = (1..2500).map { "content://test/photo_$it.jpg" }

    @Test
    fun `the public list methods split 2500 keys into statements of at most 900`() = runTest {
        val dao = RecordingScoreDao(
            scoreFor = { ScoreEntity(it, 1, 1) },
            ratingFor = { UserRatingEntity(it, 3, 1) },
        )

        assertEquals(2500, dao.getScores(uris).size)
        dao.updateAccessTimes(uris, 9L)
        assertEquals(2500, dao.getUserRatings(uris).size)

        listOf(dao.scoreBatches, dao.accessBatches, dao.ratingBatches).forEach { batches ->
            assertEquals(listOf(900, 900, 700), batches.map { it.size })
            assertEquals(uris, batches.flatten())
        }
    }

    // Shoot debrief SD-1b: Learn reads a folder's facts, any number of photos.
    @Test
    fun `the fact reads split 2500 keys, the one with a kind at 899`() = runTest {
        val dao = RecordingScoreDao(
            factsFor = { listOf(TechniqueFactEntity(it, "facts.scanned", "1", 1.0, null, 1, 1, 2)) },
        )

        assertEquals(2500, dao.getTechniqueFacts(uris).size)
        assertEquals(listOf(900, 900, 700), dao.factBatches.map { it.size })
        dao.factBatches.clear()
        assertEquals(2500, dao.getTechniqueFactsOfKind("facts.scanned", uris).size)
        assertEquals(listOf(899, 899, 702), dao.factBatches.map { it.size })
        assertEquals(uris, dao.factBatches.flatten())
    }

    @Test
    fun `an empty list runs no statement`() = runTest {
        val dao = RecordingScoreDao()
        assertTrue(dao.getScores(emptyList()).isEmpty())
        dao.updateAccessTimes(emptyList(), 1L)
        assertTrue(dao.getUserRatings(emptyList()).isEmpty())
        assertTrue(dao.scoreBatches.isEmpty() && dao.accessBatches.isEmpty() && dao.ratingBatches.isEmpty())
    }

    @Test
    fun `every IN query of the DAO is a Batch method`() {
        val source = File("src/com/photoselectortoolbox/data/cache/ScoreDao.kt").readText()
        val queryThenFunction = Regex("""@Query\(\s*(?:\"\"\"([\s\S]*?)\"\"\"|"((?:[^"\\]|\\.)*)")\s*\)\s*(?:suspend\s+)?fun\s+(\w+)""")
        val inQueries = queryThenFunction.findAll(source)
            .filter { match -> Regex("""\bIN\s*\(\s*:""").containsMatchIn(match.groupValues[1] + match.groupValues[2]) }
            .map { it.groupValues[3] }
            .toList()
        assertTrue("found no IN query: is the source path right?", inQueries.isNotEmpty())
        inQueries.forEach { name -> assertTrue("$name binds a list and is not a *Batch method", name.endsWith("Batch")) }
    }
}
