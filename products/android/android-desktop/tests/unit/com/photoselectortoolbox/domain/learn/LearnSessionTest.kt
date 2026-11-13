package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.LearnRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Learn's stored state (shoot debrief SD-1b): the facts the scan stored reach it once per settled
 * folder and once per completed scan of that folder; a late result for a folder that is no longer
 * open is dropped; lesson progress and the goal outlive the session that set them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LearnSessionTest {

    /** A repository that keeps what it is given, as the database does, and records fact reads. */
    private class FakeRepository : LearnRepository {
        val stored = mutableMapOf<String, List<TechniqueFactEntity>>()
        val progress = mutableMapOf<String, LessonProgressEntity>()
        var goal: PracticeGoalEntity? = null
        val factReads = mutableListOf<List<String>>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun factsFor(photos: List<ImageItem>): Map<String, List<TechniqueFactEntity>> {
            factReads += photos.map { it.uri }
            photos.firstOrNull()?.let { gates[it.uri.substringBeforeLast('/')]?.await() }
            return photos.mapNotNull { p -> stored[p.uri]?.let { p.uri to it } }.toMap()
        }
        override suspend fun lessonProgress() = progress.toMap()
        override suspend fun saveLessonProgress(progress: LessonProgressEntity) { this.progress[progress.lessonId] = progress }
        override suspend fun practiceGoal() = goal
        override suspend fun setPracticeGoal(goal: PracticeGoalEntity) { this.goal = goal }
        override suspend fun clearPracticeGoal() { goal = null }
        override suspend fun cameraOverrides(): Map<CameraId, CameraClass> = emptyMap()
        override suspend fun setCameraOverride(camera: CameraId, cameraClass: CameraClass) = Unit
        override suspend fun saveShoot(folderUri: String, groups: List<DebriefGroup>, partial: Boolean, nowEpochMs: Long) = Unit
        override suspend fun shootOf(folderUri: String): List<ShootSummary> = emptyList()
        override suspend fun historyCounts() = LearnHistoryCounts(0, progress.size, if (goal == null) 0 else 1)
        override suspend fun resetHistory() { progress.clear(); goal = null }
    }

    private fun photos(folder: String) = (1..3).map { ImageItem("$folder/$it.jpg", "$it.jpg", 1L, 1L, "image/jpeg") }

    private fun fact(uri: String) = TechniqueFactEntity(uri, "composition.horizon", "level", 0.9, null, 1L, 1L, 2)

    private fun TestScope.session(repo: LearnRepository) =
        LearnSession(repo, TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `facts stored by the scan reach the state once the listing has settled`() = runTest {
        val repo = FakeRepository()
        val a = photos("content://a")
        repo.stored[a[0].uri] = listOf(fact(a[0].uri))
        val session = session(repo)

        session.showFolder("content://a", a.take(1), listingSettled = false, lastCompletedScan = null)
        session.showFolder("content://a", a.take(2), listingSettled = false, lastCompletedScan = null)
        assertTrue("no read while the folder is still being listed", repo.factReads.isEmpty())

        session.showFolder("content://a", a, listingSettled = true, lastCompletedScan = null)
        session.showFolder("content://a", a, listingSettled = true, lastCompletedScan = null)

        assertEquals(1, repo.factReads.size)
        assertEquals(listOf(fact(a[0].uri)), session.state.value.factsByUri[a[0].uri])
    }

    @Test
    fun `a completed scan of this folder reloads, one of another folder does not`() = runTest {
        val repo = FakeRepository()
        val a = photos("content://a")
        val session = session(repo)
        session.showFolder("content://a", a, true, null)

        session.showFolder("content://a", a, true, CompletedScan("content://b", 5L, 3, 0, false))
        assertEquals(1, repo.factReads.size)

        repo.stored[a[1].uri] = listOf(fact(a[1].uri))
        session.showFolder("content://a", a, true, CompletedScan("content://a", 6L, 3, 0, false))
        assertEquals(2, repo.factReads.size)
        assertEquals(setOf(a[1].uri), session.state.value.factsByUri.keys)
    }

    @Test
    fun `a late result for a folder that is no longer open is dropped`() = runTest {
        val repo = FakeRepository()
        val a = photos("content://a")
        val b = photos("content://b")
        repo.stored[a[0].uri] = listOf(fact(a[0].uri))
        repo.gates["content://a"] = CompletableDeferred()
        val session = session(repo)

        session.showFolder("content://a", a, true, null)
        session.showFolder("content://b", b, true, null)
        repo.gates.getValue("content://a").complete(Unit)

        assertEquals("content://b", session.state.value.folderUri)
        assertTrue(session.state.value.factsByUri.keys.none { it.startsWith("content://a") })
    }

    @Test
    fun `a completed lesson and the goal survive a new session`() = runTest {
        val repo = FakeRepository()
        val first = session(repo)
        first.completeLesson(LessonProgressEntity("composition.horizon", 5L, 1, 2))
        first.setGoal(PracticeGoalEntity("composition.horizon", 10L, 0.5, 20))

        val second = session(repo)

        assertEquals(setOf("composition.horizon"), second.state.value.completedLessons.keys)
        assertEquals("composition.horizon", second.state.value.activeGoal?.lessonId)
        second.clearGoal()
        assertEquals(null, session(repo).state.value.activeGoal)
    }
}
