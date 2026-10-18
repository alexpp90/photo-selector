package com.phototok.domain

import com.phototok.data.model.ImageItem

/**
 * Pure state transitions for the swipe-to-delete undo window.
 *
 * A left-swipe delete is applied to the UI lists immediately but the file(s) are
 * only removed from disk once the deletion is "finalized" (next delete, folder
 * change, screen exit, or ViewModel clear). Until then the user can revert.
 *
 * Extracted from PhoneModeViewModel so the index math and sibling handling can be
 * unit-tested without Android dependencies.
 */
object PendingDeleteLogic {

    /** A deletion that has been applied to the UI but not yet to disk. */
    data class Pending(
        val image: ImageItem,
        /** Index the image had in the filtered list (for re-insertion on revert). */
        val index: Int,
        /** Index the image had in the unfiltered list (-1 if not present). */
        val allImagesIndex: Int,
        /** Sibling files removed together with the primary image. */
        val related: List<ImageItem>,
        /** Revert stays available while the image at this URI is the active one. */
        val revertAllowedUri: String?,
        /** Exact slots of the primary image and its siblings in both feed lists. */
        val slots: List<OptimisticFeed.Slot> =
            listOf(OptimisticFeed.Slot(image, index, allImagesIndex)) +
                related.map { OptimisticFeed.Slot(it, -1, -1) },
    )

    /** Snapshot of the list-related UI state after a transition. */
    data class ListsState(
        val images: List<ImageItem>,
        val allImages: List<ImageItem>,
        val currentIndex: Int,
        val portraitSectionStart: Int,
    )

    /**
     * Remove the image at [currentIndex] (and its [related] siblings) from both lists.
     * Returns null when [currentIndex] is out of bounds.
     */
    fun remove(
        images: List<ImageItem>,
        allImages: List<ImageItem>,
        currentIndex: Int,
        related: List<ImageItem>,
        sortByOrientation: Boolean,
    ): Pair<ListsState, Pending>? {
        val image = images.getOrNull(currentIndex) ?: return null
        val allImagesIndex = allImages.indexOfFirst { it.uri == image.uri }
        val targets = listOf(image) + related
        val slots = OptimisticFeed.slotsOf(images, allImages, targets)
        val removedUris = targets.map { it.uri }.toSet()

        val updatedImages = images.filter { it.uri !in removedUris }
        val updatedAll = allImages.filter { it.uri !in removedUris }
        val removedBefore = (0 until currentIndex).count { images[it].uri in removedUris }
        val newIndex = if (updatedImages.isEmpty()) {
            0
        } else {
            (currentIndex - removedBefore).coerceIn(0, updatedImages.size - 1)
        }

        val state = ListsState(
            images = updatedImages,
            allImages = updatedAll,
            currentIndex = newIndex,
            portraitSectionStart = PhoneFeedOrdering.portraitSplit(updatedImages, sortByOrientation),
        )
        val pending = Pending(
            image = image,
            index = currentIndex,
            allImagesIndex = allImagesIndex,
            related = related,
            revertAllowedUri = updatedImages.getOrNull(newIndex)?.uri,
            slots = slots,
        )
        return Pair(state, pending)
    }

