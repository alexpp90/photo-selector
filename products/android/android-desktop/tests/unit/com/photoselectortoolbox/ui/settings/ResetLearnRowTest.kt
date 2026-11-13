package com.photoselectortoolbox.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SettingsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Setup's *Reset Learn* (shoot debrief SD-3 step 4): the row beside *Reset Guidance* asks first,
 * with the counts of what goes ("Delete 2 shoots, 1 lesson, 1 goal?"); *Reset* empties Learn's
 * history in one call, *Cancel* keeps it.
 */
@Requirement("REQ-AND-LEARN.04")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-xhdpi")
class ResetLearnRowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val learn = com.photoselectortoolbox.testing.InMemoryLearnRepository()

    private val writeRatings = MutableStateFlow(SettingsRepository.DEFAULT_WRITE_RATINGS_INTO_PHOTOS)

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME)
        every { secondarySelectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SECONDARY_SELECTION_FOLDER_NAME)
        every { sortingEnabled } returns MutableStateFlow(SettingsRepository.DEFAULT_SORTING_ENABLED)
        every { customSelectionDestinationUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { groupingEnabled } returns MutableStateFlow(false)
        every { groupingLevel } returns MutableStateFlow(GroupingLevel.TIME_FILENAME)
        every { analysisThreadCount } returns MutableStateFlow(4)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { filingAction } returns MutableStateFlow(FilingAction.DEFAULT)
        every { tvDisplayScaleEnabled } returns MutableStateFlow(false)
        every { writeRatingsIntoPhotos } returns writeRatings
        coEvery { setWriteRatingsIntoPhotos(any()) } answers { writeRatings.value = firstArg() }
    }

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        learn.shoots["content://a"] = emptyList<com.photoselectortoolbox.domain.learn.DebriefGroup>() to false
        learn.shoots["content://b"] = emptyList<com.photoselectortoolbox.domain.learn.DebriefGroup>() to false
        learn.progress["composition.horizon"] = com.photoselectortoolbox.data.cache.LessonProgressEntity("composition.horizon", 1L, 2, 2)
        learn.goal = com.photoselectortoolbox.data.cache.PracticeGoalEntity("composition.horizon", 1L, 0.5, 20)
        viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            cacheRepository = mockk<CacheRepository>(relaxed = true),
            imageRepository = mockk<ImageRepository>(relaxed = true),
            context = ApplicationProvider.getApplicationContext(),
            learnRepository = learn,
        )
        rule.setContent { PhotoSelectorToolboxTheme { SettingsScreen(viewModel = viewModel) } }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun openRow() {
        rule.onAllNodes(hasText("Reset Learn")).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun `the question names what goes`() {
        openRow()
        rule.onNodeWithTag("reset_learn_dialog").assertExists()
        assertEquals(1, rule.onAllNodes(hasText("Delete 2 shoots, 1 lesson, 1 goal?")).fetchSemanticsNodes().size)
    }

    @Test
    fun `Reset empties Learn's history`() {
        openRow()
        rule.onNodeWithTag("reset_learn_confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, learn.resets)
        assertTrue(learn.progress.isEmpty() && learn.goal == null && learn.shoots.isEmpty())
        assertEquals(0, rule.onAllNodesWithTag("reset_learn_dialog").fetchSemanticsNodes().size)
    }

    @Test
    fun `Cancel keeps it`() {
        openRow()
        rule.onNodeWithTag("reset_learn_cancel").performClick()
        rule.waitForIdle()
        assertEquals(0, learn.resets)
        assertEquals(1, learn.progress.size)
    }
}
