package com.photoselectortoolbox.domain.curation

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.model.FolderReachability
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.viewmodel.DestinationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Copy and Move put a photo, and what Settings says about it — one resolution
 * for both (`[USER 2026-11-04]` "where the pictures go", REQ-AND-PLATFORM.06,
 * REQ-AND-WORKFLOW.01/.02).
 */
@Requirement("REQ-AND-PLATFORM.06", "REQ-AND-WORKFLOW.01", "REQ-AND-WORKFLOW.02")
class SelectionDestinationPlanTest {

    private val trip = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTrip"
    private val card = "content://com.android.externalstorage.documents/tree/1234-ABCD%3ADCIM"
    private val backup = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FKeepers"

    private fun described(
        uri: String,
        path: List<String>,
        name: String? = path.last(),
        reachable: Boolean = true,
        marker: Boolean = false,
    ) = FolderDescription(
        uri = uri,
        displayPath = path,
        name = name,
        hasPersistedGrant = reachable,
        exists = reachable,
        canWrite = reachable,
        hasSelectionMarker = marker,
    )

    // ── The plan ────────────────────────────────────────────────────────

    @Test
    fun `the fresh-install default is the working folder, Selection, sorted`() {
        val defaults = DestinationSettings(
            selectionFolderName = SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME,
            secondPassFolderName = SettingsRepository.DEFAULT_SECONDARY_SELECTION_FOLDER_NAME,
            sortingEnabled = SettingsRepository.DEFAULT_SORTING_ENABLED,
        )
        assertEquals(DestinationSettings(), defaults)
        val plan = SelectionDestinationPlan.resolve(defaults, OpenedFolder(trip))
        assertEquals(DestinationMode.WORKING_FOLDER, plan.mode)
        assertEquals(trip, plan.rootUri)
        assertEquals("Selection", plan.folderName)
        assertTrue(plan.sortsIntoRawAndJpeg)
        assertFalse(plan.isSecondPass)
        assertNull(plan.problem)
    }

    @Test
    fun `working folder with no folder open has no root, only the folder name`() {
        val plan = SelectionDestinationPlan.resolve(DestinationSettings(), openedFolder = null)
        assertEquals(DestinationMode.WORKING_FOLDER, plan.mode)
        assertNull(plan.rootUri)
        assertFalse(plan.hasRoot)
        assertEquals("Selection", plan.folderName)
    }

    @Test
    fun `a custom folder is the root whatever folder is open`() {
        val settings = DestinationSettings(customDestinationUri = backup)
        listOf(null, OpenedFolder(trip), OpenedFolder(card, name = "Selection", hasSelectionMarker = true)).forEach { opened ->
            val plan = SelectionDestinationPlan.resolve(settings, opened)
            assertEquals(DestinationMode.CUSTOM, plan.mode)
            assertEquals(backup, plan.rootUri)
            // The custom folder is not the folder being culled: never a second pass.
            assertEquals("Selection", plan.folderName)
            assertFalse(plan.isSecondPass)
        }
    }

    @Test
    fun `a custom destination whose Selection subfolder is opened applies second pass`() {
        val settings = DestinationSettings(
            customDestinationUri = backup,
            selectionFolderName = "Selection",
            secondPassFolderName = "Final",
        )
        val openedSelection = OpenedFolder(
            uri = "$backup%2FSelection",
            name = "Selection",
            hasSelectionMarker = true,
        )
        val plan = SelectionDestinationPlan.resolve(settings, openedSelection)
        assertEquals(DestinationMode.CUSTOM, plan.mode)
        assertEquals("$backup%2FSelection", plan.rootUri)
        assertEquals("Final", plan.folderName)
        assertTrue(plan.isSecondPass)
    }

