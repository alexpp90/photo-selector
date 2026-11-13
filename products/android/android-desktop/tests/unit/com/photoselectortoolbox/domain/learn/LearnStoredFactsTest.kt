package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Learn on the facts the scan stored (shoot debrief SD-1b): a practice goal counts photos by when
 * they were taken (S4), and a lesson quiz does not always put the right answer first (S3).
 */
class LearnStoredFactsTest {

    private val goalStart = 1_000_000L

    private fun photo(name: String, fileTime: Long) = ImageItem("content://f/$name", name, 1L, fileTime, "image/jpeg")

    private fun facts(uri: String, captureTime: Long?) = listOfNotNull(
        TechniqueFactEntity(uri, "composition.horizon", "level", 0.9, null, 1L, 1L, 2),
        captureTime?.let { TechniqueFactEntity(uri, ScanFacts.CAPTURE_TIME, it.toString(), 0.95, null, 1L, 1L, 2) },
    )

    @Test
    fun `goal progress counts a photo by its capture time, the file date only without one`() {
        // Taken before the goal, copied to the card after it: not progress.
        val oldShotNewFile = photo("old.jpg", fileTime = goalStart + 5_000)
        // Taken after the goal, its file date older (a camera clock ahead of the copy): progress.
        val newShotOldFile = photo("new.jpg", fileTime = goalStart - 5_000)
        val newShotOldFile2 = photo("new2.jpg", fileTime = goalStart - 9_000)
        // No capture time: its file date decides.
        val noCaptureTime = photo("bare.jpg", fileTime = goalStart + 1)
        val factsByUri = mapOf(
            oldShotNewFile.uri to facts(oldShotNewFile.uri, captureTime = goalStart - 86_400_000),
            newShotOldFile.uri to facts(newShotOldFile.uri, captureTime = goalStart + 60_000),
            newShotOldFile2.uri to facts(newShotOldFile2.uri, captureTime = goalStart + 120_000),
            noCaptureTime.uri to facts(noCaptureTime.uri, captureTime = null),
        )
        val goal = PracticeGoalEntity("composition.horizon", goalStart, 0.5, 20)

        val status = PracticeAndInsightsEngine.evaluateGoalProgress(
            goal, listOf(oldShotNewFile, newShotOldFile, newShotOldFile2, noCaptureTime), factsByUri,
        )

        // The two shot after the goal and the one without a capture time; by file dates it was 2.
        assertEquals(3, status.recentSampleSize)
    }

    @Test
    fun `over all lessons the right answer is not always at the same position`() {
        val positions = LessonCatalog.ALL_LESSONS.flatMap { lesson ->
            LessonCatalog.buildSpotItQuiz(lesson).map { it.correctIndex }
        }

        assertTrue("right answers at $positions", positions.toSet().size > 1)
    }

    @Test
    fun `a question keeps its order for the same lesson and photo, and the right answer stays right`() {
        LessonCatalog.ALL_LESSONS.forEach { lesson ->
            val first = LessonCatalog.buildSpotItQuiz(lesson)
            val again = LessonCatalog.buildSpotItQuiz(lesson)
            assertEquals(first, again)
            first.zip(listOf(lesson.fallbackOptions[lesson.fallbackCorrectIndex], lesson.tryThisPrompt)).forEach { (q, right) ->
                assertEquals(right, q.options[q.correctIndex])
            }
        }
    }
}
