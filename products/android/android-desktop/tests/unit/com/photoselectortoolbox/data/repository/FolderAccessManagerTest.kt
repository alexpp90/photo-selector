package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.SafGrantBackend
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
}
