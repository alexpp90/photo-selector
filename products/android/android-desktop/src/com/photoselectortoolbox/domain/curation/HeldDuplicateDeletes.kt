package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.DuplicateGroup

/**
 * One delete on the Dupes screen, held back the way the cull holds a delete (REQ-AND-LAYOUT.22):
 * the copies leave the duplicate list now and nothing touches the disk until the screen or the
 * folder is left. Undo puts the list back exactly as it was before this delete.
 */
data class HeldDuplicateDelete(
    /** Every file the delete removes: each copy with its sidecars and companions. */
    val files: List<CompanionFile>,
    /** How many copies (photos, not files) were deleted. */
    val copies: Int,
    /** The duplicate list before this delete, restored by undo. */
    val groupsBefore: List<DuplicateGroup>,
    /** The ticked copies before this delete, restored by undo. */
    val selectionBefore: Set<String>,
)

/**
 * Pure state of the held Dupes deletes: a stack like the cull's undo history, newest last,
 * [UndoPolicy.MAX_UNDO_HISTORY] deep. A delete pushed off the bottom is committed by the caller.
 */
object HeldDuplicateDeletes {

    /** [groups] without the copies in [deleted]; a group left with one copy is no duplicate any more. */
    fun remainingGroups(groups: List<DuplicateGroup>, deleted: Set<String>): List<DuplicateGroup> =
        groups.mapNotNull { group ->
            val left = group.files.filter { it !in deleted }
            if (left.size > 1) group.copy(files = left) else null
        }

    /** The stack with [held] on top, and the deletes that no longer fit (oldest first). */
    fun push(
        stack: List<HeldDuplicateDelete>,
        held: HeldDuplicateDelete,
        maxDepth: Int = UndoPolicy.MAX_UNDO_HISTORY,
    ): Pair<List<HeldDuplicateDelete>, List<HeldDuplicateDelete>> {
        val all = stack + held
        val overflow = (all.size - maxDepth).coerceAtLeast(0)
        return all.drop(overflow) to all.take(overflow)
    }
}