    /** Re-insert a [pending] deletion (primary + siblings) into both lists. */
    fun restore(
        images: List<ImageItem>,
        allImages: List<ImageItem>,
        pending: Pending,
        sortByOrientation: Boolean,
    ): ListsState {
        val updatedImages = images.toMutableList()
        pending.slots
            .filter { it.index >= 0 }
            .sortedBy { it.index }
            .forEach { slot ->
                if (updatedImages.none { it.uri == slot.image.uri }) {
                    updatedImages.add(slot.index.coerceIn(0, updatedImages.size), slot.image)
                }
            }

        val updatedAll = allImages.toMutableList()
        pending.slots
            .filter { it.allImagesIndex >= 0 }
            .sortedBy { it.allImagesIndex }
            .forEach { slot ->
                if (updatedAll.none { it.uri == slot.image.uri }) {
                    updatedAll.add(slot.allImagesIndex.coerceIn(0, updatedAll.size), slot.image)
                }
            }
        // Sidecars were never feed items, so they have no place to come back to.
        pending.slots
            .filter { it.allImagesIndex < 0 && !CompanionFiles.isSidecar(it.image.fileName) }
            .forEach { slot ->
                if (updatedAll.none { it.uri == slot.image.uri }) {
                    updatedAll.add(slot.image)
                }
            }

        val restoredIndex = updatedImages.indexOfFirst { it.uri == pending.image.uri }
            .takeIf { it >= 0 }
            ?: pending.index.coerceIn(0, (updatedImages.size - 1).coerceAtLeast(0))

        return ListsState(
            images = updatedImages,
            allImages = updatedAll,
            currentIndex = restoredIndex,
            portraitSectionStart = PhoneFeedOrdering.portraitSplit(updatedImages, sortByOrientation),
        )
    }

    /** Maximum number of pending deletions held in the session undo stack (`U-18`). */
    const val MAX_STACK_SIZE = 50

    /** Result of pushing a new deletion onto the bounded LIFO undo stack. */
    data class StackPushResult(
        val state: ListsState,
        val pushed: Pending,
        val updatedStack: List<Pending>,
        /** Non-null when the stack overflowed [MAX_STACK_SIZE] and the oldest item must be committed. */
        val evictedOldest: Pending?,
    )

    /** Result of popping and restoring the most recent deletion from the LIFO undo stack. */
    data class StackPopResult(
        val state: ListsState,
        val restored: Pending,
        val remainingStack: List<Pending>,
    )

    /**
     * Remove the image at [currentIndex] and push it onto [currentStack] (LIFO: newest last).
     * If [currentStack] is already at [maxStackSize], the oldest entry (`first()`) is evicted
     * and returned as [StackPushResult.evictedOldest] so the caller can commit it immediately.
     */
    fun pushRemove(
        images: List<ImageItem>,
        allImages: List<ImageItem>,
        currentIndex: Int,
        related: List<ImageItem>,
        sortByOrientation: Boolean,
        currentStack: List<Pending>,
        maxStackSize: Int = MAX_STACK_SIZE,
    ): StackPushResult? {
        val (state, pending) = remove(
            images = images,
            allImages = allImages,
            currentIndex = currentIndex,
            related = related,
            sortByOrientation = sortByOrientation,
        ) ?: return null

        val cap = maxStackSize.coerceAtLeast(1)
        val evicted = if (currentStack.size >= cap) currentStack.first() else null
        val kept = if (currentStack.size >= cap) currentStack.drop(1) else currentStack
        val updatedStack = kept + pending
        return StackPushResult(
            state = state,
            pushed = pending,
            updatedStack = updatedStack,
            evictedOldest = evicted,
        )
    }

    /**
     * Pop the most recently deleted item (`last()`) from [currentStack] and restore it into
     * [images] and [allImages]. Returns null when [currentStack] is empty.
     */
    fun popRestore(
        images: List<ImageItem>,
        allImages: List<ImageItem>,
        currentStack: List<Pending>,
        sortByOrientation: Boolean,
    ): StackPopResult? {
        val latest = currentStack.lastOrNull() ?: return null
        val state = restore(
            images = images,
            allImages = allImages,
            pending = latest,
            sortByOrientation = sortByOrientation,
        )
        return StackPopResult(
            state = state,
            restored = latest,
            remainingStack = currentStack.dropLast(1),
        )
    }

    /** All primary + sibling URIs in [stack] in FIFO order, deduplicated. */
    fun allTargetUris(stack: List<Pending>): List<String> =
        stack.flatMap { pending ->
            listOf(pending.image.uri) + pending.related.map { it.uri }
        }.distinct()
}

