package com.phototok.data.source

/**
 * Outcome of a single SAF file copy or move operation (`docs/shared/FILE_OPERATIONS.md`): the
 * shared `:saf` result type.
 *
 * - `success` is true when the transfer completed in full (copy verified; for a move, the
 *   source was also removed).
 * - `partial` is true when a move's destination copy was verified on disk, but removing the
 *   original source failed. The destination copy is preserved and the caller must surface a
 *   partial-outcome warning (`Copied to … — original could not be removed`) and restore the
 *   source item to the feed.
 */
typealias FileOperationResult = com.photoselector.saf.FileOperationResult
