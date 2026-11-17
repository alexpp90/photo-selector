package com.photoselectortoolbox.data.repository

import com.photoselectortoolbox.data.cache.CopiedPhotoDao
import com.photoselectortoolbox.data.cache.CopiedPhotoEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.KeptFiles
import com.photoselectortoolbox.di.IoDispatcher
import com.photoselectortoolbox.domain.curation.CopiedRecord
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.learn.FolderKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The copied photos of each folder, kept across sessions (`docs/shared/FILE_OPERATIONS.md` §5,
 * D3): local app state, nothing is written into or beside a photo.
 */
interface CopiedPhotoRepository {

    /** [photo] of [folderUri] was copied; [keptFiles] is what the copy put in the Selection (`KeptFiles.of`). */
    suspend fun record(folderUri: String, photo: ImageItem, keptFiles: Map<String, Long>, nowEpochMs: Long)

    suspend fun copiedIn(folderUri: String): List<CopiedRecord>

    suspend fun forget(folderUri: String, sourceUris: Collection<String>)
}

@Singleton
class CopiedPhotoRepositoryImpl @Inject constructor(
    private val dao: CopiedPhotoDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CopiedPhotoRepository {

    override suspend fun record(folderUri: String, photo: ImageItem, keptFiles: Map<String, Long>, nowEpochMs: Long) =
        withContext(ioDispatcher) {
            // The photo's own copy is the first member (`ImageItem.allFiles` order, as `KeptFiles.of` keeps it).
            // A copy whose destination went unrecorded can never be verified: nothing to remember.
            val primary = keptFiles.keys.firstOrNull()?.takeUnless { it.startsWith(KeptFiles.UNRECORDED) }
                ?: return@withContext
            dao.upsert(
                CopiedPhotoEntity(
                    folderKey = FolderKey.of(folderUri),
                    sourceUri = photo.uri,
                    fileSize = photo.fileSize,
                    lastModified = photo.lastModified,
                    primaryCopyId = primary,
                    keptFilesJson = JSONObject().apply { keptFiles.forEach { (id, size) -> put(id, size) } }.toString(),
                    copiedAt = nowEpochMs,
                ),
            )
        }

    override suspend fun copiedIn(folderUri: String): List<CopiedRecord> = withContext(ioDispatcher) {
        dao.copiedIn(FolderKey.of(folderUri)).map { row ->
            val json = JSONObject(row.keptFilesJson)
            CopiedRecord(
                sourceUri = row.sourceUri,
                identity = FileIdentity(row.fileSize, row.lastModified),
                primaryCopyId = row.primaryCopyId,
                keptFiles = json.keys().asSequence().associateWith { json.getLong(it) },
            )
        }
    }

    override suspend fun forget(folderUri: String, sourceUris: Collection<String>) = withContext(ioDispatcher) {
        if (sourceUris.isNotEmpty()) dao.forget(FolderKey.of(folderUri), sourceUris)
    }
}
