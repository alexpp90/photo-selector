package com.phototok.data.repository

import android.content.Context
import android.net.Uri
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
 * [SettingsRepository]'s use of the shared [FolderAccessManager] (`WP-07` / `F-09`, B-8): recents,
 * folder switches and the pending-delete journal. The manager itself is tested in `:saf`
 * (`com.photoselector.saf.FolderAccessManagerTest`).
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
    fun `evicting a recent folder releases its grant unless another purpose still references it`() = runTest {
        val backend = FakeSafGrantBackend()
        val manager = FolderAccessManager(backend)
        val settings = SettingsRepository(context, manager)

        settings.setLastFolderUri(null)
        settings.setPhoneCollectionUri(null)
        settings.setPhoneLeftSwipeUri(null)

        // Populate 10 recent folders (MAX_STORED_RECENT_PATHS = 10)
        for (i in 1..10) {
            val uri = "content://tree/recent_$i"
            manager.takeGrant(Uri.parse(uri), FolderAccessPurpose.SOURCE)
            settings.addRecentPath(uri, "Folder $i")
        }
        backend.releasedCalls.clear()

        // Add an 11th folder -> recent_1 is evicted and not referenced anywhere else -> released
        val uri11 = "content://tree/recent_11"
        manager.takeGrant(Uri.parse(uri11), FolderAccessPurpose.SOURCE)
        settings.addRecentPath(uri11, "Folder 11")

        assertTrue(
            "Evicted recent_1 grant must be released",
            backend.releasedCalls.any { it.first == "content://tree/recent_1" },
        )

        // Pin recent_2 as SELECTION_TARGET, then evict it by adding recent_12
        settings.setPhoneCollectionUri("content://tree/recent_2")
        backend.releasedCalls.clear()

        val uri12 = "content://tree/recent_12"
        manager.takeGrant(Uri.parse(uri12), FolderAccessPurpose.SOURCE)
        settings.addRecentPath(uri12, "Folder 12")

        assertFalse(
            "recent_2 must NOT be released on eviction while SELECTION_TARGET still references it",
            backend.releasedCalls.any { it.first == "content://tree/recent_2" },
        )

        // Once SELECTION_TARGET changes away from recent_2, recent_2 is released
        settings.setPhoneCollectionUri("content://tree/custom_collection")
        assertTrue(
            "recent_2 must be released once SELECTION_TARGET no longer references it",
            backend.releasedCalls.any { it.first == "content://tree/recent_2" },
        )
    }

    @Test
    fun `switching folders with a pending delete keeps the old grant until it is committed`() = runTest {
        val folderA = tree
        val folderB = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        val backend = FakeSafGrantBackend()
        val manager = FolderAccessManager(backend)
        val settings = SettingsRepository(context, manager)
        settings.setLastFolderUri(null)
        settings.setPersistedPendingDeleteUris(emptySet())
        backend.releasedCalls.clear()
        backend.persistedUris.clear()

        settings.setLastFolderUri(folderA)
        settings.setPersistedPendingDeleteUris(setOf(document))
        settings.setLastFolderUri(folderB)

        assertTrue("the journal still needs folder A", folderA in backend.persistedUris)
        assertFalse(backend.releasedCalls.any { it.first == folderA })

        settings.removePersistedPendingDeleteUris(listOf(document))
        settings.releaseFolderGrantIfUnreferenced(folderA)

        assertTrue(backend.releasedCalls.any { it.first == folderA })
        settings.setLastFolderUri(null)
        settings.setPersistedPendingDeleteUris(emptySet())
    }

    @Test
    fun `removing journal entries leaves entries queued meanwhile alone`() = runTest {
        val backend = FakeSafGrantBackend()
        val settings = SettingsRepository(context, FolderAccessManager(backend))
        val other = "$tree/document/primary%3ADCIM%2FIMG_0002.JPG"
        settings.setPersistedPendingDeleteUris(setOf(document, other))

        settings.removePersistedPendingDeleteUris(listOf(document))

        assertEquals(setOf(other), settings.persistedPendingDeleteUris.first())
        settings.setPersistedPendingDeleteUris(emptySet())
    }
}
