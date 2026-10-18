package com.photoselectortoolbox.data.repository

/**
 * The outcome of moving, copying or reversing a move: the shared `:saf` result type.
 *
 * `moveImage`/`copyImage` used to return `Boolean`, which discarded the one piece of
 * information an undo needs: **where the file went**. The shape is the one
 * [com.photoselectortoolbox.domain.usecase.MoveToSelectionUseCase]'s `MoveResult` aliases, so
 * there is exactly one result shape for file operations in this product. Whether an undo is
 * possible is decided by [com.photoselectortoolbox.domain.curation.UndoPolicy], which tests
 * the destination URI, not just `success`.
 */
typealias FileOperationResult = com.photoselector.saf.FileOperationResult
