package com.photoselectortoolbox.domain.learn

/**
 * A stored debrief group (`shoot_summaries`, shoot debrief SD-3) as Learn reads it back, with the
 * previous shoot of the same camera class: the summary from another folder whose last capture
 * time is the latest before this one's first, partial ones left out.
 */
data class ShootSummary(
    val cameraClass: CameraClass,
    val cameraNames: List<String>,
    val firstCaptureTime: Long?,
    val lastCaptureTime: Long?,
    val photoCount: Int,
    val metrics: List<MetricCount>,
    val partial: Boolean,
    val updatedAt: Long,
    val previous: ShootSummary? = null,
) {
    /** The previous shoot's percentage for [metric], when both counts allow one (see [ShootDebriefEngine.previousValue]). */
    fun previousPercent(metric: DebriefMetric): Int? {
        val current = metrics.firstOrNull { it.metric == metric } ?: return null
        return ShootDebriefEngine.previousValue(current, previous?.metrics?.firstOrNull { it.metric == metric })
    }
}

/** What *Reset Learn* deletes, for its confirmation ("Delete 14 shoots, 3 lessons, 1 goal?"). */
data class LearnHistoryCounts(val shoots: Int, val lessons: Int, val goals: Int)
