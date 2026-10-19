package com.photoselector.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.EnumMap

/**
 * Why the app holds a persisted SAF grant for a folder URI.
 *
 * Tracking the purpose lets [FolderAccessManager] keep a grant while any active
 * role still needs it (for example when the same folder is both the current
 * source and the custom Selection destination, or is in the recents list and also
 * the Selection target) and release it as soon as the last role drops it.
 */
enum class FolderAccessPurpose {
    SOURCE,
    SELECTION_TARGET,
    LEFT_SWIPE_TARGET,
    RECENT_FOLDER,

    /** The folder an in-memory undo history (moves, pending deletes) still operates on. */
    UNDO_HISTORY,

    /** Document URIs in the pending-delete journal: their tree's grant is needed to commit or recover them. */
    PENDING_DELETE_JOURNAL,
}

/**
 * Seam over `ContentResolver` persisted URI permission calls so unit tests can
 * verify grant acquisition, release, and startup reconciliation deterministically.
 */
interface SafGrantBackend {
    fun takePersistableGrant(uri: Uri, modeFlags: Int)
    fun releasePersistableGrant(uri: Uri, modeFlags: Int)
    fun persistedGrantUris(): Set<String>
}

/**
 * The real backend. This file is the only place in either Android product that calls
 * `takePersistableUriPermission` / `releasePersistableUriPermission` (the sole-caller rule,
 * enforced by `SoleCallerRuleTest`).
 */
class ContentResolverSafGrantBackend(
    private val contentResolver: ContentResolver,
) : SafGrantBackend {
    override fun takePersistableGrant(uri: Uri, modeFlags: Int) {
        contentResolver.takePersistableUriPermission(uri, modeFlags)
    }

    override fun releasePersistableGrant(uri: Uri, modeFlags: Int) {
        contentResolver.releasePersistableUriPermission(uri, modeFlags)
    }

    override fun persistedGrantUris(): Set<String> =
        contentResolver.persistedUriPermissions
            .mapTo(LinkedHashSet()) { it.uri.toString() }
}

/**
 * Central manager for persisted SAF tree URI grants (`WP-07` / `F-09`).
 *
 * Every persisted folder grant in the app goes through this class so that:
 * 1. Taking a grant records the URI against its [FolderAccessPurpose].
 * 2. Changing a folder setting or evicting a recent folder releases any grant
 *    no longer referenced by any purpose, preventing exhaustion of Android's
 *    per-app persisted URI grant limit (128 prior to API 30, 512 on API 30+).
 * 3. Startup reconciliation releases any orphaned OS grants left behind by
 *    earlier sessions.
 *
 * The product holds exactly one instance (its dependency graph provides it as a singleton).
 * Without a [backend] — the no-argument constructor, used by tests — references are tracked
 * but no OS grant is taken or released, except through [takeGrant]'s `fallbackContext`.
 */
