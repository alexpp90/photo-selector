package com.phototok.viewmodel

import android.net.Uri
import android.util.Log
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SystemTrashRepository
import com.phototok.domain.OptimisticFeed
import com.phototok.domain.PendingDeleteLogic
import com.phototok.domain.TrashItem
import kotlin.coroutines.cancellation.CancellationException

/**
 * Commits finalised deletions (REQ-TOK-ARCH.08) on the application scope: MediaStore-indexed phone
 * photos go to the system bin after the user's consent, everything else is deleted permanently
 * ([USER 2026-11-12] decision #13). A shot with a file in the bin is all-or-nothing: its permanent
 * members (a sidecar MediaStore does not index) are deleted only once its bin files are in the bin,
 * so declining the dialog keeps the whole shot.
 */
internal class PendingDeleteCommit(
    private val imageRepository: ImageRepository,
    private val systemTrash: SystemTrashRepository,
) {
    /**
     * [gone] left the folder (deleted or in the bin); [permanentCount] / [permanentBytes] were deleted
     * for good; [failed] could not be deleted; [restore] are the slots to put back, per entry.
     */
    class Outcome(
        val gone: Set<String>,
        val permanentCount: Int,
        val permanentBytes: Long,
        val failed: List<ImageItem>,
        val restore: List<List<OptimisticFeed.Slot>>,
    )

    /** [release] false: the evicted 51st entry, whose bin files wait for the next finalisation. */
    suspend fun commit(entries: List<PendingDeleteLogic.Pending>, kind: SourceKind, release: Boolean = true): Outcome {
        val targets = entries.associateWith { listOf(it.image) + it.related }
        val bin = systemTrash.binTargets(targets.values.flatten().map { it.uri }, kind)
        val (binEntries, plainEntries) = entries.partition { e -> targets.getValue(e).any { it.uri in bin } }

        val gone = mutableSetOf<String>()
        val failed = mutableListOf<ImageItem>()
        var bytes = 0L
        var count = 0
        suspend fun deleteForGood(files: List<ImageItem>) {
            for (img in files) {
                val deleted = try {
                    imageRepository.deleteImage(Uri.parse(img.uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting file: ${img.uri}", e)
                    false
                }
                if (deleted) {
                    gone += img.uri
                    bytes += img.fileSize
                    count++
                } else {
                    Log.e(TAG, "Failed to delete file on disk: ${img.uri}")
                    failed += img
                }
            }
        }

        plainEntries.forEach { deleteForGood(targets.getValue(it)) }
        if (binEntries.isNotEmpty()) {
            val items = binEntries.flatMap { e ->
                targets.getValue(e).mapNotNull { img -> bin[img.uri]?.let { TrashItem(img.uri, it, img.fileSize) } }
            }
            val answer = systemTrash.submit(items, release = release).await()
            val trashed = answer.trashed.map { it.documentUri }.toSet()
            gone += trashed
            for (e in binEntries) {
                val files = targets.getValue(e)
                if (files.filter { it.uri in bin }.all { it.uri in trashed }) deleteForGood(files.filter { it.uri !in bin })
            }
        } else if (release) {
            systemTrash.release()
        }
        val restore = entries.mapNotNull { e ->
            if (targets.getValue(e).all { it.uri in gone }) null else OptimisticFeed.slotsLeftBehind(e.slots, gone)
        }
        return Outcome(gone, count, bytes, failed, restore)
    }

    private companion object {
        const val TAG = "PendingDeleteCommit"
    }
}
