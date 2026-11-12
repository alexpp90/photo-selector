package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.curation.PendingDeleteJournal
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The disk half of a held delete outside the cull (the Dupes screen): the same durable
 * pending-delete journal the cull writes (REQ-AND-LAYOUT.23), so a delete held when the app is
 * killed comes back as the cull's "uncompleted deletions" prompt with its identity check, and
 * the journal keeps the folder's grant while anything is held (agent rule 4).
 *
 * Every journal write of this class runs under one lock, so a commit or an undo that follows a
 * hold quickly never removes entries before they were added (FD-D-20).
 */
@Singleton
class HeldDeleteCommitter @Inject constructor(
    private val imageRepository: ImageRepository,
    private val cacheRepository: CacheRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) {

    /** What a commit did with each file it was given. */
    data class Outcome(
        val deleted: List<String>,
        /** Files that could not be deleted (the provider refused, or nothing could be read). */
        val failed: List<String>,
        /** Files kept because another file is at the URI now ([PendingDeleteJournal.keepsInSession]). */
        val kept: List<String>,
    )

    private val journalMutex = Mutex()

    /**
     * Journal [files] as deleted but not on disk yet, each with the identity it has now (the
     * listing's size and date where the provider cannot be asked). Returns the identities recorded.
     */
    suspend fun hold(files: List<CompanionFile>): Map<String, FileIdentity> = journalMutex.withLock {
        val identities = coroutineScope {
            files.map { file ->
                async { file.uri to (readIdentity(file.uri) ?: FileIdentity(file.fileSize, file.lastModified)) }
            }.awaitAll()
        }.filter { (_, identity) -> identity.isKnown }.toMap()
        settingsRepository.addPendingDeletes(files.map { it.uri }, identities)
        identities
    }

    /** Undo: the files stay, and only their own journal entries are dropped. */
    suspend fun release(uris: List<String>) = journalMutex.withLock {
        settingsRepository.removePendingDeletes(uris)
    }

    /**
     * Delete [uris] now, each only if the file there still has the identity in [recorded], drop
     * each deleted file's rating and cached analysis (FD-D-14), and remove these entries — and
     * only these — from the journal.
     */
    suspend fun commit(uris: List<String>, recorded: Map<String, FileIdentity>): Outcome = journalMutex.withLock {
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val kept = mutableListOf<String>()
        for (uri in uris) {
            val known = recorded[uri]
            if (PendingDeleteJournal.needsCurrentIdentity(known) &&
                PendingDeleteJournal.keepsInSession(known, readIdentity(uri))
            ) {
                kept += uri
                continue
            }
            val ok = try {
                imageRepository.deleteImage(context, Uri.parse(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed for $uri", e)
                false
            }
            if (ok) deleted += uri else failed += uri
        }
        settingsRepository.removePendingDeletes(uris)
        deleted.forEach { gone ->
            try {
                cacheRepository.forgetDeletedFile(gone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the file is gone; a stale cache row is dropped by the MRU prune.
                Log.e(TAG, "Could not drop the cached data of deleted $gone", e)
            }
        }
        Outcome(deleted, failed, kept)
    }

    private suspend fun readIdentity(uri: String): FileIdentity? = try {
        imageRepository.documentIdentity(context, Uri.parse(uri))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the identity of $uri", e)
        null
    }

    private companion object {
        const val TAG = "HeldDeleteCommitter"
    }
}
