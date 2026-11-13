package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.ImageFormats

/**
 * A debrief metric (shoot debrief SD-3): a count "matching of total" over the photos of one camera
 * group that have what it is measured from. The labels are the detector's, and the two sharpness
 * rules are the photo researcher's (`SD2B_RESEARCH.md` §3, §4, 2026-11-13).
 */
enum class DebriefMetric(val key: String, val needsCameraControls: Boolean) {
    /** Highlights not clipped (`exposure.clipping`; recoverable from the RAW counts as kept). */
    HIGHLIGHTS_KEPT("highlights_kept", needsCameraControls = false),

    /** A level horizon, of the photos with one (`composition.horizon`). */
    LEVEL_HORIZON("level_horizon", needsCameraControls = false),

    /** The subject clear of the frame edge, of the photos with a subject (`composition.subject_placement`). */
    SUBJECT_OFF_EDGE("subject_off_edge", needsCameraControls = false),

    /**
     * Fast enough to hand-hold: a frame counts against it only when it is slower than 1.05 / the
     * 35 mm equivalent focal length **and** soft (below [ShootDebriefEngine.SOFT_SCORE]) or not
     * scored — stabilisation is not readable from EXIF, so a sharp slow frame is no problem.
     * Exposures of 1 s or longer are left out (a tripod, or deliberate). Counted for cameras with
     * interchangeable lenses and compacts only: phones merge frames and stabilise, action cams
     * stabilise electronically and drones fly on a gimbal, so their exposure time does not
     * predict blur.
     */
    HAND_HOLD("hand_hold", needsCameraControls = true),

    /**
     * Frames not clearly soft: a sharpness of at least [ShootDebriefEngine.SOFT_SCORE] (the Photo
     * Coach's blur threshold, so Learn never contradicts it) and, in a burst of three or more
     * scored frames, at least half of the burst's best. Frames with noise of 25 or more, or shot at
     * ISO 12800 or more, are left out: noise inflates the score of a soft frame. Never compared
     * across classes.
     */
    SHARP_FRAMES("sharp_frames", needsCameraControls = false),
    ;

    companion object {
        fun ofKey(key: String): DebriefMetric? = entries.firstOrNull { it.key == key }
    }
}

/** [matching] of [total] photos. Below [ShootDebriefEngine.MIN_SAMPLE_SIZE] it is a count only. */
data class MetricCount(val metric: DebriefMetric, val matching: Int, val total: Int) {
    val enoughPhotos: Boolean get() = total >= ShootDebriefEngine.MIN_SAMPLE_SIZE

    /** The share in whole percent, or null under the sample size: no percentage then. */
    val percent: Int? get() = if (enoughPhotos && total > 0) (matching * 100) / total else null

    val problems: Int get() = total - matching
}

/** One camera class of one shoot. */
data class DebriefGroup(
    val cameraClass: CameraClass,
    val photoCount: Int,
    /** Most photos first. */
    val cameraNames: List<String>,
    val firstCaptureTime: Long?,
    val lastCaptureTime: Long?,
    /** At most [ShootDebriefEngine.MAX_METRICS], the ones with the most photos first. */
    val metrics: List<MetricCount>,
    /** The metric with the largest share of problems among those with enough photos, if any has a problem. */
    val focus: DebriefMetric?,
    /** Who chose the exposure for most photos of the group (`camera.decided_by`). */
    val decidedBy: DecidedBy = DecidedBy.UNKNOWN,
    /** The "Next time" lines for [focus], from [AdviceCatalog]; empty without a focus. */
    val advice: List<String> = emptyList(),
    /** The group's photos (not stored: the debrief screen's worked pair and filter read them). */
    val photoUris: List<String> = emptyList(),
    /** Per metric, the photos that count against it ("Show 28"); not stored. */
    val problemUris: Map<DebriefMetric, List<String>> = emptyMap(),
)

/**
 * A shoot's debrief from its photos and their stored facts (shoot debrief SD-3), pure: one group
 * per camera class — a mixed folder is grouped, never averaged — with the photo count, the camera
 * names, up to three metric counts and the focus. Only photos the scan gave facts to count. The
 * advice for the focus is SD-2b's `AdviceCatalog`; the previous shoot is [previousValue]'s.
 */
