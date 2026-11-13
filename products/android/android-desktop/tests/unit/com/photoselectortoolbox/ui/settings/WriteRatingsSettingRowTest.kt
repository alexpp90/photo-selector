package com.photoselectortoolbox.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
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
import io.mockk.coVerify
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
 * The Setup row *Write ratings into photos* (`[USER 2026-11-12]` decision #16): one switch
 * bound to `SettingsRepository.writeRatingsIntoPhotos`, showing the stored value (off on a new
 * install), writing it through `setWriteRatingsIntoPhotos`, and ignoring a sideways drag that
 * ends on it — the setting changes the user's original JPEGs, so it never flips by accident.
 */
@Requirement("REQ-AND-RATING.01")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-xhdpi")
class WriteRatingsSettingRowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

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
        viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            cacheRepository = mockk<CacheRepository>(relaxed = true),
            imageRepository = mockk<ImageRepository>(relaxed = true),
            context = ApplicationProvider.getApplicationContext(),
        )
        rule.setContent { PhotoSelectorToolboxTheme { SettingsScreen(viewModel = viewModel) } }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun row() = rule.onNodeWithTag(TAG).also { it.performScrollTo() }

    private fun state(): ToggleableState? =
        row().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)

    @Test
    fun `the row is one switch named Write ratings into photos, off on a new install`() {
        val config = row().fetchSemanticsNode().config
        assertEquals(Role.Switch, config.getOrNull(SemanticsProperties.Role))
        val name = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        assertTrue("the row reads \"$name\"", name.contains("Write ratings into photos"))
        assertEquals(ToggleableState.Off, state())
    }

    @Test
    fun `a tap stores the setting and the row follows the store`() {
        row().performClick()
        rule.waitForIdle()
        coVerify(exactly = 1) { settingsRepository.setWriteRatingsIntoPhotos(true) }
        assertEquals(ToggleableState.On, state())

        row().performClick()
        rule.waitForIdle()
        coVerify(exactly = 1) { settingsRepository.setWriteRatingsIntoPhotos(false) }
        assertEquals(ToggleableState.Off, state())
    }

    @Test
    fun `an install that already wrote ratings shows the row on`() {
        writeRatings.value = true
        rule.waitForIdle()
        assertEquals(ToggleableState.On, state())
    }

    @Test
    fun `a sideways drag across the row does not flip it`() {
        row().performTouchInput {
            down(Offset(left + 20f, centerY))
            repeat(10) { moveBy(Offset(30f, 0f)) }
            up()
        }
        rule.waitForIdle()
        coVerify(exactly = 0) { settingsRepository.setWriteRatingsIntoPhotos(any()) }
        assertEquals(ToggleableState.Off, state())
    }

    private companion object {
        const val TAG = "settings_write_ratings_into_photos"
    }
}
