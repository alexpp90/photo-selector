package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl
import com.photoselectortoolbox.domain.format.CaptureTime
import com.photoselectortoolbox.domain.format.ExifFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ComparableScene(
    val sceneId: String,
    val title: String,
    val photos: List<ImageItem>,
    val primaryPhoto: ImageItem,
    val comparisonPhoto: ImageItem,
    val diffExplanation: String,
    val differenceBullets: List<String>,
)

/**
 * Pairs two frames of one scene whose measurements differ, and says how (`U-27`,
 * REQ-AND-LEARN.03, `[USER 2026-11-12]` decision #17).
 *
 * Two frames are of one scene when they belong to the same burst (`groupId`, whatever their times
 * and focal lengths), or were taken within [MAX_TIME_GAP_MS] of each other **and**, where both
 * have a focal length, within [MAX_FOCAL_REL_DIFF] of each other. They are a pair only when a
 * metric measured on both decides it ([Difference]): sharpness differs by ≥ 40 % of the higher
 * score, or highlight clipping, shadow clipping or noise is at least twice the other with the
 * larger value ≥ 1 % (noise ≥ 1.0). An unmeasured metric is unknown, never 0. EXIF differences
 * explain a pair but never make one; there is no whole-folder fallback and no filler sentence.
 */
object SceneComparator {

    internal const val MAX_TIME_GAP_MS = 10 * 60 * 1000L
    internal const val MAX_FOCAL_REL_DIFF = 0.25
    internal const val MIN_SHARPNESS_REL_DIFF = 0.40
    internal const val MIN_RATIO = 2.0
    internal const val MIN_CLIPPING_PERCENT = 1.0
    internal const val MIN_NOISE = 1.0

    /** Frames compared with each one at most, so a long shoot of near-simultaneous frames stays linear. */
    private const val MAX_NEIGHBOURS = 64

    /** The metric that decides a pair, and which way is better. */
    enum class Metric(val higherIsBetter: Boolean) {
        SHARPNESS(true),
        HIGHLIGHT_CLIPPING(false),
        SHADOW_CLIPPING(false),
        NOISE(false),
    }

    /**
     * A measured difference between two frames: [strength] is how far it is over its threshold
     * (1.0 = exactly at it), so the strongest difference of a scene decides its pair.
     */
    data class Difference(val metric: Metric, val strength: Double, val better: ImageItem, val worse: ImageItem)

    fun findComparableScenes(images: List<ImageItem>): List<ComparableScene> {
        if (images.size < 2) return emptyList()
        val scenes = mutableListOf<ComparableScene>()
        clusters(images).forEach { cluster ->
            val decided = strongestPair(cluster.photos, cluster.isBurst) ?: return@forEach
            val bullets = explainDifference(decided.better, decided.worse)
            val index = scenes.size + 1
            scenes += ComparableScene(
                sceneId = "scene_$index",
                title = "Scene $index · ${decided.better.fileName} vs ${decided.worse.fileName}",
                photos = cluster.photos,
                primaryPhoto = decided.better,
                comparisonPhoto = decided.worse,
                diffExplanation = bullets.joinToString(" "),
                differenceBullets = bullets,
            )
        }
        return scenes
    }

    private class Cluster(val photos: List<ImageItem>, val isBurst: Boolean)

    /** Bursts first, then runs of frames each within the time and focal-length bounds of the one before. */
    private fun clusters(images: List<ImageItem>): List<Cluster> {
        val result = mutableListOf<Cluster>()
        images.filter { it.groupId != null }
            .groupBy { it.groupId }
            .values
            .filter { it.size >= 2 }
            .forEach { result += Cluster(it, isBurst = true) }

        // A frame with no time at all has no place in "within 10 minutes".
        val timed = images.filter { it.groupId == null && timeOf(it) != null }
            .sortedBy { timeOf(it) }
        var run = mutableListOf<ImageItem>()
        for (image in timed) {
            val previous = run.lastOrNull()
            if (previous != null && !sameScene(previous, image)) {
                if (run.size >= 2) result += Cluster(run, isBurst = false)
                run = mutableListOf()
            }
            run += image
        }
        if (run.size >= 2) result += Cluster(run, isBurst = false)
        return result
    }

    /** The strongest measured difference between two frames of [photos] that are of one scene. */
    private fun strongestPair(photos: List<ImageItem>, isBurst: Boolean): Difference? {
        val ordered = if (isBurst) photos else photos.sortedBy { timeOf(it) }
        var best: Difference? = null
        for (i in ordered.indices) {
            for (j in (i + 1)..min(ordered.lastIndex, i + MAX_NEIGHBOURS)) {
                val a = ordered[i]
                val b = ordered[j]
                if (!isBurst && !sameScene(a, b)) continue
                val difference = measuredDifference(a, b) ?: continue
                if (best == null || difference.strength > best.strength) best = difference
            }
        }
        return best
    }

