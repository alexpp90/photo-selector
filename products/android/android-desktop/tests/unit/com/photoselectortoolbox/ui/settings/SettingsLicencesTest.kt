package com.photoselectortoolbox.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SettingsViewModel
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
 * Settings lists the open-source licences of what the release build bundles
 * (`REQ-AND-PLATFORM.11`): the row is on the real Settings screen and opens the generated notice
 * text, which names OpenCV and Vico (Frameduel's own libraries) next to the Apache licence.
 */
@Requirement("REQ-AND-PLATFORM.11")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-xhdpi")
class SettingsLicencesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME)
        every { secondarySelectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SECONDARY_SELECTION_FOLDER_NAME)
        every { sortingEnabled } returns MutableStateFlow(SettingsRepository.DEFAULT_SORTING_ENABLED)
        every { customSelectionDestinationUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { groupingEnabled } returns MutableStateFlow(true)
        every { analysisThreadCount } returns MutableStateFlow(4)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { tvDisplayScaleEnabled } returns MutableStateFlow(false)
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
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun theSettingsScreenHasALicencesRowThatOpensTheBundledNotices() {
        rule.onNode(hasText("Open-source licences") and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("licences_dialog").assertIsDisplayed()
        val text = rule.onNodeWithTag("licences_text", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("names the Apache licence", text.contains("Apache License"))
        assertTrue("lists OpenCV", text.contains("OpenCV"))
        assertTrue("lists Vico", text.contains("Vico"))
        rule.onNodeWithTag("licences_close").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasTestTag("licences_dialog")).fetchSemanticsNodes().size)
    }
}
