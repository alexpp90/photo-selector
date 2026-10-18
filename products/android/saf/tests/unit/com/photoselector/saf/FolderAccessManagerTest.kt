package com.photoselector.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [FolderAccessManager] (`WP-07` / `F-09`, B-8 / D-10). "AD"/"TOK" name the former product
 * tests each case replaces; the tests that exercise a product's settings or journal stay in
 * that product.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderAccessManagerTest {

    private class FakeSafGrantBackend(
        initialPersisted: Set<String> = emptySet(),
        private val failTake: Boolean = false,
    ) : SafGrantBackend {
        val persistedUris = LinkedHashSet(initialPersisted)
        val takenCalls = mutableListOf<Pair<String, Int>>()
        val releasedCalls = mutableListOf<Pair<String, Int>>()

        override fun takePersistableGrant(uri: Uri, modeFlags: Int) {
            if (failTake) throw SecurityException("No persistable grant for $uri")
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

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val document = "$tree/document/primary%3ADCIM%2FIMG_0001.JPG"

    // AD + TOK: takeGrant records purpose and requests read-write persistable grant
    @Test
    fun `takeGrant records purpose and requests read-write persistable grant`() {
        val backend = FakeSafGrantBackend()
        val manager = FolderAccessManager(backend)
        val uri = Uri.parse(tree)

        val granted = manager.takeGrant(uri, FolderAccessPurpose.SOURCE)

        assertTrue(granted)
        assertEquals(setOf(uri.toString()), manager.referencedUrisFor(FolderAccessPurpose.SOURCE))
        val expectedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(uri.toString() to expectedFlags, backend.takenCalls.single())
    }

    @Test
    fun `a refused grant returns false but still records the reference`() {
        val manager = FolderAccessManager(FakeSafGrantBackend(failTake = true))

        assertFalse(manager.takeGrant(Uri.parse(tree), FolderAccessPurpose.SELECTION_TARGET))
        assertEquals(setOf(tree), manager.referencedUrisFor(FolderAccessPurpose.SELECTION_TARGET))
    }

    @Test
    fun `without a backend the fallback context's resolver is used`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = FolderAccessManager()

        // Robolectric refuses a persistable grant nobody handed out: best effort, reference kept.
        manager.takeGrant(Uri.parse(tree), FolderAccessPurpose.SOURCE, fallbackContext = context)
        assertEquals(setOf(tree), manager.allReferencedUris())
        // Without a backend nothing is released or reconciled.
        assertTrue(manager.reconcilePersistedGrants(emptyMap()).isEmpty())
    }

    // AD: releaseIfUnreferenced keeps grant while another purpose still references URI
    @Test
    fun `releaseIfUnreferenced keeps the grant while another purpose still references it`() {
        val sharedUri = "content://tree/shared"
        val backend = FakeSafGrantBackend(initialPersisted = setOf(sharedUri))
        val manager = FolderAccessManager(backend)
        manager.syncReferences(
            mapOf(
                FolderAccessPurpose.SOURCE to setOf("content://tree/new_source"),
                FolderAccessPurpose.SELECTION_TARGET to setOf(sharedUri),
            )
        )

        assertFalse(manager.releaseIfUnreferenced(sharedUri))
        assertTrue(backend.releasedCalls.isEmpty())
        assertTrue(sharedUri in backend.persistedUris)
        // An extra reference passed by the caller counts too.
        manager.syncReferences(emptyMap())
        assertFalse(manager.releaseIfUnreferenced(sharedUri, additionalReferencedUris = setOf(sharedUri)))
    }

    // AD: releaseIfUnreferenced releases grant once last purpose drops URI
    @Test
    fun `releaseIfUnreferenced releases the grant once the last purpose drops it`() {
        val oldUri = "content://tree/old_source"
        val backend = FakeSafGrantBackend(initialPersisted = setOf(oldUri))
        val manager = FolderAccessManager(backend)
        manager.syncReferences(
            mapOf(
                FolderAccessPurpose.SOURCE to setOf("content://tree/new_source"),
                FolderAccessPurpose.SELECTION_TARGET to emptySet(),
            )
        )

        assertTrue(manager.releaseIfUnreferenced(oldUri))
        assertEquals(oldUri, backend.releasedCalls.single().first)
        assertFalse(oldUri in backend.persistedUris)
    }

    // AD: reconcilePersistedGrants releases orphaned OS grants and retains referenced ones
    // TOK: reconcilePersistedGrants releases orphaned OS grants across all four purposes
    @Test
    fun `reconcile releases orphaned OS grants and keeps every purpose's grant`() {
        val referenced = mapOf(
            FolderAccessPurpose.SOURCE to setOf("content://tree/source"),
            FolderAccessPurpose.SELECTION_TARGET to setOf("content://tree/selection"),
            FolderAccessPurpose.LEFT_SWIPE_TARGET to setOf("content://tree/left_swipe"),
            FolderAccessPurpose.RECENT_FOLDER to setOf("content://tree/recent_1"),
        )
        val orphans = setOf("content://tree/orphan_1", "content://tree/orphan_2")
        val backend = FakeSafGrantBackend(initialPersisted = referenced.values.flatten().toSet() + orphans)
        val manager = FolderAccessManager(backend)

        val released = manager.reconcilePersistedGrants(referenced)

        assertEquals(orphans, released)
        assertEquals(referenced.values.flatten().toSet(), backend.persistedUris)
    }

    @Test
    fun `every purpose the products use exists`() {
        assertEquals(
            listOf("SOURCE", "SELECTION_TARGET", "LEFT_SWIPE_TARGET", "RECENT_FOLDER", "UNDO_HISTORY", "PENDING_DELETE_JOURNAL"),
            FolderAccessPurpose.entries.map { it.name },
        )
    }

    // ── B-8 / D-10: undo history and the pending-delete journal keep a grant alive ──

    // AD + TOK: a pending-delete journal entry inside the tree keeps the tree grant
    @Test
    fun `a pending-delete journal entry inside the tree keeps the tree grant`() {
        val backend = FakeSafGrantBackend(initialPersisted = setOf(tree))
        val manager = FolderAccessManager(backend)
        manager.syncReferences(
            mapOf(
                FolderAccessPurpose.SOURCE to setOf("content://other/tree/new"),
                FolderAccessPurpose.PENDING_DELETE_JOURNAL to setOf(document),
            )
        )

        assertFalse(manager.releaseIfUnreferenced(tree))
        assertTrue(backend.releasedCalls.isEmpty())
    }

    // AD + TOK: the grant is released once the journal no longer references the tree
    @Test
    fun `the grant is released once the journal no longer references the tree`() {
        val backend = FakeSafGrantBackend(initialPersisted = setOf(tree))
        val manager = FolderAccessManager(backend)
        manager.syncReferences(mapOf(FolderAccessPurpose.PENDING_DELETE_JOURNAL to setOf(document)))
        assertFalse(manager.releaseIfUnreferenced(tree))

        manager.syncReferences(emptyMap())

        assertTrue(manager.releaseIfUnreferenced(tree))
        assertEquals(listOf(tree), backend.releasedCalls.map { it.first })
    }

    // AD + TOK: a folder pinned by the undo history keeps its grant across reference syncs
    @Test
    fun `a folder pinned by the undo history keeps its grant across reference syncs`() {
        val backend = FakeSafGrantBackend(initialPersisted = setOf(tree))
        val manager = FolderAccessManager(backend)
        manager.pin(FolderAccessPurpose.UNDO_HISTORY, setOf(tree))

        manager.syncReferences(mapOf(FolderAccessPurpose.SOURCE to setOf("content://other/tree/new")))

        assertFalse(manager.releaseIfUnreferenced(tree))
        manager.pin(FolderAccessPurpose.UNDO_HISTORY, emptySet())
        assertTrue(manager.releaseIfUnreferenced(tree))
    }

    // AD + TOK: uris are compared normalized, not as raw strings
    @Test
    fun `uris are compared normalized, not as raw strings`() {
        val backend = FakeSafGrantBackend(initialPersisted = setOf(tree))
        val manager = FolderAccessManager(backend)
        // Same folder, different spelling: lower-case percent escape and a trailing slash.
        manager.syncReferences(
            mapOf(FolderAccessPurpose.SELECTION_TARGET to setOf("content://com.android.externalstorage.documents/tree/primary%3aDCIM/"))
        )

        assertFalse(manager.releaseIfUnreferenced(tree))
    }

    // AD + TOK: reconcile keeps a grant that only the journal references
    @Test
    fun `reconcile keeps a grant that only the journal references`() {
        val backend = FakeSafGrantBackend(initialPersisted = setOf(tree, "content://orphan/tree/x"))
        val manager = FolderAccessManager(backend)

        val released = manager.reconcilePersistedGrants(
            mapOf(FolderAccessPurpose.PENDING_DELETE_JOURNAL to setOf(document))
        )

        assertEquals(setOf("content://orphan/tree/x"), released)
    }

    @Test
    fun `reconcile keeps a grant only a pin references`() {
        val backend = FakeSafGrantBackend(initialPersisted = setOf(tree))
        val manager = FolderAccessManager(backend)
        manager.pin(FolderAccessPurpose.UNDO_HISTORY, setOf(tree))

        assertTrue(manager.reconcilePersistedGrants(emptyMap()).isEmpty())
    }

    @Test
    fun `canonicalUri lower-cases scheme and authority, decodes the path and drops a trailing slash`() {
        assertEquals(
            "content://com.example/tree/primary:DCIM",
            FolderAccessManager.canonicalUri("CONTENT://Com.Example/tree/primary%3ADCIM/"),
        )
        assertEquals(
            FolderAccessManager.canonicalUri("content://a/tree/x%3a1"),
            FolderAccessManager.canonicalUri("content://a/tree/x%3A1"),
        )
        // A literal '+' is not a space.
        assertEquals("content://a/tree/a+b", FolderAccessManager.canonicalUri("content://a/tree/a+b"))
        assertEquals("plain", FolderAccessManager.canonicalUri("plain/"))
    }

    @Test
    fun `recordReference adds a reference without taking a grant`() {
        val backend = FakeSafGrantBackend()
        val manager = FolderAccessManager(backend)

        manager.recordReference(tree, FolderAccessPurpose.RECENT_FOLDER)

        assertEquals(setOf(tree), manager.referencedUrisFor(FolderAccessPurpose.RECENT_FOLDER))
        assertTrue(backend.takenCalls.isEmpty())
    }
}
