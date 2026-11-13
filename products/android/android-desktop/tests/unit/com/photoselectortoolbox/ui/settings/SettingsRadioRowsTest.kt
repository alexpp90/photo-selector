package com.photoselectortoolbox.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.testing.en
import com.photoselectortoolbox.testing.str
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
 * The Grouping Level and Filing Action radio groups in Settings (device lab 2026-11-06,
 * `unnamed-control` x9): each choice is ONE node — the whole row — with `Role.RadioButton`, the
 * selected state and a name made of its label (and description), so a screen reader never
 * reads a bare "radio button, not selected". Clicking anywhere on the row, not just on the
 * circle, changes the setting.
 */
@Requirement("REQ-AND-WORKFLOW.02")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-xhdpi")
class SettingsRadioRowsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val groupingFlow = MutableStateFlow(GroupingLevel.TIME_FILENAME)
    private val filingFlow = MutableStateFlow(FilingAction.DEFAULT)

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME)
        every { secondarySelectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SECONDARY_SELECTION_FOLDER_NAME)
        every { sortingEnabled } returns MutableStateFlow(SettingsRepository.DEFAULT_SORTING_ENABLED)
        every { customSelectionDestinationUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        // The grouping levels are shown only while grouping is on.
        every { groupingEnabled } returns MutableStateFlow(true)
        every { groupingLevel } returns groupingFlow
        every { analysisThreadCount } returns MutableStateFlow(4)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { filingAction } returns filingFlow
        every { tvDisplayScaleEnabled } returns MutableStateFlow(false)
        coEvery { setGroupingLevel(any()) } answers { groupingFlow.value = firstArg() }
        coEvery { setFilingAction(any()) } answers { filingFlow.value = firstArg() }
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

    private fun groupingTag(level: GroupingLevel) = "settings_grouping_${level.name.lowercase()}"
    private fun filingTag(action: FilingAction) = "settings_filing_${action.name.lowercase()}"

    private fun row(tag: String) = rule.onNodeWithTag(tag).also { it.performScrollTo() }

    private fun config(tag: String) = row(tag).fetchSemanticsNode().config

    private fun mergedName(tag: String): String =
        config(tag).getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    private fun assertOneNamedRadio(tag: String, label: String, description: String?, selected: Boolean) {
        val config = config(tag)
        assertEquals("$tag is a radio button", Role.RadioButton, config.getOrNull(SemanticsProperties.Role))
        assertEquals("$tag selected state", selected, config.getOrNull(SemanticsProperties.Selected))
        val name = mergedName(tag)
        assertTrue("$tag has a name containing \"$label\" but reads \"$name\"", name.contains(label))
        if (description != null) assertTrue("$tag name carries its description: \"$name\"", name.contains(description))
        // The circle is a picture, not a second control: nothing inside the row is clickable on its own.
        val inner = rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasClickAction(), useUnmergedTree = true)
        assertEquals("$tag must not contain a separate clickable radio", 0, inner.fetchSemanticsNodes().size)
        val height = row(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("$tag is only $height tall", height >= 48.dp)
    }

    @Test
    fun `every grouping level is one named radio row with its selected state`() {
        for (level in GroupingLevel.entries) {
            assertOneNamedRadio(
                tag = groupingTag(level),
                label = str(level.label),
                description = str(level.description),
                selected = level == GroupingLevel.TIME_FILENAME,
            )
        }
    }

    @Test
    fun `every filing action is one named radio row with its selected state`() {
        for (action in FilingAction.entries) {
            assertOneNamedRadio(
                tag = filingTag(action),
                label = action.verb.en(),
                description = null,
                selected = action == FilingAction.DEFAULT,
            )
        }
    }

    @Test
    fun `clicking a grouping row changes the level, anywhere on the row`() {
        val other = GroupingLevel.entries.first { it != GroupingLevel.TIME_FILENAME }
        // Far right: neither the circle nor the words.
        row(groupingTag(other)).performTouchInput { click(Offset(width - 8f, height / 2f)) }
        rule.waitForIdle()

        assertEquals(other, groupingFlow.value)
        assertEquals(true, config(groupingTag(other)).getOrNull(SemanticsProperties.Selected))
        assertEquals(false, config(groupingTag(GroupingLevel.TIME_FILENAME)).getOrNull(SemanticsProperties.Selected))
    }

    @Test
    fun `clicking a filing row changes the action, anywhere on the row`() {
        val other = FilingAction.entries.first { it != FilingAction.DEFAULT }
        row(filingTag(other)).performTouchInput { click(Offset(width - 8f, height / 2f)) }
        rule.waitForIdle()

        assertEquals(other, filingFlow.value)
        assertEquals(true, config(filingTag(other)).getOrNull(SemanticsProperties.Selected))
        // The label alone is also a hit.
        row(filingTag(FilingAction.DEFAULT)).performClick()
        rule.waitForIdle()
        assertEquals(FilingAction.DEFAULT, filingFlow.value)
    }
}
