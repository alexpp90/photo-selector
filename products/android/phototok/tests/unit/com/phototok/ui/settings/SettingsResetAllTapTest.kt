package com.phototok.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Reset all seen" clears every folder's seen history in one step, so it asks first
 * (REQ-TOK-FEATURE.16): a tap opens "Reset all seen?" with Cancel focused, Reset resets, Cancel does
 * not. Only a real tap opens it: a sideways drag across the row asks nothing (lab 2026-11-05 step 10).
 * "Reset seen photos" for the open folder stays one tap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
class SettingsResetAllTapTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } returns MutableStateFlow(PhoneSettings(proMode = false))
        every { selectionFolderName } returns MutableStateFlow("Selection")
        every { sortingEnabled } returns MutableStateFlow(false)
        every { phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(folder)
    }
    private val imageRepository: ImageRepository = mockk(relaxed = true)
    private val seenRepository: SeenRepository = mockk(relaxed = true)

    private fun row(id: String = "reset_all") = rule.onAllNodes(hasTestTag("settings_row_$id"), useUnmergedTree = true).onFirst()
    private fun dialogShown() = rule.onAllNodes(hasTestTag(RESET_ALL_DIALOG_TAG), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    @After
    fun backToTouchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    private fun host() {
        val viewModel = SettingsViewModel(settingsRepository, imageRepository, seenRepository)
        rule.setContent { PhotoTokTheme { SettingsScreen(viewModel = viewModel) } }
        rule.waitForIdle()
        row().performScrollTo()
        rule.waitForIdle()
    }

    private fun tapRow() {
        row().performClick()
        rule.waitForIdle()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.16")
    fun aSidewaysDragAcrossTheRowAsksNothingAndResetsNothing() {
        host()
        row().performTouchInput {
            down(Offset(width * 0.1f, centerY))
            repeat(10) { step -> moveTo(Offset(width * (0.1f + 0.08f * (step + 1)), centerY)) }
            up()
        }
        rule.waitForIdle()
        assertFalse("a drag opened the confirmation", dialogShown())
        // The same row still answers a tap afterwards: the gesture was processed, not lost.
        tapRow()
        assertTrue(dialogShown())
        coVerify(exactly = 0) { seenRepository.resetAll() }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.16")
    fun aTapAsksFirst_andResetResets() {
        host()
        tapRow()
        assertTrue(dialogShown())
        coVerify(exactly = 0) { seenRepository.resetAll() }
        rule.onAllNodes(hasTestTag(RESET_ALL_CONFIRM_TAG), useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
        assertFalse(dialogShown())
        coVerify(exactly = 1) { seenRepository.resetAll() }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.16")
    fun cancelResetsNothing_andHasTheInitialFocus() {
        // A keyboard or D-pad user: touch mode has no focus to place.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        host()
        tapRow()
        val cancel = rule.onAllNodes(hasTestTag(RESET_ALL_CANCEL_TAG)).onFirst()
        cancel.assertIsFocused()
        val b = cancel.getBoundsInRoot()
        assertTrue("Cancel ≥ 48 dp (${b.height})", b.height >= 48.dp)
        cancel.performClick()
        rule.waitForIdle()
        assertFalse(dialogShown())
        coVerify(exactly = 0) { seenRepository.resetAll() }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.16")
    fun resetSeenForTheOpenFolderStaysOneTap() {
        host()
        row("reset_folder").performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(dialogShown())
        coVerify(exactly = 1) { seenRepository.resetSource(folder) }
    }
}

/** "Alle Gesehenen zurücksetzen?" at font scale 2.0 on a 360 dp phone: both buttons are full targets inside the dialog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class ResetAllSeenDialogTextLoadTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Requirement("REQ-TOK-FEATURE.16", "REQ-TOK-UX.03")
    fun germanAtFontScale2_buttonsAreFullTargetsInsideTheDialog() {
        rule.setContent { PhotoTokTheme { ResetAllSeenDialog(onConfirm = {}, onDismiss = {}) } }
        rule.waitForIdle()
        val dialog = rule.onAllNodes(hasTestTag(RESET_ALL_DIALOG_TAG), useUnmergedTree = true).onFirst().getBoundsInRoot()
        val bounds = listOf(RESET_ALL_CONFIRM_TAG, RESET_ALL_CANCEL_TAG).map { tag ->
            val b = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().getBoundsInRoot()
            assertTrue("$tag ≥ 48 dp tall (${b.height})", b.height >= 48.dp)
            assertTrue("$tag inside the dialog", b.left >= dialog.left && b.right <= dialog.right && b.bottom <= dialog.bottom)
            b
        }
        val (reset, cancel) = bounds
        assertTrue(
            "Reset and Cancel overlap: $reset / $cancel",
            reset.right <= cancel.left || cancel.right <= reset.left || reset.bottom <= cancel.top || cancel.bottom <= reset.top,
        )
    }
}