    @Test
    fun `sorting only decides the RAW and JPEG subfolders`() {
        listOf(true, false).forEach { sorting ->
            val working = SelectionDestinationPlan.resolve(DestinationSettings(sortingEnabled = sorting), OpenedFolder(trip))
            val custom = SelectionDestinationPlan.resolve(
                DestinationSettings(customDestinationUri = backup, sortingEnabled = sorting),
                OpenedFolder(trip),
            )
            assertEquals(sorting, working.sortsIntoRawAndJpeg)
            assertEquals(sorting, custom.sortsIntoRawAndJpeg)
            assertEquals("Selection", working.folderName)
            assertEquals("Selection", custom.folderName)
        }
    }

    @Test
    fun `an opened Selection folder is a second pass into Final, by name or by marker`() {
        val settings = DestinationSettings()
        val byName = SelectionDestinationPlan.resolve(settings, OpenedFolder(trip, name = "selection"))
        val byMarker = SelectionDestinationPlan.resolve(settings, OpenedFolder(trip, name = "Culled", hasSelectionMarker = true))
        val neither = SelectionDestinationPlan.resolve(settings, OpenedFolder(trip, name = "Culled"))
        assertEquals("Final", byName.folderName)
        assertTrue(byName.isSecondPass)
        assertEquals("Final", byMarker.folderName)
        assertTrue(byMarker.isSecondPass)
        assertEquals("Selection", neither.folderName)
        assertFalse(neither.isSecondPass)
    }

    @Test
    fun `renamed folders are used, trimmed`() {
        val settings = DestinationSettings(selectionFolderName = "  Picks ", secondPassFolderName = " Best ")
        assertEquals("Picks", SelectionDestinationPlan.resolve(settings, OpenedFolder(trip)).folderName)
        assertEquals("Best", SelectionDestinationPlan.resolve(settings, OpenedFolder(trip, name = "Picks")).folderName)
        // A folder named like the default is no second pass once the name is Picks.
        assertEquals("Picks", SelectionDestinationPlan.resolve(settings, OpenedFolder(trip, name = "Selection")).folderName)
    }

    @Test
    fun `blank names fall back to Selection and Final`() {
        val settings = DestinationSettings(selectionFolderName = "   ", secondPassFolderName = "")
        assertEquals("Selection", SelectionDestinationPlan.resolve(settings, OpenedFolder(trip)).folderName)
        assertEquals("Final", SelectionDestinationPlan.resolve(settings, OpenedFolder(trip, name = "Selection")).folderName)
    }

    @Test
    fun `a blank custom destination is the working folder`() {
        listOf(null, "", "   ").forEach { stored ->
            assertEquals(DestinationMode.WORKING_FOLDER, DestinationMode.of(stored))
            val plan = SelectionDestinationPlan.resolve(DestinationSettings(customDestinationUri = stored), OpenedFolder(trip))
            assertEquals(trip, plan.rootUri)
        }
        assertEquals(DestinationMode.CUSTOM, DestinationMode.of(backup))
    }

    @Test
    fun `an unreachable custom folder stays the destination and is flagged, never replaced`() {
        val plan = SelectionDestinationPlan.resolve(
            DestinationSettings(customDestinationUri = backup),
            OpenedFolder(trip),
            customReachable = false,
        )
        assertEquals(DestinationMode.CUSTOM, plan.mode)
        assertEquals(backup, plan.rootUri)
        assertEquals(DestinationProblem.CUSTOM_FOLDER_UNREACHABLE, plan.problem)
        // Reachability says nothing about the working folder.
        assertNull(SelectionDestinationPlan.resolve(DestinationSettings(), OpenedFolder(trip), customReachable = false).problem)
    }

    @Test
    fun `the marker lookup is needed only in working folder mode`() {
        assertTrue(SelectionDestinationPlan.needsOpenedFolderDetails(DestinationSettings()))
        assertFalse(SelectionDestinationPlan.needsOpenedFolderDetails(DestinationSettings(customDestinationUri = backup)))
    }

    // ── The preview ─────────────────────────────────────────────────────