    /** Within the time window and, where both have one, the focal-length window. */
    internal fun sameScene(a: ImageItem, b: ImageItem): Boolean = withinTime(a, b) && focalClose(a, b)

    private fun withinTime(a: ImageItem, b: ImageItem): Boolean {
        val (ta, tb) = comparableTimes(a, b) ?: return false
        return abs(ta - tb) <= MAX_TIME_GAP_MS
    }

    /** Capture times when both have one, else both file times; null when no common clock exists. */
    private fun comparableTimes(a: ImageItem, b: ImageItem): Pair<Long, Long>? {
        val ca = CaptureTime.of(a)
        val cb = CaptureTime.of(b)
        if (ca != null && cb != null) return ca to cb
        if (a.lastModified > 0L && b.lastModified > 0L) return a.lastModified to b.lastModified
        return null
    }

    /** The time a frame sorts by: its capture time, else its file time; null when it has neither. */
    private fun timeOf(image: ImageItem): Long? =
        CaptureTime.of(image) ?: image.lastModified.takeIf { it > 0L }

    private fun focalClose(a: ImageItem, b: ImageItem): Boolean {
        val ea = a.exifData
        val eb = b.exifData
        // 35 mm equivalents when both have them, else the real focal lengths; never one of each.
        val (fa, fb) = when {
            ea?.focalLength35mm != null && eb?.focalLength35mm != null -> ea.focalLength35mm to eb.focalLength35mm
            ea?.focalLength != null && eb?.focalLength != null -> ea.focalLength to eb.focalLength
            else -> return true // "where both have a focal length"
        }
        val longer = max(fa!!, fb!!)
        if (longer <= 0.0) return true
        return abs(fa - fb) <= MAX_FOCAL_REL_DIFF * longer
    }

    /** The strongest difference measured on both frames that passes its threshold, or null. */
    internal fun measuredDifference(a: ImageItem, b: ImageItem): Difference? = differences(a, b).maxByOrNull { it.strength }

    /** Every difference measured on both frames that passes its threshold. */
    private fun differences(a: ImageItem, b: ImageItem): List<Difference> {
        val sa = a.scanResult
        val sb = b.scanResult
        return listOfNotNull(
            relative(Metric.SHARPNESS, a, b, sa?.sharpnessScore, sb?.sharpnessScore),
            ratio(Metric.HIGHLIGHT_CLIPPING, a, b, sa?.highlightClipping, sb?.highlightClipping, MIN_CLIPPING_PERCENT),
            ratio(Metric.SHADOW_CLIPPING, a, b, sa?.shadowClipping, sb?.shadowClipping, MIN_CLIPPING_PERCENT),
            ratio(Metric.NOISE, a, b, sa?.noiseLevel, sb?.noiseLevel, MIN_NOISE),
        )
    }

    /**
     * The debrief's worked pair from [images] (one camera group, shoot debrief SD-5): two frames of
     * one scene, by the same rules as [findComparableScenes], with the largest measured difference
     * in [preferred] — the metric the group's "Next time" line is about — else the largest of any
     * metric. Null when nothing measured differs: no pair is made up.
     */
    fun workedPair(images: List<ImageItem>, preferred: Metric?): Difference? {
        var best: Difference? = null
        var bestPreferred: Difference? = null
        clusters(images).forEach { cluster ->
            val ordered = if (cluster.isBurst) cluster.photos else cluster.photos.sortedBy { timeOf(it) }
            for (i in ordered.indices) {
                for (j in (i + 1)..min(ordered.lastIndex, i + MAX_NEIGHBOURS)) {
                    if (!cluster.isBurst && !sameScene(ordered[i], ordered[j])) continue
                    val found = differences(ordered[i], ordered[j])
                    found.maxByOrNull { it.strength }?.let { if (best == null || it.strength > best!!.strength) best = it }
                    found.firstOrNull { it.metric == preferred }?.let {
                        if (bestPreferred == null || it.strength > bestPreferred!!.strength) bestPreferred = it
                    }
                }
            }
        }
        return bestPreferred ?: best
    }

    /** Sharpness: the difference is at least [MIN_SHARPNESS_REL_DIFF] of the higher score. */
    private fun relative(metric: Metric, a: ImageItem, b: ImageItem, va: Double?, vb: Double?): Difference? {
        if (va == null || vb == null) return null
        val higher = max(va, vb)
        if (higher <= 0.0) return null
        val strength = abs(va - vb) / higher / MIN_SHARPNESS_REL_DIFF
        if (strength < 1.0) return null
        return if (va >= vb) Difference(metric, strength, a, b) else Difference(metric, strength, b, a)
    }