class FolderAccessManager(
    private val backend: SafGrantBackend?,
) {
    constructor(context: Context) : this(ContentResolverSafGrantBackend(context.contentResolver))

    constructor() : this(backend = null)

    companion object {
        /**
         * Normalised form for comparing URIs: scheme and authority lower-cased, the rest
         * percent-decoded (so `%3A` and `%3a` agree), no trailing slash. Pure string work.
         */
        fun canonicalUri(uri: String): String {
            val schemeEnd = uri.indexOf("://")
            if (schemeEnd < 0) return uri.trimEnd('/')
            val rest = uri.substring(schemeEnd + 3)
            val authorityEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val head = uri.substring(0, schemeEnd).lowercase() + "://" + rest.substring(0, authorityEnd).lowercase()
            val path = rest.substring(authorityEnd)
            val decoded = try {
                java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                path
            }
            return head + decoded.trimEnd('/')
        }

        private const val TAG = "FolderAccessManager"
        const val READ_WRITE_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    private val lock = Any()
    private val grantsByPurpose =
        EnumMap<FolderAccessPurpose, MutableSet<String>>(FolderAccessPurpose::class.java)

    /**
     * References held by live in-memory state (the undo history, the pending-delete undo stack).
     * Unlike [grantsByPurpose] they are not derived from persisted settings, so [syncReferences]
     * must not wipe them.
     */
    private val pinnedByPurpose =
        EnumMap<FolderAccessPurpose, Set<String>>(FolderAccessPurpose::class.java)

    /**
     * Persist read/write access for [uri] and record it under [purpose].
     *
     * Taking the OS grant is best-effort (test fakes and some pickers hand over
     * process-lifetime URIs that are readable without a persistable flag), so a
     * `SecurityException` is logged and returns `false` while still recording
     * the logical reference for [purpose].
     */
    fun takeGrant(
        uri: Uri,
        purpose: FolderAccessPurpose,
        fallbackContext: Context? = null,
    ): Boolean {
        val uriString = uri.toString()
        synchronized(lock) {
            grantsByPurpose.getOrPut(purpose) { LinkedHashSet() }.add(uriString)
        }
        val activeBackend = backend
            ?: fallbackContext?.let { ContentResolverSafGrantBackend(it.contentResolver) }
        return try {
            activeBackend?.takePersistableGrant(uri, READ_WRITE_FLAGS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist URI permission for $purpose", e)
            false
        }
    }

    /** Record that [uriString] is referenced by [purpose] without re-taking the OS grant. */
    fun recordReference(uriString: String, purpose: FolderAccessPurpose) {
        synchronized(lock) {
            grantsByPurpose.getOrPut(purpose) { LinkedHashSet() }.add(uriString)
        }
    }

    /**
     * Synchronize the in-memory reference table with [referencedByPurpose].
     */
    fun syncReferences(referencedByPurpose: Map<FolderAccessPurpose, Set<String>>) {
        synchronized(lock) {
            grantsByPurpose.clear()
            for ((purpose, uris) in referencedByPurpose) {
                if (uris.isNotEmpty()) {
                    grantsByPurpose[purpose] = LinkedHashSet(uris)
                }
            }
        }
    }

    /**
     * Replace the set of URIs that live in-memory state keeps open for [purpose]; an empty set
     * unpins. Survives [syncReferences].
     */
    fun pin(purpose: FolderAccessPurpose, uris: Set<String>) {
        synchronized(lock) {
            if (uris.isEmpty()) pinnedByPurpose.remove(purpose) else pinnedByPurpose[purpose] = LinkedHashSet(uris)
        }
    }

    /**
     * Release the OS persisted grant for [uriString] if no purpose in the
     * current reference table (or [additionalReferencedUris]) still references it.
     *
     * @return `true` if the URI was unreferenced and a release was attempted.
     */
    fun releaseIfUnreferenced(
        uriString: String,
        additionalReferencedUris: Set<String> = emptySet(),
    ): Boolean {
        val stillReferenced = synchronized(lock) {
            isReferenced(uriString, additionalReferencedUris) ||
                grantsByPurpose.values.any { isReferenced(uriString, it) } ||
                pinnedByPurpose.values.any { isReferenced(uriString, it) }
        }
        if (stillReferenced) return false
        releaseGrantInternal(Uri.parse(uriString))
        return true
    }

    /**
     * Reconcile OS `persistedUriPermissions` against the URIs the app still
     * references across all [FolderAccessPurpose]s, releasing any orphaned grant.
     *
     * @return the set of URI strings whose persisted grants were released.
     */
    fun reconcilePersistedGrants(
        referencedByPurpose: Map<FolderAccessPurpose, Set<String>>,
    ): Set<String> {
        syncReferences(referencedByPurpose)
        val allReferenced = synchronized(lock) {
            (grantsByPurpose.values.flatten() + pinnedByPurpose.values.flatten()).toSet()
        }
        val persisted = try {
            backend?.persistedGrantUris().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not query persisted URI permissions", e)
            emptySet()
        }
        val released = LinkedHashSet<String>()
        for (persistedUri in persisted) {
            if (!isReferenced(persistedUri, allReferenced)) {
                releaseGrantInternal(Uri.parse(persistedUri))
                released.add(persistedUri)
            }
        }
        return released
    }

    fun referencedUrisFor(purpose: FolderAccessPurpose): Set<String> =
        synchronized(lock) {
            grantsByPurpose[purpose]?.toSet().orEmpty()
        }

    fun allReferencedUris(): Set<String> =
        synchronized(lock) {
            grantsByPurpose.values.flatten().toSet()
        }

    /**
     * True when [tree] is referenced by [references], comparing normalized URIs: the same folder in
     * another spelling (percent-escape case, trailing slash) counts, and so does a *document* inside
     * the tree (a pending-delete journal entry needs the grant of the tree it lives in).
     */
    private fun isReferenced(tree: String, references: Collection<String>): Boolean {
        val wanted = canonicalUri(tree)
        return references.any { reference ->
            val candidate = canonicalUri(reference)
            candidate == wanted || candidate.startsWith("$wanted/document/")
        }
    }

    private fun releaseGrantInternal(uri: Uri) {
        try {
            backend?.releasePersistableGrant(uri, READ_WRITE_FLAGS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not release persisted URI permission", e)
        }
    }
}
