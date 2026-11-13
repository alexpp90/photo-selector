package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem

/** One camera group of the open folder's debrief, as the Debrief tab shows it (shoot debrief SD-4, SD-5). */
data class DebriefCard(
    val group: DebriefGroup,
    /** The cameras of the group as EXIF names them: the camera chip stores its class for each. */
    val cameras: List<CameraId>,
    /** The previous shoot of this class, per metric, as a whole percentage. */
    val before: Map<DebriefMetric, Int>,
    /** The worked pair: two frames of one scene that differ most in the focus metric, or none. */
    val pair: SceneComparator.Difference?,
)

/** The open folder's debrief: one card per camera class, and how much of the folder it covers. */
data class Debrief(
    val cards: List<DebriefCard> = emptyList(),
    val photoCount: Int = 0,
    /** Photos with stored facts: 0 means the folder was not scanned. */
    val scannedCount: Int = 0,
    /** The folder's last completed scan left photos out or ran while it was being listed. */
    val partial: Boolean = false,
) {
    val scanned: Boolean get() = scannedCount > 0

    /** No photo carries camera data: Learn says so once and counts the image-based metrics only. */
    val noCameraData: Boolean get() = cards.isNotEmpty() && cards.all { it.group.cameraClass == CameraClass.UNKNOWN }
}

/** "Goal: 78 % (was 61 %)": a tracked focus, shown on a later shoot of the same camera class. */
data class GoalLine(val metric: DebriefMetric, val nowPercent: Int, val wasPercent: Int)

/**
 * Builds the Debrief tab's cards from the open folder's photos, their stored facts, the camera
 * classes the user set and the stored summaries (for the previous shoot), and tracks a focus as the
 * one practice goal (SD-5: Practice folds into the debrief). Pure.
 */
object DebriefBuilder {

    private const val GOAL_PREFIX = "debrief:"

    fun build(
        photos: List<ImageItem>,
        factsByUri: Map<String, List<TechniqueFactEntity>>,
        overrides: Map<CameraId, CameraClass>,
        summaries: List<ShootSummary>,
        partial: Boolean,
    ): Debrief {
        val groups = ShootDebriefEngine.groups(photos, factsByUri, CameraClassifier.withOverrides(overrides))
        val byUri = photos.associateBy { it.uri }
        val cards = groups.map { group ->
            val summary = summaries.firstOrNull { it.cameraClass == group.cameraClass }
            DebriefCard(
                group = group,
                cameras = group.photoUris.mapNotNull { CameraId.of(factsByUri[it]) }.distinct(),
                before = group.metrics.mapNotNull { m -> summary?.previousPercent(m.metric)?.let { m.metric to it } }.toMap(),
                pair = SceneComparator.workedPair(group.photoUris.mapNotNull(byUri::get), comparedBy(group.focus)),
            )
        }
        return Debrief(cards, photos.size, photos.count { p -> factsByUri[p.uri]?.any { it.kind == ScanFacts.MARKER } == true }, partial)
    }

    /** The measurement a worked pair shows for a focus; none for the composition metrics. */
    fun comparedBy(focus: DebriefMetric?): SceneComparator.Metric? = when (focus) {
        DebriefMetric.SHARP_FRAMES, DebriefMetric.HAND_HOLD -> SceneComparator.Metric.SHARPNESS
        DebriefMetric.HIGHLIGHTS_KEPT -> SceneComparator.Metric.HIGHLIGHT_CLIPPING
        DebriefMetric.LEVEL_HORIZON, DebriefMetric.SUBJECT_OFF_EDGE, null -> null
    }

    fun goalId(cameraClass: CameraClass, metric: DebriefMetric): String = "$GOAL_PREFIX${cameraClass.key}:${metric.key}"

    /** *Track*: [card]'s focus becomes the one practice goal, with today's share as its baseline. */
    fun goalFor(card: DebriefCard, nowEpochMs: Long): PracticeGoalEntity? {
        val focus = card.group.focus ?: return null
        val count = card.group.metrics.firstOrNull { it.metric == focus } ?: return null
        if (count.total == 0) return null
        return PracticeGoalEntity(goalId(card.group.cameraClass, focus), nowEpochMs, count.matching.toDouble() / count.total, count.total)
    }

    /** Whether [goal] tracks [card]'s focus. */
    fun tracks(goal: PracticeGoalEntity?, card: DebriefCard): Boolean =
        goal != null && card.group.focus != null && goal.lessonId == goalId(card.group.cameraClass, card.group.focus)

    /**
     * The goal's line on [card]: only on a shoot taken after the goal was set (the shoot it was set
     * on is its baseline), and only when the metric has a percentage there.
     */
    fun goalLine(goal: PracticeGoalEntity?, card: DebriefCard): GoalLine? {
        goal ?: return null
        val metric = card.group.metrics.firstOrNull { goal.lessonId == goalId(card.group.cameraClass, it.metric) } ?: return null
        val first = card.group.firstCaptureTime ?: return null
        if (first <= goal.startedAtEpochMs) return null
        val now = metric.percent ?: return null
        return GoalLine(metric.metric, now, (goal.baselineRate * 100).toInt())
    }
}
