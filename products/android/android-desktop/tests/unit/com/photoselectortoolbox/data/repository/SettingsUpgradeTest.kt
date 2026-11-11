package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.SafGrantBackend
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Updates never destroy the user's setup (invariant, standing instruction 2026-10-24). Each
 * test writes the settings an earlier release left on a device, every key at a non-default
 * value and in the format that release wrote it, then reads them through this version.
 *
 * Audit 2026-11-10 (`git log` of `SettingsRepository.kt` from `android/app/` on): every key
 * v0.3.0/v0.4.0, android-desktop-v0.6.0 and v0.7.0 wrote is read under its original name and
 * value format, so Frameduel has no settings migration and needs none. Removed values are
 * gated in the reader, never erased (`gdrive://` folders; `phone_collection_uri`, kept readable,
 * a grant holder, never read by a transfer). The one removed key, `selector_layout_focused`,
 * existed only between 2026-07-23 and 2026-08-07 and was in no release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsUpgradeTest {

    private object NoGrants : SafGrantBackend {
        override fun takePersistableGrant(uri: Uri, modeFlags: Int) = Unit
        override fun releasePersistableGrant(uri: Uri, modeFlags: Int) = Unit
        override fun persistedGrantUris(): Set<String> = emptySet()
    }

    private val context: Context = RuntimeEnvironment.getApplication()
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTrip"
    private val keepers = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FKeepers"

    @Before
    fun clear() {
        // Process-wide DataStore singleton (agent rule, 2026-10-17).
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    @After
    fun tearDown() = clear()

    private suspend fun installed(release: MutablePreferences.() -> Unit): SettingsRepository {
        context.dataStore.edit { it.release() }
        return SettingsRepository(context, FolderAccessManager(NoGrants))
    }

    /** What v0.3.0 / v0.4.0 (`android/app/`, 2026-06-27) wrote, every key changed from its default. */
    private fun MutablePreferences.v04() {
        this[stringPreferencesKey("selection_folder_name")] = "Keepers"
        this[booleanPreferencesKey("sorting_enabled")] = false
        this[booleanPreferencesKey("grouping_enabled")] = true
        this[stringPreferencesKey("grouping_level")] = "DETAILED_SIMILARITY"
        this[stringPreferencesKey("last_folder_uri")] = tree
        this[intPreferencesKey("analysis_thread_count")] = 1
        this[booleanPreferencesKey("fullscreen_buttons_enabled")] = false
        this[stringPreferencesKey("fullscreen_gesture_action")] = "move"
        this[stringPreferencesKey("phone_collection_action")] = "move"
        this[stringPreferencesKey("phone_collection_uri")] = keepers
        this[booleanPreferencesKey("phone_delete_confirm")] = false
        this[booleanPreferencesKey("phone_sort_by_orientation")] = false
        this[longPreferencesKey("phone_gesture_tutorial_ts")] = 1_750_000_000_000L
        this[stringPreferencesKey("phone_force_mode")] = "phone"
        this[stringPreferencesKey("phone_file_type_filter")] = "raw"
        this[intPreferencesKey("folder_pos_${tree.hashCode()}")] = 41
    }

    /** What android-desktop-v0.6.0 (2026-10-23) and v0.7.0 (2026-11-03) added. */
    private fun MutablePreferences.v07() {
        v04()
        this[booleanPreferencesKey("details_visible")] = false
        this[booleanPreferencesKey("filmstrip_visible")] = true
        this[booleanPreferencesKey("overlay_values_visible")] = false
        this[booleanPreferencesKey("has_seen_nav_hint")] = true
        this[booleanPreferencesKey("seen_fullscreen_gesture_hint")] = true
        this[booleanPreferencesKey("fullscreen_info_pinned")] = true
        this[stringSetPreferencesKey("seen_first_run_hints")] = setOf("filing_action", "delete_undo")
        this[stringPreferencesKey("secondary_selection_folder_name")] = "Shortlist"
        this[stringPreferencesKey("custom_selection_destination_uri")] = keepers
        this[booleanPreferencesKey("tv_display_scale_enabled")] = true
        this[stringPreferencesKey("selector_sort_order")] = "OLDEST_FIRST"
        this[stringSetPreferencesKey("pending_delete_journal")] = setOf("$tree/document/primary%3ADCIM%2FTrip%2FIMG_1.JPG")
    }

    private suspend fun assertV04Kept(settings: SettingsRepository) {
        assertEquals("Keepers", settings.selectionFolderName.first())
        assertFalse(settings.sortingEnabled.first())
        assertTrue(settings.groupingEnabled.first())
        assertEquals(GroupingLevel.DETAILED_SIMILARITY, settings.groupingLevel.first())
        assertEquals(tree, settings.lastFolderUri.first())
        assertEquals(1, settings.analysisThreadCount.first())
        assertFalse(settings.fullscreenButtonsEnabled.first())
        assertEquals(FilingAction.MOVE, settings.filingAction.first())
        assertEquals("move", settings.phoneCollectionAction.first())
        assertEquals(keepers, settings.phoneCollectionUri.first())
        assertFalse(settings.phoneDeleteConfirmEnabled.first())
        assertFalse(settings.phoneSortByOrientation.first())
        assertEquals(1_750_000_000_000L, settings.phoneGestureTutorialTs.first())
        assertEquals("phone", settings.phoneForcedMode.first())
        assertEquals("raw", settings.phoneFileTypeFilter.first())
        assertEquals(41, settings.getFolderLastPosition(tree))
    }

    @Test
    fun `everything v0_4_0 stored reads with its old effect`() = runTest {
        val settings = installed { v04() }

        assertV04Kept(settings)
        // `phone_collection_uri` was never read by a transfer: Copy and Move still go to the
        // working folder, as they did (REQ-AND-WORKFLOW.02).
        assertEquals(DestinationMode.WORKING_FOLDER, settings.destinationMode.first())
        assertEquals(null, settings.customSelectionDestinationUri.first())
    }

    @Test
    fun `everything android-desktop-v0_7_0 stored reads with its old effect`() = runTest {
        val settings = installed { v07() }

        assertV04Kept(settings)
        assertFalse(settings.detailsVisible.first())
        assertTrue(settings.filmstripVisible.first())
        assertFalse(settings.overlayValuesVisible.first())
        assertTrue(settings.hasSeenNavHint.first())
        assertTrue(settings.hasSeenFullscreenGestureHint.first())
        assertTrue(settings.fullscreenInfoPinned.first())
        assertEquals(setOf(SelectorHint.FILING, SelectorHint.DELETE_UNDO), settings.seenHints.first())
        assertEquals("Shortlist", settings.secondarySelectionFolderName.first())
        assertEquals(keepers, settings.customSelectionDestinationUri.first())
        assertEquals(DestinationMode.CUSTOM, settings.destinationMode.first())
        assertTrue(settings.tvDisplayScaleEnabled.first())
        assertEquals(SelectorSortOrder.OLDEST_FIRST, settings.selectorSortOrder.first())
        assertEquals(1, settings.pendingDeleteJournal.first().size)
        // A journal entry from before identities were recorded has none: never deleted unverified.
        assertTrue(settings.pendingDeleteIdentities.first().isEmpty())
    }

    @Test
    fun `reading an upgraded store changes nothing in it`() = runTest {
        val settings = installed { v07() }
        val before = context.dataStore.data.first().asMap()

        assertV04Kept(settings)
        settings.reconcilePersistedGrants()

        assertEquals(before, context.dataStore.data.first().asMap())
    }
}