    /** Clipping and noise: the larger is at least [MIN_RATIO] × the smaller and at least [floor]. */
    private fun ratio(metric: Metric, a: ImageItem, b: ImageItem, va: Double?, vb: Double?, floor: Double): Difference? {
        if (va == null || vb == null) return null
        val larger = max(va, vb)
        val smaller = min(va, vb)
        if (larger < floor || larger < MIN_RATIO * smaller) return null
        // A zero beside a measured value is an unbounded ratio; cap it so it compares with the others.
        val strength = if (smaller <= 0.0) MAX_STRENGTH else (larger / smaller / MIN_RATIO).coerceAtMost(MAX_STRENGTH)
        // Less clipping or noise is better.
        return if (va <= vb) Difference(metric, strength, a, b) else Difference(metric, strength, b, a)
    }

    private const val MAX_STRENGTH = 10.0

    /**
     * What was measured and set differently on [photoA] and [photoB]: one sentence per difference,
     * only differences that are there (no sentence for similar frames).
     */
    fun explainDifference(photoA: ImageItem, photoB: ImageItem): List<String> {
        val bullets = mutableListOf<String>()
        val exifA = photoA.exifData
        val exifB = photoB.exifData
        val scanA = photoA.scanResult
        val scanB = photoB.scanResult

        val sharpA = scanA?.sharpnessScore
        val sharpB = scanB?.sharpnessScore
        if (sharpA != null && sharpB != null && max(sharpA, sharpB) > 0.0 &&
            abs(sharpA - sharpB) / max(sharpA, sharpB) >= MIN_SHARPNESS_REL_DIFF
        ) {
            bullets += String.format(
                Locale.US,
                "Measured sharpness: %.0f on %s vs %.0f on %s.",
                sharpA, photoA.fileName, sharpB, photoB.fileName,
            )
        }
        ratioBullet("Highlight clipping", scanA?.highlightClipping, scanB?.highlightClipping, MIN_CLIPPING_PERCENT, photoA, photoB, "%.1f%%")
            ?.let(bullets::add)
        ratioBullet("Shadow clipping", scanA?.shadowClipping, scanB?.shadowClipping, MIN_CLIPPING_PERCENT, photoA, photoB, "%.1f%%")
            ?.let(bullets::add)
        ratioBullet("Measured noise", scanA?.noiseLevel, scanB?.noiseLevel, MIN_NOISE, photoA, photoB, "%.1f")
            ?.let(bullets::add)

        // Camera settings explain a measured difference; they are never one themselves.
        val shutterA = exifA?.shutterSpeed
        val shutterB = exifB?.shutterSpeed
        if (shutterA != null && shutterB != null && abs(shutterA - shutterB) / max(shutterA, shutterB) > 0.15) {
            val fEff = PhotoCoachEngineImpl.computeEffectiveFocalLength(exifA)
                ?: PhotoCoachEngineImpl.computeEffectiveFocalLength(exifB)
            val guidelineNote = if (fEff != null) " (1/${fEff.toInt()}s hand-holding guideline)" else ""
            bullets += "Shutter speed: ${photoA.fileName} used ${ExifFormatter.shutterSpeed(shutterA)} vs " +
                "${ExifFormatter.shutterSpeed(shutterB)} in ${photoB.fileName}$guidelineNote."
        }
        val apA = exifA?.aperture
        val apB = exifB?.aperture
        if (apA != null && apB != null && abs(apA - apB) >= 0.3) {
            bullets += "Aperture: ${photoA.fileName} shot at ${ExifFormatter.aperture(apA)} vs " +
                "${ExifFormatter.aperture(apB)} in ${photoB.fileName}, changing depth of field and light intake."
        }
        val isoA = exifA?.iso
        val isoB = exifB?.iso
        if (isoA != null && isoB != null && isoA != isoB) {
            bullets += "ISO sensitivity: ISO $isoA vs ISO $isoB."
        }
        return bullets
    }

    private fun ratioBullet(
        label: String,
        va: Double?,
        vb: Double?,
        floor: Double,
        photoA: ImageItem,
        photoB: ImageItem,
        format: String,
    ): String? {
        if (va == null || vb == null) return null
        val larger = max(va, vb)
        if (larger < floor || larger < MIN_RATIO * min(va, vb)) return null
        return String.format(Locale.US, "$label: $format on %s vs $format on %s.", va, photoA.fileName, vb, photoB.fileName)
    }
}
