package com.photoselectortoolbox.data.repository

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.photoselector.core.Requirement
import com.photoselector.saf.FolderAccessManager
import com.photoselectortoolbox.domain.curation.DestinationMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Working folder or Custom (`[USER 2026-11-04]`), read from what earlier versions stored:
 * there is no mode key, so every install upgrades with files going where they went.
 */
@Requirement("REQ-AND-WORKFLOW.02")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestinationModeSettingTest {

    /** Both key names are on users' devices: renaming either resets them. */
    private val customKey = stringPreferencesKey("custom_selection_destination_uri")
    private val legacyKey = stringPreferencesKey("phone_collection_uri")
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3AKeepers"

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        settings = SettingsRepository(context, FolderAccessManager())
    }

    @Test
    fun `nothing stored reads as the working folder`() {
        assertEquals(DestinationMode.WORKING_FOLDER, SettingsRepository.destinationModeOf(preferencesOf()))
    }

    @Test
    fun `an existing custom destination reads as Custom`() {
        assertEquals(DestinationMode.CUSTOM, SettingsRepository.destinationModeOf(preferencesOf(customKey to folder)))
        assertEquals(
            DestinationMode.CUSTOM,
            SettingsRepository.destinationModeOf(preferencesOf(customKey to folder, legacyKey to folder)),
        )
    }

    @Test
    fun `the legacy key alone reads as the working folder, where those files always went`() {
        // Before 0.6.0 Settings wrote only phone_collection_uri, which no transfer ever read.
        assertEquals(DestinationMode.WORKING_FOLDER, SettingsRepository.destinationModeOf(preferencesOf(legacyKey to folder)))
    }

    @Test
    fun `a blank custom destination reads as the working folder`() {
        assertEquals(DestinationMode.WORKING_FOLDER, SettingsRepository.destinationModeOf(preferencesOf(customKey to "  ")))
    }

    @Test
    fun `choosing and clearing round-trips through the real DataStore`() = runTest {
        try {
            settings.setCustomSelectionDestinationUri(folder)
            assertEquals(DestinationMode.CUSTOM, settings.destinationMode.first())
            assertEquals(folder, settings.customSelectionDestinationUri.first())

            settings.setCustomSelectionDestinationUri(null)
            assertEquals(DestinationMode.WORKING_FOLDER, settings.destinationMode.first())
            assertNull(settings.customSelectionDestinationUri.first())
            assertNull(settings.phoneCollectionUri.first())
        } finally {
            settings.setCustomSelectionDestinationUri(null)
        }
    }
}
