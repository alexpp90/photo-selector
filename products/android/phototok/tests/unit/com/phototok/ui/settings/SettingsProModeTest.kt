package com.phototok.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.phototok.R
import com.phototok.data.model.PhoneSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.domain.UiText
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings per mode: Casual shows the Pro mode row and the rows both modes share, Pro adds the
 * power-user rows. The rows are tagged `settings_row_<id>` so the test needs no wording.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
class SettingsProModeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val imageRepository: ImageRepository = mockk(relaxed = true)
    private val received = mutableListOf<UiText>()

    /** Rows both modes show. */
    private val bothModes = listOf(
        "source_folder", "reset_all", "clear_cache", "confirm_delete", "milestones",
    )

    /** Rows only Pro mode shows. */
    private val proOnly = listOf(
        "selection_folder", "raw_jpeg_folders", "selection_location",
        "source_type", "feed_order", "camera_info", "link_raw_jpeg", "format_filter", "swipe_right", "swipe_left",
    )

    private fun host(pro: Boolean) {
        every { settingsRepository.phoneSettings } returns MutableStateFlow(PhoneSettings(proMode = pro))
        every { settingsRepository.selectionFolderName } returns MutableStateFlow("Selection")
        every { settingsRepository.sortingEnabled } returns MutableStateFlow(false)
        every { settingsRepository.phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { settingsRepository.phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { settingsRepository.lastFolderUri } returns MutableStateFlow<String?>("content://tree/work")
        val viewModel = SettingsViewModel(settingsRepository, imageRepository)
        rule.setContent {
            PhotoTokTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onChangeSourceFolder = {},
                    onReloadFolder = {},
                    onFeedback = { received += it },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun count(tag: String) =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun casual_showsTheProModeRowAndOnlyTheSharedRows() {
        host(pro = false)
        rule.onNodeWithTag("settings_pro_mode_toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
        bothModes.forEach { assertEquals("$it shown in Casual", 1, count("settings_row_$it")) }
        proOnly.forEach { assertEquals("$it hidden in Casual", 0, count("settings_row_$it")) }
        assertEquals("Recent folders section hidden in Casual", 0, count("settings_section_recent"))
        // Confirm delete stays a Casual setting: the delete dialog's "Don't ask" writes it.
        assertEquals(1, count("settings_row_confirm_delete"))
    }

    @Test
    fun pro_showsEveryRow() {
        host(pro = true)
        rule.onNodeWithTag("settings_pro_mode_toggle").assertIsOn()
        (bothModes + proOnly).forEach { assertEquals("$it shown in Pro", 1, count("settings_row_$it")) }
        assertEquals("Recent folders section shown in Pro", 1, count("settings_section_recent"))
    }

    @Test
    fun proModeRowIsFirst_aboveStorage() {
        host(pro = false)
        val toggle = rule.onNodeWithTag("settings_pro_mode_toggle").fetchSemanticsNode().boundsInRoot
        val firstRow = rule.onNodeWithTag("settings_row_source_folder").fetchSemanticsNode().boundsInRoot
        assertTrue("Pro mode row above the first Storage row", toggle.bottom <= firstRow.top)
    }

    @Test
    fun switchingProModeAsksTheRepository_andReportsItsMessage() {
        val message = UiText.of(R.string.snackbar_pro_camera_info_on)
        coEvery { settingsRepository.setProMode(true) } returns message
        host(pro = false)

        rule.onNodeWithTag("settings_pro_mode_toggle").performClick()
        rule.waitForIdle()

        coVerify { settingsRepository.setProMode(true) }
        assertEquals("the snackbar text reaches the sheet's host", listOf(message), received)
    }

    @Test
    fun helpButtonOpensTheHelpDialog() {
        host(pro = false)
        rule.onNodeWithTag("settings_pro_mode_help").performClick()
        rule.waitForIdle()
        assertEquals(1, count("pro_mode_help_dialog"))
        rule.onNodeWithTag("pro_mode_help_got_it").performClick()
        rule.waitForIdle()
        assertEquals(0, count("pro_mode_help_dialog"))
        assertTrue(RuntimeEnvironment.getApplication().getString(R.string.cd_pro_mode_help).isNotBlank())
    }
}
