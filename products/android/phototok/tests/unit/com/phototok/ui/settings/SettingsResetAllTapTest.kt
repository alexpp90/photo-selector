package com.phototok.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.photoselector.core.Requirement
import com.phototok.data.model.PhoneSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SeenRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SettingsViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Reset all seen" clears every folder's seen history in one step and asks nothing, so only a real
 * tap may trigger it: a sideways drag across the row resets nothing (lab 2026-11-05 step 10).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
class SettingsResetAllTapTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } returns MutableStateFlow(PhoneSettings(proMode = false))
        every { selectionFolderName } returns MutableStateFlow("Selection")
        every { sortingEnabled } returns MutableStateFlow(false)
        every { phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
    }
    private val imageRepository: ImageRepository = mockk(relaxed = true)
    private val seenRepository: SeenRepository = mockk(relaxed = true)

    private fun row() = rule.onAllNodes(hasTestTag("settings_row_reset_all"), useUnmergedTree = true).onFirst()

    private fun host() {
        val viewModel = SettingsViewModel(settingsRepository, imageRepository, seenRepository)
        rule.setContent { PhotoTokTheme { SettingsScreen(viewModel = viewModel) } }
        rule.waitForIdle()
        row().performScrollTo()
        rule.waitForIdle()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.16")
    fun aSidewaysDragAcrossTheRowResetsNothing() {
        host()
        row().performTouchInput {
            down(Offset(width * 0.1f, centerY))
            repeat(10) { step -> moveTo(Offset(width * (0.1f + 0.08f * (step + 1)), centerY)) }
            up()
        }
        rule.waitForIdle()
        // The same row still answers a tap afterwards: the gesture was processed, not lost.
        row().performClick()
        rule.waitForIdle()
        coVerify(exactly = 1) { seenRepository.resetAll() }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.16")
    fun aTapResets() {
        host()
        row().performClick()
        rule.waitForIdle()
        coVerify(exactly = 1) { seenRepository.resetAll() }
    }
}