    @Test
    fun `the preview is the root's readable path plus the Selection folder`() {
        val preview = DestinationPreview.of(
            SelectionDestinationPlan.resolve(DestinationSettings(), OpenedFolder(trip)),
            listOf("Internal storage", "DCIM", "Trip"),
        )
        assertEquals(listOf("Internal storage", "DCIM", "Trip", "Selection"), preview.segments)
        assertTrue(preview.sortsIntoRawAndJpeg)
        assertTrue(preview.hasPath)
    }

    @Test
    fun `with no folder open the preview names only the folder`() {
        assertEquals(DestinationMode.WORKING_FOLDER, DestinationPreview.DEFAULT.mode)
        assertNull(DestinationPreview.DEFAULT.segments)
        assertFalse(DestinationPreview.DEFAULT.hasPath)
        assertEquals("Selection", DestinationPreview.DEFAULT.folderName)
        assertTrue(DestinationPreview.DEFAULT.sortsIntoRawAndJpeg)
    }

    // ── The Settings section state ──────────────────────────────────────

    @Test
    fun `working folder with a folder open shows that folder's Selection`() {
        val state = DestinationUiState.of(
            DestinationSettings(sortingEnabled = false),
            customFolder = null,
            openedFolder = described(trip, listOf("Internal storage", "DCIM", "Trip")),
        )
        assertEquals(DestinationMode.WORKING_FOLDER, state.mode)
        assertEquals(listOf("Internal storage", "DCIM", "Trip", "Selection"), state.preview.segments)
        assertFalse(state.preview.sortsIntoRawAndJpeg)
        assertFalse(state.customFolderUnreachable)
    }

    @Test
    fun `working folder inside a Selection shows the second-pass folder`() {
        val state = DestinationUiState.of(
            DestinationSettings(),
            customFolder = null,
            openedFolder = described("$trip%2FSelection", listOf("Internal storage", "DCIM", "Trip", "Selection")),
        )
        assertEquals(listOf("Internal storage", "DCIM", "Trip", "Selection", "Final"), state.preview.segments)
        assertTrue(state.preview.isSecondPass)
    }

    @Test
    fun `custom mode shows the custom folder's Selection, not the opened folder's`() {
        val state = DestinationUiState.of(
            DestinationSettings(customDestinationUri = backup),
            customFolder = described(backup, listOf("Internal storage", "Pictures", "Keepers")),
            openedFolder = described(card, listOf("SD card (1234-ABCD)", "DCIM")),
        )
        assertEquals(DestinationMode.CUSTOM, state.mode)
        assertEquals(listOf("Internal storage", "Pictures", "Keepers", "Selection"), state.preview.segments)
        assertEquals(listOf("SD card (1234-ABCD)", "DCIM"), state.openedFolder?.displayPath)
        assertNull(state.preview.problem)
    }

    @Test
    fun `a custom folder without its grant is a warning, with its path still shown`() {
        val lost = described(backup, listOf("Internal storage", "Pictures", "Keepers"), reachable = false)
        val state = DestinationUiState.of(DestinationSettings(customDestinationUri = backup), lost, openedFolder = null)
        assertTrue(state.customFolderUnreachable)
        assertEquals(FolderReachability.NO_PERMISSION, state.customFolder?.reachability)
        assertEquals(DestinationProblem.CUSTOM_FOLDER_UNREACHABLE, state.preview.problem)
        assertEquals(listOf("Internal storage", "Pictures", "Keepers", "Selection"), state.preview.segments)
    }

    @Test
    fun `reachability is checked grant first, then existence, then write access`() {
        val base = FolderDescription(uri = backup, displayPath = listOf("x"))
        assertEquals(FolderReachability.NO_PERMISSION, base.reachability)
        assertEquals(FolderReachability.MISSING, base.copy(hasPersistedGrant = true).reachability)
        assertEquals(FolderReachability.READ_ONLY, base.copy(hasPersistedGrant = true, exists = true).reachability)
        assertEquals(
            FolderReachability.REACHABLE,
            base.copy(hasPersistedGrant = true, exists = true, canWrite = true).reachability,
        )
    }
}
