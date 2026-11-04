package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.FolderAccessPurpose
import com.photoselector.saf.SafGrantBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * [SettingsRepository]'s use of the shared [FolderAccessManager] (`WP-07` / `F-09`, B-8).
 * The manager itself is tested in `:saf` (`com.photoselector.saf.FolderAccessManagerTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderAccessManagerTest {

    private class FakeSafGrantBackend(
        initialPersisted: Set<String> = emptySet(),
    ) : SafGrantBackend {
        val persistedUris = LinkedHashSet(initialPersisted)
        val takenCalls = mutableListOf<Pair<String, Int>>()
        val releasedCalls = mutableListOf<Pair<String, Int>>()

        override fun takePersistableGrant(uri: Uri, modeFlags: Int) {
            val s = uri.toString()
            takenCalls += s to modeFlags
            persistedUris += s
        }

        override fun releasePersistableGrant(uri: Uri, modeFlags: Int) {
            val s = uri.toString()
            releasedCalls += s to modeFlags
            persistedUris -= s
        }

        override fun persistedGrantUris(): Set<String> = persistedUris.toSet()
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val document = "$tree/document/primary%3ADCIM%2FIMG_0001.JPG"

    @Test
    fun `SettingsRepository releases previous grant on folder change and reconciles on startup`() = runTest {
        val orphan = "content://tree/orphan"
        val folderA = "content://tree/folder_a"
        val folderB = "content://tree/folder_b"
        val backend = FakeSafGrantBackend(initialPersisted = setOf(orphan))
        val manager = FolderAccessManager(backend)
        val settings = SettingsRepository(context, manager)

        // Reset any previous DataStore state
        settings.setLastFolderUri(null)
        settings.setPhoneCollectionUri(null)
        backend.releasedCalls.clear()
        backend.takenCalls.clear()
        backend.persistedUris.clear()
        backend.persistedUris += orphan

        // Set folderA as both SOURCE and SELECTION_TARGET
        settings.setLastFolderUri(folderA)
        settings.setPhoneCollectionUri(folderA)
        assertTrue(folderA in backend.persistedUris)
        assertTrue(backend.releasedCalls.isEmpty())

        // Switch SOURCE to folderB — folderA must NOT be released yet because SELECTION_TARGET still uses it
        settings.setLastFolderUri(folderB)
        assertFalse(backend.releasedCalls.any { it.first == folderA })
        assertTrue(folderA in backend.persistedUris)
        assertTrue(folderB in backend.persistedUris)

        // Clear SELECTION_TARGET — now folderA is unreferenced and must be released
        settings.setPhoneCollectionUri(null)
        assertTrue(backend.releasedCalls.any { it.first == folderA })
        assertFalse(folderA in backend.persistedUris)

        // Startup reconciliation releases orphan while keeping folderB
        val releasedOnStartup = settings.reconcilePersistedGrants()
        assertEquals(setOf(orphan), releasedOnStartup)
        assertEquals(setOf(folderB), backend.persistedUris)
    }

    @Test
    fun `switching folders with a pending delete keeps the old grant until it is committed`() = runTest {
        val folderA = tree
        val folderB = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        val backend = FakeSafGrantBackend()
        val manager = FolderAccessManager(backend)
        val settings = SettingsRepository(context, manager)
        settings.setLastFolderUri(null)
        settings.setPendingDeleteJournal(emptySet())
        backend.releasedCalls.clear()
        backend.persistedUris.clear()

        settings.setLastFolderUri(folderA)
        settings.addPendingDeletes(listOf(document))
        settings.setLastFolderUri(folderB)

        assertTrue("the journal still needs folder A", folderA in backend.persistedUris)
        assertFalse(backend.releasedCalls.any { it.first == folderA })

        settings.removePendingDeletes(listOf(document))
        settings.releaseFolderGrantIfUnreferenced(folderA)

        assertTrue(backend.releasedCalls.any { it.first == folderA })
        settings.setLastFolderUri(null)
    }

    // ── The custom Selection destination (B-8, [USER 2026-11-04]) ─────────

    private suspend fun resetDestination(settings: SettingsRepository, backend: FakeSafGrantBackend) {
        settings.setLastFolderUri(null)
        settings.setCustomSelectionDestinationUri(null)
        settings.setPhoneCollectionUri(null)
        settings.setPendingDeleteJournal(emptySet())
        backend.releasedCalls.clear()
        backend.takenCalls.clear()
        backend.persistedUris.clear()
    }

    @Test
    fun `a custom destination takes its grant and keeps it across a folder change and a restart`() = runTest {
        val custom = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FKeepers"
        val backend = FakeSafGrantBackend()
        val settings = SettingsRepository(context, FolderAccessManager(backend))
        resetDestination(settings, backend)
        try {
            settings.setCustomSelectionDestinationUri(custom)
            assertTrue("the custom folder's grant is taken", backend.takenCalls.any { it.first == custom })

            settings.setLastFolderUri(tree)
            settings.setLastFolderUri("content://com.android.externalstorage.documents/tree/primary%3AMovies")
            assertFalse(backend.releasedCalls.any { it.first == custom })

            // A restart: a fresh manager reconciles against what is stored.
            val afterRestart = SettingsRepository(context, FolderAccessManager(backend))
            afterRestart.reconcilePersistedGrants()
            assertTrue("the custom folder survives the startup reconcile", custom in backend.persistedUris)
        } finally {
            resetDestination(settings, backend)
        }
    }

    @Test
    fun `back to the working folder releases the custom grant unless the source still uses it`() = runTest {
        val custom = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FKeepers"
        val backend = FakeSafGrantBackend()
        val settings = SettingsRepository(context, FolderAccessManager(backend))
        resetDestination(settings, backend)
        try {
            settings.setCustomSelectionDestinationUri(custom)
            settings.setLastFolderUri(custom)
            settings.setCustomSelectionDestinationUri(null)
            assertFalse("the source still needs it", backend.releasedCalls.any { it.first == custom })

            settings.setCustomSelectionDestinationUri(custom)
            settings.setLastFolderUri(tree)
            settings.setCustomSelectionDestinationUri(null)
            assertTrue("nothing references it any more", backend.releasedCalls.any { it.first == custom })
            assertFalse(custom in backend.persistedUris)
        } finally {
            resetDestination(settings, backend)
        }
    }

    @Test
    fun `changing the custom folder releases the previous one`() = runTest {
        val first = "content://com.android.externalstorage.documents/tree/primary%3AOne"
        val second = "content://com.android.externalstorage.documents/tree/primary%3ATwo"
        val backend = FakeSafGrantBackend()
        val settings = SettingsRepository(context, FolderAccessManager(backend))
        resetDestination(settings, backend)
        try {
            settings.setCustomSelectionDestinationUri(first)
            settings.setCustomSelectionDestinationUri(second)
            assertTrue(backend.releasedCalls.any { it.first == first })
            assertTrue(second in backend.persistedUris)
            assertEquals(second, settings.customSelectionDestinationUri.first())
            // The legacy key is kept in step for a downgrade.
            assertEquals(second, settings.phoneCollectionUri.first())
        } finally {
            resetDestination(settings, backend)
        }
    }

    @Test
    fun `the custom destination key alone is a grant holder, and so is the legacy key`() {
        val settings = SettingsRepository(context, FolderAccessManager(FakeSafGrantBackend()))
        val custom = "content://com.android.externalstorage.documents/tree/primary%3AKeepers"
        val legacy = "content://com.android.externalstorage.documents/tree/primary%3AOld"
        val customOnly = settings.referencedUrisByPurpose(preferencesOf(stringPreferencesKey("custom_selection_destination_uri") to custom))
        assertEquals(setOf(custom), customOnly[FolderAccessPurpose.SELECTION_TARGET])
        val legacyOnly = settings.referencedUrisByPurpose(preferencesOf(stringPreferencesKey("phone_collection_uri") to legacy))
        assertEquals(setOf(legacy), legacyOnly[FolderAccessPurpose.SELECTION_TARGET])
    }
}
