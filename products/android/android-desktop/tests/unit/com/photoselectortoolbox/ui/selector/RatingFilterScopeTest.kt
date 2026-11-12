package com.photoselectortoolbox.ui.selector

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.ui.fit.FitCell
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.ui.fit.tagExists
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The rating filter is Learn's (REQ-AND-RATING.01): Cull draws no filter, so it must not obey
 * one. A filter set in Learn — the same view model, `setRatingFilter`, which only Learn's
 * `RatingFilterSelector` calls — leaves the cull's filmstrip, arrows and Go-to on every photo,
 * and Learn's own stepping still skips what the filter hides (backlog 2026-11-11).
 */
@Requirement("REQ-AND-RATING.01", "REQ-AND-INSPECT.01")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class RatingFilterScopeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel

    // Newest first: P1 (5★), P2, P3 (5★), P4, P5.
    private val photos = (1..5).map { i -> fitPhoto("P$i.JPG", 10_000L - i).copy(rating = if (i % 2 == 1) 5 else 0) }

    @Before
    fun setUp() {
        installNoImageLoader(ApplicationProvider.getApplicationContext())
        Dispatchers.setMain(dispatcher)
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope, FitSelectorSettings(filmstrip = true), photos,
        )
        val cell = FitCell(1280, 800, 1f)
        rule.setContent {
            PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = viewModel) }
        }
        rule.waitUntil(5_000) { rule.tagExists("column_current") }
    }

    @After
    fun tearDown() {
        removeNoImageLoader()
        viewModel.viewModelScope.cancel()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun current() = viewModel.uiState.value.currentImage?.fileName

    private fun press(key: Key) {
        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** What Learn's filter bar does, then back to the cull (the same activity-scoped view model). */
    private fun filterInLearn() {
        viewModel.setRatingFilter(RatingFilter.ExactRating(5))
        rule.waitForIdle()
        // The filter took: Learn now shows only the three 5★ photos.
        assertEquals(listOf("P1.JPG", "P3.JPG", "P5.JPG"), viewModel.uiState.value.filteredImages.map { it.fileName })
    }

    @Test
    fun `the cull's filmstrip shows every photo while Learn's filter is on`() {
        filterInLearn()

        val tiles = rule.onAllNodes(
            hasTestTag("filmstrip_thumbnail"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("filmstrip tiles in the cull", photos.size, tiles)
    }

    @Test
    fun `the cull's arrows step through every photo while Learn's filter is on`() {
        filterInLearn()
        assertEquals("P1.JPG", current())

        val visited = mutableListOf(current())
        repeat(photos.size - 1) {
            press(Key.DirectionRight)
            visited += current()
        }
        assertEquals(photos.map { it.fileName }, visited)
        repeat(photos.size - 1) { press(Key.DirectionLeft) }
        assertEquals("P1.JPG", current())
    }

    @Test
    fun `Learn's own stepping still skips what its filter hides`() {
        filterInLearn()
        viewModel.navigateNextInLearn()
        assertEquals("P3.JPG", current())
        viewModel.navigatePreviousInLearn()
        assertEquals("P1.JPG", current())
        assertEquals(RatingFilter.ExactRating(5), viewModel.uiState.value.ratingFilter)
    }
}
