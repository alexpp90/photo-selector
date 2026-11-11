package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.SafGrantBackend
import com.photoselectortoolbox.domain.curation.FileIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The journal's identity half in the real DataStore (REQ-AND-LAYOUT.23, finding 2026-11-05). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingDeleteJournalSettingsTest {

    private object NoGrants : SafGrantBackend {
        override fun takePersistableGrant(uri: Uri, modeFlags: Int) = Unit
        override fun releasePersistableGrant(uri: Uri, modeFlags: Int) = Unit
        override fun persistedGrantUris(): Set<String> = emptySet()
    }

    private lateinit var settings: SettingsRepository

    private val a = "content://tree/document/a.jpg"
    private val b = "content://tree/document/b.jpg"

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        settings = SettingsRepository(context, FolderAccessManager(NoGrants))
        // Process-wide DataStore singleton: start from an empty journal.
        runBlocking { settings.setPendingDeleteJournal(emptySet()) }
    }

    @Test
    fun `identities are stored with their URIs and leave with them`() = runTest {
        settings.addPendingDeletes(listOf(a, b), mapOf(a to FileIdentity(10, 1), b to FileIdentity(20, 2)))
        assertEquals(setOf(a, b), settings.pendingDeleteJournal.first())
        assertEquals(mapOf(a to FileIdentity(10, 1), b to FileIdentity(20, 2)), settings.pendingDeleteIdentities.first())

        settings.removePendingDeletes(listOf(a))
        assertEquals(setOf(b), settings.pendingDeleteJournal.first())
        assertEquals(mapOf(b to FileIdentity(20, 2)), settings.pendingDeleteIdentities.first())

        settings.setPendingDeleteJournal(emptySet())
        assertEquals(emptyMap<String, FileIdentity>(), settings.pendingDeleteIdentities.first())
    }

    @Test
    fun `a URI journalled again without an identity loses the old one`() = runTest {
        settings.addPendingDeletes(listOf(a), mapOf(a to FileIdentity(10, 1)))
        settings.addPendingDeletes(listOf(a))
        assertEquals(setOf(a), settings.pendingDeleteJournal.first())
        assertEquals(emptyMap<String, FileIdentity>(), settings.pendingDeleteIdentities.first())
    }

    @Test
    fun `an identity for a URI that is not journalled is not stored`() = runTest {
        settings.addPendingDeletes(listOf(a), mapOf(a to FileIdentity(10, 1), b to FileIdentity(20, 2)))
        assertEquals(mapOf(a to FileIdentity(10, 1)), settings.pendingDeleteIdentities.first())
    }
}
