package com.photoselectortoolbox.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** The copied-photo records of D3 ([CopiedPhotoEntity]). */
@Dao
interface CopiedPhotoDao {

    @Query("SELECT * FROM copied_photos WHERE folderKey = :folderKey")
    suspend fun copiedIn(folderKey: String): List<CopiedPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: CopiedPhotoEntity)

    @Query("DELETE FROM copied_photos WHERE folderKey = :folderKey AND sourceUri IN (:sourceUris)")
    suspend fun forgetBatch(folderKey: String, sourceUris: List<String>)

    /** Drops the records of [sourceUris] in [folderKey], in chunks under the SQLite bind limit. */
    @Transaction
    suspend fun forget(folderKey: String, sourceUris: Collection<String>) {
        sourceUris.toList().chunked(BIND_CHUNK).forEach { forgetBatch(folderKey, it) }
    }

    @Query("SELECT COUNT(*) FROM copied_photos")
    suspend fun count(): Int

    companion object {
        /** Under SQLite's 999 bound parameters, with room for the folder key. */
        const val BIND_CHUNK = 900
    }
}
