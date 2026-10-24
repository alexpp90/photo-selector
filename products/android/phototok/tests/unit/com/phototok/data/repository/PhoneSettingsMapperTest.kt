package com.phototok.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.phototok.data.model.PhoneSettings
import com.phototok.domain.CollectionAction
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.SwipeAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the Preferences -> [PhoneSettings] mapping
 * ([SettingsRepository.phoneSettingsOf]) that backs the typed settings flow.
 */
class PhoneSettingsMapperTest {

    @Test
    fun `empty preferences map to defaults`() {
        val settings = SettingsRepository.phoneSettingsOf(preferencesOf())

        // A fresh install is Casual: every Pro-only value reads its Casual value.
        assertEquals(false, settings.proMode)
        assertEquals(PhoneSettings(fileTypeFilter = FileTypeFilter.ONE_PER_SHOT, moveRelatedFiles = true), settings)
        assertEquals(CollectionAction.DEFAULT, settings.collectionAction)
        assertEquals(SwipeAction.DEFAULT, settings.leftSwipeAction)
        assertEquals(FileTypeFilter.ONE_PER_SHOT, settings.fileTypeFilter)
        assertEquals(3, settings.recentPathsCount)
        assertEquals(emptyList<Any>(), settings.recentPaths)
    }

    @Test
    fun `stored values are mapped to typed fields`() {
        val prefs = preferencesOf(
            booleanPreferencesKey("pro_mode") to true,
            stringPreferencesKey("collection_action") to CollectionAction.MOVE.key,
            stringPreferencesKey("left_swipe_action") to SwipeAction.COPY.key,
            stringPreferencesKey("file_type_filter") to FileTypeFilter.RAW.key,
            booleanPreferencesKey("direct_delete_confirm") to false,
            booleanPreferencesKey("sort_by_orientation") to true,
            booleanPreferencesKey("randomize_order") to true,
            booleanPreferencesKey("show_exif_overlay") to true,
            booleanPreferencesKey("move_related_files") to true,
            booleanPreferencesKey("recent_paths_enabled") to false,
            intPreferencesKey("recent_paths_count") to 5,
        )

        val settings = SettingsRepository.phoneSettingsOf(prefs)

        assertEquals(CollectionAction.MOVE, settings.collectionAction)
        assertEquals(SwipeAction.COPY, settings.leftSwipeAction)
        assertEquals(FileTypeFilter.RAW, settings.fileTypeFilter)
        assertEquals(false, settings.directDeleteConfirmEnabled)
        assertEquals(true, settings.sortByOrientation)
        assertEquals(true, settings.randomizeOrder)
        assertEquals(true, settings.showExifOverlay)
        assertEquals(true, settings.moveRelatedFiles)
        assertEquals(false, settings.recentPathsEnabled)
        assertEquals(5, settings.recentPathsCount)
    }

    @Test
    fun `unknown enum keys fall back to defaults`() {
        val prefs = preferencesOf(
            booleanPreferencesKey("pro_mode") to true,
            stringPreferencesKey("collection_action") to "bogus",
            stringPreferencesKey("left_swipe_action") to "bogus",
            stringPreferencesKey("file_type_filter") to "bogus",
        )

        val settings = SettingsRepository.phoneSettingsOf(prefs)

        assertEquals(CollectionAction.DEFAULT, settings.collectionAction)
        assertEquals(SwipeAction.DEFAULT, settings.leftSwipeAction)
        assertEquals(FileTypeFilter.DEFAULT, settings.fileTypeFilter)
    }

    @Test
    fun `seen first-run hints are mapped and default to empty`() {
        assertEquals(
            emptySet<String>(),
            SettingsRepository.phoneSettingsOf(preferencesOf()).seenFirstRunHints,
        )

        val prefs = preferencesOf(
            stringSetPreferencesKey("seen_first_run_hints") to
                setOf(FirstRunHint.SWIPE_RIGHT.key, FirstRunHint.TAP_HUD.key),
        )

        val settings = SettingsRepository.phoneSettingsOf(prefs)

        assertEquals(
            setOf(FirstRunHint.SWIPE_RIGHT.key, FirstRunHint.TAP_HUD.key),
            settings.seenFirstRunHints,
        )
    }
}
