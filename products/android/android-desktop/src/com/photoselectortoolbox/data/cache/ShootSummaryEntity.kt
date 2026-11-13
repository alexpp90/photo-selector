package com.photoselectortoolbox.data.cache

import androidx.room.Entity
import androidx.room.Index

/**
 * One shoot's summary for one camera class (shoot debrief SD-3): counts and times only, and the
 * folder as a hash ([folderKey], SHA-256 of the canonical tree URI), because a folder path can be
 * a place name. Learn history: *Clear cache*, the score cache's pruning and a file delete leave it
 * alone; only *Reset Learn* removes it. Never a grant holder: a summary does not open its folder.
 */
@Entity(
    tableName = "shoot_summaries",
    primaryKeys = ["folderKey", "cameraClass"],
    indices = [Index(value = ["cameraClass", "lastCaptureTime"], name = "idx_shoot_summaries_class_time")],
)
data class ShootSummaryEntity(
    val folderKey: String,
    val cameraClass: String,
    /** The camera names in the group, most photos first, one per line. */
    val cameraNames: String,
    val firstCaptureTime: Long?,
    val lastCaptureTime: Long?,
    val photoCount: Int,
    /** The metric counts as JSON: `{"<metric>":{"matching":n,"total":m}, …}`. */
    val metricsJson: String,
    val detectorVersion: Int,
    val partial: Boolean,
    val updatedAt: Long,
)

/** The camera class the user chose for one camera (make + model as EXIF writes them; SD-3). */
@Entity(tableName = "camera_overrides", primaryKeys = ["make", "model"])
data class CameraOverrideEntity(
    val make: String,
    val model: String,
    val cameraClass: String,
)
