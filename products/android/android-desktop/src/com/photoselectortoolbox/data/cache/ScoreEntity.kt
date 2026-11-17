package com.photoselectortoolbox.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scores_cache")
data class ScoreEntity(
    @PrimaryKey
    val filePath: String,
    val fileSize: Long,
    val lastModified: Long,
    val sharpnessScore: Double? = null,
    val noiseLevel: Double? = null,
    val highlightClipping: Double? = null,
    val shadowClipping: Double? = null,
    /**
     * Added in DB schema v2 for an on-device aesthetic score that never shipped; nothing computes
     * it (REQ-AND-FEATURE.02, dropped for good on 2026-11-16, D5). Kept for schema compatibility.
     */
    val aestheticScore: Double? = null,
    /** 1–5 star rating, 0 = unrated, -1 = reject. Added in DB schema v3. */
    val rating: Int = 0,
    /** JSON-serialized OpenCV composition analysis. Added in DB schema v3. */
    val compositionAnalysis: String? = null,
    val lastAccessTime: Long = System.currentTimeMillis()
)
