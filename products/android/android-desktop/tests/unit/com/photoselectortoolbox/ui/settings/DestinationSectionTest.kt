package com.photoselectortoolbox.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.curation.DestinationSettings
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.DestinationUiState
import com.photoselectortoolbox.viewmodel.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Settings → "Where photos go" (`[USER 2026-11-04]`: "I do not understand where the pictures
 * go when they get moved. There should be a selection: Custom Path or Working folder with the
 * full transparency what that folder is currently"), hosted with plain state so every branch
 * of the section is on screen: both radio rows, the path line of each state, the warning for a
 * lost folder, and what a tap asks for.
 */
@Requirement("REQ-AND-WORKFLOW.02")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h1200dp-xhdpi")
class DestinationSectionTest {

    @get:Rule
    val rule = createComposeRule()

    private val trip = FolderDescription(
        uri = "content://trip", displayPath = listOf("Internal storage", "DCIM", "Trip"), name = "Trip",
        hasPersistedGrant = true, exists = true, canWrite = true,
    )
    private val keepers = FolderDescription(
        uri = "content://keepers", displayPath = listOf("SD card", "Photos", "Keepers"), name = "Keepers",
        hasPersistedGrant = true, exists = true, canWrite = true,
    )

    private var usedWorking = 0
    private var chose = 0
    private var savedName: String? = null
    private var sorting: Boolean? = null

    private fun host(
        opened: FolderDescription? = null,
        custom: FolderDescription? = null,
        sortingOn: Boolean = true,
        selectionName: String = "Selection",
    ) {
        val settings = DestinationSettings(
            customDestinationUri = custom?.uri,
            selectionFolderName = selectionName,
            sortingEnabled = sortingOn,
        )
        val state = SettingsUiState(
            selectionFolderName = selectionName,
            sortingEnabled = sortingOn,
            destination = DestinationUiState.of(settings, custom, opened),
        )
        rule.setContent {
            PhotoSelectorToolboxTheme {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    DestinationSection(
                        uiState = state,
                        onUseWorkingFolder = { usedWorking++ },
                        onChooseCustom = { chose++ },
                        onSaveSelectionName = { savedName = it },
                        onSaveSecondPassName = {},
                        onSortingChange = { sorting = it },
                    )
                }
            }
        }
    }

    private fun text(tag: String): String? = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    /** A button's word lives on its child, so read the merged node a screen reader reads. */
    private fun mergedText(tag: String): String? = rule.onNodeWithTag(tag).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun selected(tag: String): Boolean? =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)

    @Test
    fun `a fresh install is the working folder and says where the photos will go once a folder is open`() {
        host()

        assertEquals(true, selected("destination_working"))
        assertEquals(false, selected("destination_custom"))
        assertEquals("«Selection» inside the folder you open — no folder open yet", text("destination_path"))
    }

    @Test
    fun `with a folder open the working folder shows the full path, ending in RAW and JPEG`() {
        host(opened = trip)

        assertEquals("Internal storage › DCIM › Trip › Selection › RAW · JPEG", text("destination_path"))
    }

    @Test
    fun `the path ends at the Selection folder exactly when RAW and JPEG folders are off`() {
        host(opened = trip, sortingOn = false)

        assertEquals("Internal storage › DCIM › Trip › Selection", text("destination_path"))
    }

    @Test
    fun `a renamed Selection folder is the one the path names`() {
        host(opened = trip, selectionName = "Picks")

        assertEquals("Internal storage › DCIM › Trip › Picks › RAW · JPEG", text("destination_path"))
    }

    @Test
    fun `re-culling a Selection folder says second pass and names the second-pass folder`() {
        host(opened = trip.copy(name = "Selection", hasSelectionMarker = true))

        assertEquals("Internal storage › DCIM › Trip › Final › RAW · JPEG", text("destination_path"))
        assertEquals("Second pass — the open folder is a Selection folder", text("destination_second_pass"))
    }

    @Test
    fun `a custom folder is selected, its path shown, and the button says Change`() {
        host(opened = trip, custom = keepers)

        assertEquals(false, selected("destination_working"))
        assertEquals(true, selected("destination_custom"))
        assertEquals("SD card › Photos › Keepers › Selection › RAW · JPEG", text("destination_path"))
        assertEquals("Change", mergedText("destination_choose"))
        assertFalse("no warning for a reachable folder", exists("destination_warning"))
    }

    @Test
    fun `with no custom folder the button says Choose`() {
        host(opened = trip)

        assertEquals("Choose", mergedText("destination_choose"))
    }

    @Test
    fun `a custom folder without permission shows the warning, Folder unavailable and the reason`() {
        host(opened = trip, custom = keepers.copy(hasPersistedGrant = false))

        rule.onNodeWithTag("destination_warning", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("Folder unavailable · No permission — choose the folder again", text("destination_problem"))
        // The path is still shown, so the user can see which folder is meant.
        assertEquals("SD card › Photos › Keepers › Selection › RAW · JPEG", text("destination_path"))
    }

    @Test
    fun `a missing custom folder and a read-only one each say why`() {
        host(opened = trip, custom = keepers.copy(exists = false))
        assertEquals("Folder unavailable · The folder is missing", text("destination_problem"))
    }

    @Test
    fun `a read-only custom folder says so`() {
        host(opened = trip, custom = keepers.copy(canWrite = false))
        assertEquals("Folder unavailable · The folder is read-only", text("destination_problem"))
    }

    @Test
    fun `each radio row is a whole 48 dp row with radio semantics`() {
        host(opened = trip)

        listOf("destination_working", "destination_custom").forEach { tag ->
            val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            val min = with(rule.density) { 48.dp.roundToPx() }
            assertTrue("$tag ${node.size}", node.size.height >= min && node.size.width >= min)
            assertEquals(Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
        }
        val choose = rule.onNodeWithTag("destination_choose", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(choose.size.height >= with(rule.density) { 48.dp.roundToPx() })
    }

    @Test
    fun `tapping Custom with no folder asks for the picker and changes nothing itself`() {
        host(opened = trip)

        rule.onNodeWithTag("destination_custom").performClick()

        assertEquals(1, chose)
        assertEquals(0, usedWorking)
        // The state is the ViewModel's: until a folder comes back the choice is still Working.
        assertEquals(true, selected("destination_working"))
    }

    @Test
    fun `Choose opens the picker too`() {
        host(opened = trip)

        rule.onNodeWithTag("destination_choose").performClick()

        assertEquals(1, chose)
    }

    @Test
    fun `tapping Custom while a custom folder is set does not reopen the picker, Change does`() {
        host(opened = trip, custom = keepers)

        rule.onNodeWithTag("destination_custom").performClick()
        assertEquals(0, chose)
        rule.onNodeWithTag("destination_choose").performClick()
        assertEquals(1, chose)
    }

    @Test
    fun `tapping Working folder returns to it`() {
        host(opened = trip, custom = keepers)

        rule.onNodeWithTag("destination_working").performClick()

        assertEquals(1, usedWorking)
    }

    @Test
    fun `the subfolder name fields are on screen in both modes`() {
        host(opened = trip)
        rule.onNodeWithTag("selection_name_field").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("second_pass_name_field").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the subfolder name fields stay in custom mode, where the name is used inside the custom folder`() {
        host(opened = trip, custom = keepers)
        rule.onNodeWithTag("selection_name_field").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("second_pass_name_field").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the section carries its new words and none of the old ones`() {
        host(opened = trip)

        rule.onAllNodes(hasText("Where photos go"), useUnmergedTree = true).onFirst().assertIsDisplayed()
        rule.onAllNodes(hasText("RAW and JPEG folders"), useUnmergedTree = true).onFirst().performScrollTo().assertIsDisplayed()
        listOf("Created inside the source folder by default", "File Sorting", "Custom Selection Location", "Reset").forEach {
            assertTrue("'$it' is gone", rule.onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun `the RAW and JPEG switch reports its change`() {
        host(opened = trip)

        rule.onAllNodes(hasText("RAW and JPEG folders"), useUnmergedTree = true).onFirst().performScrollTo().performClick()

        assertEquals(false, sorting)
    }

    @Test
    fun `the mode of a stored custom folder is Custom and of none is Working`() {
        assertEquals(DestinationMode.CUSTOM, DestinationUiState.of(DestinationSettings(customDestinationUri = "x"), keepers, trip).mode)
        assertEquals(DestinationMode.WORKING_FOLDER, DestinationUiState.of(DestinationSettings(), null, trip).mode)
    }
}
