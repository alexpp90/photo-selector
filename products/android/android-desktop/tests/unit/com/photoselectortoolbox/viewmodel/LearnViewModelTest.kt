package com.photoselectortoolbox.viewmodel

import androidx.lifecycle.viewModelScope
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.learn.CompletedScan
import com.photoselectortoolbox.testing.InMemoryLearnRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * Learn's view model (shoot debrief SD-1b): what the scan stored reaches its state, a completed
 * lesson and the goal outlive the instance that set them, and *Reset Learn* shows on the next entry.
 */
@Requirement("REQ-AND-LEARN.03", "REQ-AND-LEARN.04")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LearnViewModelTest {

    private val repo = InMemoryLearnRepository()
    private val made = mutableListOf<LearnViewModel>()

    private fun viewModel() = LearnViewModel(repo).also { made += it }

    private val photos = (1..3).map { ImageItem("content://a/$it.jpg", "$it.jpg", 1L, 1L, "image/jpeg") }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        made.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `facts the scan stored reach the state`() {
        val fact = TechniqueFactEntity(photos[0].uri, "composition.horizon", "level", 0.9, null, 1L, 1L, 2)
        repo.facts[photos[0].uri] = listOf(fact)
        val vm = viewModel()
        vm.showFolder("content://a", photos, listingSettled = true, lastCompletedScan = null)
        assertEquals(listOf(fact), vm.state.value.factsByUri[photos[0].uri])
    }

    @Test
    fun `a completed scan of the open folder reads the facts again`() {
        val vm = viewModel()
        vm.showFolder("content://a", photos, listingSettled = true, lastCompletedScan = null)
        assertTrue(vm.state.value.factsByUri.isEmpty())
        val fact = TechniqueFactEntity(photos[1].uri, "composition.horizon", "tilted", 0.9, null, 1L, 1L, 2)
        repo.facts[photos[1].uri] = listOf(fact)
        vm.showFolder("content://a", photos, listingSettled = true, lastCompletedScan = CompletedScan("content://a", 5L, 3, 0, false))
        assertEquals(listOf(fact), vm.state.value.factsByUri[photos[1].uri])
    }

    @Test
    fun `a lesson and the goal survive a new instance`() {
        viewModel().apply {
            completeLesson(LessonProgressEntity("composition.horizon", 10L, 2, 2))
            setGoal(PracticeGoalEntity("composition.horizon", 20L, 0.5, 20))
        }
        val again = viewModel()
        assertTrue("composition.horizon" in again.state.value.completedLessons)
        assertEquals("composition.horizon", again.state.value.activeGoal?.lessonId)
    }

    @Test
    fun `a reset in Setup shows on the next entry`() {
        val vm = viewModel()
        vm.completeLesson(LessonProgressEntity("composition.horizon", 10L, 2, 2))
        repo.progress.clear()
        repo.goal = null
        vm.onEnter()
        assertTrue(vm.state.value.completedLessons.isEmpty())
        assertNull(vm.state.value.activeGoal)
    }
}
