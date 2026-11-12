package com.photoselectortoolbox.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Settings survive updates (invariant *Updates never destroy the user's setup*): the migrations
 * the settings store runs before its first read ([SETTINGS_MIGRATIONS]), driven from every
 * schema an earlier release wrote. Each test writes the old store to a file, closes it, and opens
 * a fresh store on that file with the production migrations — the shared `preferencesDataStore`
 * singleton would never run them a second time.
 *
 * `write_ratings_into_photos` (`[USER 2026-11-12]` decision #16): off for a new install; an
 * install that already has settings wrote every JPEG rating into the photo and keeps doing so.
 */
class SettingsMigrationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = booleanPreferencesKey("write_ratings_into_photos")
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTrip"

    private fun <T> withStore(file: File, migrated: Boolean, block: suspend (DataStore<Preferences>) -> T): T {
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(
                migrations = if (migrated) SETTINGS_MIGRATIONS else emptyList(),
                scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { file },
            )
            return runBlocking { block(store) }
        } finally {
            // The file is free for the next store only once this one has fully stopped.
            runBlocking { job.cancelAndJoin() }
        }
    }

    /** The store an earlier release left, then what this release reads from it. */
    private fun upgraded(release: MutablePreferences.() -> Unit): Preferences {
        val file = File(tmp.newFolder(), "photo_selector_settings.preferences_pb")
        withStore(file, migrated = false) { store -> store.edit { it.release() } }
        return withStore(file, migrated = true) { store -> store.data.first() }
    }

    @Test
    fun `a new install does not write ratings into photos`() {
        val prefs = upgraded { }

        assertEquals(false, prefs[key])
        assertFalse(SettingsRepository.DEFAULT_WRITE_RATINGS_INTO_PHOTOS)
    }

    @Test
    fun `an install from v0_4_0 keeps writing ratings into photos`() {
        val prefs = upgraded {
            this[stringPreferencesKey("selection_folder_name")] = "Keepers"
            this[booleanPreferencesKey("sorting_enabled")] = false
            this[stringPreferencesKey("last_folder_uri")] = tree
            this[intPreferencesKey("analysis_thread_count")] = 1
            this[longPreferencesKey("phone_gesture_tutorial_ts")] = 1_750_000_000_000L
        }

        assertEquals(true, prefs[key])
        // Nothing else is changed.
        assertEquals("Keepers", prefs[stringPreferencesKey("selection_folder_name")])
        assertEquals(tree, prefs[stringPreferencesKey("last_folder_uri")])
    }

    @Test
    fun `an install from android-desktop-v0_7_0 that only dismissed a hint keeps writing ratings into photos`() {
        val prefs = upgraded {
            this[stringSetPreferencesKey("seen_first_run_hints")] = setOf("filing_action")
        }

        assertEquals(true, prefs[key])
    }

    @Test
    fun `a choice already stored is never re-derived`() {
        val file = File(tmp.newFolder(), "photo_selector_settings.preferences_pb")
        withStore(file, migrated = false) { store ->
            store.edit {
                it[stringPreferencesKey("last_folder_uri")] = tree
                it[key] = false
            }
        }

        assertEquals(false, withStore(file, migrated = true) { it.data.first()[key] })
        // And a second start changes nothing.
        assertEquals(false, withStore(file, migrated = true) { it.data.first()[key] })
    }

    @Test
    fun `the production settings store runs these migrations`() {
        // The delegate cannot be re-opened in a test (one instance per process), so its
        // declaration is read: it must hand DataStore exactly this list.
        val source = File("src/com/photoselectortoolbox/data/repository/SettingsRepository.kt").readText()
        assertTrue(source.contains("produceMigrations = { SETTINGS_MIGRATIONS }"))
        assertTrue(SETTINGS_MIGRATIONS.contains(WriteRatingsIntoPhotosMigration))
    }
}