object ShootDebriefEngine {

    /** Below this a metric is a count only, with no percentage and no previous value. */
    const val MIN_SAMPLE_SIZE = PracticeAndInsightsEngine.MIN_SAMPLE_SIZE

    const val MAX_METRICS = 3

    private const val MIN_CONFIDENCE = 0.55
    private const val HAND_HOLD_TOLERANCE = 1.05

    /** Below this sharpness a frame is clearly soft: the Photo Coach's blur threshold. */
    const val SOFT_SCORE = 40.0

    /** A frame of a burst of this many scored frames or more must reach [BURST_SHARE] of its best. */
    private const val BURST_MIN_FRAMES = 3
    private const val BURST_SHARE = 0.5

    /** A frame this noisy, or shot at this ISO or higher, is left out of the sharp-frames count. */
    private const val NOISY = 25.0
    private const val CRITICAL_ISO = 12800

    /** Exposures this long or longer are left out of the hand-holding count. */
    private const val LONG_EXPOSURE_S = 1.0

    /** One scanned photo: its item (scores, burst, format) and its stored facts. */
    private class Shot(val photo: ImageItem, val facts: List<TechniqueFactEntity>)

    fun groups(
        photos: List<ImageItem>,
        factsByUri: Map<String, List<TechniqueFactEntity>>,
        classifier: CameraClassifier = CameraClassifier.withOverrides(emptyMap()),
    ): List<DebriefGroup> {
        val scanned = photos.mapNotNull { photo ->
            factsByUri[photo.uri]?.takeIf { facts -> facts.any { it.kind == ScanFacts.MARKER } }?.let { Shot(photo, it) }
        }
        val bursts = burstBests(photos)
        return scanned.groupBy { classifier.classify(it.facts) }
            .map { (cameraClass, group) -> groupOf(cameraClass, group, bursts) }
            .sortedWith(compareBy<DebriefGroup> { it.cameraClass == CameraClass.UNKNOWN }.thenByDescending { it.photoCount })
    }

    /** Per burst of [BURST_MIN_FRAMES] or more scored frames, its best sharpness. */
    private fun burstBests(photos: List<ImageItem>): Map<Int, Double> = photos
        .filter { it.groupId != null && sharpnessOf(it) != null }
        .groupBy { it.groupId!! }
        .filterValues { it.size >= BURST_MIN_FRAMES }
        .mapValues { (_, frames) -> frames.maxOf { sharpnessOf(it)!! } }

    private fun sharpnessOf(photo: ImageItem): Double? = photo.scanResult?.sharpnessScore?.takeIf { it.isFinite() }

    private fun groupOf(cameraClass: CameraClass, shots: List<Shot>, bursts: Map<Int, Double>): DebriefGroup {
        val names = shots.mapNotNull { CameraId.of(it.facts)?.displayName }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val times = shots.mapNotNull { ScanFacts.captureTimeOf(it.facts) }
        val metrics = DebriefMetric.entries
            .filter { metric -> metricApplies(metric, cameraClass) }
            .map { metric -> count(metric, shots, bursts) }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
            .take(MAX_METRICS)
        val focus = metrics.filter { it.enoughPhotos && it.problems > 0 }
            .maxByOrNull { it.problems.toDouble() / it.total }
            ?.metric
        val decidedBy = shots.map { DecidedBy.ofKey(it.facts.firstOrNull { f -> f.kind == ScanFacts.DECIDED_BY }?.value) }
            .filter { it != DecidedBy.UNKNOWN }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: DecidedBy.UNKNOWN
        val advice = focus?.let {
            AdviceCatalog.focus(
                focus = it,
                cameraClass = cameraClass,
                decidedBy = decidedBy,
                mostlyRaw = shots.count { s -> s.photo.hasRawCompanion || ImageFormats.isRaw(s.photo.fileName) } * 2 > shots.size,
                slowSoftShare = slowSoftShare(shots),
            )
        }.orEmpty()
        val problems = metrics.associate { count ->
            count.metric to shots.filter { matches(count.metric, it, bursts) == false }.map { it.photo.uri }
        }
        return DebriefGroup(
            cameraClass, shots.size, names, times.minOrNull(), times.maxOrNull(), metrics, focus, decidedBy, advice,
            photoUris = shots.map { it.photo.uri },
            problemUris = problems,
        )
    }

