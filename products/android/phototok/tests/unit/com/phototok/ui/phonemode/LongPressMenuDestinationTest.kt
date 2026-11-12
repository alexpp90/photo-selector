package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.domain.PhotoFolders
import com.phototok.domain.ProModePolicy
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The long-press Copy and Move of the real screen name where the photo goes (REQ-TOK-GESTURE.03):
 * `Selection` in Casual on the phone, `Jackpix` on the phone for a Casual card. EN and DE, shown
 * and spoken.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-xxhdpi")
class LongPressMenuDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val screen = PhoneScreenHarness(rule)
    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun menuFor(kind: SourceKind, folder: String) {
        screen.phoneSettings = screen.phoneSettings.copy(proMode = false)
        screen.sourceSettings = ProModePolicy.casualSourceSettings(kind)
        every { screen.settingsRepository.casualCameraTargetUri } returns MutableStateFlow<String?>(null)
        screen.launch()
        screen.openFolder(PhoneScreenHarness.PHOTOS, folder = folder)
        rule.onAllNodes(hasTestTag("viewer.photo")).onFirst().performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("menu_copy_to_selection")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun item(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().single()

    private fun assertNames(destination: String) {
        val copy = item("menu_copy_to_selection")
        val move = item("menu_move_to_selection")
        assertEquals(listOf(res.getString(R.string.cd_copy_to, destination)), copy.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(listOf(res.getString(R.string.cd_move_to, destination)), move.config.getOrNull(SemanticsProperties.ContentDescription))
        val unmerged = rule.onAllNodes(hasTestTag("menu_copy_to_selection"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, unmerged.size)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun casualPhone_namesSelection_english() {
        assertEquals("Copy · Selection", res.getString(R.string.label_copy_to, PhotoFolders.SELECTION))
        menuFor(SourceKind.PHONE_LIBRARY, screen.phoneTree)
        assertNames(PhotoFolders.SELECTION)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03", "REQ-TOK-FEATURE.21")
    fun casualCard_namesJackpix_english() {
        menuFor(SourceKind.REMOVABLE, screen.sdTree)
        assertNames(PhotoFolders.CASUAL_IMPORT)
    }

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-port-xxhdpi")
    @Requirement("REQ-TOK-GESTURE.03", "REQ-TOK-UX.05")
    fun casualPhone_namesSelection_german() {
        assertEquals("Kopieren · Selection", res.getString(R.string.label_copy_to, PhotoFolders.SELECTION))
        menuFor(SourceKind.PHONE_LIBRARY, screen.phoneTree)
        assertNames(PhotoFolders.SELECTION)
    }

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-port-xxhdpi")
    @Requirement("REQ-TOK-GESTURE.03", "REQ-TOK-FEATURE.21", "REQ-TOK-UX.05")
    fun casualCard_namesJackpix_german() {
        menuFor(SourceKind.REMOVABLE, screen.sdTree)
        assertNames(PhotoFolders.CASUAL_IMPORT)
    }
}
