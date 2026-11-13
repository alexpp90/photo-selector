package com.photoselectortoolbox.ui.inspect

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.domain.learn.ScanFacts
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import com.photoselectortoolbox.testing.InMemoryLearnRepository
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.viewmodel.LearnCompositionViewModel
import com.photoselectortoolbox.viewmodel.LearnViewModel
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Learn reads what the scan stored and keeps what the user did (shoot debrief SD-1b), through
 * `InspectScreen` with the cull screen's real view model and Learn's own — not through
 * `InspectScreenContent`, whose empty default for the facts hid finding S1: a lesson takes its
 * example from a stored fact, a completed lesson is still marked after Learn is opened anew, and an
 * answered quiz question says in words whether it was right.
 */
@Requirement("REQ-AND-LEARN.03", "REQ-AND-LEARN.04")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class LearnStoredStateScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val viewModels = mutableListOf<ViewModel>()
    private val repo = InMemoryLearnRepository()
    private val photos = listOf(fitPhoto("A.JPG", 3_000), fitPhoto("B.JPG", 2_000), fitPhoto("C.JPG", 1_000))

    @Before
    fun setUp() {
        installNoImageLoader(context)
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        removeNoImageLoader()
        viewModels.forEach { it.viewModelScope.cancel() }
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun stored(uri: String, kind: String, value: String) = listOf(
        TechniqueFactEntity(uri, ScanFacts.MARKER, "1", 1.0, null, 1L, 3_000L, TechniqueFactDetector.DETECTOR_VERSION),
        TechniqueFactEntity(uri, kind, value, 0.9, null, 1L, 3_000L, TechniqueFactDetector.DETECTOR_VERSION),
    )

    /** Learn's view model on screen; a new one is Learn opened anew (after leaving it, or a restart). */
    private val learn = mutableStateOf<LearnViewModel?>(null)

    private fun newLearn() = LearnViewModel(repo).also { viewModels += it }

    private fun host() {
        learn.value = newLearn()
        val selector: SelectorViewModel = fitSelectorViewModel(context, appScope, photos = photos).also { viewModels += it }
        val composition = LearnCompositionViewModel(mockk(relaxed = true)).also { viewModels += it }
        rule.setContent {
            val current = learn.value ?: return@setContent
            key(current) {
                InspectScreen(
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                    viewModel = selector,
                    compositionViewModel = composition,
                    learnViewModel = current,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun openHorizonLesson() {
        rule.onNodeWithTag(LearnTab.LESSONS.tag).performClick()
        rule.onNodeWithTag("lesson_item_composition.horizon").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun `a lesson takes its example from the facts the scan stored`() {
        repo.facts[photos[0].uri] = stored(photos[0].uri, "composition.horizon", "level")
        repo.facts[photos[1].uri] = stored(photos[1].uri, "composition.horizon", "tilted")
        host()
        openHorizonLesson()
        val fromLibrary = rule.onAllNodes(hasText("In your library", substring = true) and hasAnyAncestorTagged("lesson_detail_card"))
        assertEquals("the quiz asks about the user's own photos", 1, fromLibrary.fetchSemanticsNodes().size)
    }

    @Test
    fun `a completed lesson is still marked when Learn is opened anew`() {
        host()
        openHorizonLesson()
        answerEveryQuestion()
        // Learn is opened anew: a new view model over the same store.
        learn.value = newLearn()
        rule.waitForIdle()
        rule.onNodeWithTag(LearnTab.LESSONS.tag).performClick()
        rule.waitForIdle()
        val done = rule.onAllNodes(
            hasContentDescription("Completed") and hasAnyAncestorTagged("lesson_item_composition.horizon"),
            useUnmergedTree = true,
        )
        assertEquals(1, done.fetchSemanticsNodes().size)
        assertTrue("composition.horizon" in repo.progress)
    }

    @Test
    fun `an answered question says whether it was right`() {
        host()
        openHorizonLesson()
        assertEquals(0, rule.onAllNodesWithTag("quiz_explanation_0", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("quiz_option_0_0").performScrollTo().performClick()
        rule.waitForIdle()
        val said = rule.onNodeWithTag("quiz_explanation_0", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue(said, said.startsWith("Right.") || said.startsWith("Not quite."))
    }

    @Test
    fun `Learn carries no Beta label`() {
        // Decision #18's exit condition is met: Compare shows both frames and progress is stored.
        host()
        rule.onNodeWithTag("back_to_cull_button").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("beta_chip", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("Beta", ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun answerEveryQuestion() {
        for (q in 0..1) {
            rule.onNodeWithTag("quiz_option_${q}_0").performScrollTo().performClick()
            rule.waitForIdle()
        }
    }

    private fun hasAnyAncestorTagged(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))
}