    /**
     * Whether [metric] is counted for [cameraClass] (C2): the hand-holding count for cameras with
     * interchangeable lenses and compacts only (`SD2B_RESEARCH.md` §4).
     */
    fun metricApplies(metric: DebriefMetric, cameraClass: CameraClass): Boolean =
        !metric.needsCameraControls || cameraClass == CameraClass.INTERCHANGEABLE || cameraClass == CameraClass.COMPACT

    private fun count(metric: DebriefMetric, shots: List<Shot>, bursts: Map<Int, Double>): MetricCount {
        var matching = 0
        var total = 0
        for (shot in shots) {
            val kept = matches(metric, shot, bursts) ?: continue
            total++
            if (kept) matching++
        }
        return MetricCount(metric, matching, total)
    }

    /** Of the frames slower than the guideline (under 1 s), the share soft or unscored; null without one. */
    private fun slowSoftShare(shots: List<Shot>): Double? {
        val slow = shots.filter { slowForHand(it.facts) == true }
        if (slow.isEmpty()) return null
        return slow.count { s -> sharpnessOf(s.photo)?.let { it < SOFT_SCORE } ?: true }.toDouble() / slow.size
    }

    /** Whether a frame is slower than the hand-holding guideline; null when that cannot be said or it is ≥ 1 s. */
    private fun slowForHand(facts: List<TechniqueFactEntity>): Boolean? {
        val seconds = facts.firstOrNull { it.kind == ScanFacts.EXPOSURE_TIME }?.value?.toDoubleOrNull()
        val fEff = facts.firstOrNull { it.kind == "optics.effective_focal_length" }?.value?.toDoubleOrNull()
        if (seconds == null || fEff == null || seconds <= 0.0 || fEff <= 0.0 || seconds >= LONG_EXPOSURE_S) return null
        return seconds > HAND_HOLD_TOLERANCE / fEff
    }

    /** Whether one photo matches [metric]; null when it has nothing to say. */
    private fun matches(metric: DebriefMetric, shot: Shot, bursts: Map<Int, Double>): Boolean? {
        val facts = shot.facts
        fun confident(kind: String) = facts.firstOrNull { it.kind == kind && it.confidence >= MIN_CONFIDENCE }?.value
        return when (metric) {
            DebriefMetric.HIGHLIGHTS_KEPT -> confident("exposure.clipping")?.let { it != "highlights_clipped" && it != "both_clipped" }
            DebriefMetric.LEVEL_HORIZON -> confident("composition.horizon")?.let { it == "level" }
            DebriefMetric.SUBJECT_OFF_EDGE -> confident("composition.subject_placement")?.let { it != "edge_crowded" }
            DebriefMetric.HAND_HOLD -> slowForHand(facts)?.let { slow ->
                !slow || (sharpnessOf(shot.photo)?.let { it >= SOFT_SCORE } ?: false)
            }
            DebriefMetric.SHARP_FRAMES -> {
                val score = sharpnessOf(shot.photo) ?: return null
                val scan = shot.photo.scanResult
                val iso = shot.photo.exifData?.iso ?: scan?.exifData?.iso
                if ((scan?.noiseLevel?.let { it >= NOISY } == true) || (iso != null && iso >= CRITICAL_ISO)) return null
                val best = shot.photo.groupId?.let(bursts::get)
                score >= SOFT_SCORE && (best == null || score >= BURST_SHARE * best)
            }
        }
    }

    /**
     * The previous shoot's percentage for [metric], shown beside this one: only when both counts
     * reach the sample size and the previous one is not partial (the caller picks that summary).
     */
    fun previousValue(current: MetricCount, previous: MetricCount?): Int? =
        if (current.enoughPhotos && previous != null && previous.metric == current.metric) previous.percent else null
}
