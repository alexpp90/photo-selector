package com.photoselectortoolbox.data.cache

import androidx.room.Entity

/**
 * A photo copied into the Selection, remembered for its folder so the copy is still a decision
 * when the folder is opened again (`docs/shared/FILE_OPERATIONS.md` §5, maintainer decision
 * 2026-11-16, D3). User state, not cache: *Clear cache* and the score cache's pruning leave it
 * alone. The folder is stored as [folderKey] (SHA-256 of its canonical tree URI, as for shoot
 * summaries); the photo by its URI and identity ([fileSize], [lastModified]), so a different file
 * that later takes the name is not taken for the copied one. Never a grant holder: a record
 * is read only while its folder is open.
 */
@Entity(tableName = "copied_photos", primaryKeys = ["folderKey", "sourceUri"])
data class CopiedPhotoEntity(
    val folderKey: String,
    val sourceUri: String,
    val fileSize: Long,
    val lastModified: Long,
    /** Provider document id of the photo's own copy in the Selection (`KeptFiles` key). */
    val primaryCopyId: String,
    /** Every bundle member's copy as JSON, `{"<document id>": <source size>, …}` (`KeptFiles.of`). */
    val keptFilesJson: String,
    val copiedAt: Long,
)
