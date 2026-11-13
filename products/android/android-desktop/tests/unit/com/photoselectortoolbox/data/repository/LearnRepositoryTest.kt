package com.photoselectortoolbox.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.cache.ScoresDatabase
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Learn's repository over a real Room database (shoot debrief SD-1b, findings S1 and S2): it
 * reads the facts the scan stored, any number of photos, only for the file as it is and the
 * current detector; lesson progress and the one practice goal are kept, and *Clear cache* leaves
 * them alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LearnRepositoryTest {

    private lateinit var db: ScoresDatabase
    private lateinit var repository: LearnRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScoresDatabase::class.java).allowMainThreadQueries().build()
        repository = LearnRepositoryImpl(db.scoreDao(), db.learnDao(), Dispatchers.IO)
    }

    @After
    fun tearDown() = db.close()

    private fun photo(i: Int) = ImageItem("content://f/$i.jpg", "$i.jpg", 100L + i, 1_000L, "image/jpeg")

    private fun fact(p: ImageItem, kind: String, version: Int = TechniqueFactDetector.DETECTOR_VERSION, size: Long = p.fileSize) =
        TechniqueFactEntity(p.uri, kind, "level", 0.9, null, size, p.lastModified, version)

    @Test
    fun `the facts of more than 900 photos are read, current ones only`() = runBlocking {
        val photos = (1..1_000).map(::photo)
        db.scoreDao().upsertTechniqueFacts(photos.map { fact(it, "composition.horizon") })
        val old = photo(2_000)
        val edited = photo(2_001)
        db.scoreDao().upsertTechniqueFacts(
            listOf(fact(old, "composition.horizon", version = 1), fact(edited, "composition.horizon", size = 1L)),
        )

        val facts = repository.factsFor(photos + old + edited)

        assertEquals(1_000, facts.size)
        assertEquals("composition.horizon", facts.getValue(photos.last().uri).single().kind)
        // A row of the old detector, or of the file before it changed, is not read.
        assertTrue(old.uri !in facts && edited.uri !in facts)
    }

    @Test
    fun `progress and the goal survive a new repository, and there is one goal`() = runBlocking {
        repository.saveLessonProgress(LessonProgressEntity("composition.horizon", 5L, 2, 2))
        repository.setPracticeGoal(PracticeGoalEntity("composition.horizon", 10L, 0.4, 25))
        repository.setPracticeGoal(PracticeGoalEntity("exposure.histogram_clipping", 20L, 0.7, 30))

        val reopened = LearnRepositoryImpl(db.scoreDao(), db.learnDao(), Dispatchers.IO)

        assertEquals(setOf("composition.horizon"), reopened.lessonProgress().keys)
        assertEquals("exposure.histogram_clipping", reopened.practiceGoal()?.lessonId)
        assertEquals(1, db.scoreDao().getAllPracticeGoals().size)
        reopened.clearPracticeGoal()
        assertNull(reopened.practiceGoal())
    }

    @Test
    fun `clear cache leaves lesson progress and the goal`() = runBlocking {
        db.scoreDao().insertOrUpdate(ScoreEntity("content://f/1.jpg", 1, 1))
        repository.saveLessonProgress(LessonProgressEntity("composition.horizon", 5L, 2, 2))
        repository.setPracticeGoal(PracticeGoalEntity("composition.horizon", 10L, 0.4, 25))

        CacheRepositoryImpl(db.scoreDao()).clearAll()

        assertEquals(1, repository.lessonProgress().size)
        assertEquals("composition.horizon", repository.practiceGoal()?.lessonId)
    }
}
