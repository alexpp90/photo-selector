package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import com.photoselector.core.Requirement
import com.phototok.data.model.PhoneSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.ui.settings.SettingsScreen
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings sheet's drag handle dismisses the sheet, so it is a control: a 48 x 48 dp target
 * with a spoken name (REQ-TOK-UX.02). Device lab 2026-11-05: the custom handle was 32 x 28 dp and
 * TalkBack read an unlabelled button. The name is M3's own string, so the test reads it from the
 * node instead of hard-coding it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SettingsSheetDragHandleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val imageRepository: ImageRepository = mockk(relaxed = true)

    private fun showSheet() {
        every { settingsRepository.phoneSettings } returns MutableStateFlow(PhoneSettings(proMode = false))
        every { settingsRepository.selectionFolderName } returns MutableStateFlow("Selection")
        every { settingsRepository.sortingEnabled } returns MutableStateFlow(true)
        every { settingsRepository.phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { settingsRepository.phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { settingsRepository.lastFolderUri } returns MutableStateFlow<String?>(null)
        val viewModel = SettingsViewModel(settingsRepository, imageRepository)
        rule.setContent {
            PhotoTokTheme {
                SettingsSheet(onDismissRequest = {}) {
                    SettingsScreen(viewModel = viewModel, onChangeSourceFolder = {}, onReloadFolder = {})
                }
            }
        }
        rule.waitForIdle()
    }

    /** The merged node TalkBack focuses on the handle: the one that offers Dismiss. */
    private fun handle(): SemanticsNode {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .fetchSemanticsNodes()
        assertEquals("nodes offering Dismiss: ${nodes.map { it.config }}", 1, nodes.size)
        return nodes.single()
    }

    private fun assertHandleIsANamedTarget(): String {
        val node = handle()
        val density = rule.density.density
        val bounds = node.boundsInRoot
        assertTrue("handle is ${bounds.width / density} dp wide", bounds.width / density >= 48f - 0.5f)
        assertTrue("handle is ${bounds.height / density} dp tall", bounds.height / density >= 48f - 0.5f)
        val name = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        assertTrue("the handle has no spoken name", name.isNotBlank())
        // The drawn pill stays 32 x 4 dp inside the 48 x 48 dp target (no stretched Surface).
        val pill = rule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(name)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().minBy { it.boundsInRoot.height }.boundsInRoot
        assertEquals(32f, pill.width / density, 0.5f)
        assertEquals(4f, pill.height / density, 0.5f)
        return name
    }

    private fun bounds(tag: String): Rect =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun handleIsA48dpNamedTarget_english() {
        showSheet()
        val name = assertHandleIsANamedTarget()
        println("drag handle name (en): $name")
    }

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-port-440dpi")
    @Requirement("REQ-TOK-UX.02")
    fun handleIsA48dpNamedTarget_german() {
        showSheet()
        val name = assertHandleIsANamedTarget()
        println("drag handle name (de): $name")
    }

    @Test
    fun firstRowSitsBelowTheHandleWithoutAGap() {
        showSheet()
        val density = rule.density.density
        val handle = handle().boundsInRoot
        val firstRow = bounds("settings_pro_mode_toggle")
        val offsetDp = (firstRow.top - handle.top) / density
        println("settings sheet: first row ${offsetDp} dp below the top of the handle target")
        // 48 dp handle target + 0 dp column top padding + 8 dp row padding.
        assertEquals(56f, offsetDp, 1f)
    }
}
