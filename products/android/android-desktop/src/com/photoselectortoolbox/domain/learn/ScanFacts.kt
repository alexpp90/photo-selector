package com.photoselectortoolbox.domain.learn

import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.CaptureTime
import java.time.ZoneId

/**
 * The facts a scan stores for every photo it analyses ([USER 2026-11-12] decision #19, option C;
 * shoot debrief SD-1a): the smaller set that something reads — Learn's lessons, its pattern
 * insights and practice goal, and the shoot debrief that follows (SD-3) — instead of every
 * detector's output. The other kinds (tonal key, background separation, leading lines,
 * simplicity, symmetry) are still detected on demand for the photo Explore shows, and never
 * stored.
 *
 * Every stored set carries a [MARKER] row, so "detected, and nothing found" differs from "never
 * detected". A photo's facts are current when its marker matches the file's size and date and
 * [TechniqueFactDetector.DETECTOR_VERSION]; otherwise the scan detects them again and replaces
 * the photo's rows in one transaction.
 */
object ScanFacts {

    /** Always written: the photo's facts were detected for this file and detector version. */
    const val MARKER = "facts.scanned"

    /** When the photo was taken, epoch milliseconds ([CaptureTime.instant]). */
    const val CAPTURE_TIME = "capture.time"

    /** The exposure time in seconds, for the debrief's hand-holding count (SD-3). */
    const val EXPOSURE_TIME = "exposure.time"

    /** The camera's EXIF `Make` and `Model` as written: the debrief's camera names and overrides (SD-3). */
    const val CAMERA_MAKE = "camera.make"
    const val CAMERA_MODEL = "camera.model"

    /** The camera's class by [CameraProfiler] and who chose the exposure (SD-2b). */
    const val CAMERA_CLASS = "camera.class"
    const val DECIDED_BY = "camera.decided_by"

    /** The detector kinds the scan keeps; [TechniqueFactDetector.detectFacts] runs only these. */
    val STORED_KINDS: Set<String> = setOf(
        "optics.crop_factor",
        "optics.effective_focal_length",
        "light.hour",
        "intent.shutter",
        "exposure.clipping",
        "composition.horizon",
        "composition.subject_placement",
        CAMERA_CLASS,
        DECIDED_BY,
        "intent.exposure_program",
        "intent.exposure_bias",
    )

    /** Every kind a stored set can hold. */
    val ALL_KINDS: Set<String> = STORED_KINDS + setOf(MARKER, CAPTURE_TIME, EXPOSURE_TIME, CAMERA_MAKE, CAMERA_MODEL)

    /** Confidence of a capture time read with the camera's own offset, and of one in the device's zone. */
    private const val WITH_OFFSET = 0.95
    private const val IN_DEVICE_ZONE = 0.75

    /**
     * The rows to store for [image]: [detected] facts of the stored kinds, its capture time from
     * [exif], and the marker. A `light.hour` fact keeps its class and loses the solar elevation it
     * was computed from: that number, with the capture time, narrows down where the photo was
     * taken, and nothing new stores a place (C3).
     */
    fun entities(
        image: ImageItem,
        exif: ExifData?,
        detected: List<TechniqueFact>,
        fallbackZone: ZoneId,
    ): List<TechniqueFactEntity> {
        val facts = detected.filter { it.kind in STORED_KINDS }.map { fact ->
            if (fact.kind == "light.hour") fact.copy(geometryJson = null) else fact
        }.toMutableList()
        CaptureTime.instant(exif?.dateTimeOriginal, exif?.offsetTimeOriginal, fallbackZone)?.let { epochMs ->
            val ownOffset = CaptureTime.offset(exif?.offsetTimeOriginal) != null
            facts += TechniqueFact(CAPTURE_TIME, epochMs.toString(), if (ownOffset) WITH_OFFSET else IN_DEVICE_ZONE)
        }
        exif?.shutterSpeed?.takeIf { it.isFinite() && it > 0.0 }?.let { seconds ->
            facts += TechniqueFact(EXPOSURE_TIME, seconds.toString(), 1.0)
        }
        exif?.make?.trim()?.takeIf { it.isNotEmpty() }?.let { facts += TechniqueFact(CAMERA_MAKE, it, 1.0) }
        exif?.model?.trim()?.takeIf { it.isNotEmpty() }?.let { facts += TechniqueFact(CAMERA_MODEL, it, 1.0) }
        facts += TechniqueFact(MARKER, "1", 1.0)
        return facts.map { fact ->
            TechniqueFactEntity(
                uri = image.uri,
                kind = fact.kind,
                value = fact.value,
                confidence = fact.confidence,
                geometryJson = fact.geometryJson,
                fileSize = image.fileSize,
                lastModified = image.lastModified,
                detectorVersion = TechniqueFactDetector.DETECTOR_VERSION,
            )
        }
    }

    /** Whether [marker] (a [MARKER] row) says [image]'s stored facts are current. */
    fun isCurrent(marker: TechniqueFactEntity, image: ImageItem): Boolean =
        marker.kind == MARKER &&
            marker.uri == image.uri &&
            marker.fileSize == image.fileSize &&
            marker.lastModified == image.lastModified &&
            marker.detectorVersion == TechniqueFactDetector.DETECTOR_VERSION

    /** The capture time stored in [facts], or null when the photo has none. */
    fun captureTimeOf(facts: List<TechniqueFactEntity>?): Long? =
        facts?.firstOrNull { it.kind == CAPTURE_TIME }?.value?.toLongOrNull()
}
