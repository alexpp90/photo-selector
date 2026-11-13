package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem

/**
 * A debrief metric (shoot debrief SD-3): a count "matching of total" over the photos of one camera
 * group that have the fact it is measured from. Each is a detector label already defined, so no
 * new threshold is introduced here. "Sharp frames" is not one yet: sharpness is an absolute
 * number that does not compare across cameras, and its threshold for a debrief is the photo
 * researcher's to set (hand-back FD-SD).
 */
enum class DebriefMetric(val key: String, val needsCameraControls: Boolean) {
    /** Highlights not clipped (`exposure.clipping`; recoverable from the RAW counts as kept). */
    HIGHLIGHTS_KEPT("highlights_kept", needsCameraControls = false),

    /** A level horizon, of the photos with one (`composition.horizon`). */
    LEVEL_HORIZON("level_horizon", needsCameraControls = false),

    /** The subject clear of the frame edge, of the photos with a subject (`composition.subject_placement`). */
    SUBJECT_OFF_EDGE("subject_off_edge", needsCameraControls = false),

    /**
     * Exposure time within the hand-holding guideline, 1 / the 35 mm equivalent focal length with
     * the 5 % tolerance of the telephoto insight (`exposure.time`, `optics.effective_focal_length`).
     * Not for a drone, which is not hand-held, nor for an unknown camera (C2).
     */
    HAND_HOLD("hand_hold", needsCameraControls = true),
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

    fun groups(
        photos: List<ImageItem>,
        factsByUri: Map<String, List<TechniqueFactEntity>>,
        classifier: CameraClassifier = CameraClassifier.withOverrides(emptyMap()),
    ): List<DebriefGroup> {
        val scanned = photos.mapNotNull { photo ->
            factsByUri[photo.uri]?.takeIf { facts -> facts.any { it.kind == ScanFacts.MARKER } }
        }
        return scanned.groupBy(classifier::classify)
            .map { (cameraClass, group) -> groupOf(cameraClass, group) }
            .sortedWith(compareBy<DebriefGroup> { it.cameraClass == CameraClass.UNKNOWN }.thenByDescending { it.photoCount })
    }

    private fun groupOf(cameraClass: CameraClass, photos: List<List<TechniqueFactEntity>>): DebriefGroup {
        val names = photos.mapNotNull { CameraId.of(it)?.displayName }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val times = photos.mapNotNull(ScanFacts::captureTimeOf)
        val metrics = DebriefMetric.entries
            .filter { metric -> metricApplies(metric, cameraClass) }
            .map { metric -> count(metric, photos) }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
            .take(MAX_METRICS)
        val focus = metrics.filter { it.enoughPhotos && it.problems > 0 }
            .maxByOrNull { it.problems.toDouble() / it.total }
            ?.metric
        return DebriefGroup(cameraClass, photos.size, names, times.minOrNull(), times.maxOrNull(), metrics, focus)
    }

    /** Whether [metric] is counted for [cameraClass] (C2: no camera-control count for an unknown camera or a drone). */
    fun metricApplies(metric: DebriefMetric, cameraClass: CameraClass): Boolean =
        !metric.needsCameraControls || (cameraClass != CameraClass.UNKNOWN && cameraClass != CameraClass.DRONE)

    private fun count(metric: DebriefMetric, photos: List<List<TechniqueFactEntity>>): MetricCount {
        var matching = 0
        var total = 0
        for (facts in photos) {
            val kept = matches(metric, facts) ?: continue
            total++
            if (kept) matching++
        }
        return MetricCount(metric, matching, total)
    }

    /** Whether one photo's [facts] match [metric]; null when it has nothing to say. */
    private fun matches(metric: DebriefMetric, facts: List<TechniqueFactEntity>): Boolean? {
        fun confident(kind: String) = facts.firstOrNull { it.kind == kind && it.confidence >= MIN_CONFIDENCE }?.value
        return when (metric) {
            DebriefMetric.HIGHLIGHTS_KEPT -> confident("exposure.clipping")?.let { it != "highlights_clipped" && it != "both_clipped" }
            DebriefMetric.LEVEL_HORIZON -> confident("composition.horizon")?.let { it == "level" }
            DebriefMetric.SUBJECT_OFF_EDGE -> confident("composition.subject_placement")?.let { it != "edge_crowded" }
            DebriefMetric.HAND_HOLD -> {
                val seconds = facts.firstOrNull { it.kind == ScanFacts.EXPOSURE_TIME }?.value?.toDoubleOrNull()
                val fEff = facts.firstOrNull { it.kind == "optics.effective_focal_length" }?.value?.toDoubleOrNull()
                if (seconds == null || fEff == null || seconds <= 0.0 || fEff <= 0.0) null else seconds <= HAND_HOLD_TOLERANCE / fEff
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
