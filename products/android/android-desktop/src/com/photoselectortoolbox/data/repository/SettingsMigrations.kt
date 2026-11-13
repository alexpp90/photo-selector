package com.photoselectortoolbox.data.repository

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * The migrations the settings store runs before its first read (`preferencesDataStore`'s
 * `produceMigrations`), so no reader ever sees a store an update has not finished (invariant
 * *Updates never destroy the user's setup*). Each is idempotent: it runs only while its key is
 * missing and never touches another key.
 */
internal val SETTINGS_MIGRATIONS: List<DataMigration<Preferences>> = listOf(WriteRatingsIntoPhotosMigration)

/**
 * *Write ratings into photos* (`[USER 2026-11-12]` decision #16, REQ-AND-RATING.01). Before the
 * setting existed every install embedded each JPEG rating in the photo; the setting is off for a
 * new install. The first start of this version therefore stores the value once: on for an
 * install that already has settings (an earlier version ran and kept its behaviour), off for an
 * empty store (a new install). A stored value — the user's choice, or this migration's — is never
 * re-derived.
 */
internal object WriteRatingsIntoPhotosMigration : DataMigration<Preferences> {

    val KEY = booleanPreferencesKey("write_ratings_into_photos")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = KEY !in currentData

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            this[KEY] = currentData.asMap().isNotEmpty()
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}
