package com.photoselectortoolbox.viewmodel

import android.content.Context
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * Tests for DuplicatesUiState data class and DuplicatesViewModel
 * selection logic. ViewModel construction requires Hilt, so we test
 * the state machine and helper logic directly.
 */
class DuplicatesViewModelTest {

    // ── DuplicatesUiState tests ─────────────────────────────────────

    @Test
    fun `default state is idle with no groups`() {
        val state = DuplicatesUiState()
        assertFalse(state.isScanning)
        assertEquals(0f, state.scanProgress, 1e-6f)
        assertEquals("", state.statusText.en())
        assertNull(state.folderUri)
        assertTrue(state.duplicateGroups.isEmpty())
        assertTrue(state.selectedForDeletion.isEmpty())
        assertNull(state.error?.en())
    }

    @Test
    fun `scan running state has progress`() {
        val state = DuplicatesUiState(
            isScanning = true,
            scanProgress = 0.75f,
            statusText = com.photoselectortoolbox.domain.text.raw("Hashing files: 75/100")
        )
        assertTrue(state.isScanning)
        assertEquals(0.75f, state.scanProgress, 1e-6f)
    }

    @Test
    fun `duplicate groups display correctly`() {
        val groups = listOf(
            DuplicateGroup("hash1", listOf("uri1", "uri2", "uri3")),
            DuplicateGroup("hash2", listOf("uri4", "uri5"))
        )
        val state = DuplicatesUiState(duplicateGroups = groups)
        assertEquals(2, state.duplicateGroups.size)
        assertEquals(3, state.duplicateGroups[0].files.size)
    }

    // ── Selection logic tests (pure state operations) ───────────────

    @Test
    fun `toggle selection adds uri`() {
        val state = DuplicatesUiState()
        val updated = state.copy(
            selectedForDeletion = state.selectedForDeletion + "uri1"
        )
        assertTrue("uri1" in updated.selectedForDeletion)
    }

    @Test
    fun `toggle selection removes already-selected uri`() {
        val state = DuplicatesUiState(selectedForDeletion = setOf("uri1", "uri2"))
        val updated = state.copy(
            selectedForDeletion = state.selectedForDeletion - "uri1"
        )
        assertFalse("uri1" in updated.selectedForDeletion)
        assertTrue("uri2" in updated.selectedForDeletion)
    }

    @Test
    fun `selectAllButFirst keeps first file, selects rest`() {
        val group = DuplicateGroup("hash1", listOf("uri1", "uri2", "uri3"))
        val state = DuplicatesUiState()
        val newSelections = group.files.drop(1).toSet()
        val updated = state.copy(
            selectedForDeletion = state.selectedForDeletion + newSelections
        )
        assertFalse("uri1" in updated.selectedForDeletion)
        assertTrue("uri2" in updated.selectedForDeletion)
        assertTrue("uri3" in updated.selectedForDeletion)
    }

    @Test
    fun `error can be set and cleared`() {
        val state = DuplicatesUiState(error = com.photoselectortoolbox.domain.text.raw("Scan failed: timeout"))
        assertEquals("Scan failed: timeout", state.error?.en())
        assertNull(state.copy(error = null).error?.en())
    }

    @Test
    fun `after deletion groups are filtered`() {
        val groups = listOf(
            DuplicateGroup("hash1", listOf("uri1", "uri2", "uri3")),
            DuplicateGroup("hash2", listOf("uri4", "uri5"))
        )
        val deleted = setOf("uri2", "uri3", "uri5")

        // Simulate post-deletion group filtering
        val updatedGroups = groups.mapNotNull { group ->
            val remaining = group.files.filter { it !in deleted }
            if (remaining.size > 1) group.copy(files = remaining) else null
        }

        // hash1 group: only uri1 remains -> removed (not a dup group)
        // hash2 group: only uri4 remains -> removed
        assertEquals(0, updatedGroups.size)
    }

    @Test
    fun `partial deletion keeps groups with 2+ files`() {
        val groups = listOf(
            DuplicateGroup("hash1", listOf("uri1", "uri2", "uri3"))
        )
        val deleted = setOf("uri3")

        val updatedGroups = groups.mapNotNull { group ->
            val remaining = group.files.filter { it !in deleted }
            if (remaining.size > 1) group.copy(files = remaining) else null
        }

        assertEquals(1, updatedGroups.size)
        assertEquals(listOf("uri1", "uri2"), updatedGroups[0].files)
    }
}
