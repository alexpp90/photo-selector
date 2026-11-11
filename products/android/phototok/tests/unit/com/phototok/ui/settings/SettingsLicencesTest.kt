package com.phototok.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.phototok.R
import com.phototok.data.model.PhoneSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SettingsViewModel
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
 * Settings lists the open-source licences of what the release build bundles (`REQ-TOK-ARCH.20`):
 * the row is on the real Settings screen, opens the generated notice text (the Apache licence
 * and the components it covers), and the dialog stays usable in German at font scale 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
class SettingsLicencesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val imageRepository: ImageRepository = mockk(relaxed = true)

    private fun host() {
        every { settingsRepository.phoneSettings } returns MutableStateFlow(PhoneSettings(proMode = false))
        every { settingsRepository.selectionFolderName } returns MutableStateFlow("Selection")
        every { settingsRepository.sortingEnabled } returns MutableStateFlow(false)
        every { settingsRepository.phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { settingsRepository.phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { settingsRepository.lastFolderUri } returns MutableStateFlow<String?>("content://tree/work")
        val viewModel = SettingsViewModel(settingsRepository, imageRepository)
        rule.setContent {
            PhotoTokTheme {
                SettingsScreen(viewModel = viewModel, onChangeSourceFolder = {}, onReloadFolder = {}, onFeedback = {})
            }
        }
        rule.waitForIdle()
    }

    private fun noticeText(): String =
        rule.onNodeWithTag("licences_text", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }

    @Test
    fun theSettingsScreenHasALicencesRowThatOpensTheBundledNotices() {
        host()
        val title = RuntimeEnvironment.getApplication().getString(R.string.setting_title_licences)
        rule.onNode(androidx.compose.ui.test.hasText(title) and androidx.compose.ui.test.hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("licences_dialog").assertIsDisplayed()
        val text = noticeText()
        assertTrue("names the Apache licence", text.contains("Apache License"))
        assertTrue("lists AndroidX", text.contains("AndroidX"))
        assertTrue("lists Hilt", text.contains("Dagger and Hilt"))
        assertTrue("Jackpix does not bundle OpenCV", !text.contains("OpenCV"))
        rule.onNodeWithTag("licences_close").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasTestTag("licences_dialog")).fetchSemanticsNodes().size)
    }

    @Test
    @Config(sdk = [34], application = Application::class, qualifiers = "de-w360dp-h640dp-xxhdpi", fontScale = 2.0f)
    fun germanAtFontScale2_theDialogIsNamedAndItsCloseButtonIsAFullTarget() {
        rule.setContent { PhotoTokTheme { LicencesDialog(onDismiss = {}) } }
        rule.waitForIdle()
        val dialog = rule.onNodeWithTag("licences_dialog", useUnmergedTree = true).getBoundsInRoot()
        val close = rule.onNodeWithTag("licences_close", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("Close ≥ 48 dp tall (${close.height})", close.height >= 48.dp)
        assertTrue("Close inside the dialog", close.right <= dialog.right && close.bottom <= dialog.bottom)
        assertEquals("Open-Source-Lizenzen", RuntimeEnvironment.getApplication().getString(R.string.setting_title_licences))
    }
}
